package poc
import kotlin.test.*

class RequestBoundGrantTest {
    private val identity = DatasetIdentity("fake-request-phone", "fake-incarnation")
    private class Journal : DeltaJournal {
        var edit: RecoveryEdit? = null
        var revision: Long? = null
        var epoch: Long? = null
        var failEpoch = false
        var failRevision = false
        override fun load() = edit
        override fun save(edit: RecoveryEdit) { this.edit = edit }
        override fun clear() { edit = null }
        override fun revisionFloor(identity: DatasetIdentity) = revision
        override fun recordVerifiedRevision(identity: DatasetIdentity, revision: Long) {
            check(!failRevision); this.revision = maxOf(this.revision ?: 0, revision)
        }
        override fun verifiedEpoch(identity: DatasetIdentity) = epoch
        override fun recordVerifiedEpoch(identity: DatasetIdentity, epoch: Long) {
            check(!failEpoch); this.epoch = maxOf(this.epoch ?: 0, epoch)
        }
    }
    private fun ready(j: Journal = Journal()) = testController(j).apply { observeTransport(true) }
    private fun request(pc: RecoveryController, sent: Long = 0, renewal: Boolean = false) =
        pc.beginGrantRequest(identity, sent, 100, 10, renewal)
    private fun reply(r: PendingGrantRequest, duration: Long = 100, epoch: Long = r.minimumEpoch,
        session: String = "synthetic-session") = Grant(identity.device, session, 7, duration,
            identity.incarnation, r.requestNonce, epoch)

