package everyarticle.sync.protocol

import kotlin.test.*

class CodecAndEnvelopeTest {
    @Test fun messagesRoundTripCanonically() {
        val edit = ArticleTextEdit(7, "タイトル", "本文😀\n二行目")
        val messages = listOf(
            SyncMessage.StatusRequest("n1"),
            SyncMessage.SessionRequest("n2", SessionMode.RENEW, "inc", "s", 4, 60_000),
            SyncMessage.SessionGrant("n3", "s", 4, "phone", "inc", 9, 60_000),
            SyncMessage.Snapshot("n4", "s", 4, "inc", 9, listOf(ArticleSnapshot(7, "TOPIC", "2026-10-12", null, "body", 5))),
            SyncMessage.SubmitEdit("n5", "s", 4, "op", "inc", 9, edit, operationDigest("op", "inc", 9, edit)),
            SyncMessage.OperationReport("n6", "op", OperationStatus.CONFLICT, null, RefusalReason.REVISION_MISMATCH, 10, "inc")
        )
        for (m in messages) {
            val bytes = m.encode()
            val decoded = SyncMessage.decode(bytes)
            assertContentEquals(bytes, decoded.encode())
        }
    }
    @Test fun strictDecodingRejectsTrailingBytesMalformedTextAndUnknownTypes() {
        val bytes = SyncMessage.StatusRequest("n").encode()
        assertFailsWith<ProtocolFormatException> { SyncMessage.decode(bytes + 0) }
        assertFailsWith<ProtocolFormatException> { SyncMessage.decode(byteArrayOf(99)) }
        val malformed = CanonicalWriter().u8(10).bytes(byteArrayOf(0xC3.toByte())).toByteArray()
        assertFailsWith<ProtocolFormatException> { SyncMessage.decode(malformed) }
        assertFailsWith<ProtocolFormatException> { ArticleTextEdit(1, null, "\uD800") .canonicalBytes() }
    }
    @Test fun digestBindsOperationIncarnationRevisionAndContent() {
        val edit = ArticleTextEdit(1, "t", "b")
        val base = operationDigest("op", "inc", 1, edit)
        assertFalse(base.contentEquals(operationDigest("op2", "inc", 1, edit)))
        assertFalse(base.contentEquals(operationDigest("op", "inc2", 1, edit)))
        assertFalse(base.contentEquals(operationDigest("op", "inc", 2, edit)))
        assertFalse(base.contentEquals(operationDigest("op", "inc", 1, edit.copy(body = "c"))))
        assertFalse(base.contentEquals(operationDigest("op", "inc", 1, edit.copy(title = null))))
    }
    @Test fun sealedEnvelopeAuthenticatesDirectionRecipientAndContent() {
        val peerForPhone = PairedPeer("pc", "PC", SyncCrypto.randomBytes(32))
        val peerForPc = PairedPeer("phone", "Phone", peerForPhone.pairingKey)
        val sealed = Envelope.seal("pc", Role.PC, peerForPc, SyncMessage.StatusRequest("n"))
        val opened = Envelope.open(sealed, "phone", Role.PC) { if (it == "pc") peerForPhone else null }
        assertTrue(opened.authenticated); assertEquals(SyncMessage.StatusRequest("n"), opened.message)
        // Reflected back as if written by the phone: different direction key.
        assertFailsWith<ProtocolFormatException> { Envelope.open(sealed, "phone", Role.PHONE) { peerForPhone } }
        assertFailsWith<ProtocolFormatException> { Envelope.open(sealed, "other", Role.PC) { peerForPhone } }
        assertFailsWith<ProtocolFormatException> { Envelope.open(sealed, "phone", Role.PC) { null } }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<ProtocolFormatException> { Envelope.open(tampered, "phone", Role.PC) { peerForPhone } }
        assertFailsWith<ProtocolFormatException> { Envelope.open(sealed.copyOf(sealed.size - 3), "phone", Role.PC) { peerForPhone } }
    }
}

