package everyarticle.desktop.sync

import everyarticle.sync.protocol.*
import poc.PendingDiscardResult
import poc.RecoveryController
import java.io.File
import kotlin.test.*

private typealias MemoryJournal = VolatileJournal

/** Phone-side mailbox wrapper that can lose the next reply (lost acknowledgement). */
class LossyMailbox(private val inner: Mailbox) : Mailbox by inner {
    var dropNextSend = false
    override fun send(name: String, bytes: ByteArray) { if (dropNextSend) { dropNextSend = false; return }; inner.send(name, bytes) }
}

class RoundTripTest {
    private val root: File = kotlin.io.path.createTempDirectory("ea-roundtrip").toFile()
    private val phoneClock = ManualClock()
    private var pcClock = 10_000L
    private var transport = true
    private var gate = WriteGate(phoneClock)
    private val store = InMemoryAuthorityStore(listOf(
        ArticleText(1, "合成TOPIC", "2026-10-12", "見出し", "スマホの本文", 1),
        ArticleText(2, "合成TOPIC", "2026-10-13", null, "二件目", 1)))
    private var authority = PhoneAuthority("phone-1", store, gate, phoneClock).apply { startProcess() }
    private val phonePeers = InMemoryPeerStore()
    private val phoneMailbox = LossyMailbox(DirectoryMailbox.forPhone(root))
    private var phone = endpoint()
    private val pcPeers = InMemoryPcPeerStore("pc-1")
    private val journal = MemoryJournal()
    private var pcPolls = true

    private fun endpoint() = PhoneEndpoint("phone-1", "合成スマホ", phoneMailbox, phonePeers, InMemoryReplayGuard(), authority, phoneClock)
    private fun client() = PcSyncClient("合成PC", DirectoryMailbox.forPc(root), pcPeers, RecoveryController(journal) { pcClock },
        transportReady = { transport }, wallMillis = { phoneClock.wall },
        sleep = { ms -> pcClock += ms; phoneClock.advance(ms); if (pcPolls) phone.poll() }, replyTimeoutMillis = 2_000,
        sessionMillis = 120_000, safetyAllowanceMillis = 5_000)
    private var pc = client()

    private fun advance(ms: Long) { pcClock += ms; phoneClock.advance(ms) }

    @BeforeTest fun pairDevices() {
        phone.beginPairing()
        pc.startPairing(); phone.poll()
        assertTrue(pc.pollPairing()); phone.poll()
        pc.confirmPairingCode(); phone.poll()
        phone.confirmPairingCode()
        pc.pollPairing()
        assertNotNull(pc.view.paired); assertNotNull(phonePeers.peer("pc-1"))
        pc.tick()
    }
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun editAndSubmit(body: String) {
        pc.connect(); assertTrue(pc.view.sessionActive, pc.view.message)
        pc.saveDraft(1, "見出し", body)
        pc.submit()
    }

    @Test fun oneEditRoundTripsAndUnlocksThePhone() {
        pc.connect()
        assertTrue(pc.view.sessionActive); assertEquals(2, pc.view.articles.size)
        assertFailsWith<EditLockedException> { gate.localWrite { } }
        pc.saveDraft(1, "見出し", "PCで編集した本文")
        assertFalse(journal.edit!!.submitted)
        pc.submit()
        assertEquals("PCで編集した本文", store.article(1)!!.body)
        assertNull(journal.edit); assertNull(pc.view.pending); assertFalse(pc.view.sessionActive)
        phone.poll() // release message
        gate.localWrite { } // phone unlocked
        assertEquals(1, store.operationCount())
    }

    @Test fun lostReplyAfterApplyIsResolvedByQueryWithoutDoubleApply() {
        pc.connect(); pc.saveDraft(1, "見出し", "反映後に応答喪失")
        phoneMailbox.dropNextSend = true
        assertFailsWith<SyncTimeoutException> { pc.submit() }
        assertTrue(journal.edit!!.submitted, "pending kept until a trusted outcome")
        advance(200_000); pc.tick() // PC lease and phone lock both expire, no messages
        pc = client(); pc.initialize() // PC app restart
        pc.connect()
        assertNull(journal.edit); assertEquals(1, store.operationCount())
        assertEquals("反映後に応答喪失", store.article(1)!!.body)
    }

