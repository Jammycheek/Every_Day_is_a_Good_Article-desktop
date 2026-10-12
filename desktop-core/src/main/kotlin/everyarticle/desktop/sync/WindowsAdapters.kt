package everyarticle.desktop.sync

import everyarticle.sync.protocol.Envelope
import everyarticle.sync.protocol.Mailbox
import everyarticle.sync.protocol.MailboxFile
import everyarticle.sync.protocol.MailboxNames
import everyarticle.sync.protocol.PairedPeer
import everyarticle.sync.protocol.SyncCrypto
import poc.DpapiProtector
import poc.TransportState
import poc.WindowsTransportReader
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded child-process call of the native helper. Arguments never contain message content. */
internal object HelperProcess {
    fun run(helper: Path, args: List<String>, stdin: ByteArray?, maxOutput: Int, timeoutSeconds: Long = 30): ByteArray {
        val process = ProcessBuilder(listOf(helper.toAbsolutePath().toString()) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val output = workers.submit<ByteArray> {
                process.inputStream.use { input ->
                    val data = ByteArrayOutputStream()
                    val chunk = ByteArray(65536)
                    while (true) {
                        val count = input.read(chunk); if (count < 0) break
                        check(data.size() + count <= maxOutput) { "Helper output exceeds limit" }
                        data.write(chunk, 0, count)
                    }
                    data.toByteArray()
                }
            }
            val write = workers.submit { process.outputStream.use { if (stdin != null) it.write(stdin) } }
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "Helper timeout" }
            write.get(5, TimeUnit.SECONDS)
            val result = output.get(5, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { "Helper failed (${process.exitValue()})" }
            return result
        } finally {
            if (process.isAlive) process.destroyForcibly()
            workers.shutdownNow()
        }
    }
}

/** Current USB/MTP phone selector from the existing polling probe; null unless exactly one ready device. */
class WindowsTransport(helper: Path) {
    private val reader = WindowsTransportReader(helper)
    @Volatile var selector: String? = null
        private set
    fun sample(): Boolean {
        val observation = reader.sample()
        selector = if (observation.state == TransportState.USB_MTP_READY) observation.localSelector else null
        return selector != null
    }
}

/** PC side of the mailbox over WPD/MTP. Only the helper's whitelisted mailbox commands are used. */
class WpdMailbox(private val helper: Path, private val selector: () -> String?) : Mailbox {
    private val objectIds = HashMap<String, String>()
    private fun device() = selector() ?: throw IllegalStateException("No USB/MTP phone")

    override fun send(name: String, bytes: ByteArray) {
        require(MailboxNames.isMessage(name))
        val out = HelperProcess.run(helper, listOf("mailbox-write", device(), MailboxNames.TO_PHONE, name), bytes, 4096)
        check(String(out, Charsets.UTF_8).contains("mailbox-written=1")) { "Mailbox write not confirmed" }
    }

    override fun receive(): List<MailboxFile> {
        val device = device()
        val listing = String(HelperProcess.run(helper, listOf("mailbox-list", device, MailboxNames.TO_PC), null, 1 shl 20), Charsets.UTF_8)
        val entries = parseListing(listing)
        objectIds.clear()
        return entries.sortedBy { it.second }.mapNotNull { (id, name, size) ->
            if (size > Envelope.MAX_FILE_BYTES) return@mapNotNull null
            objectIds[name] = id
            val bytes = HelperProcess.run(helper, listOf("mailbox-read", device, id), null, Envelope.MAX_FILE_BYTES)
            MailboxFile(name, bytes)
        }
    }

    override fun delete(name: String) {
        val id = objectIds.remove(name) ?: return
        HelperProcess.run(helper, listOf("mailbox-delete", device(), id), null, 4096)
    }

    companion object {
        /** Strict parser for "object-id= / name= / size=" triples. */
        fun parseListing(text: String): List<Triple<String, String, Long>> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            require(lines.size % 3 == 0) { "Malformed mailbox listing" }
            return lines.chunked(3).map { (a, b, c) ->
                require(a.startsWith("object-id=") && b.startsWith("name=") && c.startsWith("size="))
                val name = b.removePrefix("name=")
                require(MailboxNames.isMessage(name))
                Triple(a.removePrefix("object-id="), name, c.removePrefix("size=").toLong())
            }
        }
    }
}

/** PC identity and the paired phone key, DPAPI-protected (current Windows user only). */
class ProtectedPcPeerStore(private val directory: Path, private val protector: DpapiProtector) : PcPeerStore {
    private val file = directory.resolve("pairing.dpapi")
    private var cachedId: String? = null
    private var cachedPeer: PairedPeer? = null

    init { Files.createDirectories(directory); load() }

    override val pcId: String get() = cachedId!!
    override fun peer() = cachedPeer
    override fun save(peer: PairedPeer) { cachedPeer = peer; write() }
    override fun forget() { cachedPeer = null; write() }

    private fun load() {
        if (!Files.exists(file)) { cachedId = "pc-" + SyncCrypto.randomHex(8); write(); return }
        val plain = protector.transform(Files.readAllBytes(file), false)
        try {
            DataInputStream(plain.inputStream()).use {
                check(it.readInt() == 0x45415031) { "Unsupported pairing file" }
                cachedId = it.readUTF()
                cachedPeer = if (it.readBoolean()) {
                    val id = it.readUTF(); val name = it.readUTF(); val key = ByteArray(32).also { k -> it.readFully(k) }
                    PairedPeer(id, name, key)
                } else null
                check(it.read() == -1)
            }
        } finally { plain.fill(0) }
    }

    private fun write() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeInt(0x45415031); it.writeUTF(cachedId!!)
            val p = cachedPeer
            it.writeBoolean(p != null)
            if (p != null) { it.writeUTF(p.peerId); it.writeUTF(p.displayName); it.write(p.pairingKey) }
        }
        val plain = buffer.toByteArray()
        val encrypted = try { protector.transform(plain, true) } finally { plain.fill(0) }
        val temporary = Files.createTempFile(directory, "pairing-", ".tmp")
        try {
            Files.write(temporary, encrypted)
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