class PairingTest {
    @Test fun bothSidesDeriveSameCodeAndKeyAndConfirm() {
        val pc = PcPairing("pc", "Desk")
        val phone = PhonePairing("phone", "Phone", pc.start())
        val (reveal, pcResult) = pc.onAccept(phone.accept())
        val phoneResult = phone.onReveal(reveal)
        assertEquals(pcResult.sas, phoneResult.sas); assertEquals(6, pcResult.sas.length)
        assertContentEquals(pcResult.pairingKey, phoneResult.pairingKey)
        val forPc = pcResult.verifyPeerConfirmation(Role.PHONE, SyncMessage.PairConfirm("phone", phoneResult.confirmationMac(Role.PHONE)))
        assertEquals("phone", forPc.peerId)
        assertFailsWith<ProtocolFormatException> {
            pcResult.verifyPeerConfirmation(Role.PHONE, SyncMessage.PairConfirm("phone", pcResult.confirmationMac(Role.PC)))
        }
    }
    @Test fun keySubstitutionBreaksCommitmentOrCodes() {
        val pc = PcPairing("pc", "Desk")
        val start = pc.start()
        val phone = PhonePairing("phone", "Phone", start)
        phone.accept()
        val attacker = PcPairing("pc", "Desk") // different key, cannot match the original commitment
        val (forgedReveal, _) = attacker.onAccept(PhonePairing("phone", "Phone", attacker.start()).accept())
        assertFailsWith<ProtocolFormatException> { phone.onReveal(forgedReveal) }
        // A man in the middle runs separate pairings with each side; the codes differ (1 in 10^6 chance of a match).
        val pcSide = PcPairing("pc", "Desk")
        val (revealToPc, pcResult) = pcSide.onAccept(PhonePairing("phone", "Phone", pcSide.start()).accept())
        val attackerAsPc = PcPairing("pc", "Desk")
        val realPhone = PhonePairing("phone", "Phone", attackerAsPc.start())
        val (attackerReveal, _) = attackerAsPc.onAccept(realPhone.accept())
        assertNotEquals(pcResult.sas, realPhone.onReveal(attackerReveal).sas)
        assertNotNull(revealToPc)
    }
}

class AuthorityTest {
    private val clock = ManualClock()
    private val gate = WriteGate(clock)
    private val store = InMemoryAuthorityStore(listOf(ArticleText(1, "TOPIC", "2026-10-12", "t", "original", 1), ArticleText(2, "TOPIC", "2026-10-13", null, "two", 1)))
    private val authority = PhoneAuthority("phone", store, gate, clock).apply { startProcess() }
    private val inc get() = store.snapshotState().incarnation

    private fun acquire(nonce: String = "a", peer: String = "pc", duration: Long = 60_000): SyncMessage =
        authority.handle(peer, SyncMessage.SessionRequest(nonce, SessionMode.ACQUIRE, inc, null, null, duration))!!
    private fun submit(g: SyncMessage.SessionGrant, op: String, body: String, base: Long = g.baseRevision, peer: String = "pc"): SyncMessage.OperationReport {
        val edit = ArticleTextEdit(1, "t", body)
        return authority.handle(peer, SyncMessage.SubmitEdit("s-$op", g.sessionId, g.epoch, op, g.datasetIncarnation, base, edit,
            operationDigest(op, g.datasetIncarnation, base, edit))) as SyncMessage.OperationReport
    }
    private fun query(op: String, body: String, base: Long): SyncMessage.OperationReport {
        val edit = ArticleTextEdit(1, "t", body)
        return authority.handle("pc", SyncMessage.OperationQuery("q", op, inc, base, operationDigest(op, inc, base, edit))) as SyncMessage.OperationReport
    }

