package poc
import kotlin.test.*

class TransportCoordinatorTest {
    private class Journal : DeltaJournal {
        var edit: RecoveryEdit? = null
        private val floors = mutableMapOf<DatasetIdentity, Long>()
        override fun load() = edit
        override fun save(edit: RecoveryEdit) { this.edit = edit }
        override fun clear() { edit = null }
        override fun revisionFloor(identity: DatasetIdentity) = floors[identity]
        override fun recordAppliedRevision(identity: DatasetIdentity, revision: Long) {
            floors[identity] = maxOf(floors[identity] ?: 0, revision)
        }
    }
    private val ready = TransportObservation(TransportState.USB_MTP_READY, "fake-selector")
    @Test fun actualTransportLossSuspendsMockSessionAndPreservesDelta() {
        val pc = RecoveryController(Journal()); val link = TransportCoordinator(pc)
        link.observe(1, ready, 0, 0)
        pc.acceptGrant(Grant("fake-app", "s1", 1, 10000, "fake-dataset-v1"), 0)
        pc.loadSyntheticSnapshot(byteArrayOf(1), 0); pc.stage("fake-op", "synthetic-delta", 1)
        link.observe(2, TransportObservation(TransportState.NONE), 2, 2)
        assertFalse(pc.canEdit(2)); assertFalse(pc.hasSnapshot); assertNotNull(pc.pending)
        link.observe(3, ready, 3, 3)
        assertFalse(pc.canEdit(3)); assertNotNull(pc.pending)
    }
    @Test fun changedSelectorRevokesEvenIfNoZeroDeviceSampleWasSeen() {
        val pc = RecoveryController(Journal()); val link = TransportCoordinator(pc)
        link.observe(1, ready, 0, 0); pc.acceptGrant(Grant("fake-app", "s1", 1, 10000, "fake-dataset-v1"), 0)
        link.observe(2, TransportObservation(TransportState.USB_MTP_READY, "another-selector"), 1, 1)
        assertFalse(pc.canEdit(1))
    }
    @Test fun blockedProbeAndLateSuccessCannotReviveExpiredObservation() {
        val pc = RecoveryController(Journal()); val link = TransportCoordinator(pc, 3000)
        link.observe(1, ready, 0, 0); pc.acceptGrant(Grant("fake-app", "s1", 1, 10000, "fake-dataset-v1"), 0)
        pc.loadSyntheticSnapshot(byteArrayOf(1), 0)
        link.tick(3000)
        assertFalse(pc.canEdit(3000)); assertFalse(pc.hasSnapshot)
        link.observe(2, ready, 1, 4000)
        assertEquals(TransportState.ERROR, link.state)
        assertFailsWith<IllegalArgumentException> { pc.acceptGrant(Grant("fake-app", "s2", 1, 10000, "fake-dataset-v1"), 4000) }
    }
    @Test fun staleResultsDoNotOverrideLatestFailure() {
        val pc = RecoveryController(Journal()); val link = TransportCoordinator(pc)
        link.observe(2, TransportObservation(TransportState.ERROR), 0, 0)
        link.observe(1, ready, 1, 1)
        assertEquals(TransportState.ERROR, link.state)
    }
    @Test fun grantExpiresWhileTransportRemainsReady() {
        val pc = RecoveryController(Journal()); val link = TransportCoordinator(pc)
        link.observe(1, ready, 0, 0); pc.acceptGrant(Grant("fake-app", "s1", 1, 10, "fake-dataset-v1"), 0)
        pc.loadSyntheticSnapshot(byteArrayOf(1), 0); link.tick(10)
        assertEquals(TransportState.USB_MTP_READY, link.state)
        assertFalse(pc.canEdit(10)); assertFalse(pc.hasSnapshot)
    }
}
