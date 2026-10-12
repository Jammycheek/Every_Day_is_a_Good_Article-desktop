package poc
import kotlin.test.*
import java.nio.file.*
import java.nio.file.attribute.AclFileAttributeView
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class WindowsDeltaJournalTest {
    private val ownedJournals = mutableListOf<WindowsDeltaJournal>()
    private fun openJournal(directory: Path, protector: DpapiProtector) =
        WindowsDeltaJournal(directory, protector).also { ownedJournals.add(it) }
    private fun closeJournals() { ownedJournals.forEach { it.close() }; ownedJournals.clear() }
    @Test fun confirmedDiscardDeletesOnlyPendingAndRetainsExactProtectedFloorAcrossRestart() {
        val helper = System.getenv("POC_HELPER") ?: error("POC_HELPER must point to the built Windows helper")
        val directory = Files.createTempDirectory("ea-poc-discard-test")
        try {
            val protector = DpapiProtector(Path.of(helper))
            val journal = openJournal(directory, protector)
            val identity = DatasetIdentity("fake-phone", "fake-dataset-v1")
            journal.recordVerifiedRevision(identity, 8)
            journal.save(RecoveryEdit(PendingEdit("fake-phone", 7, "fake-op", "fake-dataset-v1"), "SYNTHETIC-DISCARD"))
            val floorFile = directory.resolve("revision-floors.dpapi")
            val floorBytes = Files.readAllBytes(floorFile)
            val pc = testController(journal); pc.observeTransport(true)
            assertFailsWith<IllegalStateException> { pc.acceptFixtureGrant(FixtureGrant("fake-phone", "fresh", 8, 100, "fake-dataset-v1"), 0) }
            val preview = pc.preparePendingDiscard()!!
            assertTrue(Files.exists(directory.resolve("pending.dpapi")))
            assertEquals(PendingDiscardResult.DISCARDED, pc.discardPending(preview.confirmation))
            assertFalse(Files.exists(directory.resolve("pending.dpapi")))
            assertContentEquals(floorBytes, Files.readAllBytes(floorFile))
            journal.close()
            val restarted = testController(openJournal(directory, protector)); restarted.observeTransport(true)
            assertFailsWith<IllegalStateException> { restarted.acceptFixtureGrant(FixtureGrant("fake-phone", "stale", 7, 100, "fake-dataset-v1"), 0) }
            restarted.acceptFixtureGrant(FixtureGrant("fake-phone", "fresh", 8, 100, "fake-dataset-v1"), 0)
            assertTrue(restarted.canEdit(0)); assertFalse(restarted.hasSnapshot)
        } finally {
            closeJournals()
            Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun dpapiRevisionFloorsPersistAdvanceAndRemainIndependentOfPendingDeletion() {
        val helper = System.getenv("POC_HELPER") ?: error("POC_HELPER must point to the built Windows helper")
        val directory = Files.createTempDirectory("ea-poc-floor-test")
        try {
            val protector = DpapiProtector(Path.of(helper))
            val journal = openJournal(directory, protector)
            val a = DatasetIdentity("fake-phone", "fake-dataset-v1")
            val b = DatasetIdentity("fake-phone", "fake-dataset-v2")
            journal.recordVerifiedRevision(a, 8); journal.recordVerifiedRevision(a, 10)
            journal.recordVerifiedRevision(a, 9); journal.recordVerifiedRevision(b, 1)
            journal.clear()
            journal.close()
            val restartedStore = openJournal(directory, protector)
            assertEquals(10L, restartedStore.revisionFloor(a)); assertEquals(1L, restartedStore.revisionFloor(b))
            val encrypted = Files.readAllBytes(directory.resolve("revision-floors.dpapi"))
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("fake-dataset-v1"))
            assertEquals(1, Files.getFileAttributeView(directory.resolve("revision-floors.dpapi"), AclFileAttributeView::class.java).acl.size)
            val pc = testController(restartedStore); pc.observeTransport(true)
            assertFailsWith<IllegalStateException> { pc.acceptFixtureGrant(FixtureGrant("fake-phone", "stale", 9, 100, "fake-dataset-v1"), 0) }
            pc.acceptFixtureGrant(FixtureGrant("fake-phone", "fresh", 10, 100, "fake-dataset-v1"), 0)
            assertTrue(pc.canEdit(0)); assertFalse(pc.hasSnapshot)
        } finally {
            closeJournals()
            Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun corruptedRevisionFloorBlocksGrantsAndIsPreserved() {
        val helper = System.getenv("POC_HELPER") ?: error("POC_HELPER must point to the built Windows helper")
        val directory = Files.createTempDirectory("ea-poc-floor-corrupt-test")
        try {
            val protector = DpapiProtector(Path.of(helper))
            val journal = openJournal(directory, protector)
            journal.recordVerifiedRevision(DatasetIdentity("fake-phone", "fake-dataset-v1"), 8)
            val pc = testController(journal); pc.observeTransport(true)
            pc.acceptFixtureGrant(FixtureGrant("fake-phone", "s1", 8, 100, "fake-dataset-v1"), 0)
            pc.loadSyntheticSnapshot(byteArrayOf(1), 0)
            val file = directory.resolve("revision-floors.dpapi")
            val damaged = Files.readAllBytes(file); damaged[damaged.lastIndex] = (damaged.last().toInt() xor 0xff).toByte()
            Files.write(file, damaged)
            assertFails { pc.acceptFixtureGrant(FixtureGrant("fake-phone", "s2", 9, 100, "fake-dataset-v1"), 1) }
            assertFalse(pc.canEdit(1)); assertFalse(pc.hasSnapshot)
            assertContentEquals(damaged, Files.readAllBytes(file))
        } finally {
            closeJournals()
            Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun validLegacyJournalIsReadableButNeverAssignedAnIncarnation() {
        val helper = System.getenv("POC_HELPER") ?: error("POC_HELPER must point to the built Windows helper")
        val directory = Files.createTempDirectory("ea-poc-legacy-test")
        try {
            val protector = DpapiProtector(Path.of(helper))
            val journal = openJournal(directory, protector)
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use {
                it.writeInt(0x45415031); it.writeUTF("fake-phone"); it.writeLong(7)
                it.writeUTF("fake-op"); it.writeUTF("SYNTHETIC-LEGACY")
            }
            val encrypted = protector.transform(buffer.toByteArray(), true)
            val file = directory.resolve("pending.dpapi"); Files.write(file, encrypted)
            val pc = testController(journal); val legacy = pc.pending!!
            assertNull(legacy.metadata.datasetInstance); assertEquals("SYNTHETIC-LEGACY", legacy.syntheticDelta)
            pc.observeTransport(true)
            assertFailsWith<IllegalStateException> { pc.acceptFixtureGrant(FixtureGrant("fake-phone", "s1", 7, 100, "fake-dataset-v1"), 0) }
            assertEquals(ReconciliationResult.NOT_THIS_OPERATION,
                pc.reconcileApplied(AppliedReceipt("fake-phone", 7, "fake-op", legacy.contentDigest, 8, "fake-dataset-v1")))
            assertContentEquals(encrypted, Files.readAllBytes(file))
            assertFalse(Files.exists(directory.resolve("revision-floors.dpapi")))
            val preview = pc.preparePendingDiscard()!!
            assertNull(preview.metadata.datasetInstance)
            pc.cancelPendingDiscard(preview.confirmation)
            assertContentEquals(encrypted, Files.readAllBytes(file))
            assertEquals(PendingDiscardResult.DISCARDED, pc.discardPending(pc.preparePendingDiscard()!!.confirmation))
            assertFalse(Files.exists(file)); assertFalse(pc.canEdit(0))
            pc.acceptFixtureGrant(FixtureGrant("fake-phone", "new", 8, 100, "fake-dataset-v1"), 0)
            assertTrue(pc.canEdit(0)); assertFalse(pc.hasSnapshot)
        } finally {
            closeJournals()
            Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun malformedLegacyJournalStopsEditingAndRetainsEncryptedBytes() {
        val helper = System.getenv("POC_HELPER") ?: error("POC_HELPER must point to the built Windows helper")
        val directory = Files.createTempDirectory("ea-poc-malformed-test")
        try {
            val protector = DpapiProtector(Path.of(helper))
            val pc = testController(openJournal(directory, protector))
            pc.observeTransport(true); pc.acceptFixtureGrant(FixtureGrant("fake-phone", "s1", 7, 100, "fake-dataset-v1"), 0)
            pc.loadSyntheticSnapshot(byteArrayOf(1), 0)
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use {
                it.writeInt(0x45415031); it.writeUTF("fake-phone"); it.writeLong(7)
                it.writeUTF("fake-op"); it.writeUTF("\uD800") // legacy writeUTF can preserve unpaired surrogates
            }
            val encrypted = protector.transform(buffer.toByteArray(), true)
            val file = directory.resolve("pending.dpapi")
            Files.write(file, encrypted)
            assertIs<IllegalArgumentException>(assertFailsWith<StorageFormatException> { pc.pending }.cause)
            assertIs<IllegalArgumentException>(assertFailsWith<StorageFormatException> { pc.preparePendingDiscard() }.cause)
            assertFalse(pc.canEdit(1)); assertFalse(pc.hasSnapshot)
            assertContentEquals(encrypted, Files.readAllBytes(file))
        } finally {
            closeJournals()
            Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun dpapiRestartRecoveryAndCorruptionRejection() {
        val helper = System.getenv("POC_HELPER") ?: error("POC_HELPER must point to the built Windows helper")
        val directory = Files.createTempDirectory("ea-poc-recovery-test")
        try {
            val protector = DpapiProtector(Path.of(helper))
            val journal = openJournal(directory, protector)
            val pc = testController(journal)
            pc.observeTransport(true); pc.acceptFixtureGrant(FixtureGrant("fake-phone", "s1", 7, 100, "fake-dataset-v1"), 0)
            pc.loadSyntheticSnapshot(byteArrayOf(42), 0)
            pc.stage("fake-op", "SYNTHETIC-SECRET-DELTA", 1); pc.observeTransport(false)
            assertFalse(pc.hasSnapshot)
            val encrypted = Files.readAllBytes(directory.resolve("pending.dpapi"))
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("SYNTHETIC-SECRET-DELTA"))
            val entries = Files.getFileAttributeView(directory.resolve("pending.dpapi"), AclFileAttributeView::class.java).acl
            assertEquals(1, entries.size)
            journal.close()
            val restartedStore = openJournal(directory, protector)
            val restarted = testController(restartedStore)
            assertFalse(restarted.hasSnapshot); assertFalse(restarted.canEdit(2))
            assertEquals("SYNTHETIC-SECRET-DELTA", restarted.recoveryCandidate("fake-phone", 7, "fake-dataset-v1")?.syntheticDelta)
            assertFailsWith<IllegalStateException> { restarted.recoveryCandidate("different", 7, "fake-dataset-v1") }
            val edit = restarted.pending!!
            assertEquals(ReconciliationResult.CLEARED,
                restarted.reconcileApplied(AppliedReceipt("fake-phone", 7, "fake-op", edit.contentDigest, 8, "fake-dataset-v1")))
            assertFalse(Files.exists(directory.resolve("pending.dpapi")))
            assertFalse(restarted.canEdit(102)); assertFalse(restarted.hasSnapshot)
            restarted.observeTransport(true); restarted.acceptFixtureGrant(FixtureGrant("fake-phone", "s2", 8, 200, "fake-dataset-v1"), 103)
            restarted.suspendSession()
            restartedStore.save(RecoveryEdit(PendingEdit("fake-phone", 7, "fake-op", "fake-dataset-v1"), "SYNTHETIC-SECRET-DELTA"))
            val damaged = Files.readAllBytes(directory.resolve("pending.dpapi"))
            damaged[damaged.lastIndex] = (damaged.last().toInt() xor 0xff).toByte()
            Files.write(directory.resolve("pending.dpapi"), damaged)
            restartedStore.close()
            assertFails { openJournal(directory, protector).load() }
            assertTrue(Files.exists(directory.resolve("pending.dpapi"))) // do not discard unreadable recovery
        } finally {
            closeJournals()
            Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}
