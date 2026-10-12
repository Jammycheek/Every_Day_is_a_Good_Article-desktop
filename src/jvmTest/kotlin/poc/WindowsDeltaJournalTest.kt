package poc
import kotlin.test.*
import java.nio.file.*
import java.nio.file.attribute.AclFileAttributeView
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class WindowsDeltaJournalTest {
    @Test fun malformedLegacyJournalStopsEditingAndRetainsEncryptedBytes() {
        val helper = System.getenv("POC_HELPER") ?: error("POC_HELPER must point to the built Windows helper")
        val directory = Files.createTempDirectory("ea-poc-malformed-test")
        try {
            val protector = DpapiProtector(Path.of(helper))
            val pc = RecoveryController(WindowsDeltaJournal(directory, protector))
            pc.observeTransport(true); pc.acceptGrant(Grant("fake-phone", "s1", 7, 100), 0)
            pc.loadSyntheticSnapshot(byteArrayOf(1), 0)
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use {
                it.writeInt(0x45415031); it.writeUTF("fake-phone"); it.writeLong(7)
                it.writeUTF("fake-op"); it.writeUTF("\uD800") // legacy writeUTF can preserve unpaired surrogates
            }
            val encrypted = protector.transform(buffer.toByteArray(), true)
            val file = directory.resolve("pending.dpapi")
            Files.write(file, encrypted)
            assertFailsWith<IllegalArgumentException> { pc.pending }
            assertFalse(pc.canEdit(1)); assertFalse(pc.hasSnapshot)
            assertContentEquals(encrypted, Files.readAllBytes(file))
        } finally {
            Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun dpapiRestartRecoveryAndCorruptionRejection() {
        val helper = System.getenv("POC_HELPER") ?: error("POC_HELPER must point to the built Windows helper")
        val directory = Files.createTempDirectory("ea-poc-recovery-test")
        try {
            val protector = DpapiProtector(Path.of(helper))
            val journal = WindowsDeltaJournal(directory, protector)
            val pc = RecoveryController(journal)
            pc.observeTransport(true); pc.acceptGrant(Grant("fake-phone", "s1", 7, 100), 0)
            pc.loadSyntheticSnapshot(byteArrayOf(42), 0)
            pc.stage("fake-op", "SYNTHETIC-SECRET-DELTA", 1); pc.observeTransport(false)
            assertFalse(pc.hasSnapshot)
            val encrypted = Files.readAllBytes(directory.resolve("pending.dpapi"))
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("SYNTHETIC-SECRET-DELTA"))
            val entries = Files.getFileAttributeView(directory.resolve("pending.dpapi"), AclFileAttributeView::class.java).acl
            assertEquals(1, entries.size)
            val restarted = RecoveryController(WindowsDeltaJournal(directory, protector))
            assertFalse(restarted.hasSnapshot); assertFalse(restarted.canEdit(2))
            assertEquals("SYNTHETIC-SECRET-DELTA", restarted.recoveryCandidate("fake-phone", 7)?.syntheticDelta)
            assertFailsWith<IllegalStateException> { restarted.recoveryCandidate("different", 7) }
            val edit = restarted.pending!!
            assertEquals(ReconciliationResult.CLEARED,
                restarted.reconcileApplied(AppliedReceipt("fake-phone", 7, "fake-op", edit.contentDigest, 8)))
            assertFalse(Files.exists(directory.resolve("pending.dpapi")))
            assertFalse(restarted.canEdit(102)); assertFalse(restarted.hasSnapshot)
            restarted.observeTransport(true); restarted.acceptGrant(Grant("fake-phone", "s2", 8, 200), 103)
            restarted.suspendSession()
            journal.save(RecoveryEdit(PendingEdit("fake-phone", 7, "fake-op"), "SYNTHETIC-SECRET-DELTA"))
            val damaged = Files.readAllBytes(directory.resolve("pending.dpapi"))
            damaged[damaged.lastIndex] = (damaged.last().toInt() xor 0xff).toByte()
            Files.write(directory.resolve("pending.dpapi"), damaged)
            assertFails { WindowsDeltaJournal(directory, protector).load() }
            assertTrue(Files.exists(directory.resolve("pending.dpapi"))) // do not discard unreadable recovery
        } finally {
            Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}
