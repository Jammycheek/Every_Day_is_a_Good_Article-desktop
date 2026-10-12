package poc
import kotlin.test.*

class SessionGateTest {
    private fun ready(): SessionGate = SessionGate().apply {
        observeTransport(true)
        acceptGrant(Grant("fake-device", "s1", 3, 100, "fake-dataset-v1"), 0)
        loadSnapshot(0)
    }

    @Test fun connectionAloneDoesNotPermitEditing() {
        val gate = SessionGate()
        gate.observeTransport(true)
        assertFalse(gate.canEdit(0))
        assertFailsWith<IllegalStateException> { gate.loadSnapshot(0) }
    }
    @Test fun disconnectDropsSnapshotAndGrantAndReconnectDoesNotRenew() {
        val gate = ready()
        gate.observeTransport(false)
        assertFalse(gate.snapshotLoaded)
        assertNull(gate.grant)
        gate.observeTransport(true)
        assertFalse(gate.canEdit(2))
    }
    @Test fun expiryDropsSnapshotAndGrant() {
        val gate = ready()
        assertFalse(gate.canEdit(100))
        assertFalse(gate.snapshotLoaded)
        assertNull(gate.grant)
    }
    @Test fun explicitSuspensionRequiresFreshGrantAndSnapshot() {
        val gate = ready()
        gate.suspendSession()
        assertNull(gate.grant); assertFalse(gate.snapshotLoaded); assertFalse(gate.canEdit(1))
        assertFailsWith<IllegalStateException> { gate.loadSnapshot(1) }
        gate.acceptGrant(Grant("fake-device", "s2", 3, 200, "fake-dataset-v1"), 101)
        assertTrue(gate.canEdit(101)); assertFalse(gate.snapshotLoaded)
        gate.loadSnapshot(101); assertTrue(gate.snapshotLoaded)
    }
    @Test fun expiredGrantAndReplacementAreRejected() {
        val gate = SessionGate()
        gate.observeTransport(true)
        assertFailsWith<IllegalArgumentException> { gate.acceptGrant(Grant("d", "s", 0, 1, "fake-dataset-v1"), 1) }
        gate.acceptGrant(Grant("d", "s", 0, 10, "fake-dataset-v1"), 1)
        assertFailsWith<IllegalStateException> { gate.acceptGrant(Grant("d", "new", 0, 20, "fake-dataset-v1"), 2) }
    }
}
