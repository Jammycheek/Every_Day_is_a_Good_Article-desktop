package poc

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.*
import kotlin.test.*

/** Test-only pipe peer; no device access and no production fault switches. */
object ProtectionFailureFixture {
    @JvmStatic fun main(args: Array<String>) {
        if (args[0] == "early-reject") kotlin.system.exitProcess(3)
        System.`in`.readBytes()
        when (args[0]) {
            "exit1" -> kotlin.system.exitProcess(1)
            "reject" -> kotlin.system.exitProcess(3)
            "timeout" -> Thread.sleep(30000)
            "empty" -> Unit
            "overflow" -> System.out.write(ByteArray(131073))
            "reject-once", "reject-then-fail" -> {
                val marker = Path.of(args[1])
                if (!Files.exists(marker)) {
                    Files.write(marker, byteArrayOf(1)); kotlin.system.exitProcess(3)
                }
                if (args[0] == "reject-then-fail") kotlin.system.exitProcess(1)
                val buffer = ByteArrayOutputStream()
                DataOutputStream(buffer).use { it.writeInt(0x45414631); it.writeInt(0) }
                System.out.write(buffer.toByteArray())
            }
            else -> error("Unknown fixture")
        }
        System.out.flush()
    }
}

class ProtectionFailureTest {
    private val identity = DatasetIdentity("fake-failure-phone", "fake-instance")
    private fun realProtector() = DpapiProtector(Path.of(System.getenv("POC_HELPER") ?: error("POC_HELPER required")))
    private fun fixture(mode: String, marker: Path? = null): DpapiProtector {
        val classpath = listOf(ProtectionFailureFixture::class.java, kotlin.Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        return DpapiProtector({ listOf(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp", classpath, "poc.ProtectionFailureFixture", mode) + listOfNotNull(marker?.toString()) },
            if (mode == "timeout") 1000 else 10000)
    }
    private fun trial(action: (Path) -> Unit) {
        val dir = Files.createTempDirectory("ea-poc-protection-failure")
        try {
            WindowsDeltaJournal(dir, realProtector()).use {
                it.recordVerifiedRevision(identity, 7)
                it.save(RecoveryEdit(PendingEdit(identity.device, 7, "fake-op", identity.incarnation), "SYNTHETIC"))
            }
            action(dir)
        } finally {
            Files.newDirectoryStream(dir).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }
    private fun unavailable(protector: (Path) -> DpapiProtector) = trial { dir ->
        val floors = Files.readAllBytes(dir.resolve("revision-floors.dpapi"))
        val pending = Files.readAllBytes(dir.resolve("pending.dpapi"))
        WindowsDeltaJournal(dir, protector(dir)).use {
            val pc = testController(it)
            assertFailsWith<ProtectionUnavailableException> { pc.prepareQuarantineRevisionHistory() }
            assertFailsWith<ProtectionUnavailableException> { pc.pending }
            assertFalse(pc.canEdit(0)); assertFalse(pc.hasSnapshot)
            assertFalse(it.historyIsIncomplete())
            assertEquals(0, Files.newDirectoryStream(dir, "revision-floors-quarantine-*.dpapi").use { files -> files.count() })
        }
        assertContentEquals(floors, Files.readAllBytes(dir.resolve("revision-floors.dpapi")))
        assertContentEquals(pending, Files.readAllBytes(dir.resolve("pending.dpapi")))
        WindowsDeltaJournal(dir, realProtector()).use { assertEquals(7L, it.revisionFloor(identity)); assertNotNull(it.load()) }
    }
    @Test fun missingHelperNeverOffersQuarantineOrLosesPending() = unavailable { DpapiProtector(it.resolve("missing.exe")) }
    @Test fun genericHelperFailureNeverOffersQuarantineOrLosesPending() = unavailable { fixture("exit1") }
    @Test fun helperTimeoutNeverOffersQuarantineOrLosesPending() = unavailable { fixture("timeout") }
    @Test fun emptySuccessfulReplyNeverOffersQuarantine() = unavailable { fixture("empty") }
    @Test fun oversizedReplyNeverOffersQuarantine() = unavailable { fixture("overflow") }
    @Test fun incompleteInputCannotBeClassifiedAsDecryptionRejection() {
        assertFailsWith<ProtectionUnavailableException> { fixture("early-reject").transform(ByteArray(131072), false) }
    }
    @Test fun recoveredSecondReadNeverOffersQuarantineAndLeavesNoWarning() = trial { dir ->
        val floors = Files.readAllBytes(dir.resolve("revision-floors.dpapi"))
        WindowsDeltaJournal(dir, fixture("reject-once", dir.resolve("fixture-marker"))).use {
            assertFailsWith<IllegalStateException> { it.prepareQuarantineHistory() }
            assertFalse(it.historyIsIncomplete())
        }
        assertContentEquals(floors, Files.readAllBytes(dir.resolve("revision-floors.dpapi")))
    }
    @Test fun operationalFailureOnSecondReadNeverOffersQuarantine() = unavailable { dir ->
        fixture("reject-then-fail", dir.resolve("fixture-marker"))
    }
    @Test fun validProtectionWithInvalidFloorSchemaOffersConfirmedQuarantine() = trial { dir ->
        val malformed = realProtector().transform(byteArrayOf(1, 2, 3, 4), true)
        Files.write(dir.resolve("revision-floors.dpapi"), malformed)
        WindowsDeltaJournal(dir, realProtector()).use {
            assertFailsWith<StorageFormatException> { it.revisionFloor(identity) }
            val preview = it.prepareQuarantineHistory()
            assertFalse(it.historyIsIncomplete())
            assertEquals(HistoryRecoveryResult.RECOVERED, it.recoverHistory(preview.confirmation))
            val archive = Files.newDirectoryStream(dir, "revision-floors-quarantine-*.dpapi").use { files -> files.single() }
            assertContentEquals(malformed, Files.readAllBytes(archive)); assertNotNull(it.load())
        }
    }
    @Test fun realDecryptionRejectionHasDedicatedTypeForPendingAndHistory() = trial { dir ->
        Files.write(dir.resolve("revision-floors.dpapi"), byteArrayOf(1, 2, 3, 4))
        Files.write(dir.resolve("pending.dpapi"), byteArrayOf(1, 2, 3, 4))
        WindowsDeltaJournal(dir, realProtector()).use {
            assertFailsWith<DecryptionRejectedException> { it.load() }
            assertFailsWith<DecryptionRejectedException> { it.revisionFloor(identity) }
            val preview = it.prepareQuarantineHistory(); it.cancelHistoryRecovery(preview.confirmation)
            assertFalse(it.historyIsIncomplete())
        }
    }
}
