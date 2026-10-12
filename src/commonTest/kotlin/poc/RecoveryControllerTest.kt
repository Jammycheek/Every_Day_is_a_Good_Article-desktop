package poc
import kotlin.test.*

class RecoveryControllerTest {
    private class Journal : DeltaJournal {
        var edit: RecoveryEdit? = null
        var failRead = false
        var failSave = false
        var failClear = false
        override fun load(): RecoveryEdit? { check(!failRead); return edit }
        override fun save(edit: RecoveryEdit) { check(!failSave); this.edit = edit }
        override fun clear() { check(!failClear); edit = null }
    }
    private fun ready(journal: Journal) = RecoveryController(journal).apply {
        observeTransport(true)
        acceptGrant(Grant("fake-phone", "s1", 7, 100), 0)
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
        val restarted = RecoveryController(journal)
        assertFalse(restarted.hasSnapshot); assertFalse(restarted.canEdit(2))
        assertNotNull(restarted.recoveryCandidate("fake-phone", 7))
        assertFailsWith<IllegalStateException> { restarted.recoveryCandidate("other-phone", 7) }
        assertFailsWith<IllegalStateException> { restarted.recoveryCandidate("fake-phone", 8) }
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
            edit.contentDigest, resultingRevision)

    @Test fun mismatchedReceiptAndClearFailurePreservePending() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        val applied = receipt(pc.pending!!)
        assertFailsWith<IllegalStateException> { pc.reconcileApplied(applied.copy(operation = "old-op")) }
        assertNotNull(pc.pending)
        journal.failClear = true
        assertFailsWith<IllegalStateException> { pc.reconcileApplied(applied) }
        assertNotNull(pc.pending); assertFalse(pc.canEdit(3)); assertFalse(pc.hasSnapshot)
    }
    @Test fun correctReceiptClearsDeltaAndRequiresNewGrant() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        pc.reconcileApplied(receipt(pc.pending!!))
        assertNull(pc.pending); assertFalse(pc.hasSnapshot); assertFalse(pc.canEdit(3))
    }

    @Test fun lostAppliedReplyAfterExpiryReconcilesBeforeNewRevisionGrant() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        val applied = receipt(pc.pending!!, 8) // phone has already committed revision 7 -> 8
        pc.tick(100)
        assertFailsWith<IllegalStateException> { pc.acceptGrant(Grant("fake-phone", "s2", 8, 200), 101) }
        assertNotNull(pc.pending) // reproduce the old dead end until the applied outcome is queried
        pc.observeTransport(false)
        pc.reconcileApplied(applied) // no MTP, lease, snapshot or old session required
        assertNull(pc.pending); assertFalse(pc.canEdit(101)); assertFalse(pc.hasSnapshot)
        pc.observeTransport(true)
        assertFalse(pc.canEdit(102))
        pc.acceptGrant(Grant("fake-phone", "s2", 8, 200), 102)
        assertTrue(pc.canEdit(103)); assertFalse(pc.hasSnapshot)
    }

    @Test fun allReceiptBindingMismatchesRetainExactPendingEdit() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        val saved = pc.pending!!; val good = receipt(saved)
        val invalid = listOf(good.copy(device = "other-phone"), good.copy(baseRevision = 6),
            good.copy(operation = "other-op"), good.copy(contentDigest = "0".repeat(64)),
            good.copy(resultingRevision = 7), good.copy(resultingRevision = -1))
        for (bad in invalid) {
            assertFailsWith<IllegalStateException> { pc.reconcileApplied(bad) }
            assertEquals(saved, pc.pending); assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot)
        }
    }

    @Test fun oldReceiptCannotClearDifferentPayloadWithReusedOperationName() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "first-payload", 1)
        val oldReceipt = receipt(pc.pending!!)
        pc.suspendSession()
        journal.edit = RecoveryEdit(PendingEdit("fake-phone", 7, "op1"), "different-payload")
        assertFailsWith<IllegalStateException> { pc.reconcileApplied(oldReceipt) }
        assertEquals("different-payload", pc.pending!!.syntheticDelta)
    }

    @Test fun duplicateReceiptNeverClearsNewOperation() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        val applied = receipt(pc.pending!!)
        pc.reconcileApplied(applied)
        assertFailsWith<IllegalStateException> { pc.reconcileApplied(applied) }
        pc.acceptGrant(Grant("fake-phone", "s2", 8, 200), 2)
        pc.loadSyntheticSnapshot(byteArrayOf(9), 2); pc.stage("op2", "new-change", 3)
        assertFailsWith<IllegalStateException> { pc.reconcileApplied(applied) }
        assertEquals("op2", pc.pending!!.metadata.operation); assertFalse(pc.canEdit(4))
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
        val original = RecoveryEdit(PendingEdit("fake-phone", 7, "op1"), "合成-data")
        assertEquals(64, original.contentDigest.length)
        assertEquals(original.contentDigest, original.copy().contentDigest)
        assertNotEquals(original.contentDigest, original.copy(metadata = original.metadata.copy(device = "other")).contentDigest)
        assertNotEquals(original.contentDigest, original.copy(metadata = original.metadata.copy(baseRevision = 8)).contentDigest)
        assertNotEquals(original.contentDigest, original.copy(metadata = original.metadata.copy(operation = "op2")).contentDigest)
        assertNotEquals(original.contentDigest, original.copy(syntheticDelta = "different").contentDigest)
        val a = RecoveryEdit(PendingEdit("fake-phone", 7, "ab"), "c")
        val b = RecoveryEdit(PendingEdit("fake-phone", 7, "a"), "bc")
        assertNotEquals(a.contentDigest, b.contentDigest)
    }
}
