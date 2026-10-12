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
        advance(130_000); phone.poll(); gate.tick()
        transport = true; pc.tick()
        pc.connect() // same base revision: editing resumes with the protected draft
        assertTrue(pc.view.sessionActive)
        pc.submit()
        assertEquals("切断前の下書き", store.article(1)!!.body)
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
