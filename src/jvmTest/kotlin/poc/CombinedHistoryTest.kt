package poc

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.*
import kotlin.test.*

class CombinedHistoryTest {
    private val identity = DatasetIdentity("fake-combined-phone", "fake-instance")
    private fun trial(action: (Path, DpapiProtector) -> Unit) {
        val dir = Files.createTempDirectory("ea-poc-combined-history")
        val protector = DpapiProtector(Path.of(System.getenv("POC_HELPER") ?: error("POC_HELPER required")))
        try { action(dir, protector) } finally {
            Files.newDirectoryStream(dir).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.delete(dir)
        }
    }
    private fun legacy(protector: DpapiProtector, magic: Int, entries: Map<DatasetIdentity, Long>): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(magic); out.writeInt(entries.size)
            entries.forEach { (key, value) -> out.writeUTF(key.device); out.writeUTF(key.incarnation); out.writeLong(value) }
        }
        return protector.transform(buffer.toByteArray(), true)
    }
    @Test fun legacyFilesMergePreserveEpochOnlyKeysAndNeverResurrectForgottenEpochs() = trial { dir, protector ->
        val epochOnly = DatasetIdentity(identity.device, "fake-epoch-only")
        val legacyEpochBytes = legacy(protector, 0x45414531, mapOf(identity to 9L, epochOnly to 20L))
        Files.write(dir.resolve("revision-floors.dpapi"), legacy(protector, 0x45414631, mapOf(identity to 7L)))
        Files.write(dir.resolve("grant-epochs.dpapi"), legacyEpochBytes)
        WindowsDeltaJournal(dir, protector).use { journal ->
            assertEquals(7L, journal.revisionFloor(identity)); assertEquals(9L, journal.verifiedEpoch(identity))
            journal.recordVerifiedGrant(identity, 8, 10)
            assertEquals(20L, journal.verifiedEpoch(epochOnly))
            assertEquals(VerifiedHistoryEntry(null, 20), journal.verifiedHistoryEntries()[epochOnly])
            assertContentEquals(legacyEpochBytes, Files.readAllBytes(dir.resolve("grant-epochs.dpapi")))
            val preview = journal.prepareForgetHistory(identity)!!
            assertEquals(8L, preview.previousFloor); assertEquals(10L, preview.previousEpoch)
            assertTrue(preview.consequence.contains("generations"))
            assertEquals(HistoryRecoveryResult.RECOVERED, journal.recoverHistory(preview.confirmation))
            assertNull(journal.revisionFloor(identity)); assertNull(journal.verifiedEpoch(identity))
            val epochPreview = journal.prepareForgetHistory(epochOnly)!!
            assertNull(epochPreview.previousFloor); assertEquals(20L, epochPreview.previousEpoch)
            assertEquals(HistoryRecoveryResult.RECOVERED, journal.recoverHistory(epochPreview.confirmation))
        }
        WindowsDeltaJournal(dir, protector).use { journal ->
            assertNull(journal.verifiedEpoch(identity)); assertNull(journal.verifiedEpoch(epochOnly))
            val pc = testController(journal); pc.observeTransport(true)
            val request = pc.beginGrantRequest(identity, 0, 100, 0, rebaseline = true)
            assertEquals(0L, request.minimumEpoch)
            assertEquals(GrantAcceptanceResult.ACCEPTED, pc.acceptFreshHistoryBaseline(
                Grant(identity.device, "fake-restored", 1, 100, identity.incarnation, request.requestNonce, 0), request, 1))
        }
    }
    @Test fun corruptionOfLegacyEpochCanQuarantineBothFilesAndPreservePending() = trial { dir, protector ->
        val floors = legacy(protector, 0x45414631, mapOf(identity to 7L))
        val epochs = byteArrayOf(1, 2, 3)
        Files.write(dir.resolve("revision-floors.dpapi"), floors); Files.write(dir.resolve("grant-epochs.dpapi"), epochs)
        WindowsDeltaJournal(dir, protector).use { journal ->
            journal.save(RecoveryEdit(PendingEdit(identity.device, 7, "fake-op", identity.incarnation), "SYNTHETIC"))
            val pending = Files.readAllBytes(dir.resolve("pending.dpapi"))
            assertFailsWith<DecryptionRejectedException> { journal.verifiedEpoch(identity) }
            val preview = journal.prepareQuarantineHistory()
            assertEquals(HistoryRecoveryResult.RECOVERED, journal.recoverHistory(preview.confirmation))
            assertContentEquals(pending, Files.readAllBytes(dir.resolve("pending.dpapi")))
            assertContentEquals(floors, Files.readAllBytes(Files.newDirectoryStream(dir, "revision-floors-quarantine-*").use { it.single() }))
            assertContentEquals(epochs, Files.readAllBytes(Files.newDirectoryStream(dir, "grant-epochs-quarantine-*").use { it.single() }))
            assertNull(journal.verifiedEpoch(identity)); assertTrue(journal.historyRecoveryRequired(identity))
        }
    }
    @Test fun atomicGrantWriteFailureChangesNeitherMaximumNorPendingBytes() = trial { dir, protector ->
        WindowsDeltaJournal(dir, protector).use { journal -> journal.recordVerifiedGrant(identity, 7, 9) }
        val before = Files.readAllBytes(dir.resolve("revision-floors.dpapi"))
        val helper = System.getenv("POC_HELPER")
        val failing = DpapiProtector({ encrypt -> if (encrypt) listOf(dir.resolve("missing-helper.exe").toString())
            else listOf(helper, "unprotect") }, 15000)
        WindowsDeltaJournal(dir, failing).use { journal ->
            assertFailsWith<ProtectionUnavailableException> { journal.recordVerifiedGrant(identity, 8, 10) }
            assertContentEquals(before, Files.readAllBytes(dir.resolve("revision-floors.dpapi")))
            assertEquals(7L, journal.revisionFloor(identity)); assertEquals(9L, journal.verifiedEpoch(identity))
        }
    }
    @Test fun sharedCapacityIsFreedByConfirmedForgetIncludingEpochOnlyIdentity() = trial { dir, protector ->
        val entries = (0 until 256).associate { DatasetIdentity(identity.device, "fake-$it") to it.toLong() }
        Files.write(dir.resolve("grant-epochs.dpapi"), legacy(protector, 0x45414531, entries))
        WindowsDeltaJournal(dir, protector).use { journal ->
            assertFails { journal.recordVerifiedGrant(identity, 7, 9) }
            val forget = journal.prepareForgetHistory(entries.keys.first())!!
            assertEquals(HistoryRecoveryResult.RECOVERED, journal.recoverHistory(forget.confirmation))
            journal.recordVerifiedGrant(identity, 7, 9)
            assertEquals(9L, journal.verifiedEpoch(identity)); assertEquals(7L, journal.revisionFloor(identity))
        }
    }
    @Test fun epochChangeInvalidatesForgetPreviewAndCancellationIsOneUse() = trial { dir, protector ->
        WindowsDeltaJournal(dir, protector).use { journal ->
            journal.recordVerifiedGrant(identity, 7, 9)
            val preview = journal.prepareForgetHistory(identity)!!
            journal.recordVerifiedEpoch(identity, 10)
            assertEquals(HistoryRecoveryResult.CHANGED, journal.recoverHistory(preview.confirmation))
            assertEquals(10L, journal.verifiedEpoch(identity)); assertFalse(journal.historyIsIncomplete())
            val cancelled = journal.prepareForgetHistory(identity)!!; journal.cancelHistoryRecovery(cancelled.confirmation)
            assertEquals(HistoryRecoveryResult.INVALID_CONFIRMATION, journal.recoverHistory(cancelled.confirmation))
        }
    }
}