    @Test fun grantLocksAllLocalWritesUntilReleaseOrExpiryWithoutPcMessage() {
        val g = acquire() as SyncMessage.SessionGrant
        assertFailsWith<EditLockedException> { gate.localWrite { } }
        assertIs<SyncMessage.SessionRefused>(acquire("b", "other-pc"))
        clock.advance(60_000)
        gate.localWrite { store.localEdit(2, "local", clock.wall) } // abandoned lock released by expiry alone
        assertNull(gate.currentLock)
        val g2 = acquire("c") as SyncMessage.SessionGrant
        assertTrue(g2.epoch > g.epoch)
        authority.handle("pc", SyncMessage.SessionRelease(g2.sessionId, g2.epoch))
        gate.localWrite { }
    }
    @Test fun applyIsAtomicIdempotentAndBumpsRevision() {
        val g = acquire() as SyncMessage.SessionGrant
        val first = submit(g, "op1", "pc-edit")
        assertEquals(OperationStatus.APPLIED, first.status); assertEquals(g.baseRevision + 1, first.resultingRevision)
        assertEquals("pc-edit", store.article(1)!!.body)
        val duplicate = submit(g, "op1", "pc-edit")
        assertEquals(OperationStatus.APPLIED, duplicate.status); assertEquals(first.resultingRevision, duplicate.resultingRevision)
        assertEquals(1, store.operationCount()); assertEquals(g.baseRevision + 1, store.snapshotState().revision)
        val reused = submit(g, "op1", "different")
        assertEquals(OperationStatus.REJECTED, reused.status); assertEquals(RefusalReason.OPERATION_ID_REUSED, reused.reason)
        assertEquals("pc-edit", store.article(1)!!.body)
    }
    @Test fun lostReplyIsRecoveredByQueryOutsideTheSession() {
        val g = acquire() as SyncMessage.SessionGrant
        submit(g, "op1", "pc-edit") // reply lost
        clock.advance(120_000) // session expired, no PC message
        val report = query("op1", "pc-edit", g.baseRevision)
        assertEquals(OperationStatus.APPLIED, report.status); assertEquals(g.baseRevision + 1, report.resultingRevision)
    }
    @Test fun expiredStaleOrForeignSessionsNeverWrite() {
        val g = acquire() as SyncMessage.SessionGrant
        clock.advance(60_000)
        val late = submit(g, "op1", "late")
        assertEquals(OperationStatus.NOT_APPLIED, late.status); assertEquals(RefusalReason.SESSION_EXPIRED, late.reason)
        val g2 = acquire("b") as SyncMessage.SessionGrant
        val stale = submit(g, "op2", "stale") // old epoch while a newer session holds the lock
        assertEquals(OperationStatus.NOT_APPLIED, stale.status); assertEquals(RefusalReason.NO_SESSION, stale.reason)
        val foreign = submit(g2, "op3", "foreign", peer = "intruder")
        assertEquals(RefusalReason.NO_SESSION, foreign.reason)
        assertEquals("original", store.article(1)!!.body)
    }
    @Test fun processRestartInvalidatesEveryOldSessionToken() {
        val g = acquire() as SyncMessage.SessionGrant
        val restartedGate = WriteGate(clock)
        val restarted = PhoneAuthority("phone", store, restartedGate, clock).apply { startProcess() }
        val edit = ArticleTextEdit(1, "t", "x")
        val r = restarted.handle("pc", SyncMessage.SubmitEdit("n", g.sessionId, g.epoch, "op", inc, g.baseRevision, edit,
            operationDigest("op", inc, g.baseRevision, edit))) as SyncMessage.OperationReport
        assertEquals(OperationStatus.NOT_APPLIED, r.status)
        restartedGate.localWrite { } // lock was memory-only; local editing resumes after restart
        assertTrue(store.snapshotState().epoch > g.epoch)
    }
    @Test fun renewalExtendsOnlyALiveMatchingSessionAndNeverRevives() {
        val g = acquire(duration = 30_000) as SyncMessage.SessionGrant
        clock.advance(20_000)
        val renewed = authority.handle("pc", SyncMessage.SessionRequest("r", SessionMode.RENEW, inc, g.sessionId, g.epoch, 30_000))
        assertIs<SyncMessage.SessionGrant>(renewed); assertEquals(g.epoch, renewed.epoch)
        clock.advance(25_000)
        assertNotNull(gate.currentLock)
        clock.advance(10_000)
        val revived = authority.handle("pc", SyncMessage.SessionRequest("r2", SessionMode.RENEW, inc, g.sessionId, g.epoch, 30_000))
        assertEquals(RefusalReason.SESSION_EXPIRED, (revived as SyncMessage.SessionRefused).reason)
    }
    @Test fun deadlinePassingWhileWaitingForTheDatabaseStopsSubmitRenewAndSnapshot() {
        val g = acquire(duration = 30_000) as SyncMessage.SessionGrant
        clock.advance(29_000) // still live at gate entry ...
        store.beforeNextTransaction = { clock.advance(2_000) } // ... but expires while the database is busy
        val late = submit(g, "op1", "late")
        assertEquals(OperationStatus.NOT_APPLIED, late.status); assertEquals(RefusalReason.SESSION_EXPIRED, late.reason)
        assertEquals("original", store.article(1)!!.body); assertEquals(0, store.operationCount())
        assertEquals(g.baseRevision, store.snapshotState().revision)
        assertEquals(OperationStatus.NOT_APPLIED, query("op1", "late", g.baseRevision).status)

        val g2 = acquire("b", duration = 30_000) as SyncMessage.SessionGrant
        clock.advance(29_000)
        store.beforeNextTransaction = { clock.advance(2_000) }
        val renewal = authority.handle("pc", SyncMessage.SessionRequest("r", SessionMode.RENEW, inc, g2.sessionId, g2.epoch, 30_000))
        assertEquals(RefusalReason.SESSION_EXPIRED, (renewal as SyncMessage.SessionRefused).reason)
        gate.localWrite { } // not revived: the expired lock is gone

        val g3 = acquire("c", duration = 30_000) as SyncMessage.SessionGrant
        clock.advance(29_000)
        store.beforeNextTransaction = { clock.advance(2_000) }
        val snapshot = authority.handle("pc", SyncMessage.SnapshotRequest("s", g3.sessionId, g3.epoch))
        assertEquals(RefusalReason.SESSION_EXPIRED, (snapshot as SyncMessage.SessionRefused).reason)
    }
    @Test fun localEditBetweenSessionsTurnsPendingIntoConflictNotOverwrite() {
        val g = acquire() as SyncMessage.SessionGrant
        authority.handle("pc", SyncMessage.SessionRelease(g.sessionId, g.epoch))
        gate.localWrite { store.localEdit(1, "phone-edit", clock.wall) }
        val g2 = acquire("b") as SyncMessage.SessionGrant
        val r = submit(g2, "op1", "pc-edit", base = g.baseRevision)
        assertEquals(OperationStatus.CONFLICT, r.status); assertEquals("phone-edit", store.article(1)!!.body)
        assertEquals(OperationStatus.CONFLICT, query("op1", "pc-edit", g.baseRevision).status)
    }
    @Test fun notAppliedOnlyWhenBaseIsStillCurrent() {
        val g = acquire() as SyncMessage.SessionGrant
        assertEquals(OperationStatus.NOT_APPLIED, query("never-sent", "x", g.baseRevision).status)
    }
    @Test fun prunedHistoryAnswersUnknownInsteadOfGuessing() {
        val small = PhoneAuthority("phone", store, gate, clock, AuthorityPolicy(retainOperations = 1, retainMillis = 1)).apply { startProcess() }
        fun grant(n: String) = small.handle("pc", SyncMessage.SessionRequest(n, SessionMode.ACQUIRE, inc, null, null, 60_000)) as SyncMessage.SessionGrant
        fun apply(g: SyncMessage.SessionGrant, op: String) {
            val edit = ArticleTextEdit(1, "t", op)
            small.handle("pc", SyncMessage.SubmitEdit("n", g.sessionId, g.epoch, op, inc, g.baseRevision, edit, operationDigest(op, inc, g.baseRevision, edit)))
            small.handle("pc", SyncMessage.SessionRelease(g.sessionId, g.epoch))
        }
        val g1 = grant("1"); apply(g1, "opA"); clock.advance(10)
        apply(grant("2"), "opB"); clock.advance(10)
        apply(grant("3"), "opC")
        val edit = ArticleTextEdit(1, "t", "opA")
        val report = small.handle("pc", SyncMessage.OperationQuery("q", "opA", inc, g1.baseRevision, operationDigest("opA", inc, g1.baseRevision, edit))) as SyncMessage.OperationReport
        assertEquals(OperationStatus.UNKNOWN, report.status)
    }
    @Test fun restoreChangesIncarnationAndRejectsOldPendingAndSessions() {
        val g = acquire() as SyncMessage.SessionGrant
        authority.handle("pc", SyncMessage.SessionRelease(g.sessionId, g.epoch))
        gate.localWrite { store.restore(listOf(ArticleText(1, "TOPIC", "2026-10-12", "t", "restored", 2))) }
        val edit = ArticleTextEdit(1, "t", "pc")
        val q = authority.handle("pc", SyncMessage.OperationQuery("q", "op", g.datasetIncarnation, g.baseRevision,
            operationDigest("op", g.datasetIncarnation, g.baseRevision, edit))) as SyncMessage.OperationReport
        assertEquals(OperationStatus.REJECTED, q.status); assertEquals(RefusalReason.INCARNATION_MISMATCH, q.reason)
        val old = authority.handle("pc", SyncMessage.SessionRequest("x", SessionMode.ACQUIRE, g.datasetIncarnation, null, null, 60_000))
        assertEquals(RefusalReason.INCARNATION_MISMATCH, (old as SyncMessage.SessionRefused).reason)
    }
    @Test fun failedWriteRollsBackEverything() {
        val g = acquire() as SyncMessage.SessionGrant
        store.failNextWrite = true
        assertFails { submit(g, "op1", "pc-edit") }
        assertEquals("original", store.article(1)!!.body); assertEquals(0, store.operationCount())
        assertEquals(OperationStatus.NOT_APPLIED, query("op1", "pc-edit", g.baseRevision).status)
        assertEquals(OperationStatus.APPLIED, submit(g, "op1", "pc-edit").status)
    }
}

