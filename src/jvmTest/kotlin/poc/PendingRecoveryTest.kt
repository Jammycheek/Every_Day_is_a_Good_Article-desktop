package poc
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.*
import kotlin.test.*

class PendingRecoveryTest {
    private val identity = DatasetIdentity("fake-pending-recovery", "fake-instance")
    private fun helper() = Path.of(System.getenv("POC_HELPER") ?: error("POC_HELPER required"))
    private fun native(encrypt: Boolean) = listOf(helper().toString(), if (encrypt) "protect" else "unprotect")
    private fun fixture(mode: String): List<String> {
        val cp = listOf(ProtectionFailureFixture::class.java, kotlin.Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        return listOf(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp", cp, "poc.ProtectionFailureFixture", mode)
    }
    private fun trial(action: (Path, DpapiProtector) -> Unit) {
        val dir = Files.createTempDirectory("ea-poc-pending-recovery")
        val real = DpapiProtector(helper())
        try {
            WindowsDeltaJournal(dir, real).use { journal ->
                journal.recordVerifiedGrant(identity, 7, 9)
                journal.save(RecoveryEdit(PendingEdit(identity.device, 7, "fake-op", identity.incarnation), "SYNTHETIC"))
            }
            action(dir, real)
        } finally { Files.newDirectoryStream(dir).use { it.forEach { p -> Files.deleteIfExists(p) } }; Files.delete(dir) }
    }
    @Test fun unreadablePendingCanBeConfirmedArchivedExportedAndFreshGrantAccepted() = trial { dir, real ->
        val path = dir.resolve("pending.dpapi"); val damaged = byteArrayOf(1,2,3,4); Files.write(path, damaged)
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java).acl
        val history = Files.readAllBytes(dir.resolve("revision-floors.dpapi"))
        var archiveId = ""
        WindowsDeltaJournal(dir, real).use { journal ->
            val pc = testController(journal); pc.observeTransport(true)
            assertFails { pc.acceptFixtureGrant(FixtureGrant(identity.device, "fake-blocked", 7, 100, identity.incarnation), 0) }
            val preview = pc.preparePendingQuarantine()!!
            assertEquals(RecoveryFailureReason.DATA_REJECTED, preview.reason); assertEquals(4L, preview.ciphertextSize)
            assertTrue(preview.consequence.contains("never be recoverable")); assertFalse(pc.canEdit(0))
            val result = pc.quarantinePending(preview.confirmation)
            assertEquals(PendingQuarantineResult.QUARANTINED, result.result); archiveId = result.archiveId!!
            val archive = dir.resolve(archiveId)
            assertContentEquals(damaged, Files.readAllBytes(archive)); assertEquals(acl, Files.getFileAttributeView(archive, AclFileAttributeView::class.java).acl)
            assertNull(pc.pending); assertFalse(pc.canEdit(0)); assertTrue(pc.pendingHistoryIncomplete)
            assertContentEquals(history, Files.readAllBytes(dir.resolve("revision-floors.dpapi")))
            assertEquals(PendingQuarantineResult.INVALID_CONFIRMATION, pc.quarantinePending(preview.confirmation).result)
            pc.acceptFixtureGrant(FixtureGrant(identity.device, "fake-fresh", 7, 100, identity.incarnation), 1)
            assertTrue(pc.canEdit(1)); assertFalse(pc.hasSnapshot); pc.suspendSession()
        }
        val exportDir = Files.createTempDirectory("ea-poc-cipher-export")
        try {
            WindowsDeltaJournal(dir, DpapiProtector(dir.resolve("missing.exe"))).use { journal ->
                assertTrue(journal.pendingHistoryIncomplete())
                val output = exportDir.resolve("cipher.dpapi")
                journal.exportPendingArchive(archiveId, output); assertContentEquals(damaged, Files.readAllBytes(output))
                assertEquals(1, Files.getFileAttributeView(output, AclFileAttributeView::class.java).acl.size)
                assertFails { journal.exportPendingArchive(archiveId, output) }; assertContentEquals(damaged, Files.readAllBytes(output))
                assertFails { journal.exportPendingArchive("../pending.dpapi", exportDir.resolve("bad")) }
                assertContentEquals(damaged, Files.readAllBytes(dir.resolve(archiveId)))
            }
        } finally { Files.newDirectoryStream(exportDir).use { it.forEach { p -> Files.delete(p) } }; Files.delete(exportDir) }
    }
    @Test fun readableAndMissingPendingAreNeverQuarantined() = trial { dir, real ->
        WindowsDeltaJournal(dir, real).use { journal ->
            assertFails { journal.preparePendingQuarantine() }; assertFalse(journal.pendingHistoryIncomplete())
            journal.clear(); assertNull(journal.preparePendingQuarantine())
        }
    }
    @Test fun changedCiphertextEvenWithSameSizeAndTimestampRejectsConfirmation() = trial { dir, real ->
        val path = dir.resolve("pending.dpapi"); Files.write(path, byteArrayOf(1,2,3,4))
        WindowsDeltaJournal(dir, real).use { journal ->
            val preview = journal.preparePendingQuarantine()!!; val time = Files.getLastModifiedTime(path)
            Files.write(path, byteArrayOf(4,3,2,1)); Files.setLastModifiedTime(path, time)
            assertEquals(PendingQuarantineResult.CHANGED, journal.quarantinePending(preview.confirmation).result)
            assertFalse(journal.pendingHistoryIncomplete()); assertTrue(Files.exists(path))
            assertEquals(PendingQuarantineResult.INVALID_CONFIRMATION, journal.quarantinePending(preview.confirmation).result)
        }
    }
    @Test fun replacementCancellationForeignConfirmationAndRemovedSourceAreSafe() = trial { dir, real ->
        Files.write(dir.resolve("pending.dpapi"), byteArrayOf(1,2,3,4))
        WindowsDeltaJournal(dir, real).use { journal ->
            val first = journal.preparePendingQuarantine()!!; val second = journal.preparePendingQuarantine()!!
            assertEquals(PendingQuarantineResult.INVALID_CONFIRMATION, journal.quarantinePending(first.confirmation).result)
            journal.cancelPendingQuarantine(second.confirmation)
            assertEquals(PendingQuarantineResult.INVALID_CONFIRMATION, journal.quarantinePending(second.confirmation).result)
            assertEquals(PendingQuarantineResult.INVALID_CONFIRMATION, journal.quarantinePending(PendingQuarantineConfirmation()).result)
            val third = journal.preparePendingQuarantine()!!; Files.delete(dir.resolve("pending.dpapi"))
            assertEquals(PendingQuarantineResult.NO_PENDING, journal.quarantinePending(third.confirmation).result)
            assertFalse(journal.pendingHistoryIncomplete())
        }
    }
    @Test fun temporaryHelperFailureAndLossAfterPreviewNeverArchiveOrWarn() = trial { dir, real ->
        val path = dir.resolve("pending.dpapi"); val original = Files.readAllBytes(path)
        WindowsDeltaJournal(dir, DpapiProtector(dir.resolve("missing.exe"))).use { journal ->
            assertFailsWith<ProtectionUnavailableException> { journal.preparePendingQuarantine() }
            assertFalse(journal.pendingHistoryIncomplete())
        }
        Files.write(path, byteArrayOf(1,2,3,4)); var failed = false
        val protector = DpapiProtector({ if (failed) fixture("exit1") else native(it) }, 10000)
        WindowsDeltaJournal(dir, protector).use { journal ->
            val preview = journal.preparePendingQuarantine()!!; failed = true
            assertFailsWith<ProtectionUnavailableException> { journal.quarantinePending(preview.confirmation) }
            assertFalse(journal.pendingHistoryIncomplete()); assertContentEquals(byteArrayOf(1,2,3,4), Files.readAllBytes(path))
            assertEquals(PendingQuarantineResult.INVALID_CONFIRMATION, journal.quarantinePending(preview.confirmation).result)
        }
        assertTrue(original.isNotEmpty()); assertEquals(0, Files.newDirectoryStream(dir, "pending-quarantine-*.dpapi").use { it.count() })
    }
    @Test fun keyStateIsCarriedToHistoryAndPendingPreviewAndCanaryRejectsKeyFailure() = trial { dir, _ ->
        var calls = 0
        val routed = DpapiProtector({ encrypt -> if (!encrypt && ++calls in setOf(1,2)) fixture("key-reject") else native(encrypt) }, 10000)
        WindowsDeltaJournal(dir, routed).use { journal ->
            val preview = journal.preparePendingQuarantine()!!
            assertEquals(RecoveryFailureReason.KEY_STATE, preview.reason); assertTrue(preview.guidance.contains("previous password"))
            journal.cancelPendingQuarantine(preview.confirmation)
        }
        calls = 0
        WindowsDeltaJournal(dir, routed).use { journal ->
            assertEquals(RecoveryFailureReason.KEY_STATE, journal.prepareQuarantineHistory().reason)
        }
        val keyFailure = DpapiProtector({ fixture("key-reject") }, 10000)
        assertEquals(RecoveryFailureReason.KEY_STATE, assertFailsWith<DecryptionRejectedException> { keyFailure.transform(byteArrayOf(1), false) }.reason)
        assertFailsWith<ProtectionUnavailableException> { keyFailure.requireAvailableEnvironment() }
        val early = DpapiProtector({ fixture("early-key-reject") }, 10000)
        assertFailsWith<ProtectionUnavailableException> { early.transform(ByteArray(131072), false) }
    }
    @Test fun repairedHistoricalKeyKeepsUnchangedReadablePendingInsteadOfArchivingIt() = trial { dir, _ ->
        var calls = 0
        val routed = DpapiProtector({ encrypt -> if (!encrypt && ++calls <= 2) fixture("key-reject") else native(encrypt) }, 10000)
        WindowsDeltaJournal(dir, routed).use { journal ->
            val preview = journal.preparePendingQuarantine()!!
            assertEquals(PendingQuarantineResult.READABLE, journal.quarantinePending(preview.confirmation).result)
            assertNotNull(journal.load()); assertFalse(journal.pendingHistoryIncomplete())
        }
    }
    @Test fun oversizedPendingUsesStreamingFingerprintAndMarkerFailurePreservesCiphertext() = trial { dir, real ->
        val path = dir.resolve("pending.dpapi"); val oversized = ByteArray(131073) { 42 }; Files.write(path, oversized)
        WindowsDeltaJournal(dir, real).use { journal ->
            val preview = journal.preparePendingQuarantine()!!; assertEquals(RecoveryFailureReason.INVALID_FORMAT, preview.reason)
            val obstruction = dir.resolve("pending-quarantined.warning"); Files.createDirectory(obstruction)
            Files.write(obstruction.resolve("block"), byteArrayOf(1))
            try {
                assertFails { journal.quarantinePending(preview.confirmation) }
                assertContentEquals(oversized, Files.readAllBytes(path))
                assertEquals(PendingQuarantineResult.INVALID_CONFIRMATION, journal.quarantinePending(preview.confirmation).result)
            } finally { Files.delete(obstruction.resolve("block")); Files.delete(obstruction) }
            val result = journal.quarantinePending(journal.preparePendingQuarantine()!!.confirmation)
            assertEquals(PendingQuarantineResult.QUARANTINED, result.result)
            assertContentEquals(oversized, Files.readAllBytes(dir.resolve(result.archiveId!!)))
        }
    }
}
