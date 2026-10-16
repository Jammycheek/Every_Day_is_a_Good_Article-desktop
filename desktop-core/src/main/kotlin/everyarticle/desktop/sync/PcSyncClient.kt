package everyarticle.desktop.sync

import everyarticle.sync.protocol.ArticleSnapshot
import everyarticle.sync.protocol.ArticleTextEdit
import everyarticle.sync.protocol.Envelope
import everyarticle.sync.protocol.Mailbox
import everyarticle.sync.protocol.MailboxNames
import everyarticle.sync.protocol.OperationStatus
import everyarticle.sync.protocol.PairedPeer
import everyarticle.sync.protocol.PairingResult
import everyarticle.sync.protocol.PcPairing
import everyarticle.sync.protocol.ProtocolFormatException
import everyarticle.sync.protocol.RefusalReason
import everyarticle.sync.protocol.Role
import everyarticle.sync.protocol.SessionMode
import everyarticle.sync.protocol.SyncCrypto
import everyarticle.sync.protocol.SyncMessage
import everyarticle.sync.protocol.operationDigest
import poc.AppliedReceipt
import poc.DatasetIdentity
import poc.Grant
import poc.GrantAcceptanceResult
import poc.PendingDiscardConfirmation
import poc.PendingDiscardPreview
import poc.PendingDiscardResult
import poc.RecoveryController
import poc.RecoveryEdit
import poc.ReconciliationResult

/** Why the protected pairing data could not be read. [TRANSIENT] may succeed on retry. */
enum class PairingStoreProblem { TRANSIENT, KEY_STATE, DATA_REJECTED, INVALID_FORMAT }

class PairingStoreUnavailableException(val problem: PairingStoreProblem, cause: Throwable? = null) :
    IllegalStateException("Pairing store unavailable: $problem", cause)

/** Paired peers and this PC's own stable identity, kept in protected local storage. */
interface PcPeerStore {
    val pcId: String
    fun peer(): PairedPeer?
    fun save(peer: PairedPeer)
    fun forget()
    /** Read (or create) the stored identity; throws [PairingStoreUnavailableException]. Called before anything else. */
    fun open() {}
    /** User-confirmed: keep the unreadable data aside and start without a pairing. Returns where it was kept. */
    fun moveAsideAndReset(): String = throw UnsupportedOperationException()
}

class InMemoryPcPeerStore(override val pcId: String = "pc-" + SyncCrypto.randomHex(8)) : PcPeerStore {
    private var value: PairedPeer? = null
    override fun peer() = value
    override fun save(peer: PairedPeer) { value = peer }
    override fun forget() { value = null }
}

/** What the user must decide about a pending edit before editing can continue. */
enum class DecisionKind { CONFLICT, REJECTED, UNKNOWN, UNREADABLE }

enum class ConnectionStep { CHECKING_USB, CHECKING_PHONE, CHECKING_PENDING, REQUESTING_PERMISSION, IMPORTING_ARTICLES }

data class PendingSummary(val articleId: Long, val title: String?, val body: String, val submitted: Boolean,
    val baseRevision: Long, val incarnation: String?)

/**
 * Newest editor text that is NOT in the protected journal (autosave failed or permission ended first).
 * Kept in memory only, outside the editor, so it survives the session ending; it is never sent to the phone.
 * [sessionId]/[baseRevision]/[incarnation] describe the session it was typed in; it is restored automatically only
 * into a new session at exactly that base, otherwise the user exports or discards it.
 */
data class UnsavedInput(val articleId: Long, val title: String?, val body: String,
    val sessionId: String?, val baseRevision: Long?, val incarnation: String?)

data class ClientView(
    val paired: PairedPeer? = null,
    val pairingCode: String? = null,
    val pairingConfirmedLocally: Boolean = false,
    val pairingInProgress: Boolean = false,
    val transportReady: Boolean = false,
    val transportProblem: String? = null,
    val connectionStep: ConnectionStep? = null,
    val phoneRevision: Long? = null,
    val sessionActive: Boolean = false,
    val leaseRemainingMillis: Long? = null,
    val articles: List<ArticleSnapshot> = emptyList(),
    val pending: PendingSummary? = null,
    val decision: DecisionKind? = null,
    val decisionReason: RefusalReason? = null,
    val resubmitAllowed: Boolean = false,
    val unsaved: UnsavedInput? = null,
    /** Pairing data could not be read at start; pairing and connecting stay off until retried or reset. */
    val pairingStoreProblem: PairingStoreProblem? = null,
    val message: String? = null
)

