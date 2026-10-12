package everyarticle.sync.protocol

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Message files exchanged through the shared folder:
 *   EveryArticleSync/to-phone/<id>.eam  written by the PC
 *   EveryArticleSync/to-pc/<id>.eam     written by the phone
 * Implementations: MTP (Windows WPD), Android SAF, and a plain directory for tests.
 */
interface Mailbox {
    fun send(name: String, bytes: ByteArray)
    /** Returns complete candidate files in name order. A partially transferred file fails authentication later. */
    fun receive(): List<MailboxFile>
    fun delete(name: String)
}

data class MailboxFile(val name: String, val bytes: ByteArray)

object MailboxNames {
    const val ROOT = "EveryArticleSync"
    const val TO_PHONE = "to-phone"
    const val TO_PC = "to-pc"
    const val SUFFIX = ".eam"
    private val pattern = Regex("[0-9]{13}-[0-9a-f]{16}\\.eam")

    /** Time prefix keeps listing order roughly chronological; uniqueness comes from the random part. */
    fun next(wallMillis: Long): String = "%013d-%s%s".format(wallMillis, SyncCrypto.randomHex(8), SUFFIX)
    fun isMessage(name: String) = pattern.matches(name)
}

/** Plain directory pair. Used by tests and simulated peers; writes are atomic renames. */
class DirectoryMailbox(root: File, outbox: String, inbox: String) : Mailbox {
    private val outDir = File(root, outbox).apply { mkdirs() }
    private val inDir = File(root, inbox).apply { mkdirs() }

    override fun send(name: String, bytes: ByteArray) {
        require(MailboxNames.isMessage(name))
        val temporary = File(outDir, "$name.part")
        temporary.writeBytes(bytes)
        Files.move(temporary.toPath(), File(outDir, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    override fun receive(): List<MailboxFile> = (inDir.listFiles() ?: emptyArray())
        .filter { it.isFile && MailboxNames.isMessage(it.name) && it.length() <= Envelope.MAX_FILE_BYTES }
        .sortedBy { it.name }
        .map { MailboxFile(it.name, it.readBytes()) }

    override fun delete(name: String) { require(MailboxNames.isMessage(name)); File(inDir, name).delete() }

    companion object {
        fun forPc(root: File) = DirectoryMailbox(root, MailboxNames.TO_PHONE, MailboxNames.TO_PC)
        fun forPhone(root: File) = DirectoryMailbox(root, MailboxNames.TO_PC, MailboxNames.TO_PHONE)
    }
}