    @Test fun disconnectReleasesSnapshotButProtectsTheDraft() {
        pc.connect(); pc.saveDraft(1, "見出し", "切断前の下書き")
        transport = false; pc.tick()
        assertFalse(pc.view.sessionActive); assertTrue(pc.view.articles.isEmpty())
        assertEquals("切断前の下書き", pc.view.pending!!.body)
        assertFailsWith<IllegalStateException> { pc.saveDraft(1, "見出し", "切断後") }
        assertEquals("切断前の下書き", pc.view.pending!!.body)
        assertEquals("切断後", pc.view.unsaved!!.body, "text that could not be saved is kept aside, not dropped")
        advance(130_000); phone.poll(); gate.tick()
        transport = true; pc.tick()
        pc.connect() // same base revision: editing resumes, and the newest text goes back into the protected draft
        assertTrue(pc.view.sessionActive); assertNull(pc.view.unsaved)
        assertEquals("切断後", pc.view.pending!!.body)
        assertEquals("スマホの本文", store.article(1)!!.body, "restoring never sends anything")
        pc.submit()
        assertEquals("切断後", store.article(1)!!.body)
    }

    @Test fun expiredSessionSubmitIsNotAppliedThenOnlyProvenResubmitIsAllowed() {
        pc.connect(); pc.saveDraft(1, "見出し", "期限切れ後")
        phoneClock.advance(130_000) // phone lock expired; PC has not noticed yet
        pc.submit()
        assertEquals("スマホの本文", store.article(1)!!.body)
        assertTrue(journal.edit!!.submitted)
        pc.release(); pc.connect()
        assertTrue(pc.view.resubmitAllowed); assertTrue(pc.view.sessionActive)
        pc.resubmit()
        assertEquals("期限切れ後", store.article(1)!!.body); assertNull(journal.edit)
    }

    @Test fun phoneEditAfterLockExpiryBecomesConflictNotOverwrite() {
        pc.connect(); pc.saveDraft(1, "見出し", "PC側の変更")
        transport = false; pc.tick()
        phoneClock.advance(130_000)
        gate.localWrite { store.localEdit(1, "スマホ側の変更", phoneClock.wall) }
        transport = true; pc.tick(); pc.connect()
        assertEquals(DecisionKind.CONFLICT, pc.view.decision); assertFalse(pc.view.sessionActive)
        assertEquals("スマホ側の変更", store.article(1)!!.body)
        assertNotNull(journal.edit, "never discarded without confirmation")
        val preview = pc.prepareDiscard()!!
        assertEquals(PendingDiscardResult.DISCARDED, pc.discard(preview.confirmation))
        assertNull(journal.edit); assertEquals("スマホ側の変更", store.article(1)!!.body)
    }

    @Test fun submittedConflictIsNeverResent() {
        pc.connect(); pc.saveDraft(1, "見出し", "送信済み")
        phoneMailbox.dropNextSend = false
        phoneClock.advance(130_000); pc.submit() // NOT_APPLIED: session expired at the phone
        pc.release(); phone.poll()
        gate.localWrite { store.localEdit(1, "スマホが先に更新", phoneClock.wall) }
        pc.connect()
        assertEquals(DecisionKind.CONFLICT, pc.view.decision); assertFalse(pc.view.resubmitAllowed)
        assertEquals("スマホが先に更新", store.article(1)!!.body)
    }

