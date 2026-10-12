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
import java.security.SecureRandom
import java.security.MessageDigest

class ProtectionUnavailableException(cause: Throwable? = null) : IllegalStateException("Recovery protection temporarily unavailable", cause)
class DecryptionRejectedException(val reason: RecoveryFailureReason = RecoveryFailureReason.DATA_REJECTED) : IllegalStateException("Recovery decryption rejected: $reason")
open class StorageFormatException(cause: Throwable? = null) : IllegalStateException("Invalid recovery storage format", cause)

class LegacyReimportBlockedException : StorageFormatException()

class DpapiProtector internal constructor(private val command: (Boolean) -> List<String>, private val timeoutMillis: Long) {
    constructor(helper: Path) : this({ encrypt -> listOf(helper.toAbsolutePath().toString(),
        if (encrypt) "protect" else "unprotect") }, 15000)
    /** Checks current protection availability, not the existence of historical keys or file integrity. */
    internal fun requireAvailableEnvironment() {
        val challenge = ByteArray(32)
        var encrypted: ByteArray? = null
        var restored: ByteArray? = null
        try {
            SecureRandom().nextBytes(challenge)
            encrypted = transform(challenge, true)
            restored = transform(encrypted, false)
            if (!challenge.contentEquals(restored)) throw ProtectionUnavailableException()
        } catch (failure: Exception) {
            throw if (failure is ProtectionUnavailableException) failure else ProtectionUnavailableException(failure)
        } finally {
            challenge.fill(0); encrypted?.fill(0); restored?.fill(0)
        }
    }
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
            if (!encrypt && (exit == 3 || exit == 4) && result.isEmpty())
                throw DecryptionRejectedException(if (exit == 4) RecoveryFailureReason.KEY_STATE else RecoveryFailureReason.DATA_REJECTED)
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

/** The chosen export folder has no Windows ACLs; ask the user before calling again with explicit permission. */
class UnprotectedExportDestinationException : IllegalStateException("Destination filesystem has no access control lists")

/** One article title+body edit plus framing; keeps the DPAPI record below the 128 KiB helper bound. */
const val MAX_DELTA_BYTES = 102_400

class JournalInUseException : IllegalStateException("Recovery directory is already in use")

/** Owns one recovery directory until close/process exit. Callers must still serialize controller calls. */
class WindowsDeltaJournal(private val directory: Path, private val protector: DpapiProtector) : RevisionHistoryRecoveryJournal, PendingQuarantineJournal, AutoCloseable {
    private val file = directory.resolve("pending.dpapi")
    private val floorFile = directory.resolve("revision-floors.dpapi")
    private val epochFile = directory.resolve("grant-epochs.dpapi")
    private val lockChannel: FileChannel
    private val directoryLock: FileLock
    private var closed = false
    private val migrationMarker = directory.resolve("history-combined.marker")
    private val pendingWarning = directory.resolve("pending-quarantined.warning")
    private var pendingQuarantinePreview: PendingQuarantinePreview? = null
    private var pendingFingerprint: CipherFingerprint? = null
    private val warningFile = directory.resolve("history-incomplete.warning")
    private var recoveryPreview: HistoryRecoveryPreview? = null
    private data class HistoryValue(val floor: Long?, val epoch: Long?)
    private data class Source(val bytes: ByteArray?, val attributes: BasicFileAttributes)
    private var previewSources: Map<Path, Source> = emptyMap()
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
        invalidateRecoveryPreview(); invalidatePendingQuarantine()
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
                check(version == 0x45415031 || version == 0x45415032 || version == 0x45415033) { "Unsupported recovery version" }
                val device = it.readUTF()
                val instance = if (version != 0x45415031) it.readUTF() else null
                val revision = it.readLong(); val operation = it.readUTF()
                val submitted = if (version == 0x45415033) it.readBoolean() else false
                val delta = if (version == 0x45415033) {
                    val size = it.readInt(); check(size in 1..MAX_DELTA_BYTES)
                    val bytes = ByteArray(size); it.readFully(bytes)
                    try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() } finally { bytes.fill(0) }
                } else it.readUTF()
                check(it.read() == -1 && revision >= 0 && device.isNotBlank() && operation.isNotBlank() && delta.isNotEmpty())
                return RecoveryEdit(PendingEdit(device, revision, operation, instance), delta, submitted)
            }
        } catch (failure: Exception) { throw StorageFormatException(failure) }
        finally { plain.fill(0) }
    }
    @Synchronized override fun save(edit: RecoveryEdit) {
        requireOwnership()
        check(load() == null) { "Pending operation already exists" }
        writePending(edit)
    }
    /** Atomic in-place update of the same operation (autosave or submitted marker); never changes identity. */
    @Synchronized override fun replace(edit: RecoveryEdit) {
        requireOwnership()
        val current = load() ?: error("No pending operation to replace")
        check(current.metadata == edit.metadata) { "Replacement must keep the same operation identity" }
        check(!current.submitted || edit.submitted) { "A submitted operation cannot return to staged" }
        writePending(edit)
    }
    private fun writePending(edit: RecoveryEdit) {
        val instance = requireNotNull(edit.metadata.datasetInstance) { "Legacy identity requires explicit resolution" }
        val fields = listOf(edit.metadata.device, instance, edit.metadata.operation)
        require(fields.all { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 4096 })
        val delta = edit.syntheticDelta.toByteArray(Charsets.UTF_8)
        require(delta.size in 1..MAX_DELTA_BYTES)
        require(edit.metadata.baseRevision >= 0)
        val buffer = ByteArrayOutputStream()
        try {
            DataOutputStream(buffer).use {
                it.writeInt(0x45415033); it.writeUTF(edit.metadata.device); it.writeUTF(instance); it.writeLong(edit.metadata.baseRevision)
                it.writeUTF(edit.metadata.operation); it.writeBoolean(edit.submitted); it.writeInt(delta.size); it.write(delta)
            }
        } finally { delta.fill(0) }
        writeProtected(file, buffer.toByteArray())
    }
    @Synchronized override fun revisionFloor(identity: DatasetIdentity): Long? {
        requireOwnership(); return readHistory()[identity]?.floor
    }
    @Synchronized override fun verifiedEpoch(identity: DatasetIdentity): Long? {
        requireOwnership(); return readHistory()[identity]?.epoch
    }
    @Synchronized override fun recordVerifiedRevision(identity: DatasetIdentity, revision: Long) {
        requireOwnership(); require(revision >= 0)
        updateHistory(identity, revision, null)
    }
    @Synchronized override fun recordVerifiedEpoch(identity: DatasetIdentity, epoch: Long) {
        requireOwnership(); require(epoch >= 0)
        updateHistory(identity, null, epoch)
    }
    @Synchronized override fun recordVerifiedGrant(identity: DatasetIdentity, revision: Long, epoch: Long) {
        requireOwnership(); require(revision >= 0 && epoch >= 0)
        updateHistory(identity, revision, epoch)
    }
    private fun updateHistory(identity: DatasetIdentity, revision: Long?, epoch: Long?) {
        val history = readHistory().toMutableMap()
        val previous = history[identity]
        fun maximum(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else maxOf(a, b)
        val next = HistoryValue(maximum(previous?.floor, revision), maximum(previous?.epoch, epoch))
        if (next == previous) return
        history[identity] = next
        writeHistory(history)
    }
    private fun writeHistory(history: Map<DatasetIdentity, HistoryValue>) {
        require(history.size <= 256) { "Verified history capacity exceeded; explicitly forget an identity" }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(0x45414632); output.writeInt(history.size)
            history.entries.sortedWith(compareBy({ it.key.device }, { it.key.incarnation })).forEach { (key, value) ->
                require(listOf(key.device, key.incarnation).all { it.toByteArray(Charsets.UTF_8).size <= 4096 })
                output.writeUTF(key.device); output.writeUTF(key.incarnation)
                output.writeLong(value.floor ?: -1); output.writeLong(value.epoch ?: -1)
            }
        }
        // This atomic replacement is authoritative. Legacy epoch bytes are retained, never re-imported.
        // Mark before replacement: failures retain bytes but disable automatic legacy re-import.
        if (!readMarker(migrationMarker)) writeMarker(migrationMarker)
        writeProtected(floorFile, buffer.toByteArray())
        retireLegacyEpochs()
    }
    private fun invalidateRecoveryPreview() {
        recoveryPreview = null
        previewSources.values.forEach { it.bytes?.fill(0) }; previewSources = emptyMap()
    }
    @Synchronized override fun verifiedHistoryEntries(): Map<DatasetIdentity, VerifiedHistoryEntry> {
        requireOwnership(); return readHistory().mapValues { VerifiedHistoryEntry(it.value.floor, it.value.epoch) }
    }
    @Synchronized override fun revisionHistoryEntries(): Map<DatasetIdentity, Long> {
        requireOwnership(); return readFloors()
    }
    private fun captureSources(): Map<Path, Source> = listOf(floorFile, epochFile).mapNotNull { path ->
        val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) { return@mapNotNull null }
        check(attributes.isRegularFile) { "History must be a regular file" }
        val bytes = try { readEncrypted(path) } catch (_: StorageFormatException) { null }
        path to Source(bytes, attributes)
    }.toMap()
    @Synchronized override fun prepareForgetHistory(identity: DatasetIdentity): HistoryRecoveryPreview? {
        requireOwnership(); invalidateRecoveryPreview()
        val value = readHistory()[identity] ?: return null
        previewSources = captureSources()
        return HistoryRecoveryPreview(HistoryRecoveryKind.FORGET_IDENTITY, identity, value.floor,
            HistoryRecoveryConfirmation(), value.epoch).also { recoveryPreview = it }
    }
    @Synchronized override fun prepareQuarantineHistory(): HistoryRecoveryPreview {
        requireOwnership(); invalidateRecoveryPreview()
        val first = failureReason { readHistory() } ?: error("Readable history requires identity-scoped forgetting")
        Thread.sleep(100)
        val second = failureReason { readHistory() } ?: error("History is readable on retry")
        val reason = if (first == second) first else RecoveryFailureReason.MIXED
        protector.requireAvailableEnvironment() // do not offer recovery when the current environment cannot round-trip
        previewSources = captureSources()
        check(previewSources.isNotEmpty())
        return HistoryRecoveryPreview(HistoryRecoveryKind.QUARANTINE_ALL, null, null,
            HistoryRecoveryConfirmation(), reason = reason, guidance = recoveryGuidance(reason)).also { recoveryPreview = it }
    }
    @Synchronized override fun cancelHistoryRecovery(confirmation: HistoryRecoveryConfirmation) {
        requireOwnership()
        if (recoveryPreview?.confirmation === confirmation) invalidateRecoveryPreview()
    }
    @Synchronized override fun recoverHistory(confirmation: HistoryRecoveryConfirmation): HistoryRecoveryResult {
        requireOwnership()
        val preview = recoveryPreview
        if (preview == null || preview.confirmation !== confirmation) return HistoryRecoveryResult.INVALID_CONFIRMATION
        val sources = previewSources
        recoveryPreview = null; previewSources = emptyMap() // one use, including failures
        try {
            val currentPaths = captureSources()
            try {
                if (currentPaths.keys != sources.keys) return HistoryRecoveryResult.CHANGED
                for ((path, expected) in sources) {
                    val current = currentPaths.getValue(path)
                    if (expected.bytes != null) {
                        if (current.bytes == null || !current.bytes.contentEquals(expected.bytes)) return HistoryRecoveryResult.CHANGED
                    } else {
                        val a = current.attributes; val b = expected.attributes
                        if (a.fileKey() != b.fileKey() || a.size() != b.size() || a.lastModifiedTime() != b.lastModifiedTime())
                            return HistoryRecoveryResult.CHANGED
                    }
                }
            } finally { currentPaths.values.forEach { it.bytes?.fill(0) } }
            protector.requireAvailableEnvironment() // both forget and quarantine must check before writing the warning
            writeMarker(warningFile) // durable warning before losing either defence
            when (preview.kind) {
                HistoryRecoveryKind.FORGET_IDENTITY -> {
                    val history = readHistory().toMutableMap()
                    check(history.remove(preview.identity!!) == HistoryValue(preview.previousFloor, preview.previousEpoch))
                    writeHistory(history) // both maxima removed in the same replacement, frees one shared slot
                }
                HistoryRecoveryKind.QUARANTINE_ALL -> {
                    for (path in sources.keys) {
                        val stem = if (path == floorFile) "revision-floors" else "grant-epochs"
                        Files.move(path, directory.resolve("$stem-quarantine-${UUID.randomUUID()}.dpapi"), StandardCopyOption.ATOMIC_MOVE)
                    }
                    // Retain original ciphertext and ACL. Migration/quarantine of two legacy files is not a transaction.
                }
            }
            return HistoryRecoveryResult.RECOVERED
        } finally { sources.values.forEach { it.bytes?.fill(0) } }
    }
    @Synchronized override fun historyRecoveryRequired(identity: DatasetIdentity): Boolean {
        requireOwnership()
        return historyIsIncomplete() && readHistory()[identity]?.floor == null
    }
    @Synchronized override fun historyIsIncomplete(): Boolean { requireOwnership(); return readMarker(warningFile) || (readMarker(migrationMarker) && !hasRegularHistoryFile()) }
    @Synchronized override fun finishHistoryRebaseline(identity: DatasetIdentity) {
        requireOwnership(); check(historyIsIncomplete() || readMarker(migrationMarker)); check(readFloors()[identity] != null)
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
    private fun readFloors(): Map<DatasetIdentity, Long> = readHistory().mapNotNull { (key, value) ->
        value.floor?.let { key to it }
    }.toMap()
    private fun readHistory(): Map<DatasetIdentity, HistoryValue> {
        val encrypted = readEncrypted(floorFile)
        var combined = false
        val floors = if (encrypted == null) emptyMap() else {
            val plain = protector.transform(encrypted, false)
            try {
                DataInputStream(ByteArrayInputStream(plain)).use { input ->
                    val magic = input.readInt(); check(magic == 0x45414631 || magic == 0x45414632)
                    combined = magic == 0x45414632
                    val count = input.readInt(); check(count in 0..256)
                    val values = mutableMapOf<DatasetIdentity, HistoryValue>()
                    repeat(count) {
                        val key = DatasetIdentity(input.readUTF(), input.readUTF())
                        val floor = input.readLong(); val epoch = if (combined) input.readLong() else -1
                        check(floor >= -1 && epoch >= -1 && (floor >= 0 || epoch >= 0))
                        check(listOf(key.device, key.incarnation).all { it.toByteArray(Charsets.UTF_8).size <= 4096 })
                        check(values.put(key, HistoryValue(floor.takeIf { it >= 0 }, epoch.takeIf { it >= 0 })) == null)
                    }
                    check(input.read() == -1); values
                }
            } catch (failure: Exception) { throw StorageFormatException(failure) }
            finally { plain.fill(0) }
        }
        if (combined) return floors
        if (readMarker(migrationMarker)) {
            if (encrypted != null || hasLegacyEpochFile()) throw LegacyReimportBlockedException()
            if (!readMarker(warningFile)) writeMarker(warningFile) // missing combined history must remain a warning after one baseline
            return emptyMap() // explicit quarantine/missing combined history: require fresh baseline
        }
        val epochs = readLegacyEpochs()
        val keys = floors.keys + epochs.keys
        if (keys.size > 256) throw StorageFormatException()
        return keys.associateWith { HistoryValue(floors[it]?.floor, epochs[it]) }
    }
    private fun hasRegularHistoryFile(): Boolean = regularFileExists(floorFile)
    private fun hasLegacyEpochFile(): Boolean = regularFileExists(epochFile)
    private fun regularFileExists(path: Path): Boolean {
        val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) { return false }
        check(attributes.isRegularFile); return true
    }
    private fun retireLegacyEpochs() {
        if (hasLegacyEpochFile()) Files.move(epochFile,
            directory.resolve("grant-epochs-migrated-${UUID.randomUUID()}.dpapi"), StandardCopyOption.ATOMIC_MOVE)
    }
    private fun failureReason(read: () -> Any?): RecoveryFailureReason? = try { read(); null }
        catch (failure: DecryptionRejectedException) { failure.reason }
        catch (_: LegacyReimportBlockedException) { RecoveryFailureReason.LEGACY_REIMPORT_BLOCKED }
        catch (_: StorageFormatException) { RecoveryFailureReason.INVALID_FORMAT }
    private fun readLegacyEpochs(): Map<DatasetIdentity, Long> {
        val encrypted = readEncrypted(epochFile) ?: return emptyMap()
        val plain = protector.transform(encrypted, false)
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                check(input.readInt() == 0x45414531)
                val count = input.readInt(); check(count in 0..256)
                val values = mutableMapOf<DatasetIdentity, Long>()
                repeat(count) {
                    val key = DatasetIdentity(input.readUTF(), input.readUTF()); val epoch = input.readLong()
                    check(epoch >= 0)
                    check(listOf(key.device, key.incarnation).all { it.toByteArray(Charsets.UTF_8).size <= 4096 })
                    check(values.put(key, epoch) == null)
                }
                check(input.read() == -1); return values
            }
        } catch (failure: Exception) { throw StorageFormatException(failure) }
        finally { plain.fill(0) }
    }
    private fun writeProtected(target: Path, plain: ByteArray) {
        val encrypted = try { protector.transform(plain, true) } finally { plain.fill(0) }
        val prefix = when (target) {
            file -> "pending-"
            floorFile -> "floor-"
            epochFile -> "grant-epoch-"
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
    private data class CipherFingerprint(val size: Long, val digest: String, val fileKey: Any?, val modified: FileTime)
    private fun fingerprint(path: Path): CipherFingerprint? {
        val before = try { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) { return null }
        check(before.isRegularFile) { "Ciphertext must be a regular file" }
        val hash = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(8192); var size = 0L
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                while (true) {
                    val count = input.read(chunk); if (count < 0) break
                    hash.update(chunk, 0, count); size += count
                }
            }
        } finally { chunk.fill(0) }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(after.isRegularFile && before.fileKey() == after.fileKey() && before.size() == size &&
            after.size() == size && before.lastModifiedTime() == after.lastModifiedTime()) { "Ciphertext changed during inspection" }
        return CipherFingerprint(size, hash.digest().joinToString("") { "%02x".format(it) }, after.fileKey(), after.lastModifiedTime())
    }
    private fun invalidatePendingQuarantine() { pendingQuarantinePreview = null; pendingFingerprint = null }
    @Synchronized override fun preparePendingQuarantine(): PendingQuarantinePreview? {
        requireOwnership(); invalidatePendingQuarantine()
        val captured = fingerprint(file) ?: return null
        val first = failureReason { load() } ?: error("Readable pending edits require discard/export, not quarantine")
        Thread.sleep(100)
        val second = failureReason { load() } ?: error("Pending edit is readable on retry")
        protector.requireAvailableEnvironment()
        check(fingerprint(file) == captured) { "Pending ciphertext changed during preview" }
        val reason = if (first == second) first else RecoveryFailureReason.MIXED
        return PendingQuarantinePreview(captured.size, reason, PendingQuarantineConfirmation(), guidance = recoveryGuidance(reason)).also {
            pendingQuarantinePreview = it; pendingFingerprint = captured
        }
    }
    @Synchronized override fun cancelPendingQuarantine(confirmation: PendingQuarantineConfirmation) {
        requireOwnership(); if (pendingQuarantinePreview?.confirmation === confirmation) invalidatePendingQuarantine()
    }
    @Synchronized override fun pendingHistoryIncomplete(): Boolean { requireOwnership(); return readMarker(pendingWarning) }
    @Synchronized override fun quarantinePending(confirmation: PendingQuarantineConfirmation): PendingQuarantineOutcome {
        requireOwnership()
        val preview = pendingQuarantinePreview
        if (preview == null || preview.confirmation !== confirmation)
            return PendingQuarantineOutcome(PendingQuarantineResult.INVALID_CONFIRMATION)
        val expected = pendingFingerprint!!; invalidatePendingQuarantine()
        val current = fingerprint(file) ?: return PendingQuarantineOutcome(PendingQuarantineResult.NO_PENDING)
        if (current != expected) return PendingQuarantineOutcome(PendingQuarantineResult.CHANGED)
        protector.requireAvailableEnvironment()
        // Key repair may make unchanged ciphertext readable while the user is confirming.
        val reason = failureReason { load() } ?: return PendingQuarantineOutcome(PendingQuarantineResult.READABLE)
        if (preview.reason != RecoveryFailureReason.MIXED && reason != preview.reason)
            return PendingQuarantineOutcome(PendingQuarantineResult.CHANGED)
        if (fingerprint(file) != expected) return PendingQuarantineOutcome(PendingQuarantineResult.CHANGED)
        val archiveId = "pending-quarantine-${UUID.randomUUID()}.dpapi"
        writeMarker(pendingWarning)
        Files.move(file, directory.resolve(archiveId), StandardCopyOption.ATOMIC_MOVE)
        return PendingQuarantineOutcome(PendingQuarantineResult.QUARANTINED, archiveId)
    }
    /** Export only retained ciphertext, no decryption/current-key requirement and no overwrite. */
    /**
     * V1: an ACL-capable destination (NTFS) is the default. A filesystem without ACLs (FAT32/exFAT removable media)
     * is accepted only with [allowUnprotectedFilesystem] after the user explicitly chose it; the bytes stay DPAPI
     * ciphertext that only the same Windows account with its original key can open (see [EXPORT_GUIDANCE]).
     */
    @Synchronized fun exportPendingArchive(archiveId: String, destination: Path, allowUnprotectedFilesystem: Boolean = false) {
        requireOwnership()
        require(Regex("pending-quarantine-[0-9a-f-]{36}\\.dpapi").matches(archiveId))
        UUID.fromString(archiveId.removePrefix("pending-quarantine-").removeSuffix(".dpapi"))
        val source = directory.resolve(archiveId)
        val expected = fingerprint(source) ?: error("Archive missing")
        val requested = destination.toAbsolutePath().normalize()
        val parent = requested.parent.toRealPath()
        check(parent != directory.toRealPath()) { "Export needs a separate existing local directory" }
        // Resolve existing directory aliases once; never follow a destination-file link.
        val target = parent.resolve(requested.fileName)
        // The view object exists on every Windows volume; only the file store says whether ACLs are kept (not on FAT32/exFAT).
        val aclCapable = Files.getFileStore(parent).supportsFileAttributeView(AclFileAttributeView::class.java)
        if (!aclCapable && !allowUnprotectedFilesystem) throw UnprotectedExportDestinationException()
        var created = false
        try {
            FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                created = true; if (aclCapable) restrict(target)
                Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val chunk = ByteArray(8192)
                    try {
                        while (true) {
                            val count = input.read(chunk); if (count < 0) break
                            val buffer = ByteBuffer.wrap(chunk, 0, count)
                            while (buffer.hasRemaining()) output.write(buffer)
                        }
                        output.force(true)
                    } finally { chunk.fill(0) }
                }
            }
            check(fingerprint(source) == expected && fingerprint(target)?.let { it.size == expected.size && it.digest == expected.digest } == true)
        } catch (failure: Exception) {
            if (created) runCatching { Files.deleteIfExists(target) }
            throw failure
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
