package everyarticle.sync.protocol

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore

/** Thrown to local (phone UI) writers while a live PC session holds the edit lock. */
class EditLockedException : IllegalStateException("Editing is locked by a PC session")

/**
 * Authoritative sync counters, persisted in the same database as the articles.
 *  - incarnation: regenerated whenever the dataset is replaced (backup restore); never reused.
 *  - revision: +1 in the same transaction as every content write (local or PC). Never decreases.
 *  - epoch: +1 at every process start and every new session, before the session can write.
 *  - prunedThroughRevision: highest resultingRevision whose operation record was deleted.
 */
data class SyncState(val incarnation: String, val revision: Long, val epoch: Long, val prunedThroughRevision: Long) {
    companion object {
        fun initial(): SyncState = SyncState(SyncCrypto.randomHex(16), 0, 0, 0)
        /** Dataset replaced (e.g. ZIP restore): new identity, and every old session/revision becomes unusable. */
        fun replaced(old: SyncState): SyncState =
            SyncState(SyncCrypto.randomHex(16), old.revision + 1, old.epoch + 1, old.revision + 1)
    }
}

class OperationRecord(
    val operationId: String, val digest: ByteArray, val datasetIncarnation: String,
    val baseRevision: Long, val resultingRevision: Long, val appliedAtWallMillis: Long
)

data class ArticleText(val articleId: Long, val topicName: String, val articleDate: String,
    val title: String?, val body: String, val updatedAtMillis: Long)

/** One database transaction. All reads/writes inside [AuthorityStore.transaction] commit or roll back together. */
interface AuthorityTransaction {
    fun state(): SyncState
    fun saveState(state: SyncState)
    fun operation(operationId: String): OperationRecord?
    fun insertOperation(record: OperationRecord)
    /**
     * Delete applied-operation records that are both outside the newest [keepNewest] and applied before
     * [appliedBeforeWallMillis], strictly in ascending resultingRevision order.
     * @return highest resultingRevision deleted, or null when nothing was deleted.
     */
    fun pruneOperations(keepNewest: Int, appliedBeforeWallMillis: Long): Long?
    fun articles(): List<ArticleText>
    fun article(articleId: Long): ArticleText?
    /** Replace one article's title/body and updatedAt only; images and other rows are untouched. */
    fun replaceArticleText(articleId: Long, title: String?, body: String, wallMillis: Long)
}

interface AuthorityStore { fun <T> transaction(block: (AuthorityTransaction) -> T): T }

/** elapsedMillis must keep counting during deep sleep (Android: SystemClock.elapsedRealtime()). */
interface AuthorityClock { fun elapsedMillis(): Long; fun wallMillis(): Long }

data class LockInfo(val peerId: String, val sessionId: String, val epoch: Long, val deadlineElapsed: Long)

/**
 * The single exclusion point for every authoritative write path. Local writes, PC session
 * acquisition/renewal/apply, lock expiry and release all run under the same fair semaphore,
 * so an expired PC session can never race a local edit and vice versa.
 * A semaphore (not a thread-owned lock) is used so coroutine-based callers may release on another thread.
 */
class WriteGate(private val clock: AuthorityClock) {
    private val semaphore = Semaphore(1, true)
    @Volatile private var lock: LockInfo? = null
    private val listeners = CopyOnWriteArrayList<(LockInfo?) -> Unit>()

    /** Live lock, ignoring one that already passed its deadline but was not yet expired under the gate. */
    val currentLock: LockInfo? get() = lock?.takeIf { clock.elapsedMillis() < it.deadlineElapsed }

    fun addListener(listener: (LockInfo?) -> Unit) { listeners += listener; listener(currentLock) }
    fun removeListener(listener: (LockInfo?) -> Unit) { listeners -= listener }

    inner class Permit internal constructor() : AutoCloseable {
        private var open = true
        override fun close() { if (open) { open = false; semaphore.release() } }
    }

    /**
     * Enter a local write. Fails immediately with [EditLockedException] while a live PC session holds the lock;
     * keep the permit until the database transaction (including the revision bump) has committed.
     */
    fun beginLocalWrite(): Permit {
        semaphore.acquireUninterruptibly()
        try {
            expireIfDue()
            if (lock != null) throw EditLockedException()
        } catch (failure: Throwable) { semaphore.release(); throw failure }
        return Permit()
    }

    fun <T> localWrite(block: () -> T): T = beginLocalWrite().use { block() }

    /** Expire an abandoned lock without any PC message. Call periodically and on app resume. */
    fun tick() { exclusive { } }

    internal fun <T> exclusive(block: () -> T): T {
        semaphore.acquireUninterruptibly()
        try { expireIfDue(); return block() } finally { semaphore.release() }
    }
    internal fun liveLock(): LockInfo? = lock
    internal fun setLock(value: LockInfo?) { lock = value; listeners.forEach { it(value) } }
    private fun expireIfDue() { val l = lock ?: return; if (clock.elapsedMillis() >= l.deadlineElapsed) setLock(null) }
}

