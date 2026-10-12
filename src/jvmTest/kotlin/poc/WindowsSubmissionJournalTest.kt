package poc
import kotlin.test.*
import java.nio.file.*

/** Real DPAPI: article-sized payload, submitted marker and atomic replacement survive restart. */
class WindowsSubmissionJournalTest {
    @Test fun largeTextSubmittedMarkerAndReplacementSurviveRestart() {
        val helper = System.getenv("POC_HELPER") ?: error("POC_HELPER required")
        val directory = Files.createTempDirectory("ea-poc-submission")
        val protector = DpapiProtector(Path.of(helper))
        try {
            val body = "合成本文".repeat(8_000) // 96,000 UTF-8 bytes
            val metadata = PendingEdit("fake-phone", 3, "op-large", "fake-instance")
            WindowsDeltaJournal(directory, protector).use { journal ->
                journal.save(RecoveryEdit(metadata, "short"))
                journal.replace(RecoveryEdit(metadata, body))
                journal.replace(RecoveryEdit(metadata, body, submitted = true))
                assertFailsWith<IllegalStateException> { journal.replace(RecoveryEdit(metadata, "unsubmit")) }
                assertFailsWith<IllegalStateException> { journal.replace(RecoveryEdit(metadata.copy(operation = "other"), body, true)) }
                assertFailsWith<IllegalArgumentException> { journal.replace(RecoveryEdit(metadata, "x".repeat(MAX_DELTA_BYTES + 1), true)) }
            }
            WindowsDeltaJournal(directory, protector).use { journal ->
                val loaded = journal.load()!!
                assertEquals(body, loaded.syntheticDelta); assertTrue(loaded.submitted); assertEquals(metadata, loaded.metadata)
            }
        } finally {
            Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}