class SyncTimeoutException : IllegalStateException("No reply from the phone")

/**
 * PC side of protocol v1. All methods must run on one worker thread; the UI only reads [view].
 * The phone stays authoritative: every decision about applying comes from its reply or status query,
 * and a pending edit is cleared only through [RecoveryController.reconcileApplied] or an explicitly confirmed discard.
 */
class PcSyncClient(
    private val pcName: String,
    private val mailbox: Mailbox,
    private val peers: PcPeerStore,
    private val controller: RecoveryController,
    private val transportReady: () -> Boolean,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val replyTimeoutMillis: Long = 20_000,
    private val sessionMillis: Long = 5 * 60_000,
    private val safetyAllowanceMillis: Long = 15_000,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val transportProblem: () -> String? = { null },
    private val onChange: (ClientView) -> Unit = {}
) {
    var view = ClientView(paired = peers.peer())
        private set(value) { field = value; onChange(value) }

    private var pairing: PcPairing? = null
    private var pairingResult: PairingResult? = null
    private var phoneConfirm: SyncMessage.PairConfirm? = null
    private var session: SyncMessage.SessionGrant? = null
    /** The grant the editor was last opened under; stays set after the session ends to label late editor text. */
    private var lastGrant: SyncMessage.SessionGrant? = null
    private var validityMillis = 0L

    private fun update(block: ClientView.() -> ClientView) { view = view.block() }
    private fun peer(): PairedPeer = peers.peer() ?: error("Not paired")

    // ---------------- pairing ----------------

    /** The phone's pairing screen must be open first. */
    fun startPairing() {
        checkPairingStore()
        drainPairingFiles()
        val p = PcPairing(peers.pcId, pcName).also { pairing = it }
        pairingResult = null; phoneConfirm = null
        mailbox.send(MailboxNames.next(wallMillis()), Envelope.pairing(peers.pcId, Envelope.BROADCAST, p.start()))
        update { copy(pairingCode = null, pairingConfirmedLocally = false, pairingInProgress = true, message = "スマホの応答を待っています") }
    }

    /** Poll for pairing replies; returns true once the 6-digit code is available. */
    fun pollPairing(): Boolean {
        for (file in mailbox.receive()) {
            val opened = try { Envelope.open(file.bytes, peers.pcId, Role.PHONE) { null } }
                catch (_: ProtocolFormatException) { continue }
            mailbox.delete(file.name)
            when (val m = opened.message) {
                is SyncMessage.PairAccept -> {
                    val p = pairing ?: continue
                    if (pairingResult != null || m.phoneId != opened.senderId) continue
                    val (reveal, result) = p.onAccept(m)
                    pairingResult = result
                    mailbox.send(MailboxNames.next(wallMillis()), Envelope.pairing(peers.pcId, m.phoneId, reveal))
                    update { copy(pairingCode = result.sas, message = "両方の画面に同じ6桁が出ているか確認してください") }
                }
                is SyncMessage.PairConfirm -> if (opened.senderId == pairingResult?.peerId) { phoneConfirm = m; finishPairingIfReady() }
                else -> Unit
            }
        }
        return pairingResult != null
    }

    fun confirmPairingCode() {
        val r = pairingResult ?: error("No pairing code yet")
        mailbox.send(MailboxNames.next(wallMillis()),
            Envelope.pairing(peers.pcId, r.peerId, SyncMessage.PairConfirm(peers.pcId, r.confirmationMac(Role.PC))))
        update { copy(pairingConfirmedLocally = true, message = "スマホ側の確認を待っています") }
        finishPairingIfReady()
    }

    fun cancelPairing() { pairing = null; pairingResult = null; phoneConfirm = null; update { copy(pairingCode = null, pairingConfirmedLocally = false, pairingInProgress = false) } }

    private fun finishPairingIfReady() {
        val r = pairingResult ?: return
        val confirm = phoneConfirm ?: return
        if (!view.pairingConfirmedLocally) return
        try {
            val peer = r.verifyPeerConfirmation(Role.PHONE, confirm)
            peers.save(peer)
            update { copy(paired = peer, pairingCode = null, pairingConfirmedLocally = false, pairingInProgress = false, message = "ペアリングが完了しました") }
        } catch (_: ProtocolFormatException) {
            update { copy(pairingCode = null, pairingInProgress = false, message = "ペアリングを確認できませんでした。最初からやり直してください") }
        }
        pairing = null; pairingResult = null; phoneConfirm = null
    }

    private fun drainPairingFiles() { mailbox.receive().forEach { mailbox.delete(it.name) } }

    // ---------------- request/reply ----------------

    private fun send(message: SyncMessage) {
        val peer = peer()
        mailbox.send(MailboxNames.next(wallMillis()), Envelope.seal(peers.pcId, Role.PC, peer, message))
    }

    /** Wait for the reply carrying [nonce]. Other authenticated replies are stale and ignored. */
    private fun await(nonce: String, startedAt: Long): SyncMessage {
        val peer = peer()
        fun remaining(): Long {
            val elapsed = elapsedMillis() - startedAt
            if (elapsed < 0 || elapsed >= replyTimeoutMillis) throw SyncTimeoutException()
            return replyTimeoutMillis - elapsed
        }
        while (true) {
            remaining()
            val files = mailbox.receive()
            remaining() // Include native/MTP time; a slow receive must not restart the wait budget.
            for (file in files) {
                remaining()
                val opened = try { Envelope.open(file.bytes, peers.pcId, Role.PHONE) { if (it == peer.peerId) peer else null } }
                    catch (_: ProtocolFormatException) { continue } // may still be in transfer; phone side never writes partial names
                mailbox.delete(file.name)
                remaining()
                if (!opened.authenticated) continue
                if (replyNonce(opened.message) == nonce) return opened.message
            }
            sleep(minOf(250, remaining()))
        }
    }

    private fun replyNonce(m: SyncMessage): String? = when (m) {
        is SyncMessage.StatusReport -> m.requestNonce
        is SyncMessage.SessionGrant -> m.requestNonce
        is SyncMessage.SessionRefused -> m.requestNonce
        is SyncMessage.Snapshot -> m.requestNonce
        is SyncMessage.OperationReport -> m.requestNonce
        else -> null
    }

    private fun request(build: (String) -> SyncMessage): SyncMessage {
        val nonce = SyncCrypto.randomHex(16)
        val startedAt = elapsedMillis()
        send(build(nonce))
        return await(nonce, startedAt)
    }

    // ---------------- connection lifecycle ----------------

    /** Periodic: transport, lease expiry and renewal. Releases the snapshot as soon as permission ends. */
    fun tick() {
        val ready = try { transportReady() } catch (_: Exception) { false }
        val problem = if (ready) null else transportProblem() ?: "USB/MTPの接続を確認できません。ファイル転送とロック解除を確認して、再試行してください"
        if (ready != view.transportReady || problem != view.transportProblem)
            update { copy(transportReady = ready, transportProblem = problem) }
        controller.observeTransport(ready)
        controller.tick()
        val remaining = controller.leaseRemainingMillis()
        if (remaining == null && session != null) {
            session = null
            update { copy(sessionActive = false, leaseRemainingMillis = null, articles = emptyList(),
                message = if (ready) "編集許可の期限が切れました。未反映の編集は保護されています" else "USB/MTPの接続を確認できなくなりました。未反映の編集は保護されています") }
        } else if (remaining != null) {
            update { copy(leaseRemainingMillis = remaining) }
            if (remaining < validityMillis / 3) renew()
        }
    }

    /**
     * Connect: status → reconcile any pending edit by query → acquire a request-bound grant → snapshot.
     * Never acquires editing while a pending edit's outcome needs a user decision.
     */
    fun connect() {
        checkPairingStore()
        update { copy(connectionStep = ConnectionStep.CHECKING_USB) }
        try {
            connectInternal()
        } catch (failure: Exception) {
            release() // A grant followed by a failed snapshot must not strand the next connection attempt.
            throw failure
        } finally {
            update { copy(connectionStep = null) }
        }
    }

    private fun connectInternal() {
        tick()
        check(view.transportReady) { view.transportProblem ?: "USB/MTPの接続を確認できません" }
        update { copy(connectionStep = ConnectionStep.CHECKING_PHONE, message = "スマホの「PCと連携」を開いたままお待ちください") }
        val status = request { SyncMessage.StatusRequest(it) } as? SyncMessage.StatusReport ?: error("Unexpected reply")
        update { copy(phoneRevision = status.revision, decision = null, decisionReason = null, resubmitAllowed = false, message = null,
            connectionStep = ConnectionStep.CHECKING_PENDING) }
        val pending = try { controller.pending } catch (failure: Exception) {
            update { copy(decision = DecisionKind.UNREADABLE, message = "保護された未反映の編集を読み取れません") }
            return
        }
        refreshPending(pending)
        if (pending != null) {
            if (pending.metadata.datasetInstance != status.datasetIncarnation) {
                if (pending.submitted) { if (!reconcileByQuery(pending)) return }
                else { decide(DecisionKind.REJECTED, RefusalReason.INCARNATION_MISMATCH); return }
            } else if (pending.submitted) {
                if (!reconcileByQuery(pending)) return
            } else if (pending.metadata.baseRevision != status.revision) {
                decide(DecisionKind.CONFLICT, RefusalReason.REVISION_MISMATCH); return
            }
        }
        acquire(status)
    }

    /** @return true when editing may continue (cleared, or provably not applied with an unchanged base). */
    private fun reconcileByQuery(pending: RecoveryEdit): Boolean {
        val edit = EditPayload.decode(pending.syntheticDelta)
        val incarnation = pending.metadata.datasetInstance ?: run { decide(DecisionKind.UNKNOWN, null); return false }
        val digest = operationDigest(pending.metadata.operation, incarnation, pending.metadata.baseRevision, edit)
        val report = request { SyncMessage.OperationQuery(it, pending.metadata.operation, incarnation, pending.metadata.baseRevision, digest) }
            as? SyncMessage.OperationReport ?: error("Unexpected reply")
        return handleReport(pending, report)
    }

    private fun handleReport(pending: RecoveryEdit, report: SyncMessage.OperationReport): Boolean {
        update { copy(phoneRevision = report.currentRevision) }
        return when (report.status) {
            OperationStatus.APPLIED -> {
                val receipt = AppliedReceipt(pending.metadata.device, pending.metadata.baseRevision, pending.metadata.operation,
                    pending.contentDigest, report.resultingRevision ?: error("Applied without revision"), pending.metadata.datasetInstance!!)
                val result = controller.reconcileApplied(receipt)
                check(result == ReconciliationResult.CLEARED || result == ReconciliationResult.NO_PENDING) { "Receipt did not match" }
                refreshPending(null)
                update { copy(sessionActive = false, articles = emptyList(), message = "スマホに反映されました（版 ${report.resultingRevision}）") }
                true
            }
            OperationStatus.NOT_APPLIED -> {
                update { copy(resubmitAllowed = true, message = "未反映であることを確認しました。再提出できます") }
                true
            }
            OperationStatus.IN_PROGRESS -> { update { copy(message = "スマホで処理中です。少し待ってから再接続してください") }; false }
            OperationStatus.CONFLICT -> { decide(DecisionKind.CONFLICT, report.reason); false }
            OperationStatus.REJECTED -> { decide(DecisionKind.REJECTED, report.reason); false }
            OperationStatus.UNKNOWN -> { decide(DecisionKind.UNKNOWN, report.reason); false }
        }
    }

    private fun decide(kind: DecisionKind, reason: RefusalReason?) {
        update { copy(decision = kind, decisionReason = reason, resubmitAllowed = false, message = null) }
    }

    private fun acquire(status: SyncMessage.StatusReport) {
        update { copy(connectionStep = ConnectionStep.REQUESTING_PERMISSION) }
        val p = peer()
        val identity = DatasetIdentity(p.peerId, status.datasetIncarnation)
        val rebaseline = try { controller.revisionHistoryNeedsBaseline(identity) } catch (_: IllegalStateException) { false }
        val pendingRequest = controller.beginGrantRequest(identity, sessionMillis, safetyAllowanceMillis, renewal = false, rebaseline = rebaseline)
        val startedAt = elapsedMillis()
        send(SyncMessage.SessionRequest(pendingRequest.requestNonce, SessionMode.ACQUIRE, status.datasetIncarnation, null, null, sessionMillis))
        val reply = try { await(pendingRequest.requestNonce, startedAt) } catch (t: SyncTimeoutException) { controller.cancelGrantRequest(pendingRequest); throw t }
        if (reply is SyncMessage.SessionRefused) {
            controller.cancelGrantRequest(pendingRequest)
            update { copy(message = refusalMessage(reply.reason)) }; return
        }
        val grant = reply as SyncMessage.SessionGrant
        if (grant.appIdentity != p.peerId) error("Grant from another app identity")
        val local = Grant(grant.appIdentity, grant.sessionId, grant.baseRevision, grant.validityMillis, grant.datasetIncarnation,
            grant.requestNonce, grant.epoch)
        val accepted = if (rebaseline) controller.acceptFreshHistoryBaseline(local, pendingRequest) else controller.acceptGrant(local, pendingRequest)
        check(accepted == GrantAcceptanceResult.ACCEPTED)
        session = grant; lastGrant = grant; validityMillis = grant.validityMillis
        update { copy(connectionStep = ConnectionStep.IMPORTING_ARTICLES) }
        val snapshot = request { SyncMessage.SnapshotRequest(it, grant.sessionId, grant.epoch) }
        if (snapshot !is SyncMessage.Snapshot || snapshot.sessionId != grant.sessionId || snapshot.epoch != grant.epoch ||
            snapshot.revision != grant.baseRevision || snapshot.datasetIncarnation != grant.datasetIncarnation) {
            release(); update { copy(message = "取り込みデータを確認できませんでした") }; return
        }
        controller.loadSyntheticSnapshot(snapshot.encode())
        update { copy(sessionActive = true, articles = snapshot.articles, phoneRevision = snapshot.revision,
            leaseRemainingMillis = controller.leaseRemainingMillis(), message = "編集できます（スマホ側の編集はロック中）") }
        restoreUnsaved(grant, snapshot.articles)
    }

    /**
     * Unsaved text from an earlier session goes back into the journal only when nothing on the phone moved since it
     * was typed (same incarnation and base revision); otherwise it stays aside for export so it never overwrites.
     */
    private fun restoreUnsaved(grant: SyncMessage.SessionGrant, articles: List<ArticleSnapshot>) {
        val u = view.unsaved ?: return
        val sameBase = u.incarnation == grant.datasetIncarnation && u.baseRevision == grant.baseRevision &&
            articles.any { it.articleId == u.articleId }
        if (!sameBase) {
            update { copy(message = "保存できなかった入力は、スマホのデータが変わったため自動では戻しません。テキストで保存してください") }
            return
        }
        try {
            store(u.articleId, u.title, u.body)
            update { copy(unsaved = null, message = "保存できなかった入力を編集に戻し、暗号化保存しました") }
        } catch (_: Exception) {
            update { copy(message = "保存できなかった入力を戻せませんでした。テキストで保存してください") }
        }
    }

    private fun renew() {
        val s = session ?: return
        val p = peer()
        val identity = DatasetIdentity(p.peerId, s.datasetIncarnation)
        val pendingRequest = try { controller.beginGrantRequest(identity, sessionMillis, safetyAllowanceMillis, renewal = true) }
            catch (_: Exception) { return }
        val startedAt = elapsedMillis()
        send(SyncMessage.SessionRequest(pendingRequest.requestNonce, SessionMode.RENEW, s.datasetIncarnation, s.sessionId, s.epoch, sessionMillis))
        val reply = try { await(pendingRequest.requestNonce, startedAt) } catch (_: SyncTimeoutException) { controller.cancelGrantRequest(pendingRequest); return }
        if (reply !is SyncMessage.SessionGrant) { controller.cancelGrantRequest(pendingRequest); return }
        val local = Grant(reply.appIdentity, reply.sessionId, reply.baseRevision, reply.validityMillis, reply.datasetIncarnation, reply.requestNonce, reply.epoch)
        try { controller.acceptGrant(local, pendingRequest) } catch (_: Exception) {
            session = null
            update { copy(sessionActive = false, articles = emptyList(), message = "延長できませんでした。未反映の編集は保護されています") }
            return
        }
        validityMillis = reply.validityMillis
        update { copy(leaseRemainingMillis = controller.leaseRemainingMillis()) }
    }

    /** End the session: phone unlocks immediately; without this message it unlocks at expiry anyway. */
    fun release() {
        val s = session
        session = null
        controller.suspendSession()
        if (s != null) runCatching { send(SyncMessage.SessionRelease(s.sessionId, s.epoch)) }
        update { copy(sessionActive = false, articles = emptyList(), leaseRemainingMillis = null) }
    }

    // ---------------- editing ----------------

    /**
     * Autosave: encrypt and persist the editor text while the grant is live. One article edit at a time.
     * If it cannot be saved, the text is kept as [ClientView.unsaved] (memory only) so it is never silently dropped.
     */
    fun saveDraft(articleId: Long, title: String?, body: String) {
        val origin = lastGrant
        val input = UnsavedInput(articleId, title, body, origin?.sessionId, origin?.baseRevision, origin?.datasetIncarnation)
        try {
            check(session != null) { "編集許可がないため保存できません" }
            store(articleId, title, body)
        } catch (failure: Exception) {
            update { copy(unsaved = input, message = "最新の入力を暗号化保存できませんでした。画面上部からテキストで保存できます") }
            throw failure
        }
        // Newer text of the same article in the same session supersedes earlier unsaved text; anything else stays aside.
        val u = view.unsaved
        if (u != null && u.articleId == articleId && u.sessionId == session?.sessionId) update { copy(unsaved = null) }
    }

    private fun store(articleId: Long, title: String?, body: String) {
        val current = controller.pending
        check(current == null || EditPayload.decode(current.syntheticDelta).articleId == articleId) { "別の記事の未反映の編集があります" }
        check(current?.submitted != true) { "提出済みの編集は変更できません" }
        val operation = current?.metadata?.operation ?: SyncCrypto.randomHex(16)
        controller.restage(operation, EditPayload.encode(ArticleTextEdit(articleId, title, body)))
        refreshPending(controller.pending)
    }

    /** Persist "submitted" first, then send. A lost reply is resolved later by [connect]'s status query. */
    fun submit() {
        val s = session ?: error("No session")
        val pending = controller.markSubmitted()
        refreshPending(pending)
        val incarnation = pending.metadata.datasetInstance!!
        val edit = EditPayload.decode(pending.syntheticDelta)
        val digest = operationDigest(pending.metadata.operation, incarnation, pending.metadata.baseRevision, edit)
        val report = try {
            request { SyncMessage.SubmitEdit(it, s.sessionId, s.epoch, pending.metadata.operation, incarnation,
                pending.metadata.baseRevision, edit, digest) } as SyncMessage.OperationReport
        } catch (t: SyncTimeoutException) {
            update { copy(message = "応答がありません。再接続すると状態を照会します（再送はしません）") }
            throw t
        }
        if (handleReport(pending, report) && report.status == OperationStatus.APPLIED) release() // unlock the phone now
        else if (report.status == OperationStatus.NOT_APPLIED) update { copy(message = refusalMessage(report.reason)) }
    }

    fun resubmit() {
        check(view.resubmitAllowed && session != null) { "再提出の条件を満たしていません" }
        update { copy(resubmitAllowed = false) }
        submit()
    }

    // ---------------- explicit user decisions ----------------

    fun prepareDiscard(): PendingDiscardPreview? = controller.preparePendingDiscard()

    fun discard(confirmation: PendingDiscardConfirmation): PendingDiscardResult {
        val result = controller.discardPending(confirmation)
        if (result == PendingDiscardResult.DISCARDED) {
            session = null
            refreshPending(null); update { copy(decision = null, decisionReason = null, sessionActive = false, articles = emptyList(),
                message = "PC側の未反映の編集を破棄しました（スマホのデータは変わりません）") }
        }
        return result
    }

    fun preparePendingQuarantine() = controller.preparePendingQuarantine()
    fun cancelPendingQuarantine(confirmation: poc.PendingQuarantineConfirmation) = controller.cancelPendingQuarantine(confirmation)
    fun quarantinePending(confirmation: poc.PendingQuarantineConfirmation) = controller.quarantinePending(confirmation).also {
        if (it.result == poc.PendingQuarantineResult.QUARANTINED) { refreshPending(null); update { copy(decision = null) } }
    }

    fun unsavedPlainText(): String? = view.unsaved?.let { (it.title?.let { t -> "$t\n\n" } ?: "") + it.body }

    /**
     * Explicit, confirmed by the UI: drop the in-memory unsaved text the user was shown ([expected]); newer text that
     * replaced it meanwhile is kept. The journal and the phone are untouched. @return true if it was discarded.
     */
    fun discardUnsaved(expected: UnsavedInput?): Boolean {
        if (expected == null || view.unsaved != expected) return false
        update { copy(unsaved = null, message = "保存できなかった入力を破棄しました") }
        return true
    }

    fun pendingPlainText(): String? = controller.pending?.let { EditPayload.decode(it.syntheticDelta) }
        ?.let { (it.title?.let { t -> "$t\n\n" } ?: "") + it.body }

    private fun refreshPending(edit: RecoveryEdit?) {
        val summary = edit?.let {
            val e = EditPayload.decode(it.syntheticDelta)
            PendingSummary(e.articleId, e.title, e.body, it.submitted, it.metadata.baseRevision, it.metadata.datasetInstance)
        }
        update { copy(pending = summary) }
    }

    private fun refusalMessage(reason: RefusalReason?): String = when (reason) {
        RefusalReason.BUSY -> "スマホは別のPCと編集中です"
        RefusalReason.SESSION_EXPIRED, RefusalReason.NO_SESSION, RefusalReason.STALE_EPOCH ->
            "編集許可が無効になっていました。再接続してください（編集は保護されています）"
        RefusalReason.INCARNATION_MISMATCH -> "スマホのデータが復元などで置き換えられています"
        RefusalReason.TOO_LARGE -> "データが大きすぎて取り込めません"
        RefusalReason.LOCAL_EDIT_ACTIVE -> "スマホで編集中です"
        else -> "スマホが要求を受け付けませんでした（${reason ?: "不明"}）"
    }

    /** Remove the stored pairing key on this PC (the phone's entry is removed on the phone). */
    fun forgetPairing() { release(); peers.forget(); update { copy(paired = null) } }

    fun initialize() {
        refreshPending(try { controller.pending } catch (_: Exception) { null })
        openPairingStore()
    }

    // ---------------- pairing data that cannot be read ----------------

    private fun checkPairingStore() = check(view.pairingStoreProblem == null) { "ペアリング情報を読み取れていません" }

    /** @return true when the pairing data is readable (or was created). */
    fun openPairingStore(): Boolean = try {
        peers.open()
        update { copy(pairingStoreProblem = null, paired = peers.peer()) }
        true
    } catch (failure: PairingStoreUnavailableException) {
        session = null
        update { copy(pairingStoreProblem = failure.problem, paired = null, sessionActive = false, articles = emptyList()) }
        false
    }

    /**
     * Explicit and confirmed in the UI: keep the unreadable pairing file under another name and start unpaired.
     * Pending edits and revision history are separate files and stay exactly as they are.
     */
    fun resetPairingStore() {
        check(view.pairingStoreProblem != null) { "ペアリング情報は読み取れています" }
        val kept = peers.moveAsideAndReset()
        update { copy(pairingStoreProblem = null, paired = null,
            message = "読めなかったペアリング情報を「$kept」として残しました。未反映の編集と履歴はそのままです。もう一度ペアリングしてください") }
    }
}
