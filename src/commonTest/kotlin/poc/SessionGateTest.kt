package poc
import kotlin.test.*

class SessionGateTest {
    private fun ready(): SessionGate = SessionGate().apply {
        observeTransport(true)
        acceptGrant(Grant("fake-device", "s1", 3, 100), 0)
        loadSnapshot(0)
    }

    @Test fun connectionAloneDoesNotPermitEditing() {
        val gate = SessionGate()
        gate.observeTransport(true)
        assertFalse(gate.canEdit(0))
        assertFailsWith<IllegalStateException> { gate.loadSnapshot(0) }
    }
    @Test fun disconnectDropsSnapshotAndGrantButKeepsDelta() {
        val gate = ready()
        gate.stage("fake-op", 1)
        gate.observeTransport(false)
        assertFalse(gate.snapshotLoaded)
        assertNull(gate.grant)
        assertEquals("fake-op", gate.pending?.operation)
        gate.observeTransport(true)
        assertFalse(gate.canEdit(2))
    }
    @Test fun expirySuspendsWithoutLosingDelta() {
        val gate = ready()
        gate.stage("fake-op", 1)
        assertFalse(gate.canEdit(100))
        assertFalse(gate.snapshotLoaded)
        assertNotNull(gate.pending)
    }
    @Test fun recoveryRejectsOtherDeviceOrRevision() {
        val gate = ready()
        gate.stage("fake-op", 1)
        gate.observeTransport(false)
        gate.observeTransport(true)
        assertFailsWith<IllegalStateException> { gate.acceptGrant(Grant("other", "s2", 3, 200), 101) }
        assertFailsWith<IllegalStateException> { gate.acceptGrant(Grant("fake-device", "s2", 4, 200), 101) }
        gate.acceptGrant(Grant("fake-device", "s2", 3, 200), 101)
        assertNotNull(gate.pending)
    }
    @Test fun transferOrStaleAcknowledgementDoesNotClearDelta() {
        val gate = ready()
        gate.stage("fake-op", 1)
        assertFailsWith<IllegalStateException> { gate.acknowledge("fake-device", "old", "fake-op", 2) }
        assertFailsWith<IllegalStateException> { gate.acknowledge("fake-device", "s1", "wrong", 2) }
        assertNotNull(gate.pending)
        gate.acknowledge("fake-device", "s1", "fake-op", 2)
        assertNull(gate.pending)
        assertFalse(gate.snapshotLoaded)
    }
    @Test fun expiredGrantAndReplacementAreRejected() {
        val gate = SessionGate()
        gate.observeTransport(true)
        assertFailsWith<IllegalArgumentException> { gate.acceptGrant(Grant("d", "s", 0, 1), 1) }
        gate.acceptGrant(Grant("d", "s", 0, 10), 1)
        assertFailsWith<IllegalStateException> { gate.acceptGrant(Grant("d", "new", 0, 20), 2) }
    }
}
