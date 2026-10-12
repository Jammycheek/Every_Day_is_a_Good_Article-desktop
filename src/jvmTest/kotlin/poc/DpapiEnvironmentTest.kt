package poc

import java.io.File
import java.nio.file.*
import kotlin.test.*

class DpapiEnvironmentTest {
    private val identity = DatasetIdentity("fake-canary-phone", "fake-instance")
    private fun helper() = Path.of(System.getenv("POC_HELPER") ?: error("POC_HELPER required"))
    private fun fixture(mode: String): List<String> {
        val classpath = listOf(ProtectionFailureFixture::class.java, kotlin.Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        return listOf(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp", classpath, "poc.ProtectionFailureFixture", mode)
    }
    private fun trial(action: (Path, DpapiProtector) -> Unit) {
        val dir = Files.createTempDirectory("ea-poc-canary-test")
        val real = DpapiProtector(helper())
        try {
            WindowsDeltaJournal(dir, real).use { journal ->
                journal.recordVerifiedGrant(identity, 7, 9)
                journal.save(RecoveryEdit(PendingEdit(identity.device, 7, "fake-op", identity.incarnation), "SYNTHETIC"))
            }
            action(dir, real)
        } finally {
            Files.newDirectoryStream(dir).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.delete(dir)
        }
    }
    private fun native(encrypt: Boolean) = listOf(helper().toString(), if (encrypt) "protect" else "unprotect")

    @Test fun realEnvironmentRoundTripsFreshCanariesWithoutChangingStorage() = trial { dir, real ->
        val before = Files.newDirectoryStream(dir).use { files -> files.associateWith { Files.readAllBytes(it) } }
        real.requireAvailableEnvironment(); real.requireAvailableEnvironment()
        before.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        assertEquals(before.keys, Files.newDirectoryStream(dir).use { it.toSet() })
    }
    @Test fun currentEnvironmentRejectionNeverOffersQuarantineForNormalHistory() = trial { dir, _ ->
        val floors = Files.readAllBytes(dir.resolve("revision-floors.dpapi"))
        val pending = Files.readAllBytes(dir.resolve("pending.dpapi"))
        val unavailable = DpapiProtector({ encrypt -> if (encrypt) native(true) else fixture("reject") }, 10000)
        WindowsDeltaJournal(dir, unavailable).use { journal ->
            val failure = assertFailsWith<ProtectionUnavailableException> { journal.prepareQuarantineHistory() }
            assertIs<DecryptionRejectedException>(failure.cause)
            assertFalse(journal.historyIsIncomplete())
        }
        assertContentEquals(floors, Files.readAllBytes(dir.resolve("revision-floors.dpapi")))
        assertContentEquals(pending, Files.readAllBytes(dir.resolve("pending.dpapi")))
        assertEquals(0, Files.newDirectoryStream(dir, "*-quarantine-*.dpapi").use { it.count() })
    }
    @Test fun canaryHelperFailuresAndMismatchNeverOfferQuarantineOrWriteWarning() = trial { dir, _ ->
        val before = Files.readAllBytes(dir.resolve("revision-floors.dpapi"))
        val pending = Files.readAllBytes(dir.resolve("pending.dpapi"))
        for (mode in listOf("missing-protect", "protect-failure", "exit1", "timeout", "empty", "overflow", "mismatch")) {
            var unprotectCalls = 0
            val protector = DpapiProtector({ encrypt ->
                if (encrypt) when (mode) {
                    "missing-protect" -> listOf(dir.resolve("missing.exe").toString())
                    "protect-failure" -> fixture("exit1")
                    else -> native(true)
                } else {
                    unprotectCalls++
                    fixture(if (unprotectCalls <= 2) "reject" else mode)
                }
            }, if (mode == "timeout") 1000 else 10000)
            WindowsDeltaJournal(dir, protector).use { journal ->
                assertFailsWith<ProtectionUnavailableException>(mode) { journal.prepareQuarantineHistory() }
                assertFalse(journal.historyIsIncomplete())
            }
            assertContentEquals(before, Files.readAllBytes(dir.resolve("revision-floors.dpapi")))
            assertContentEquals(pending, Files.readAllBytes(dir.resolve("pending.dpapi")))
            assertEquals(0, Files.newDirectoryStream(dir, "*-quarantine-*.dpapi").use { it.count() })
        }
    }
    @Test fun environmentLostAfterPreviewPreventsConfirmationAndConsumesToken() = trial { dir, _ ->
        val damaged = byteArrayOf(1, 2, 3, 4)
        Files.write(dir.resolve("revision-floors.dpapi"), damaged)
        val pending = Files.readAllBytes(dir.resolve("pending.dpapi"))
        var unavailable = false
        val protector = DpapiProtector({ encrypt -> if (unavailable) fixture("reject") else native(encrypt) }, 10000)
        WindowsDeltaJournal(dir, protector).use { journal ->
            val preview = journal.prepareQuarantineHistory()
            unavailable = true
            assertFailsWith<ProtectionUnavailableException> { journal.recoverHistory(preview.confirmation) }
            assertEquals(HistoryRecoveryResult.INVALID_CONFIRMATION, journal.recoverHistory(preview.confirmation))
            assertFalse(journal.historyIsIncomplete())
            assertContentEquals(damaged, Files.readAllBytes(dir.resolve("revision-floors.dpapi")))
            assertContentEquals(pending, Files.readAllBytes(dir.resolve("pending.dpapi")))
            assertEquals(0, Files.newDirectoryStream(dir, "*-quarantine-*.dpapi").use { it.count() })
            unavailable = false
            assertEquals(HistoryRecoveryResult.RECOVERED, journal.recoverHistory(journal.prepareQuarantineHistory().confirmation))
        }
    }
    @Test fun invalidSchemaStillRequiresAvailableEnvironmentBeforeQuarantine() = trial { dir, real ->
        val malformed = real.transform(byteArrayOf(1, 2, 3, 4), true)
        Files.write(dir.resolve("revision-floors.dpapi"), malformed)
        var failed = true
        val protector = DpapiProtector({ encrypt -> if (encrypt && failed) fixture("exit1") else native(encrypt) }, 10000)
        WindowsDeltaJournal(dir, protector).use { journal ->
            assertFailsWith<ProtectionUnavailableException> { journal.prepareQuarantineHistory() }
            assertFalse(journal.historyIsIncomplete()); assertContentEquals(malformed, Files.readAllBytes(dir.resolve("revision-floors.dpapi")))
            failed = false
            val preview = journal.prepareQuarantineHistory()
            assertEquals(HistoryRecoveryResult.RECOVERED, journal.recoverHistory(preview.confirmation))
            assertContentEquals(malformed, Files.readAllBytes(Files.newDirectoryStream(dir, "*-quarantine-*.dpapi").use { it.single() }))
            assertNotNull(journal.load())
        }
    }
}