    @Test fun restoreOnPhoneRejectsOldPendingByIncarnation() {
        pc.connect(); pc.saveDraft(1, "見出し", "復元前の編集")
        pc.release(); phone.poll()
        gate.localWrite { store.restore(listOf(ArticleText(1, "合成TOPIC", "2026-10-12", "見出し", "復元データ", 2))) }
        pc.connect()
        assertEquals(DecisionKind.REJECTED, pc.view.decision)
        assertEquals(RefusalReason.INCARNATION_MISMATCH, pc.view.decisionReason)
        assertEquals("復元データ", store.article(1)!!.body)
    }

    @Test fun prunedHistoryGivesUnknownAndNoResend() {
        authority = PhoneAuthority("phone-1", store, gate, phoneClock, AuthorityPolicy(retainOperations = 0, retainMillis = 1)).apply { startProcess() }
        phone = endpoint()
        pc.connect(); pc.saveDraft(1, "見出し", "一回目")
        phoneMailbox.dropNextSend = true
        assertFailsWith<SyncTimeoutException> { pc.submit() }
        pc.release(); phone.poll(); advance(10)
        // A later edit applies and prunes the first record.
        pc = client()
        journal.edit!!.let { saved ->
            journal.edit = null
            editAndSubmit("二回目")
            journal.edit = saved
        }
        pc.connect()
        assertEquals(DecisionKind.UNKNOWN, pc.view.decision); assertFalse(pc.view.resubmitAllowed)
    }

    @Test fun phoneRestartInvalidatesTheSessionAndPcReconnects() {
        pc.connect(); pc.saveDraft(1, "見出し", "再起動をまたぐ")
        gate = WriteGate(phoneClock)
        authority = PhoneAuthority("phone-1", store, gate, phoneClock).apply { startProcess() }
        phone = endpoint()
        pc.submit()
        assertEquals("スマホの本文", store.article(1)!!.body)
        pc.release(); pc.connect()
        assertTrue(pc.view.resubmitAllowed)
        pc.resubmit()
        assertEquals("再起動をまたぐ", store.article(1)!!.body)
    }

    @Test fun storageFailureDuringAutosaveStopsEditingWithoutLosingTheLastSavedText() {
        pc.connect(); pc.saveDraft(1, "見出し", "保存済み")
        journal.failSave = true
        assertFails { pc.saveDraft(1, "見出し", "保存失敗") }
        assertTrue(journal.edit!!.syntheticDelta.endsWith("保存済み"))
    }

    @Test fun staleAndForeignReplyFilesAreIgnored() {
        pc.connect()
        val stale = Envelope.seal("phone-1", Role.PHONE, phonePeers.peer("pc-1")!!,
            SyncMessage.OperationReport("not-a-pending-nonce", "op", OperationStatus.APPLIED, 99, null, 99, "x"))
        DirectoryMailbox.forPhone(root).send(MailboxNames.next(phoneClock.wall), stale)
        pc.saveDraft(1, "見出し", "古い応答を無視")
        pc.submit()
        assertEquals("古い応答を無視", store.article(1)!!.body); assertNull(journal.edit)
    }

    // ---------- finding 1: the newest editor text is saved before release / switch / close, or kept aside ----------

    private var workerView = ClientView()
    private var closeState = CloseState.OPEN
    private val frozenLog = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
    /** Runs on the worker thread: simulates the user typing while a worker task is in progress. */
    private var whileWorking: ((ClientView) -> Unit)? = null
    private var whilePolling: (() -> Unit)? = null
    private fun worker() = SyncWorker({ onChange ->
        PcSyncClient("合成PC", DirectoryMailbox.forPc(root), pcPeers, RecoveryController(journal) { pcClock },
            transportReady = { transport }, wallMillis = { phoneClock.wall },
            sleep = { ms -> pcClock += ms; phoneClock.advance(ms); whilePolling?.invoke(); if (pcPolls) phone.poll() },
            replyTimeoutMillis = 2_000, sessionMillis = 120_000, safetyAllowanceMillis = 5_000, onChange = onChange)
    }, onView = { workerView = it; whileWorking?.invoke(it) }, onCloseState = { closeState = it },
        onInputFrozen = { frozenLog += it }, autosaveDelayMillis = 600_000, tickPeriodMillis = 0)

