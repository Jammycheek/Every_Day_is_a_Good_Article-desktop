package poc
import kotlin.test.*

class SubmissionStateTest {
    private class Journal : DeltaJournal {
        var edit: RecoveryEdit? = null
        var failReplace = false
        override fun load() = edit
        override fun save(edit: RecoveryEdit) { check(this.edit == null); this.edit = edit }
        override fun replace(edit: RecoveryEdit) { check(!failReplace); this.edit = edit }
        override fun clear() { edit = null }
        override fun revisionFloor(identity: DatasetIdentity): Long? = null
        override fun recordVerifiedRevision(identity: DatasetIdentity, revision: Long) {}
        override fun verifiedEpoch(identity: DatasetIdentity): Long? = null
        override fun recordVerifiedEpoch(identity: DatasetIdentity, epoch: Long) {}
        override fun recordVerifiedGrant(identity: DatasetIdentity, revision: Long, epoch: Long) {}
    }
    private fun ready(journal: Journal) = testController(journal).apply {
        observeTransport(true)
        acceptFixtureGrant(FixtureGrant("fake-phone", "s1", 7, 100, "fake-instance"), 0)
        loadSyntheticSnapshot(byteArrayOf(1), 0)
    }

    @Test fun autosaveReplacesOnlyTheSameStagedOperation() {
        val journal = Journal(); val pc = ready(journal)
        pc.restage("op1", "first"); pc.restage("op1", "second")
        assertEquals("second", journal.edit!!.syntheticDelta); assertFalse(journal.edit!!.submitted)
        assertFailsWith<IllegalStateException> { pc.restage("op2", "other operation") }
        assertEquals("op1", journal.edit!!.metadata.operation)
    }
    @Test fun submittedEditCanNeverBeEditedAgain() {
        val journal = Journal(); val pc = ready(journal)
        pc.restage("op1", "text")
        assertTrue(pc.markSubmitted().submitted); assertTrue(journal.edit!!.submitted)
        assertFailsWith<IllegalStateException> { pc.restage("op1", "changed after submit") }
        assertEquals("text", journal.edit!!.syntheticDelta)
    }
    @Test fun autosaveAndSubmitRequireALiveGrant() {
        val journal = Journal(); val pc = ready(journal)
        pc.restage("op1", "text")
        pc.tick(100)
        assertFailsWith<IllegalStateException> { pc.restage("op1", "after expiry") }
        assertFailsWith<IllegalStateException> { pc.markSubmitted() }
        assertEquals("text", journal.edit!!.syntheticDelta); assertFalse(journal.edit!!.submitted)
    }
    @Test fun replaceFailureStopsEditingAndKeepsLastSavedText() {
        val journal = Journal(); val pc = ready(journal)
        pc.restage("op1", "saved"); journal.failReplace = true
        assertFails { pc.restage("op1", "lost") }
        assertFalse(pc.canEdit(1)); assertFalse(pc.hasSnapshot)
        assertEquals("saved", journal.edit!!.syntheticDelta)
    }
    @Test fun leaseRemainingAndActiveGrantFollowTheGate() {
        val journal = Journal(); val pc = ready(journal)
        assertEquals("s1", pc.activeGrant?.session)
        pc.tick(100)
        assertNull(pc.activeGrant); assertNull(pc.leaseRemainingMillis())
    }
}
