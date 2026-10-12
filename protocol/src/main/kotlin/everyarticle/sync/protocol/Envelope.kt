package everyarticle.sync.protocol

/** Which side wrote a message. Direction is bound into the key so a message can never be reflected back. */
enum class Role { PC, PHONE }

/** A peer that completed numeric-comparison pairing. The key never leaves protected local storage. */
data class PairedPeer(val peerId: String, val displayName: String, val pairingKey: ByteArray) {
    init { require(peerId.isNotBlank() && pairingKey.size == 32) }
}

data class OpenedMessage(val senderId: String, val messageId: String, val message: SyncMessage, val authenticated: Boolean)

/**
 * File envelope:
 *   "EASY" | u8 version | u8 kind(0=pairing plaintext, 1=sealed) | sender | recipient | messageId(16)
 *   kind 0: body bytes
 *   kind 1: iv(12) | AES-256-GCM(body) with the header as associated data
 * MTP only carries these files; it never authenticates anything.
 */
object Envelope {
    private val MAGIC = byteArrayOf('E'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte(), 'Y'.code.toByte())
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

    private fun header(kind: Int, sender: String, recipient: String, messageId: ByteArray): ByteArray =
        MAGIC + CanonicalWriter().u8(PROTOCOL_VERSION).u8(kind).string(sender).string(recipient).bytes(messageId).toByteArray()

    private fun directionKey(peer: PairedPeer, writer: Role): ByteArray =
        SyncCrypto.hkdf(peer.pairingKey, ByteArray(0), "EA-message-key-v1|${writer.name}", 32)

    fun pairing(sender: String, recipient: String, message: SyncMessage): ByteArray {
        require(message.type in 1..4) { "Only pairing messages travel in plaintext" }
        val id = SyncCrypto.randomBytes(16)
        return header(0, sender, recipient, id) + CanonicalWriter().bytes(message.encode()).toByteArray()
    }

    fun seal(sender: String, writer: Role, peer: PairedPeer, message: SyncMessage): ByteArray {
        require(message.type >= 10) { "Pairing messages are not sealed" }
        val id = SyncCrypto.randomBytes(16)
        val head = header(1, sender, peer.peerId, id)
        val iv = SyncCrypto.randomBytes(12)
        val ciphertext = SyncCrypto.aesGcmSeal(directionKey(peer, writer), iv, head, message.encode())
        return head + CanonicalWriter().bytes(iv).bytes(ciphertext).toByteArray()
    }

    /**
     * @param localId only messages addressed to this endpoint are accepted.
     * @param writer the role expected to have written the file (the opposite of the reader).
     * @param peers lookup for sealed messages; unknown senders are rejected.
     */
    fun open(bytes: ByteArray, localId: String, writer: Role, peers: (String) -> PairedPeer?): OpenedMessage {
        if (bytes.size > MAX_FILE_BYTES || bytes.size < MAGIC.size || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC))
            throw ProtocolFormatException("Not a sync envelope")
        val r = CanonicalReader(bytes.copyOfRange(MAGIC.size, bytes.size))
        if (r.u8() != PROTOCOL_VERSION) throw ProtocolFormatException("Unsupported protocol version")
        val kind = r.u8()
        val sender = r.string(256); val recipient = r.string(256); val id = r.bytes(16)
        if (id.size != 16) throw ProtocolFormatException("Invalid message id")
        if (recipient != localId) throw ProtocolFormatException("Message is addressed to another endpoint")
        val head = header(kind, sender, recipient, id)
        return when (kind) {
            0 -> {
                val message = SyncMessage.decode(r.bytes(64 * 1024)).also { r.end() }
                if (message.type !in 1..4) throw ProtocolFormatException("Unsealed non-pairing message")
                OpenedMessage(sender, id.hex(), message, authenticated = false)
            }
            1 -> {
                val peer = peers(sender) ?: throw ProtocolFormatException("Sender is not paired")
                val iv = r.bytes(12); val ciphertext = r.bytes(MAX_FILE_BYTES); r.end()
                val message = SyncMessage.decode(SyncCrypto.aesGcmOpen(directionKey(peer, writer), iv, head, ciphertext))
                if (message.type < 10) throw ProtocolFormatException("Pairing message inside sealed envelope")
                OpenedMessage(sender, id.hex(), message, authenticated = true)
            }
            else -> throw ProtocolFormatException("Unknown envelope kind")
        }
    }
}
