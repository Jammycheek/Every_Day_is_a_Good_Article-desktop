package poc

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.concurrent.*

class DpapiProtector(private val helper: Path) {
    fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= 131072)
        check(System.getProperty("os.name").startsWith("Windows"))
        val process = ProcessBuilder(helper.toAbsolutePath().toString(), if (encrypt) "protect" else "unprotect")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
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
            check(process.waitFor(15, TimeUnit.SECONDS)) { "Recovery helper timeout" }
            check(process.exitValue() == 0) { "Recovery protection failed" }
            write.get(1, TimeUnit.SECONDS)
            return output.get(1, TimeUnit.SECONDS).also { check(it.isNotEmpty()) }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.outputStream.close(); process.inputStream.close()
            workers.shutdownNow()
        }
    }
}

/** One synthetic operation, one local writer. Not a production storage/retention policy. */
class WindowsDeltaJournal(private val directory: Path, private val protector: DpapiProtector) : DeltaJournal {
    private val file = directory.resolve("pending.dpapi")
    private val floorFile = directory.resolve("revision-floors.dpapi")
    init {
        Files.createDirectories(directory)
        check(!Files.isSymbolicLink(directory))
        restrict(directory)
    }
    private fun restrict(path: Path) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: error("Windows ACL unavailable")
        val owner = view.owner
        view.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
            .setPermissions(*AclEntryPermission.values()).build())
    }
    override fun load(): RecoveryEdit? {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 131072)
        val plain = protector.transform(Files.readAllBytes(file), false)
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
        } finally { plain.fill(0) }
    }
    override fun save(edit: RecoveryEdit) {
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
    override fun revisionFloor(identity: DatasetIdentity): Long? = readFloors()[identity]
    override fun recordAppliedRevision(identity: DatasetIdentity, revision: Long) {
        require(revision >= 0)
        val floors = readFloors().toMutableMap()
        val previous = floors[identity]
        if (previous != null && previous >= revision) return
        floors[identity] = revision
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
    private fun readFloors(): Map<DatasetIdentity, Long> {
        if (!Files.exists(floorFile, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        check(Files.isRegularFile(floorFile, LinkOption.NOFOLLOW_LINKS) && Files.size(floorFile) <= 131072)
        val plain = protector.transform(Files.readAllBytes(floorFile), false)
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
        } finally { plain.fill(0) }
    }
    private fun writeProtected(target: Path, plain: ByteArray) {
        val encrypted = try { protector.transform(plain, true) } finally { plain.fill(0) }
        val temporary = Files.createTempFile(directory, "pending-", ".tmp")
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
    override fun clear() { Files.deleteIfExists(file) }
}