    @Test fun deadlineUsesSendTimeAndAllowanceRatherThanResponseArrival() {
        val pc = ready(); val r = request(pc, 10)
        pc.acceptGrant(reply(r), r, 50); pc.loadSyntheticSnapshot(byteArrayOf(1), 50)
        assertTrue(pc.canEdit(99)); assertFalse(pc.canEdit(100)); assertFalse(pc.hasSnapshot)
    }
    @Test fun exactDeadlineAndLaterRepliesAreRejectedAndCannotBeRetried() {
        for (at in listOf(90L, 91L)) {
            val pc = ready(); val r = request(pc)
            assertFails { pc.acceptGrant(reply(r), r, at) }
            assertFails { pc.acceptGrant(reply(r), r, at) }
            assertFalse(pc.canEdit(at)); assertFalse(pc.hasSnapshot)
        }
    }
    @Test fun wrongNonceConsumesAttemptAndCorrectedReplyCannotReuseIt() {
        val pc = ready(); val r = request(pc)
        assertFails { pc.acceptGrant(reply(r).copy(requestNonce = "other-request"), r, 1) }
        assertFails { pc.acceptGrant(reply(r), r, 2) }; assertFalse(pc.canEdit(2))
    }
    @Test fun replacementCancellationForeignCapabilityAndRestartInvalidateRequests() {
        val j = Journal(); val pc = ready(j); val first = request(pc); val second = request(pc)
        assertNotEquals(first.requestNonce, second.requestNonce)
        assertFails { pc.acceptGrant(reply(first), first, 1) }
        assertFails { pc.acceptGrant(reply(second), second, 2) } // failure suspends all outstanding work
        val cancelled = request(pc, 3); pc.cancelGrantRequest(cancelled)
        assertFails { pc.acceptGrant(reply(cancelled), cancelled, 4) }
        val foreign = request(ready(), 5)
        assertFails { pc.acceptGrant(reply(foreign), foreign, 5) }
        val beforeRestart = request(pc, 6)
        assertFails { ready(j).acceptGrant(reply(beforeRestart), beforeRestart, 6) }
    }
    @Test fun acceptedRequestIsOneUseEvenAfterExplicitSuspension() {
        val pc = ready(); val r = request(pc)
        pc.acceptGrant(reply(r), r, 1); pc.suspendSession()
        assertFails { pc.acceptGrant(reply(r), r, 2) }; assertFalse(pc.canEdit(2))
    }
    @Test fun wrongIdentityIncarnationDurationAndNegativeEpochAreRejected() {
        val invalid: List<(Grant) -> Grant> = listOf(
            { it.copy(device = "other") }, { it.copy(datasetInstance = "other") },
            { it.copy(validityDuration = 10) }, { it.copy(validityDuration = 101) },
            { it.copy(epoch = -1) })
        invalid.forEach { change ->
            val pc = ready(); val r = request(pc)
            assertFails { pc.acceptGrant(change(reply(r)), r, 1) }; assertFalse(pc.canEdit(1))
        }
    }
    @Test fun staleAndEqualAcquisitionEpochsAreRejectedAcrossControllerRestart() {
        val j = Journal(); val pc = ready(j); val r = request(pc)
        pc.acceptGrant(reply(r, epoch = 9), r, 1); pc.suspendSession()
        for (epoch in listOf(8L, 9L)) {
            val other = ready(j); val next = request(other)
            assertEquals(10L, next.minimumEpoch)
            assertFails { other.acceptGrant(reply(next, epoch = epoch), next, 1) }
            assertEquals(9L, j.epoch)
        }
        val other = ready(j); val next = request(other)
        other.acceptGrant(reply(next, epoch = 10), next, 1); assertEquals(10L, j.epoch)
    }
    @Test fun epochPersistenceFailureNeverEnablesEditing() {
        val j = Journal(); j.failEpoch = true
        val pc = ready(j); val r = request(pc)
        assertFails { pc.acceptGrant(reply(r), r, 1) }; assertFalse(pc.canEdit(1)); assertNull(j.epoch)
    }
    @Test fun revisionFailureAfterEpochSaveKeepsEpochAndRequiresNewAcquisition() {
        val j = Journal(); j.failRevision = true
        val pc = ready(j); val r = request(pc)
        assertFails { pc.acceptGrant(reply(r, epoch = 9), r, 1) }
        assertEquals(9L, j.epoch); assertFalse(pc.canEdit(1))
        j.failRevision = false
        val next = request(pc, 2); assertEquals(10L, next.minimumEpoch)
        pc.acceptGrant(reply(next), next, 3); assertTrue(pc.canEdit(3))
    }
    @Test fun blockedStorageCannotActivateAnAlreadyExpiredGrant() {
        var clock = 0L
        val pc = RecoveryController(Journal()) { clock.also { clock += 100 } }
        pc.observeTransport(true); val r = request(pc)
        assertFails { pc.acceptGrant(reply(r), r, 1) }; assertFalse(pc.canEdit(101))
    }
    @Test fun renewalRetainsSnapshotAndEpochWithASeparatelyBoundRequest() {
        val j = Journal(); val pc = ready(j); val r = request(pc)
        pc.acceptGrant(reply(r, epoch = 9), r, 1); pc.loadSyntheticSnapshot(byteArrayOf(1), 1)
        val renew = request(pc, 50, true)
        pc.acceptGrant(reply(renew, epoch = 9), renew, 60)
        assertTrue(pc.hasSnapshot); assertEquals(9L, j.epoch)
        assertTrue(pc.canEdit(139)); assertFalse(pc.canEdit(140)); assertFalse(pc.hasSnapshot)
    }
    @Test fun lateRenewalAndRenewalAfterSuspensionOrDisconnectCannotReviveEditing() {
        for (mode in 0..2) {
            val pc = ready(); val r = request(pc); pc.acceptGrant(reply(r), r, 1)
            val renew = request(pc, 50, true)
            if (mode == 1) pc.suspendSession()
            if (mode == 2) { pc.observeTransport(false); pc.observeTransport(true) }
            assertFails { pc.acceptGrant(reply(renew), renew, if (mode == 0) 90 else 60) }
            assertFalse(pc.canEdit(100))
        }
    }
    @Test fun renewalWithChangedSessionEpochOrRevisionIsRejected() {
        val invalid: List<(Grant) -> Grant> = listOf({ it.copy(session = "other") },
            { it.copy(epoch = 1) }, { it.copy(baseRevision = 8) })
        invalid.forEach { change ->
            val pc = ready(); val r = request(pc); pc.acceptGrant(reply(r), r, 1)
            val renew = request(pc, 50, true)
            assertFails { pc.acceptGrant(change(reply(renew)), renew, 60) }; assertFalse(pc.canEdit(60))
        }
    }
    @Test fun storageCrossingTheOldDeadlineCannotReviveByRenewal() {
        var delayed = false; var clock = 0L
        val pc = RecoveryController(Journal()) { clock.also { if (delayed) clock += 50 } }
        pc.observeTransport(true); val r = request(pc)
        pc.acceptGrant(reply(r), r, 1); pc.loadSyntheticSnapshot(byteArrayOf(1), 1)
        val renew = request(pc, 50, true); delayed = true
        assertFails { pc.acceptGrant(reply(renew), renew, 60) } // completed at 110: old=90, new=140
        assertFalse(pc.hasSnapshot); assertFalse(pc.canEdit(110))
    }
    @Test fun expiryRequiresFreshHigherEpochAcquisitionAndNewSnapshot() {
        val pc = ready(); val r = request(pc); pc.acceptGrant(reply(r), r, 1)
        pc.loadSyntheticSnapshot(byteArrayOf(1), 1)
        val next = request(pc, 100)
        assertFalse(pc.hasSnapshot)
        assertEquals(1L, next.minimumEpoch)
        pc.acceptGrant(reply(next), next, 101)
        assertTrue(pc.canEdit(101)); assertFalse(pc.hasSnapshot)
    }
    @Test fun durationOverflowExhaustedEpochAndClockRollbackFailClosed() {
        val pc = ready()
        assertFails { pc.beginGrantRequest(identity, Long.MAX_VALUE, 100, 0) }
        val exhausted = Journal().apply { epoch = Long.MAX_VALUE }
        assertFails { request(ready(exhausted)) }
        val other = ready(); val r = request(other, 10)
        other.acceptGrant(reply(r), r, 20)
        assertFalse(other.canEdit(19)); assertFalse(other.hasSnapshot)
        assertFails { request(other, 21, true) }
    }
}
