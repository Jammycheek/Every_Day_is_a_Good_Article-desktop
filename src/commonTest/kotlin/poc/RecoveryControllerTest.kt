package poc
import kotlin.test.*

class RecoveryControllerTest {
    private class Journal : DeltaJournal {
        var edit: RecoveryEdit? = null
        var failSave = false
        var failClear = false
        override fun load() = edit
        override fun save(edit: RecoveryEdit) { check(!failSave); this.edit = edit }
        override fun clear() { check(!failClear); edit = null }
    }
    private fun ready(journal: Journal) = RecoveryController(journal).apply {
        observeTransport(true)
        acceptGrant(Grant("fake-phone", "s1", 7, 100), 0)
        loadSyntheticSnapshot(byteArrayOf(1, 2, 3), 0)
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
    @Test fun staleAckAndClearFailurePreservePending() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        assertFailsWith<IllegalStateException> { pc.acknowledge("fake-phone", "old", "op1", 2) }
        assertNotNull(pc.pending)
        journal.failClear = true
        assertFailsWith<IllegalStateException> { pc.acknowledge("fake-phone", "s1", "op1", 2) }
        assertNotNull(pc.pending); assertFalse(pc.canEdit(3)); assertFalse(pc.hasSnapshot)
    }
    @Test fun correctAckClearsDeltaAndRequiresNewGrant() {
        val journal = Journal(); val pc = ready(journal); pc.stage("op1", "synthetic-change", 1)
        pc.acknowledge("fake-phone", "s1", "op1", 2)
        assertNull(pc.pending); assertFalse(pc.hasSnapshot); assertFalse(pc.canEdit(3))
    }
}
