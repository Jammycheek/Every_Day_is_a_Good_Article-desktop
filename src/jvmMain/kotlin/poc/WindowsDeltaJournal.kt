package poc

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.concurrent.*
import java.util.UUID

class ProtectionUnavailableException(cause: Throwable? = null) : IllegalStateException("Recovery protection temporarily unavailable", cause)
class DecryptionRejectedException : IllegalStateException("Recovery ciphertext or key rejected")
class StorageFormatException(cause: Throwable? = null) : IllegalStateException("Invalid recovery storage format", cause)

class DpapiProtector internal constructor(private val command: (Boolean) -> List<String>, private val timeoutMillis: Long) {
    constructor(helper: Path) : this({ encrypt -> listOf(helper.toAbsolutePath().toString(),
        if (encrypt) "protect" else "unprotect") }, 15000)
    fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= 131072)
        check(System.getProperty("os.name").startsWith("Windows"))
        val process = try { ProcessBuilder(command(encrypt)).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
            catch (failure: Exception) { throw ProtectionUnavailableException(failure) }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val output = workers.submit<ByteArray> {
                process.inputStream.use { input ->
                    val data = ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        check(data.size() + count <= 131072) { "Recovery response exceeds limit" }
                        data.write(chunk, 0, count)
                    }
                    data.toByteArray()
                }
            }
            val write = workers.submit { process.outputStream.use { it.write(bytes) } }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw ProtectionUnavailableException()
            write.get(1, TimeUnit.SECONDS)
            val result = output.get(1, TimeUnit.SECONDS)
            val exit = process.exitValue()
            if (!encrypt && exit == 3 && result.isEmpty()) throw DecryptionRejectedException()
            if (exit != 0 || result.isEmpty()) {
                result.fill(0); throw ProtectionUnavailableException()
            }
            return result
        } catch (failure: DecryptionRejectedException) {
            throw failure
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw if (failure is ProtectionUnavailableException) failure else ProtectionUnavailableException(failure)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.outputStream.close() }; runCatching { process.inputStream.close() }
            workers.shutdownNow()
        }
    }
}

class JournalInUseException : IllegalStateException("Recovery directory is already in use")