    private fun journalBody() = EditPayload.decode(journal.edit!!.syntheticDelta).body

    @Test fun releaseRightAfterTypingSavesTheNewestTextFirst() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle(); assertTrue(workerView.sessionActive)
        w.editorChanged(EditorText(1, "見出し", "途中"))
        w.editorChanged(EditorText(1, "見出し", "最後の入力")) // autosave has not fired yet
        w.run("終了") { release() }; w.awaitIdle()
        assertEquals("最後の入力", journalBody()); assertNull(workerView.unsaved); assertFalse(workerView.sessionActive)
        w.shutdown()
    }

    @Test fun switchingArticlesSavesTheCurrentTextBeforeTheNextEditorStarts() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        w.editorChanged(EditorText(1, "見出し", "切り替え前の入力"))
        w.flushNow()
        w.editorChanged(EditorText(2, null, "別の記事")) // must not cancel the save above
        w.run("終了") { release() }; w.awaitIdle()
        assertEquals("切り替え前の入力", journalBody())
        assertEquals(2L, workerView.unsaved?.articleId, "the other article's text is kept aside, not dropped")
        w.shutdown()
    }

    @Test fun textTypedAfterExpiryIsKeptOutsideTheEditorAndRestoredAtTheSameBase() {
        val w = worker()
        w.run("接続") { connect(); saveDraft(1, "見出し", "保存済み") }; w.awaitIdle()
        advance(130_000) // PC lease and phone lock both end before the autosave runs
        w.editorChanged(EditorText(1, "見出し", "期限後の入力"))
        w.run("確認") { tick() }; w.awaitIdle()
        assertFalse(workerView.sessionActive)
        assertEquals("期限後の入力", workerView.unsaved?.body); assertEquals("保存済み", journalBody())
        w.run("接続") { connect() }; w.awaitIdle()
        assertTrue(workerView.sessionActive); assertNull(workerView.unsaved)
        assertEquals("期限後の入力", journalBody()); assertEquals("スマホの本文", store.article(1)!!.body)
        w.shutdown()
    }

    @Test fun unsavedTextIsNotRestoredAfterThePhoneMovedOn() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        transport = false; advance(130_000)
        w.editorChanged(EditorText(1, "見出し", "保存できなかった入力"))
        w.run("確認") { tick() }; w.awaitIdle()
        gate.tick(); gate.localWrite { store.localEdit(2, "スマホで別の記事を更新", phoneClock.wall) }
        transport = true
        w.run("接続") { connect() }; w.awaitIdle()
        assertEquals("保存できなかった入力", workerView.unsaved?.body, "kept for export, never applied to a moved base")
        assertNull(journal.edit); assertEquals("スマホの本文", store.article(1)!!.body)
        assertEquals("保存できなかった入力", runCatching { var t: String? = null; w.run("書き出し") { t = unsavedPlainText() }; w.awaitIdle(); t }.getOrNull()?.substringAfter("\n\n"))
        w.shutdown()
    }

    @Test fun closeSavesFirstAndIsBlockedOnlyByTextThatCannotBeSaved() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        w.editorChanged(EditorText(1, "見出し", "閉じる直前"))
        w.requestClose(); w.awaitIdle()
        assertEquals(CloseState.READY, closeState); assertEquals("閉じる直前", journalBody())
        w.cancelClose()
        w.editorChanged(EditorText(1, "見出し", "許可なしの入力")) // session already released by the close above
        w.requestClose(); w.awaitIdle()
        assertEquals(CloseState.BLOCKED_BY_UNSAVED, closeState); assertEquals("許可なしの入力", workerView.unsaved?.body)
        w.closeDiscardingUnsaved(workerView.unsaved); w.awaitIdle()
        assertEquals(CloseState.READY, closeState); assertNull(workerView.unsaved); assertEquals("閉じる直前", journalBody())
        w.shutdown()
    }

    // ---------- re-review: input racing with close and submit ----------

    @Test fun inputArrivingWhileClosingIsKeptAndBlocksTheClose() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        w.editorChanged(EditorText(1, "見出し", "最初の入力"))
        var typed = false
        whileWorking = { v -> if (!v.sessionActive && !typed) { typed = true; w.editorChanged(EditorText(1, "見出し", "終了処理中の追加入力")) } }
        w.requestClose()
        assertEquals(true, frozenLog.last(), "input is frozen the moment close is requested")
        w.awaitIdle()
        assertTrue(typed)
        assertEquals("最初の入力", journalBody())
        assertEquals("終了処理中の追加入力", workerView.unsaved?.body, "late input is kept, not dropped")
        assertEquals(CloseState.BLOCKED_BY_UNSAVED, closeState)
        assertFalse(w.confirmExit())
        w.shutdown()
    }

    @Test fun inputAfterCloseWasDecidedReopensTheDecision() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        w.requestClose(); w.awaitIdle()
        assertEquals(CloseState.READY, closeState)
        w.editorChanged(EditorText(1, "見出し", "決定直後の入力")) // an event already in flight
        assertEquals(CloseState.BLOCKED_BY_UNSAVED, closeState)
        assertFalse(w.confirmExit(), "exit is refused once input arrived")
        w.flushNow(); w.awaitIdle()
        assertEquals("決定直後の入力", workerView.unsaved?.body)
        // Discarding what the dialog showed works; newer input in between would block again.
        val shown = workerView.unsaved
        w.editorChanged(EditorText(1, "見出し", "ダイアログ表示後の入力"))
        w.closeDiscardingUnsaved(shown); w.awaitIdle()
        assertEquals(CloseState.BLOCKED_BY_UNSAVED, closeState)
        assertEquals("ダイアログ表示後の入力", workerView.unsaved?.body)
        w.closeDiscardingUnsaved(workerView.unsaved); w.awaitIdle()
        assertEquals(CloseState.READY, closeState); assertTrue(w.confirmExit()); assertEquals(CloseState.EXITING, closeState)
        w.shutdown()
    }

    @Test fun inputTypedWhileSubmittingIsNeitherOverwrittenNorLost() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        var typed = false
        whilePolling = { if (!typed) { typed = true; w.editorChanged(EditorText(1, "見出し", "提出後の追加入力")) } }
        w.submit(EditorText(1, "見出し", "提出した本文"))
        assertEquals(true, frozenLog.last(), "input is frozen the moment submit is pressed")
        w.awaitIdle(); whilePolling = null
        assertTrue(typed)
        assertEquals("提出した本文", store.article(1)!!.body, "exactly the text on screen at the click is applied")
        assertEquals(1, store.operationCount())
        assertEquals("提出後の追加入力", workerView.unsaved?.body, "newer input is kept aside, not overwritten")
        assertEquals(false, frozenLog.last())
        w.shutdown()
    }

    @Test fun inputTypedWhileSubmitWaitsForAnUnansweredReplyIsKept() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        phoneMailbox.dropNextSend = true // reply lost: submit times out, the edit stays pending as submitted
        var typed = false
        whilePolling = { if (!typed) { typed = true; w.editorChanged(EditorText(1, "見出し", "応答待ち中の入力")) } }
        w.submit(EditorText(1, "見出し", "応答が来ない提出"))
        w.awaitIdle(); whilePolling = null
        assertTrue(journal.edit!!.submitted); assertEquals("応答が来ない提出", journalBody())
        assertEquals("応答待ち中の入力", workerView.unsaved?.body)
        w.shutdown()
    }

    // ---------- second re-review: tasks already queued, and saves handed to another task ----------

    private fun blockWorker(w: SyncWorker): java.util.concurrent.CountDownLatch {
        val gate = java.util.concurrent.CountDownLatch(1)
        w.run("先行処理") { gate.await(30, java.util.concurrent.TimeUnit.SECONDS) }
        return gate
    }

    @Test fun aTaskQueuedBeforeSubmitCannotSaveLaterInputAheadOfTheSubmission() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        val gate = blockWorker(w)
        w.run("確認") { tick() } // already queued before the click; flushes the input slot when it runs
        w.submit(EditorText(1, "見出し", "押した時点の文章"))
        w.editorChanged(EditorText(1, "見出し", "押した後の追加入力")) // event already in flight
        gate.countDown(); w.awaitIdle()
        assertEquals("押した時点の文章", store.article(1)!!.body)
        assertEquals(1, store.operationCount())
        assertEquals("押した後の追加入力", workerView.unsaved?.body, "kept aside, neither saved first nor overwritten")
        w.shutdown()
    }

    @Test fun aSaveHandedToAnotherTaskKeepsTheCloseUndecidedUntilItFinishes() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        val gate = blockWorker(w)
        w.requestClose()
        w.editorChanged(EditorText(1, "見出し", "終了中に預けた入力"))
        w.flushNow() // takes the slot and queues its own save behind the close task
        gate.countDown(); w.awaitIdle()
        assertEquals("終了中に預けた入力", workerView.unsaved?.body)
        assertEquals(CloseState.BLOCKED_BY_UNSAVED, closeState, "close never became READY while the save was pending")
        assertFalse(w.confirmExit())
        w.shutdown()
    }

    @Test fun closeRequestedDuringSubmitWaitsForTheSubmitAndItsLaterInput() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        val gate = blockWorker(w)
        w.submit(EditorText(1, "見出し", "提出中に閉じる"))
        w.editorChanged(EditorText(1, "見出し", "提出中の追加入力"))
        w.requestClose()
        gate.countDown(); w.awaitIdle()
        assertEquals("提出中に閉じる", store.article(1)!!.body)
        assertEquals("提出中の追加入力", workerView.unsaved?.body)
        assertEquals(CloseState.BLOCKED_BY_UNSAVED, closeState)
        w.shutdown()
    }

    @Test fun noOtherTaskDeclaresTheCloseReadyBeforeThePhoneIsReleased() {
        val w = worker()
        w.run("接続") { connect() }; w.awaitIdle()
        val latch = blockWorker(w)
        var stateAfterTick: CloseState? = null
        w.run("確認") { tick(); stateAfterTick = closeState } // queued ahead of the close task
        w.requestClose()
        latch.countDown(); w.awaitIdle()
        assertEquals(CloseState.CLOSING, stateAfterTick, "not READY before the close task released the phone")
        assertEquals(CloseState.READY, closeState); assertFalse(workerView.sessionActive)
        phone.poll(); gate.localWrite { } // the phone got the release and is unlocked
        w.shutdown()
    }

    // ---------- finding 2: unreadable pairing data is handled on screen, never by losing edits ----------

    private class FakeProtector {
        var failRead: Exception? = null
        fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
            if (!encrypt) failRead?.let { throw it }
            return ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }
        }
    }

    @Test fun unreadablePairingDataCanBeRetriedOrKeptAsideWithoutTouchingThePendingEdit() {
        pc.connect(); pc.saveDraft(1, "見出し", "ペアリング情報が壊れる前の編集"); pc.release(); phone.poll()
        val dir = kotlin.io.path.createTempDirectory("ea-pairing")
        val protector = FakeProtector()
        val firstStore = ProtectedPcPeerStore(dir, protector::transform) { 1_000 }
        firstStore.open(); firstStore.save(pcPeers.peer()!!)
        val originalBytes = java.nio.file.Files.readAllBytes(dir.resolve("pairing.dpapi"))

        // Transient failure at start: shown, retried, nothing moved.
        protector.failRead = poc.ProtectionUnavailableException()
        val pairingStore = ProtectedPcPeerStore(dir, protector::transform) { 2_000 }
        val restarted = PcSyncClient("合成PC", DirectoryMailbox.forPc(root), pairingStore, RecoveryController(journal) { pcClock },
            transportReady = { transport }, wallMillis = { phoneClock.wall },
            sleep = { ms -> pcClock += ms; phoneClock.advance(ms); phone.poll() }, replyTimeoutMillis = 2_000,
            sessionMillis = 120_000, safetyAllowanceMillis = 5_000)
        restarted.initialize() // must not throw
        assertEquals(PairingStoreProblem.TRANSIENT, restarted.view.pairingStoreProblem)
        assertEquals("ペアリング情報が壊れる前の編集", restarted.view.pending?.body, "pending edit still visible")
        assertFailsWith<IllegalStateException> { restarted.connect() }
        assertFailsWith<IllegalStateException> { restarted.startPairing() }
        protector.failRead = null
        assertTrue(restarted.openPairingStore()); assertNull(restarted.view.pairingStoreProblem)
        assertEquals("phone-1", restarted.view.paired?.peerId)

        // Permanent failure: confirmed reset keeps the file aside; the journal is untouched; pairing again works.
        protector.failRead = poc.DecryptionRejectedException(poc.RecoveryFailureReason.KEY_STATE)
        assertFalse(restarted.openPairingStore())
        assertEquals(PairingStoreProblem.KEY_STATE, restarted.view.pairingStoreProblem)
        val journalBefore = journal.edit
        restarted.resetPairingStore()
        protector.failRead = null
        assertNull(restarted.view.pairingStoreProblem); assertNull(restarted.view.paired)
        assertContentEquals(originalBytes, java.nio.file.Files.readAllBytes(dir.resolve("pairing-unreadable-2000.dpapi")))
        assertNotEquals("pc-1", pairingStore.pcId)
        assertSame(journalBefore, journal.edit)

        phone.beginPairing()
        restarted.startPairing(); phone.poll()
        assertTrue(restarted.pollPairing()); phone.poll()
        restarted.confirmPairingCode(); phone.poll()
        phone.confirmPairingCode(); restarted.pollPairing()
        assertEquals("phone-1", restarted.view.paired?.peerId)
        restarted.tick(); restarted.connect()
        assertTrue(restarted.view.sessionActive, restarted.view.message)
        assertEquals("ペアリング情報が壊れる前の編集", restarted.view.pending?.body)
        restarted.submit()
        assertEquals("ペアリング情報が壊れる前の編集", store.article(1)!!.body)
        dir.toFile().deleteRecursively()
    }

    @Test fun pairingDataWithABadFormatIsReportedAsSuch() {
        val dir = kotlin.io.path.createTempDirectory("ea-pairing")
        val protector = FakeProtector()
        java.nio.file.Files.write(dir.resolve("pairing.dpapi"), protector.transform("not a pairing file".toByteArray(), true))
        val store = ProtectedPcPeerStore(dir, protector::transform)
        assertEquals(PairingStoreProblem.INVALID_FORMAT, assertFailsWith<PairingStoreUnavailableException> { store.open() }.problem)
        assertFailsWith<IllegalStateException> { store.pcId }
        dir.toFile().deleteRecursively()
    }

    @Test fun editPayloadRoundTrips() {
        val edit = ArticleTextEdit(5, "a:b", "本文:😀")
        assertEquals(edit, EditPayload.decode(EditPayload.encode(edit)))
        val untitled = ArticleTextEdit(5, null, "-:x")
        assertEquals(untitled, EditPayload.decode(EditPayload.encode(untitled)))
    }
}

class WpdListingTest {
    @Test fun listingParserIsStrict() {
        val ok = "object-id=o1\nname=1760000000000-0123456789abcdef.eam\nsize=10\n"
        assertEquals(listOf(Triple("o1", "1760000000000-0123456789abcdef.eam", 10L)), WpdMailbox.parseListing(ok))
        assertFails { WpdMailbox.parseListing("object-id=o1\nname=photo.jpg\nsize=1\n") }
        assertFails { WpdMailbox.parseListing("object-id=o1\nname=1760000000000-0123456789abcdef.eam\n") }
    }
}