data class AuthorityPolicy(
    val maxSessionMillis: Long = 10 * 60_000,
    val minSessionMillis: Long = 5_000,
    val retainOperations: Int = 256,
    val retainMillis: Long = 30L * 24 * 60 * 60_000,
    val maxSnapshotBytes: Int = 6 * 1024 * 1024
)

/**
 * Platform-neutral reference implementation of the phone's authoritative rules. Android supplies a
 * Room-backed [AuthorityStore]; the rules themselves are tested on the JVM in the public repository.
 */
class PhoneAuthority(
    private val appIdentity: String,
    private val store: AuthorityStore,
    private val gate: WriteGate,
    private val clock: AuthorityClock,
    private val policy: AuthorityPolicy = AuthorityPolicy()
) {
    @Volatile private var started = false

    /** Must run once per process before any message: invalidates every session token of a previous process. */
    fun startProcess() {
        gate.exclusive { store.transaction { tx -> tx.state().let { tx.saveState(it.copy(epoch = it.epoch + 1)) } } }
        started = true
    }

    fun handle(peerId: String, message: SyncMessage): SyncMessage? {
        check(started) { "startProcess() must run first" }
        return when (message) {
            is SyncMessage.StatusRequest -> gate.exclusive {
                val s = store.transaction { it.state() }
                SyncMessage.StatusReport(message.requestNonce, appIdentity, s.incarnation, s.revision, gate.liveLock() != null)
            }
            is SyncMessage.SessionRequest -> gate.exclusive {
                if (message.mode == SessionMode.ACQUIRE) acquire(peerId, message) else renew(peerId, message)
            }
            is SyncMessage.SessionRelease -> gate.exclusive {
                gate.liveLock()?.takeIf { it.peerId == peerId && it.sessionId == message.sessionId && it.epoch == message.epoch }
                    ?.let { gate.setLock(null) }
                null
            }
            is SyncMessage.SnapshotRequest -> gate.exclusive { snapshot(peerId, message) }
            is SyncMessage.SubmitEdit -> gate.exclusive { submit(peerId, message) }
            is SyncMessage.OperationQuery -> gate.exclusive { query(message) }
            else -> null // pairing and phone-originated types are not requests
        }
    }

    private fun validity(requested: Long): Long? =
        if (requested < policy.minSessionMillis) null else minOf(requested, policy.maxSessionMillis)

    private fun acquire(peerId: String, m: SyncMessage.SessionRequest): SyncMessage {
        if (gate.liveLock() != null) return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.BUSY)
        val validity = validity(m.maximumDurationMillis)
            ?: return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.INVALID_PAYLOAD)
        val state = store.transaction { tx ->
            val s = tx.state()
            if (s.incarnation != m.datasetIncarnation) null
            else s.copy(epoch = s.epoch + 1).also { tx.saveState(it) }
        } ?: return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.INCARNATION_MISMATCH)
        val sessionId = SyncCrypto.randomHex(16)
        // The lease starts after the epoch is durable, i.e. no earlier than receipt of this request.
        gate.setLock(LockInfo(peerId, sessionId, state.epoch, clock.elapsedMillis() + validity))
        return SyncMessage.SessionGrant(m.requestNonce, sessionId, state.epoch, appIdentity, state.incarnation, state.revision, validity)
    }

    private fun renew(peerId: String, m: SyncMessage.SessionRequest): SyncMessage {
        val live = gate.liveLock() ?: return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.SESSION_EXPIRED)
        if (live.peerId != peerId || live.sessionId != m.sessionId || live.epoch != m.epoch)
            return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.NO_SESSION)
        val validity = validity(m.maximumDurationMillis)
            ?: return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.INVALID_PAYLOAD)
        val s = store.transaction { it.state() }
        // The database may have kept us waiting: an expired lock is never revived by a late renewal.
        if (!unexpired(live)) return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.SESSION_EXPIRED)
        if (s.epoch != live.epoch) return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.STALE_EPOCH)
        if (s.incarnation != m.datasetIncarnation) return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.INCARNATION_MISMATCH)
        gate.setLock(live.copy(deadlineElapsed = maxOf(live.deadlineElapsed, clock.elapsedMillis() + validity)))
        return SyncMessage.SessionGrant(m.requestNonce, live.sessionId, live.epoch, appIdentity, s.incarnation, s.revision, validity)
    }

    /** Checked again after every database wait; the gate entry check alone is not enough. */
    private fun unexpired(lock: LockInfo): Boolean = clock.elapsedMillis() < lock.deadlineElapsed

    private fun matchingSession(peerId: String, sessionId: String, epoch: Long): LockInfo? =
        gate.liveLock()?.takeIf { it.peerId == peerId && it.sessionId == sessionId && it.epoch == epoch }

    private fun snapshot(peerId: String, m: SyncMessage.SnapshotRequest): SyncMessage {
        matchingSession(peerId, m.sessionId, m.epoch) ?: return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.NO_SESSION)
        val (state, articles) = store.transaction { tx -> tx.state() to tx.articles() }
        if (gate.liveLock()?.let(::unexpired) != true) return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.SESSION_EXPIRED)
        if (state.epoch != m.epoch) return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.STALE_EPOCH)
        val size = articles.sumOf { 64L + CanonicalWriter.utf8(it.body).size + (it.title?.let { t -> CanonicalWriter.utf8(t).size } ?: 0) +
            CanonicalWriter.utf8(it.topicName).size }
        if (size > policy.maxSnapshotBytes || articles.size > MAX_SNAPSHOT_ARTICLES)
            return SyncMessage.SessionRefused(m.requestNonce, RefusalReason.TOO_LARGE)
        return SyncMessage.Snapshot(m.requestNonce, m.sessionId, m.epoch, state.incarnation, state.revision,
            articles.map { ArticleSnapshot(it.articleId, it.topicName, it.articleDate, it.title, it.body, it.updatedAtMillis) })
    }

    private fun report(m: SyncMessage.SubmitEdit, status: OperationStatus, s: SyncState, reason: RefusalReason? = null, result: Long? = null) =
        SyncMessage.OperationReport(m.requestNonce, m.operationId, status, result, reason, s.revision, s.incarnation)

    private fun submit(peerId: String, m: SyncMessage.SubmitEdit): SyncMessage = store.transaction { tx ->
        val s = tx.state()
        val existing = tx.operation(m.operationId)
        if (existing != null) {
            // Idempotent: the same operation is reported, never applied twice.
            return@transaction if (SyncCrypto.constantTimeEquals(existing.digest, m.digest))
                report(m, OperationStatus.APPLIED, s, result = existing.resultingRevision)
            else report(m, OperationStatus.REJECTED, s, RefusalReason.OPERATION_ID_REUSED)
        }
        if (!SyncCrypto.constantTimeEquals(m.digest, operationDigest(m.operationId, m.datasetIncarnation, m.baseRevision, m.edit)))
            return@transaction report(m, OperationStatus.REJECTED, s, RefusalReason.DIGEST_MISMATCH)
        if (s.incarnation != m.datasetIncarnation)
            return@transaction report(m, OperationStatus.REJECTED, s, RefusalReason.INCARNATION_MISMATCH)
        val notApplied = if (s.revision == m.baseRevision) OperationStatus.NOT_APPLIED else OperationStatus.CONFLICT
        val session = gate.liveLock()
        if (session == null) return@transaction report(m, notApplied, s, RefusalReason.SESSION_EXPIRED)
        if (session.peerId != peerId || session.sessionId != m.sessionId || session.epoch != m.epoch)
            return@transaction report(m, notApplied, s, RefusalReason.NO_SESSION)
        // Epoch fence: a token from an earlier session or process can never write.
        if (s.epoch != m.epoch) return@transaction report(m, notApplied, s, RefusalReason.STALE_EPOCH)
        if (s.revision != m.baseRevision) return@transaction report(m, OperationStatus.CONFLICT, s, RefusalReason.REVISION_MISMATCH)
        if (tx.article(m.edit.articleId) == null) return@transaction report(m, OperationStatus.REJECTED, s, RefusalReason.UNKNOWN_ARTICLE)
        // Last check before the change, after any wait for the database: past the deadline nothing is written.
        if (!unexpired(session)) return@transaction report(m, notApplied, s, RefusalReason.SESSION_EXPIRED)
        val now = clock.wallMillis()
        tx.replaceArticleText(m.edit.articleId, m.edit.title, m.edit.body, now)
        val resulting = s.revision + 1
        tx.insertOperation(OperationRecord(m.operationId, m.digest.copyOf(), s.incarnation, m.baseRevision, resulting, now))
        val pruned = tx.pruneOperations(policy.retainOperations, now - policy.retainMillis)
        val next = s.copy(revision = resulting, prunedThroughRevision = maxOf(s.prunedThroughRevision, pruned ?: 0))
        tx.saveState(next)
        report(m, OperationStatus.APPLIED, next, result = resulting)
    }

    private fun query(m: SyncMessage.OperationQuery): SyncMessage = store.transaction { tx ->
        val s = tx.state()
        fun r(status: OperationStatus, reason: RefusalReason? = null, result: Long? = null) =
            SyncMessage.OperationReport(m.requestNonce, m.operationId, status, result, reason, s.revision, s.incarnation)
        val existing = tx.operation(m.operationId)
        when {
            existing != null && SyncCrypto.constantTimeEquals(existing.digest, m.digest) ->
                r(OperationStatus.APPLIED, result = existing.resultingRevision)
            existing != null -> r(OperationStatus.REJECTED, RefusalReason.OPERATION_ID_REUSED)
            s.incarnation != m.datasetIncarnation -> r(OperationStatus.REJECTED, RefusalReason.INCARNATION_MISMATCH)
            // A record that could prove application may have been pruned: refuse to guess.
            m.baseRevision + 1 <= s.prunedThroughRevision -> r(OperationStatus.UNKNOWN)
            s.revision == m.baseRevision -> r(OperationStatus.NOT_APPLIED)
            else -> r(OperationStatus.CONFLICT, RefusalReason.REVISION_MISMATCH)
        }
    }
}