/** Owns one recovery directory until close/process exit. Callers must still serialize controller calls. */
class WindowsDeltaJournal(private val directory: Path, private val protector: DpapiProtector) : RevisionHistoryRecoveryJournal, AutoCloseable {
    private val file = directory.resolve("pending.dpapi")
    private val floorFile = directory.resolve("revision-floors.dpapi")
    private val lockChannel: FileChannel
    private val directoryLock: FileLock
    private var closed = false
    private val warningFile = directory.resolve("history-incomplete.warning")
    private var recoveryPreview: HistoryRecoveryPreview? = null
    private var previewBytes: ByteArray? = null
    private var previewAttributes: BasicFileAttributes? = null
    init {
        Files.createDirectories(directory)
        check(!Files.isSymbolicLink(directory))
        val channel = FileChannel.open(directory.resolve("journal.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        try {
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw JournalInUseException()
            try {
                restrict(directory)
                restrict(directory.resolve("journal.lock"))
            } catch (failure: Exception) {
                lock.release()
                throw failure
            }
            lockChannel = channel
            directoryLock = lock
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }
    private fun requireOwnership() {
        check(!closed && lockChannel.isOpen && directoryLock.isValid) { "Recovery directory ownership is closed or lost" }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        invalidateRecoveryPreview()
        try { directoryLock.release() } finally { lockChannel.close() }
        // Never unlink the lock file: replacing its file identity could create two lock owners.
    }
    private fun restrict(path: Path) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: error("Windows ACL unavailable")
        val owner = view.owner
        view.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
            .setPermissions(*AclEntryPermission.values()).build())
    }
    @Synchronized override fun load(): RecoveryEdit? {
        requireOwnership()
        val encrypted = readEncrypted(file) ?: return null
        val plain = protector.transform(encrypted, false)
        try {
            DataInputStream(ByteArrayInputStream(plain)).use {
                val version = it.readInt()
                check(version == 0x45415031 || version == 0x45415032) { "Unsupported recovery version" }
                val device = it.readUTF()
                val instance = if (version == 0x45415032) it.readUTF() else null
                val revision = it.readLong(); val operation = it.readUTF(); val delta = it.readUTF()
                check(it.read() == -1 && revision >= 0 && device.isNotBlank() && operation.isNotBlank() && delta.isNotEmpty())
                return RecoveryEdit(PendingEdit(device, revision, operation, instance), delta)
            }
        } catch (failure: Exception) { throw StorageFormatException(failure) }
        finally { plain.fill(0) }
    }
    @Synchronized override fun save(edit: RecoveryEdit) {
        requireOwnership()
        check(load() == null) { "Pending operation already exists" }
        val instance = requireNotNull(edit.metadata.datasetInstance) { "Legacy identity requires explicit resolution" }
        val fields = listOf(edit.metadata.device, instance, edit.metadata.operation, edit.syntheticDelta)
        require(fields.all { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 4096 })
        require(edit.metadata.baseRevision >= 0)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeInt(0x45415032); it.writeUTF(edit.metadata.device); it.writeUTF(instance); it.writeLong(edit.metadata.baseRevision)
            it.writeUTF(edit.metadata.operation); it.writeUTF(edit.syntheticDelta)
        }
        writeProtected(file, buffer.toByteArray())
    }
    @Synchronized override fun revisionFloor(identity: DatasetIdentity): Long? {
        requireOwnership()
        return readFloors()[identity]
    }
    @Synchronized override fun recordVerifiedRevision(identity: DatasetIdentity, revision: Long) {
        requireOwnership()
        require(revision >= 0)
        val floors = readFloors().toMutableMap()
        val previous = floors[identity]
        if (previous != null && previous >= revision) return
        floors[identity] = revision
        writeFloors(floors)
    }
    private fun writeFloors(floors: Map<DatasetIdentity, Long>) {
        require(floors.size <= 256) { "Revision floor capacity exceeded" }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(0x45414631); output.writeInt(floors.size)
            floors.entries.sortedWith(compareBy({ it.key.device }, { it.key.incarnation })).forEach { (key, value) ->
                require(listOf(key.device, key.incarnation).all { it.toByteArray(Charsets.UTF_8).size <= 4096 })
                output.writeUTF(key.device); output.writeUTF(key.incarnation); output.writeLong(value)
            }
        }
        writeProtected(floorFile, buffer.toByteArray())
    }
    private fun invalidateRecoveryPreview() {
        recoveryPreview = null
        previewBytes?.fill(0); previewBytes = null
        previewAttributes = null
    }
    @Synchronized override fun revisionHistoryEntries(): Map<DatasetIdentity, Long> {
        requireOwnership(); return readFloors()
    }
    @Synchronized override fun prepareForgetHistory(identity: DatasetIdentity): HistoryRecoveryPreview? {
        requireOwnership(); invalidateRecoveryPreview()
        val bytes = readEncrypted(floorFile) ?: return null
        val floor = readFloors()[identity] ?: return null
        previewBytes = bytes
        return HistoryRecoveryPreview(HistoryRecoveryKind.FORGET_IDENTITY, identity, floor,
            HistoryRecoveryConfirmation()).also { recoveryPreview = it }
    }
    @Synchronized override fun prepareQuarantineHistory(): HistoryRecoveryPreview {
        requireOwnership(); invalidateRecoveryPreview()
        val attributes = Files.readAttributes(floorFile, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile) { "History must be a regular file" }
        // Only data/key rejection or validated format failure can offer quarantine.
        // Both reads must agree; an operational/IO failure propagates without a preview.
        fun rejected(): Boolean = try { readFloors(); false }
            catch (_: DecryptionRejectedException) { true }
            catch (_: StorageFormatException) { true }
        check(rejected()) { "Readable history requires identity-scoped forgetting" }
        Thread.sleep(100)
        check(rejected()) { "History is readable on retry; repair the helper or use scoped forgetting" }
        val bytes = try { readEncrypted(floorFile) } catch (_: StorageFormatException) { null }
        previewBytes = bytes
        previewAttributes = attributes
        return HistoryRecoveryPreview(HistoryRecoveryKind.QUARANTINE_ALL, null, null,
            HistoryRecoveryConfirmation()).also { recoveryPreview = it }
    }
    @Synchronized override fun cancelHistoryRecovery(confirmation: HistoryRecoveryConfirmation) {
        requireOwnership()
        if (recoveryPreview?.confirmation === confirmation) invalidateRecoveryPreview()
    }
    @Synchronized override fun recoverHistory(confirmation: HistoryRecoveryConfirmation): HistoryRecoveryResult {
        requireOwnership()
        val preview = recoveryPreview
        if (preview == null || preview.confirmation !== confirmation) return HistoryRecoveryResult.INVALID_CONFIRMATION
        val expected = previewBytes
        val attributes = previewAttributes
        recoveryPreview = null; previewBytes = null; previewAttributes = null // one use, including failures
        try {
            if (expected != null) {
                val current = readEncrypted(floorFile)
                if (current == null || !current.contentEquals(expected)) return HistoryRecoveryResult.CHANGED
            } else {
                check(preview.kind == HistoryRecoveryKind.QUARANTINE_ALL && attributes != null)
                val current = try { Files.readAttributes(floorFile, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
                    catch (_: NoSuchFileException) { return HistoryRecoveryResult.CHANGED }
                if (!current.isRegularFile || current.fileKey() != attributes.fileKey() || current.size() != attributes.size() ||
                    current.lastModifiedTime() != attributes.lastModifiedTime()) return HistoryRecoveryResult.CHANGED
            }
            // Durable generic marker precedes history changes. No identifiers/plaintext payload.
            writeMarker(warningFile)
            when (preview.kind) {
                HistoryRecoveryKind.FORGET_IDENTITY -> {
                    val floors = readFloors().toMutableMap()
                    check(floors.remove(preview.identity!!) == preview.previousFloor)
                    writeFloors(floors)
                }
                HistoryRecoveryKind.QUARANTINE_ALL -> {
                    val quarantine = directory.resolve("revision-floors-quarantine-${UUID.randomUUID()}.dpapi")
                    Files.move(floorFile, quarantine, StandardCopyOption.ATOMIC_MOVE)
                    // Keep original encrypted bytes and ACL. No automatic restore, promotion or deletion.
                }
            }
            return HistoryRecoveryResult.RECOVERED
        } finally { expected?.fill(0) }
    }
    @Synchronized override fun historyRecoveryRequired(identity: DatasetIdentity): Boolean {
        requireOwnership()
        return readMarker(warningFile) && readFloors()[identity] == null
    }
    @Synchronized override fun historyIsIncomplete(): Boolean { requireOwnership(); return readMarker(warningFile) }
    @Synchronized override fun finishHistoryRebaseline(identity: DatasetIdentity) {
        requireOwnership(); check(readMarker(warningFile)); check(readFloors()[identity] != null)
        // Marker remains: baselining one identity must not unblock another lost/unknown identity.
    }
    private fun readMarker(path: Path): Boolean {
        val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) { return false }
        // Presence is the conservative warning bit; damaged content cannot erase it or block recovery.
        check(attributes.isRegularFile) { "Invalid history recovery marker" }
        return true
    }
    private fun writeMarker(target: Path) {
        val temporary = Files.createTempFile(directory, "history-marker-", ".tmp")
        try {
            restrict(temporary)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(byteArrayOf(1))); channel.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun readFloors(): Map<DatasetIdentity, Long> {
        val encrypted = readEncrypted(floorFile) ?: return emptyMap()
        val plain = protector.transform(encrypted, false)
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                check(input.readInt() == 0x45414631) { "Unsupported revision floor version" }
                val count = input.readInt(); check(count in 0..256)
                val floors = mutableMapOf<DatasetIdentity, Long>()
                repeat(count) {
                    val identity = DatasetIdentity(input.readUTF(), input.readUTF())
                    val revision = input.readLong(); check(revision >= 0)
                    check(listOf(identity.device, identity.incarnation).all { it.toByteArray(Charsets.UTF_8).size <= 4096 })
                    check(floors.put(identity, revision) == null) { "Duplicate revision floor identity" }
                }
                check(input.read() == -1)
                return floors
            }
        } catch (failure: Exception) { throw StorageFormatException(failure) }
        finally { plain.fill(0) }
    }
    private fun writeProtected(target: Path, plain: ByteArray) {
        val encrypted = try { protector.transform(plain, true) } finally { plain.fill(0) }
        val prefix = when (target) {
            file -> "pending-"
            floorFile -> "floor-"
            else -> error("Unknown protected storage target")
        }
        val temporary = Files.createTempFile(directory, prefix, ".tmp")
        try {
            restrict(temporary)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(encrypted)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            encrypted.fill(0)
            Files.deleteIfExists(temporary)
        }
    }
    @Synchronized override fun clear() { requireOwnership(); Files.deleteIfExists(file) }
    private fun readEncrypted(path: Path): ByteArray? {
        val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return null }
        // Access denied and other IO errors are not equivalent to an absent history file.
        check(attributes.isRegularFile) { "Recovery storage must be a regular file" }
        if (attributes.size() !in 1..131072) throw StorageFormatException()
        return Files.readAllBytes(path).also { if (it.size !in 1..131072) throw StorageFormatException() }
    }
}
