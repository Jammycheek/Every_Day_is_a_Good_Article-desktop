package poc
import java.nio.file.*
import kotlin.test.*

class WindowsEpochTest {
    private val identity = DatasetIdentity("fake-epoch-phone", "fake-instance")
    private fun trial(action: (Path, DpapiProtector) -> Unit) {
        val dir = Files.createTempDirectory("ea-poc-epoch-test")
        try { action(dir, DpapiProtector(Path.of(System.getenv("POC_HELPER") ?: error("POC_HELPER required")))) }
        finally {
            Files.newDirectoryStream(dir).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }
    private fun accept(pc: RecoveryController, epoch: Long, revision: Long = 7, baseline: Boolean = false) {
        val request = pc.beginGrantRequest(identity, 0, 100, 0, rebaseline = baseline)
        val reply = Grant(identity.device, "fake-epoch-session", revision, 100, identity.incarnation, request.requestNonce, epoch)
        if (baseline) pc.acceptFreshHistoryBaseline(reply, request, 0) else pc.acceptGrant(reply, request, 0)
    }
    @Test fun protectedEpochSurvivesRestartRejectsEqualOrOlderAndNeverDecreases() = trial { dir, protector ->
        WindowsDeltaJournal(dir, protector).use {
            val pc = testController(it); pc.observeTransport(true); accept(pc, 9); pc.suspendSession()
            val path = dir.resolve("grant-epochs.dpapi"); val bytes = Files.readAllBytes(path)
            it.recordVerifiedEpoch(identity, 8)
            assertContentEquals(bytes, Files.readAllBytes(path))
            assertEquals(1, Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView::class.java).acl.size)
        }
        WindowsDeltaJournal(dir, protector).use {
            val pc = testController(it); pc.observeTransport(true)
            assertFails { accept(pc, 8) }; assertFails { accept(pc, 9) }
            assertEquals(9L, it.verifiedEpoch(identity)); accept(pc, 10); pc.suspendSession()
            assertEquals(10L, it.verifiedEpoch(identity))
        }
    }
    @Test fun revisionHistoryForgettingDoesNotForgetEpochAndBaselineUsesSameValidation() = trial { dir, protector ->
        WindowsDeltaJournal(dir, protector).use {
            val pc = testController(it); pc.observeTransport(true); accept(pc, 9); pc.suspendSession()
            pc.recoverRevisionHistory(pc.prepareForgetRevisionHistory(identity)!!.confirmation)
            assertEquals(9L, it.verifiedEpoch(identity)); assertTrue(pc.revisionHistoryNeedsBaseline(identity))
            assertFails { accept(pc, 9, 3, true) }
            accept(pc, 10, 3, true); pc.suspendSession()
            assertEquals(3L, it.revisionFloor(identity)); assertEquals(10L, it.verifiedEpoch(identity))
            assertTrue(pc.revisionHistoryIncomplete)
        }
    }
    @Test fun unreadableEpochStorageBlocksRequestsWithoutDeletingHistoryOrPending() = trial { dir, protector ->
        WindowsDeltaJournal(dir, protector).use {
            it.recordVerifiedRevision(identity, 7)
            it.save(RecoveryEdit(PendingEdit(identity.device, 7, "fake-op", identity.incarnation), "SYNTHETIC"))
            val pending = Files.readAllBytes(dir.resolve("pending.dpapi"))
            val floors = Files.readAllBytes(dir.resolve("revision-floors.dpapi"))
            Files.write(dir.resolve("grant-epochs.dpapi"), byteArrayOf(1, 2, 3, 4))
            val pc = testController(it); pc.observeTransport(true)
            assertFailsWith<DecryptionRejectedException> { pc.beginGrantRequest(identity, 0, 100, 0) }
            assertFalse(pc.canEdit(0)); assertFalse(it.historyIsIncomplete())
            assertContentEquals(pending, Files.readAllBytes(dir.resolve("pending.dpapi")))
            assertContentEquals(floors, Files.readAllBytes(dir.resolve("revision-floors.dpapi")))
        }
    }
}
