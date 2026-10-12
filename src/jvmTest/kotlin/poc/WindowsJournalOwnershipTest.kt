package poc

import java.io.File
import java.nio.file.*
import java.util.concurrent.*
import kotlin.test.*

class WindowsJournalOwnershipTest {
    private fun helper() = Path.of(System.getenv("POC_HELPER") ?: error("POC_HELPER required"))
    private fun cleanup(directory: Path) {
        Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(directory)
    }
    private fun child(directory: Path, mode: String): Process {
        val classpath = listOf(JournalLockSmoke::class.java, kotlin.Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
            .joinToString(File.pathSeparator)
        return ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp", classpath, "poc.JournalLockSmoke", helper().toString(), directory.toString(), mode)
            .redirectErrorStream(true).start()
    }
    private fun completed(directory: Path, mode: String, expected: String) {
        val process = child(directory, mode)
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Child timed out")
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals(0, process.exitValue(), output)
            assertEquals(expected, output)
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
            process.inputStream.close(); process.outputStream.close()
        }
    }

    @Test fun sameJvmAliasesAreRejectedSeparateDirectoriesWorkAndClosedOwnerCannotAccess() {
        val directory = Files.createTempDirectory("ea-poc-owner-test")
        val other = Files.createTempDirectory("ea-poc-other-owner-test")
        val protector = DpapiProtector(helper())
        val owner = WindowsDeltaJournal(directory, protector)
        try {
            val identity = DatasetIdentity("fake-lock-phone", "fake-dataset-v1")
            owner.recordAppliedRevision(identity, 7)
            owner.save(RecoveryEdit(PendingEdit("fake-lock-phone", 7, "op-owner", "fake-dataset-v1"), "SYNTHETIC-OWNER"))
            val pending = Files.readAllBytes(directory.resolve("pending.dpapi"))
            val floors = Files.readAllBytes(directory.resolve("revision-floors.dpapi"))
            assertFailsWith<JournalInUseException> { WindowsDeltaJournal(directory.resolve("."), protector).close() }
            WindowsDeltaJournal(other, protector).use { assertNull(it.load()) }
            assertContentEquals(pending, Files.readAllBytes(directory.resolve("pending.dpapi")))
            assertContentEquals(floors, Files.readAllBytes(directory.resolve("revision-floors.dpapi")))
            assertEquals(1, Files.getFileAttributeView(directory.resolve("journal.lock"),
                java.nio.file.attribute.AclFileAttributeView::class.java).acl.size)
            owner.close(); owner.close() // idempotent; the lock file is deliberately retained
            assertTrue(Files.exists(directory.resolve("journal.lock")))
            assertFailsWith<IllegalStateException> { owner.load() }
            assertFailsWith<IllegalStateException> { owner.clear() }
            assertFailsWith<IllegalStateException> { owner.revisionFloor(identity) }
            assertFailsWith<IllegalStateException> { owner.recordAppliedRevision(identity, 8) }
            assertFailsWith<IllegalStateException> { owner.save(RecoveryEdit(PendingEdit("fake-lock-phone", 7, "op2", "fake-dataset-v1"), "NEW")) }
            WindowsDeltaJournal(directory, protector).use {
                assertEquals(7L, it.revisionFloor(identity)); assertEquals("SYNTHETIC-OWNER", it.load()!!.syntheticDelta)
            }
        } finally { owner.close(); cleanup(directory); cleanup(other) }
    }

    private fun competingProcesses(forceStop: Boolean) {
        val directory = Files.createTempDirectory("ea-poc-process-lock-test")
        val owner = child(directory, "hold")
        val reader = Executors.newSingleThreadExecutor()
        try {
            val output = owner.inputStream.bufferedReader()
            assertEquals("HELD", reader.submit<String> { output.readLine() }.get(20, TimeUnit.SECONDS))
            val pending = Files.readAllBytes(directory.resolve("pending.dpapi"))
            val floors = Files.readAllBytes(directory.resolve("revision-floors.dpapi"))
            completed(directory, "busy", "BUSY")
            assertContentEquals(pending, Files.readAllBytes(directory.resolve("pending.dpapi")))
            assertContentEquals(floors, Files.readAllBytes(directory.resolve("revision-floors.dpapi")))
            if (forceStop) owner.destroyForcibly()
            else owner.outputStream.bufferedWriter().use { it.write("release\n"); it.flush() }
            assertTrue(owner.waitFor(10, TimeUnit.SECONDS))
            if (!forceStop) assertEquals(0, owner.exitValue())
            completed(directory, "verify", "VERIFIED")
            assertContentEquals(pending, Files.readAllBytes(directory.resolve("pending.dpapi")))
            assertContentEquals(floors, Files.readAllBytes(directory.resolve("revision-floors.dpapi")))
        } finally {
            if (owner.isAlive) { owner.destroyForcibly(); owner.waitFor(10, TimeUnit.SECONDS) }
            owner.inputStream.close(); owner.outputStream.close(); reader.shutdownNow()
            cleanup(directory)
        }
    }
    @Test fun otherProcessCannotOpenUntilGracefulReleaseAndCannotDecreaseFloor() = competingProcesses(false)
    @Test fun forcedProcessTerminationReleasesLockWithoutDeletingPendingOrFloor() = competingProcesses(true)
}
