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
                check(it.readInt() == 0x45415031) { "Unsupported recovery version" }
                val device = it.readUTF(); val revision = it.readLong(); val operation = it.readUTF(); val delta = it.readUTF()
                check(it.read() == -1 && revision >= 0 && device.isNotBlank() && operation.isNotBlank() && delta.isNotEmpty())
                return RecoveryEdit(PendingEdit(device, revision, operation), delta)
            }
        } finally { plain.fill(0) }
    }
    override fun save(edit: RecoveryEdit) {
        check(load() == null) { "Pending operation already exists" }
        val fields = listOf(edit.metadata.device, edit.metadata.operation, edit.syntheticDelta)
        require(fields.all { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 4096 })
        require(edit.metadata.baseRevision >= 0)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeInt(0x45415031); it.writeUTF(edit.metadata.device); it.writeLong(edit.metadata.baseRevision)
            it.writeUTF(edit.metadata.operation); it.writeUTF(edit.syntheticDelta)
        }
        val plain = buffer.toByteArray()
        val encrypted = try { protector.transform(plain, true) } finally { plain.fill(0) }
        val temporary = Files.createTempFile(directory, "pending-", ".tmp")
        try {
            restrict(temporary)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(encrypted)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            encrypted.fill(0)
            Files.deleteIfExists(temporary)
        }
    }
    override fun clear() { Files.deleteIfExists(file) }
}
