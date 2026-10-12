package poc
import kotlin.test.*

class RecoveryControllerTest {
    private class Journal : DeltaJournal {
        var edit: RecoveryEdit? = null
        var failRead = false
        var failSave = false
        var failClear = false
        var failFloorRead = false
        var failFloorSave = false
        val floors = mutableMapOf<DatasetIdentity, Long>()
        private val epochs = mutableMapOf<DatasetIdentity, Long>()
        override fun verifiedEpoch(identity: DatasetIdentity) = epochs[identity]
        override fun recordVerifiedGrant(identity: DatasetIdentity, revision: Long, epoch: Long) {
            recordVerifiedRevision(identity, revision); recordVerifiedEpoch(identity, epoch)
        }
        override fun recordVerifiedEpoch(identity: DatasetIdentity, epoch: Long) { epochs[identity] = maxOf(epochs[identity] ?: 0, epoch) }
        override fun load(): RecoveryEdit? { check(!failRead); return edit }
        override fun save(edit: RecoveryEdit) { check(!failSave); this.edit = edit }
        override fun clear() { check(!failClear); edit = null }
        override fun revisionFloor(identity: DatasetIdentity): Long? { check(!failFloorRead); return floors[identity] }
        override fun recordVerifiedRevision(identity: DatasetIdentity, revision: Long) {
            check(!failFloorSave); floors[identity] = maxOf(floors[identity] ?: 0, revision)
        }
    }
    private fun ready(journal: Journal) = testController(journal).apply {
        observeTransport(true)
        acceptFixtureGrant(FixtureGrant("fake-phone", "s1", 7, 100, "fake-dataset-v1"), 0)
        loadSyntheticSnapshot(byteArrayOf(1, 2, 3), 0)
    }
    @Test fun unreadableJournalStopsEditingWithoutDiscardingRecord() {
        val journal = Journal(); val pc = ready(journal)
        pc.stage("op1", "synthetic-change", 1); journal.failRead = true
        assertFailsWith<IllegalStateException> { pc.pending }
        assertFalse(pc.hasSnapshot); assertFalse(pc.canEdit(2))
        journal.failRead = false
        assertNotNull(pc.pending)
    }
    @Test fun secondEditCannotOverwritePendingDelta() {
        val journal = Journal(); val pc = ready(journal)
        pc.stage("op1", "original-change", 1)
        assertFailsWith<IllegalStateException> { pc.stage("op2", "replacement-change", 2) }
        assertEquals("original-change", pc.pending?.syntheticDelta)
    }
    @Test fun detachDropsSnapshotAndPermissionButPreservesDelta() {
        val journal = Journal(); val pc = ready(journal)
        pc.stage("op1", "synthetic-change", 1)
        pc.observeTransport(false)
        assertFalse(pc.hasSnapshot); assertFalse(pc.canEdit(2))
        assertEquals("synthetic-change", pc.pending?.syntheticDelta)
        pc.observeTransport(true)
        assertFalse(pc.canEdit(3)) // fresh transport cannot renew lease
    }
    @Test fun restartedControllerRestoresOnlyPendingDelta() {
        val journal = Journal(); ready(journal).stage("op1", "synthetic-change", 1)
        val restarted = testController(journal)
        assertFalse(restarted.hasSnapshot); assertFalse(restarted.canEdit(2))
        assertNotNull(restarted.recoveryCandidate("fake-phone", 7, "fake-dataset-v1"))
        assertFailsWith<IllegalStateException> { restarted.recoveryCandidate("other-phone", 7, "fake-dataset-v1") }
        assertFailsWith<IllegalStateException> { restarted.recoveryCandidate("fake-phone", 8, "fake-dataset-v1") }
    }
    @Test fun leaseExpiryReleasesSnapshotAndRetainsDelta() {
        val journal = Journal(); val pc = ready(journal)
        pc.stage("op1", "synthetic-change", 1); pc.tick(100)
        assertFalse(pc.hasSnapshot); assertNotNull(pc.pending)
    }
    @Test fun saveFailureStopsEditingWithoutReportingStagedDelta() {
        val journal = Journal(); val pc = ready(journal); journal.failSave = true
        assertFailsWith<IllegalStateException> { pc.stage("op1", "synthetic-change", 1) }
        assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot); assertNull(pc.pending)
    }
    private fun receipt(edit: RecoveryEdit, resultingRevision: Long = edit.metadata.baseRevision + 1) =
        AppliedReceipt(edit.metadata.device, edit.metadata.baseRevision, edit.metadata.operation,
            edit.contentDigest, resultingRevision, edit.metadata.datasetInstance!!)

    @Test fun mismatchedReceiptAndClearFailurePreservePending() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        val applied = receipt(pc.pending!!)
        assertEquals(ReconciliationResult.NOT_THIS_OPERATION, pc.reconcileApplied(applied.copy(operation = "old-op")))
        assertTrue(pc.canEdit(2)); assertTrue(pc.hasSnapshot)
        assertNotNull(pc.pending)
        journal.failClear = true
        assertFailsWith<IllegalStateException> { pc.reconcileApplied(applied) }
        assertNotNull(pc.pending); assertFalse(pc.canEdit(3)); assertFalse(pc.hasSnapshot)
    }
    @Test fun correctReceiptClearsDeltaAndRequiresNewGrant() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        assertEquals(ReconciliationResult.CLEARED, pc.reconcileApplied(receipt(pc.pending!!)))
        assertNull(pc.pending); assertFalse(pc.hasSnapshot); assertFalse(pc.canEdit(3))
    }

    @Test fun lostAppliedReplyAfterExpiryReconcilesBeforeNewRevisionGrant() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        val applied = receipt(pc.pending!!, 8) // phone has already committed revision 7 -> 8
        pc.tick(100)
        assertFailsWith<IllegalStateException> { pc.acceptFixtureGrant(FixtureGrant("fake-phone", "s2", 8, 200, "fake-dataset-v1"), 101) }
        assertNotNull(pc.pending) // reproduce the old dead end until the applied outcome is queried
        pc.observeTransport(false)
        assertEquals(ReconciliationResult.CLEARED, pc.reconcileApplied(applied)) // no MTP or lease required
        assertNull(pc.pending); assertFalse(pc.canEdit(101)); assertFalse(pc.hasSnapshot)
        pc.observeTransport(true)
        assertFalse(pc.canEdit(102))
        pc.acceptFixtureGrant(FixtureGrant("fake-phone", "s2", 8, 200, "fake-dataset-v1"), 102)
        assertTrue(pc.canEdit(103)); assertFalse(pc.hasSnapshot)
    }

    @Test fun allReceiptBindingMismatchesRetainExactPendingEdit() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        val saved = pc.pending!!; val good = receipt(saved)
        for (unrelated in listOf(good.copy(device = "other-phone"), good.copy(operation = "other-op"))) {
            assertEquals(ReconciliationResult.NOT_THIS_OPERATION, pc.reconcileApplied(unrelated))
            assertEquals(saved, pc.pending); assertTrue(pc.canEdit(2)); assertTrue(pc.hasSnapshot)
        }
        val invalid = listOf(good.copy(baseRevision = 6), good.copy(contentDigest = "0".repeat(64)),
            good.copy(resultingRevision = 7), good.copy(resultingRevision = -1))
        for (bad in invalid) {
            assertEquals(ReconciliationResult.CONFLICT, pc.reconcileApplied(bad))
            assertEquals(saved, pc.pending); assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot)
        }
    }

    @Test fun oldReceiptCannotClearDifferentPayloadWithReusedOperationName() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "first-payload", 1)
        val oldReceipt = receipt(pc.pending!!)
        pc.suspendSession()
        journal.edit = RecoveryEdit(PendingEdit("fake-phone", 7, "op1", "fake-dataset-v1"), "different-payload")
        assertEquals(ReconciliationResult.CONFLICT, pc.reconcileApplied(oldReceipt))
        assertEquals("different-payload", pc.pending!!.syntheticDelta)
    }

    @Test fun duplicateReceiptNeverClearsNewOperation() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        val applied = receipt(pc.pending!!)
        pc.reconcileApplied(applied)
        assertEquals(ReconciliationResult.NO_PENDING, pc.reconcileApplied(applied))
        pc.acceptFixtureGrant(FixtureGrant("fake-phone", "s2", 8, 200, "fake-dataset-v1"), 2)
        pc.loadSyntheticSnapshot(byteArrayOf(9), 2); pc.stage("op2", "new-change", 3)
        assertEquals(ReconciliationResult.NOT_THIS_OPERATION, pc.reconcileApplied(applied))
        assertEquals("op2", pc.pending!!.metadata.operation); assertTrue(pc.canEdit(4)); assertTrue(pc.hasSnapshot)
    }

    @Test fun receiptReadFailureStopsEditingAndPreservesRecord() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        val applied = receipt(pc.pending!!); journal.failRead = true
        assertFailsWith<IllegalStateException> { pc.reconcileApplied(applied) }
        assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot)
        journal.failRead = false
        assertNotNull(pc.pending)
    }

    @Test fun digestBindsIdentityRevisionOperationAndUnambiguousPayload() {
        val original = RecoveryEdit(PendingEdit("fake-phone", 7, "op1", "fake-dataset-v1"), "合成-data")
        assertEquals(64, original.contentDigest.length)
        assertEquals(original.contentDigest, original.copy().contentDigest)
        assertNotEquals(original.contentDigest, original.copy(metadata = original.metadata.copy(device = "other")).contentDigest)
        assertNotEquals(original.contentDigest, original.copy(metadata = original.metadata.copy(baseRevision = 8)).contentDigest)
        assertNotEquals(original.contentDigest, original.copy(metadata = original.metadata.copy(operation = "op2")).contentDigest)
        assertNotEquals(original.contentDigest, original.copy(syntheticDelta = "different").contentDigest)
        val a = RecoveryEdit(PendingEdit("fake-phone", 7, "ab", "fake-dataset-v1"), "c")
        val b = RecoveryEdit(PendingEdit("fake-phone", 7, "a", "fake-dataset-v1"), "bc")
        assertNotEquals(a.contentDigest, b.contentDigest)
    }

    @Test fun duplicateReceiptWithNoPendingDoesNotInterruptUnsavedSnapshot() {
        val journal = Journal(); val pc = ready(journal)
        val old = receipt(RecoveryEdit(PendingEdit("fake-phone", 6, "old-op", "fake-dataset-v1"), "old-change"))
        assertEquals(ReconciliationResult.NO_PENDING, pc.reconcileApplied(old))
        assertTrue(pc.canEdit(2)); assertTrue(pc.hasSnapshot); assertNull(pc.pending)
        pc.stage("new-op", "new-change", 3) // current session continues normally
        assertNotNull(pc.pending)
    }

    @Test fun sameOperationConflictStopsActiveSessionWithoutClearing() {
        for (field in 0..2) {
            val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
            val saved = pc.pending!!; val good = receipt(saved)
            val bad = when (field) {
                0 -> good.copy(baseRevision = 6)
                1 -> good.copy(contentDigest = "0".repeat(64))
                else -> good.copy(resultingRevision = 7)
            }
            assertEquals(ReconciliationResult.CONFLICT, pc.reconcileApplied(bad))
            assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot); assertEquals(saved, pc.pending)
        }
    }

    @Test fun malformedSurrogatesAreRejectedInAllPersistedStringFields() {
        val bad = listOf("\uD800", "\uDBFF", "\uDC00", "\uDFFF", "a\uD800b", "\uD800\uD800", "\uDC00\uD800")
        for (value in bad) {
            assertFailsWith<IllegalArgumentException> { RecoveryEdit(PendingEdit(value, 7, "op", "fake-dataset-v1"), "change") }
            assertFailsWith<IllegalArgumentException> { RecoveryEdit(PendingEdit("phone", 7, value, "fake-dataset-v1"), "change") }
            assertFailsWith<IllegalArgumentException> { RecoveryEdit(PendingEdit("phone", 7, "op", "fake-dataset-v1"), value) }
            assertFailsWith<IllegalArgumentException> { recoveryContentDigest(PendingEdit("phone", 7, "op", "fake-dataset-v1"), value) }
        }
        val valid = RecoveryEdit(PendingEdit("phone", 7, "op", "fake-dataset-v1"), "合成\uD83D\uDE00\u0000")
        assertEquals(64, valid.contentDigest.length)
        assertNotEquals(valid.contentDigest, valid.copy(syntheticDelta = "合成?\u0000").contentDigest)
    }

    @Test fun verifiedRevisionSurvivesControllerRestartAndRejectsOlderGrants() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
        assertEquals(ReconciliationResult.CLEARED, pc.reconcileApplied(receipt(pc.pending!!, 8)))
        val restarted = testController(journal); restarted.observeTransport(true)
        assertFailsWith<IllegalStateException> { restarted.acceptFixtureGrant(FixtureGrant("fake-phone", "stale", 7, 200, "fake-dataset-v1"), 2) }
        assertFalse(restarted.canEdit(2)); assertFalse(restarted.hasSnapshot); assertNull(restarted.pending)
        restarted.acceptFixtureGrant(FixtureGrant("fake-phone", "fresh", 8, 200, "fake-dataset-v1"), 3)
        assertTrue(restarted.canEdit(3)); assertFalse(restarted.hasSnapshot)
    }

    @Test fun revisionFloorsAreScopedByApplicationAndDatasetIncarnation() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
        pc.reconcileApplied(receipt(pc.pending!!, 8))
        pc.acceptFixtureGrant(FixtureGrant("fake-phone", "new-dataset", 0, 200, "fake-dataset-v2"), 2)
        pc.loadSyntheticSnapshot(byteArrayOf(1), 2); pc.stage("op2", "new", 3)
        pc.reconcileApplied(receipt(pc.pending!!, 1))
        assertEquals(8L, journal.floors[DatasetIdentity("fake-phone", "fake-dataset-v1")])
        assertEquals(1L, journal.floors[DatasetIdentity("fake-phone", "fake-dataset-v2")])
        pc.acceptFixtureGrant(FixtureGrant("other-phone", "other", 0, 200, "fake-dataset-v1"), 4)
        assertTrue(pc.canEdit(4)) // same incarnation string on another app identity is a different key
    }

    @Test fun otherIncarnationReceiptCannotClearOrInterruptCurrentOperation() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
        val saved = pc.pending!!
        assertEquals(ReconciliationResult.NOT_THIS_OPERATION,
            pc.reconcileApplied(receipt(saved).copy(datasetInstance = "fake-dataset-v2")))
        assertEquals(saved, pc.pending); assertTrue(pc.canEdit(2)); assertTrue(pc.hasSnapshot)
        assertEquals(7L, journal.floors[DatasetIdentity("fake-phone", "fake-dataset-v1")])
        assertNotEquals(saved.contentDigest, saved.copy(metadata = saved.metadata.copy(datasetInstance = "fake-dataset-v2")).contentDigest)
    }

    @Test fun floorWriteFailurePreservesPendingAndStopsEditing() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
        val saved = pc.pending!!; journal.failFloorSave = true
        assertFailsWith<IllegalStateException> { pc.reconcileApplied(receipt(saved)) }
        assertEquals(saved, pc.pending); assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot)
        assertEquals(7L, journal.floors[DatasetIdentity("fake-phone", "fake-dataset-v1")])
    }

    @Test fun floorReadFailureBlocksGrantAndSuspendsExistingSnapshot() {
        val journal = Journal(); val pc = ready(journal)
        val request = pc.beginGrantRequest(DatasetIdentity("fake-phone", "fake-dataset-v1"), 1, 200, 0, renewal = true)
        journal.failFloorRead = true
        assertFailsWith<IllegalStateException> { pc.acceptGrant(Grant("fake-phone", "s1", 7, 200,
            "fake-dataset-v1", request.requestNonce, request.minimumEpoch), request, 2) }
        assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot)
    }

    @Test fun acceptedGrantRevisionIsAlsoRetainedBeforeReportingPermission() {
        val journal = Journal(); val pc = ready(journal); pc.suspendSession()
        pc.acceptFixtureGrant(FixtureGrant("fake-phone", "newer", 10, 200, "fake-dataset-v1"), 2)
        pc.suspendSession()
        val restarted = testController(journal); restarted.observeTransport(true)
        assertFailsWith<IllegalStateException> { restarted.acceptFixtureGrant(FixtureGrant("fake-phone", "older", 9, 200, "fake-dataset-v1"), 3) }
        journal.failFloorSave = true
        assertFailsWith<IllegalStateException> { restarted.acceptFixtureGrant(FixtureGrant("fake-phone", "write-failure", 11, 200, "fake-dataset-v1"), 3) }
        assertFalse(restarted.canEdit(3)); assertFalse(restarted.hasSnapshot)
        assertEquals(10L, journal.floors[DatasetIdentity("fake-phone", "fake-dataset-v1")])
    }

    @Test fun floorIsSavedBeforeClearAndNeverDecreasedByOlderAppliedOutcome() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
        val applied = receipt(pc.pending!!, 8); journal.failClear = true
        assertFailsWith<IllegalStateException> { pc.reconcileApplied(applied) }
        val identity = DatasetIdentity("fake-phone", "fake-dataset-v1")
        assertEquals(8L, journal.floors[identity]); assertNotNull(pc.pending)
        journal.floors[identity] = 10; journal.failClear = false
        assertEquals(ReconciliationResult.CLEARED, pc.reconcileApplied(applied))
        assertEquals(10L, journal.floors[identity])
        assertFailsWith<IllegalStateException> { pc.acceptFixtureGrant(FixtureGrant("fake-phone", "stale", 8, 200, "fake-dataset-v1"), 2) }
    }

    @Test fun legacyUnboundPendingIsPreservedWithoutGuessingIncarnation() {
        val journal = Journal()
        val legacy = RecoveryEdit(PendingEdit("fake-phone", 7, "op1"), "legacy-change")
        journal.edit = legacy
        val pc = testController(journal); pc.observeTransport(true)
        assertFailsWith<IllegalStateException> { pc.acceptFixtureGrant(FixtureGrant("fake-phone", "s1", 7, 100, "fake-dataset-v1"), 0) }
        assertEquals(ReconciliationResult.NOT_THIS_OPERATION,
            pc.reconcileApplied(AppliedReceipt("fake-phone", 7, "op1", legacy.contentDigest, 8, "fake-dataset-v1")))
        assertEquals(legacy, pc.pending); assertNull(pc.pending!!.metadata.datasetInstance)
        assertTrue(journal.floors.isEmpty()); assertFalse(pc.canEdit(0))
    }

    @Test fun confirmedDiscardResolvesChangedRevisionWithoutGrantingEditingOrRemovingFloor() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
        pc.observeTransport(false) // unknown/rejected outcome; phone has since advanced
        pc.observeTransport(true)
        assertFailsWith<IllegalStateException> { pc.acceptFixtureGrant(FixtureGrant("fake-phone", "s2", 8, 200, "fake-dataset-v1"), 2) }
        val preview = pc.preparePendingDiscard()!!
        assertEquals(pc.pending!!.metadata, preview.metadata)
        assertEquals(pc.pending!!.contentDigest, preview.contentDigest)
        assertNotNull(pc.pending); assertFalse(pc.canEdit(2))
        pc.observeTransport(false) // confirmation does not require USB or an editing lease
        assertEquals(PendingDiscardResult.DISCARDED, pc.discardPending(preview.confirmation))
        assertNull(pc.pending); assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot)
        assertEquals(7L, journal.floors[DatasetIdentity("fake-phone", "fake-dataset-v1")])
        val restarted = testController(journal); restarted.observeTransport(true)
        assertFailsWith<IllegalStateException> { restarted.acceptFixtureGrant(FixtureGrant("fake-phone", "stale", 6, 200, "fake-dataset-v1"), 3) }
        restarted.acceptFixtureGrant(FixtureGrant("fake-phone", "fresh", 8, 200, "fake-dataset-v1"), 3)
        assertTrue(restarted.canEdit(3)); assertFalse(restarted.hasSnapshot)
        restarted.loadSyntheticSnapshot(byteArrayOf(9), 3); restarted.stage("op2", "new-change", 4)
        assertEquals(PendingDiscardResult.INVALID_CONFIRMATION, pc.discardPending(preview.confirmation))
        assertEquals("op2", restarted.pending!!.metadata.operation)
    }

    @Test fun preparingCancellingAndInvalidConfirmationsLeaveActiveSessionUntouched() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
        val saved = pc.pending!!; val first = pc.preparePendingDiscard()!!
        assertTrue(pc.canEdit(2)); assertTrue(pc.hasSnapshot); assertEquals(saved, pc.pending)
        val second = pc.preparePendingDiscard()!!
        assertEquals(PendingDiscardResult.INVALID_CONFIRMATION, pc.discardPending(first.confirmation))
        assertEquals(PendingDiscardResult.INVALID_CONFIRMATION, pc.discardPending(PendingDiscardConfirmation()))
        val other = testController(journal)
        assertEquals(PendingDiscardResult.INVALID_CONFIRMATION, other.discardPending(second.confirmation))
        pc.cancelPendingDiscard(first.confirmation) // old cancellation cannot cancel the new preview
        pc.cancelPendingDiscard(second.confirmation)
        assertEquals(PendingDiscardResult.INVALID_CONFIRMATION, pc.discardPending(second.confirmation))
        assertEquals(saved, pc.pending); assertTrue(pc.canEdit(2)); assertTrue(pc.hasSnapshot)
    }

    @Test fun changedMetadataOrPayloadRequiresNewConfirmationAndPreservesCurrentEdit() {
        for (payloadChanged in listOf(false, true)) {
            val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
            val preview = pc.preparePendingDiscard()!!; val saved = pc.pending!!
            journal.edit = if (payloadChanged) saved.copy(syntheticDelta = "replacement")
                else saved.copy(metadata = saved.metadata.copy(operation = "op2"))
            val replacement = journal.edit
            assertEquals(PendingDiscardResult.CHANGED, pc.discardPending(preview.confirmation))
            assertEquals(replacement, pc.pending); assertTrue(pc.canEdit(2)); assertTrue(pc.hasSnapshot)
            assertEquals(PendingDiscardResult.INVALID_CONFIRMATION, pc.discardPending(preview.confirmation))
        }
    }

    @Test fun missingPendingIsNoOpAndAppliedReconciliationInvalidatesDiscardConfirmation() {
        val journal = Journal(); val pc = ready(journal)
        assertNull(pc.preparePendingDiscard()); assertTrue(pc.canEdit(2)); assertTrue(pc.hasSnapshot)
        pc.stage("op1", "change", 2); val preview = pc.preparePendingDiscard()!!
        journal.edit = null // external disappearance does not authorize deletion of future records
        assertEquals(PendingDiscardResult.NO_PENDING, pc.discardPending(preview.confirmation))
        assertTrue(pc.canEdit(2)); assertTrue(pc.hasSnapshot)
        pc.stage("op2", "new-change", 2); val next = pc.preparePendingDiscard()!!
        assertEquals(ReconciliationResult.CLEARED, pc.reconcileApplied(receipt(pc.pending!!)))
        assertEquals(PendingDiscardResult.INVALID_CONFIRMATION, pc.discardPending(next.confirmation))
        assertEquals(8L, journal.floors[DatasetIdentity("fake-phone", "fake-dataset-v1")])
    }

    @Test fun readOrDeleteFailureStopsEditingRetainsPendingAndConsumesConfirmation() {
        for (readFailure in listOf(false, true)) {
            val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
            val saved = pc.pending!!; val preview = pc.preparePendingDiscard()!!
            journal.failRead = readFailure; journal.failClear = !readFailure
            assertFailsWith<IllegalStateException> { pc.discardPending(preview.confirmation) }
            assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot)
            journal.failRead = false; journal.failClear = false
            assertEquals(saved, pc.pending)
            assertEquals(PendingDiscardResult.INVALID_CONFIRMATION, pc.discardPending(preview.confirmation))
            assertEquals(PendingDiscardResult.DISCARDED, pc.discardPending(pc.preparePendingDiscard()!!.confirmation))
        }
    }

    @Test fun unreadablePendingCannotBePreviewedOrDiscarded() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "change", 1)
        val preview = pc.preparePendingDiscard()!!; val saved = pc.pending!!; journal.failRead = true
        assertFailsWith<IllegalStateException> { pc.preparePendingDiscard() }
        assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot)
        assertEquals(PendingDiscardResult.INVALID_CONFIRMATION, pc.discardPending(preview.confirmation))
        journal.failRead = false; assertEquals(saved, pc.pending)
    }

    @Test fun legacyUnboundPendingCanBeExplicitlyDiscardedWithoutAssigningIdentity() {
        val journal = Journal(); journal.edit = RecoveryEdit(PendingEdit("fake-phone", 7, "legacy-op"), "legacy")
        val pc = testController(journal); val preview = pc.preparePendingDiscard()!!
        assertNull(preview.metadata.datasetInstance)
        assertEquals(PendingDiscardResult.DISCARDED, pc.discardPending(preview.confirmation))
        assertTrue(journal.floors.isEmpty()); assertFalse(pc.canEdit(0))
        pc.observeTransport(true); pc.acceptFixtureGrant(FixtureGrant("fake-phone", "fresh", 8, 100, "fake-dataset-v1"), 0)
        assertTrue(pc.canEdit(0)); assertFalse(pc.hasSnapshot)
    }

    @Test fun malformedStageNeverPersistsReplacementContent() {
        val journal = Journal(); val pc = ready(journal)
        assertFailsWith<IllegalArgumentException> { pc.stage("op1", "\uD800", 1) }
        assertNull(pc.pending)
        pc.stage("op1", "valid\uD83D\uDE00", 2)
        assertEquals("valid\uD83D\uDE00", pc.pending!!.syntheticDelta)
    }
}