class PhoneEndpointTest {
    @Test fun pairingThenAuthenticatedRequestAndReplayIsDropped() {
        val root = kotlin.io.path.createTempDirectory("ea-endpoint").toFile()
        val clock = ManualClock()
        val gate = WriteGate(clock)
        val store = InMemoryAuthorityStore(listOf(ArticleText(1, "T", "2026-10-12", null, "b", 1)))
        val authority = PhoneAuthority("phone", store, gate, clock).apply { startProcess() }
        val phonePeers = InMemoryPeerStore()
        val states = mutableListOf<PhonePairingState>()
        val endpoint = PhoneEndpoint("phone", "Phone", DirectoryMailbox.forPhone(root), phonePeers, InMemoryReplayGuard(), authority, clock) { states += it }
        val pcBox = DirectoryMailbox.forPc(root)
        fun pcSend(bytes: ByteArray) = pcBox.send(MailboxNames.next(clock.wall), bytes)
        fun pcReceive() = pcBox.receive().also { files -> files.forEach { pcBox.delete(it.name) } }

        val pc = PcPairing("pc", "Desk")
        pcSend(Envelope.pairing("pc", "phone", pc.start()))
        endpoint.poll(); assertTrue(pcReceive().isEmpty(), "pairing is ignored unless the user opened it")
        endpoint.beginPairing()
        val attempt = PcPairing("pc", "Desk")
        pcSend(Envelope.pairing("pc", "phone", attempt.start())); endpoint.poll()
        val accept = Envelope.open(pcReceive().single().bytes, "pc", Role.PHONE) { null }.message as SyncMessage.PairAccept
        val (reveal, pcResult) = attempt.onAccept(accept)
        pcSend(Envelope.pairing("pc", "phone", reveal)); endpoint.poll()
        val compare = states.last() as PhonePairingState.CompareCode
        assertEquals(pcResult.sas, compare.sas)
        pcSend(Envelope.pairing("pc", "phone", SyncMessage.PairConfirm("pc", pcResult.confirmationMac(Role.PC)))); endpoint.poll()
        assertNull(phonePeers.peer("pc"), "not stored before the phone user confirms")
        endpoint.confirmPairingCode()
        assertIs<PhonePairingState.Paired>(states.last())
        val phoneConfirm = Envelope.open(pcReceive().single().bytes, "pc", Role.PHONE) { null }.message as SyncMessage.PairConfirm
        val peerOnPc = pcResult.verifyPeerConfirmation(Role.PHONE, phoneConfirm)

        val request = Envelope.seal("pc", Role.PC, peerOnPc, SyncMessage.StatusRequest("n1"))
        pcSend(request); endpoint.poll()
        val reply = Envelope.open(pcReceive().single().bytes, "pc", Role.PHONE) { peerOnPc }
        assertEquals("n1", (reply.message as SyncMessage.StatusReport).requestNonce)
        pcSend(request); endpoint.poll()
        assertTrue(pcReceive().isEmpty(), "replayed request file is dropped")
        root.deleteRecursively()
    }
}
