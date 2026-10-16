package everyarticle.sync.protocol

import java.security.KeyPair

/**
 * Result of key agreement. [sas] must be shown on both screens and compared by the user before
 * either side sends [SyncMessage.PairConfirm]; [pairingKey] is stored only after both confirmations.
 */
class PairingResult internal constructor(
    val peerId: String, val peerName: String, val sas: String,
    val pairingKey: ByteArray, internal val transcriptHash: ByteArray
) {
    fun confirmationMac(writer: Role): ByteArray =
        SyncCrypto.hmacSha256(pairingKey, CanonicalWriter().string("EA-pair-confirm-v1").string(writer.name)
            .bytes(transcriptHash).toByteArray())

    /** @throws ProtocolFormatException if the peer's confirmation does not prove the same key. */
    fun verifyPeerConfirmation(peerWriter: Role, confirm: SyncMessage.PairConfirm): PairedPeer {
        if (confirm.senderId != peerId || !SyncCrypto.constantTimeEquals(confirm.mac, confirmationMac(peerWriter)))
            throw ProtocolFormatException("Pairing confirmation mismatch")
        return PairedPeer(peerId, peerName, pairingKey)
    }
}

private fun commitment(pcId: String, publicKey: ByteArray, nonce: ByteArray): ByteArray =
    SyncCrypto.sha256(CanonicalWriter().string("EA-pair-commit-v1").string(pcId).bytes(publicKey).bytes(nonce).toByteArray())

private fun derive(shared: ByteArray, pcId: String, phoneId: String, pcKey: ByteArray, phoneKey: ByteArray,
    pcNonce: ByteArray, phoneNonce: ByteArray, peerId: String, peerName: String): PairingResult {
    val transcript = SyncCrypto.sha256(CanonicalWriter().string("EA-pair-transcript-v1").string(pcId).string(phoneId)
        .bytes(pcKey).bytes(phoneKey).bytes(pcNonce).bytes(phoneNonce).toByteArray())
    val sasBytes = SyncCrypto.hkdf(shared, transcript, "EA-sas-v1", 8)
    val sasValue = sasBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }.let { java.lang.Long.remainderUnsigned(it, 1_000_000) }
    val key = SyncCrypto.hkdf(shared, transcript, "EA-pair-key-v1", 32)
    shared.fill(0)
    return PairingResult(peerId, peerName, "%06d".format(sasValue), key, transcript)
}

/**
 * PC side of commit-then-reveal numeric comparison: the PC commits to its key before it sees the
 * phone's, so an attacker cannot search for keys that produce matching 6-digit codes.
 */
class PcPairing(private val pcId: String, private val pcName: String) {
    private val keys: KeyPair = SyncCrypto.generateKeyPair()
    private val nonce = SyncCrypto.randomBytes(32)
    private val publicKey = SyncCrypto.encodePublicKey(keys.public)
    private var revealed = false

    fun start(): SyncMessage.PairStart = SyncMessage.PairStart(pcId, pcName, commitment(pcId, publicKey, nonce))

    fun onAccept(accept: SyncMessage.PairAccept): Pair<SyncMessage.PairReveal, PairingResult> {
        check(!revealed) { "Pairing attempt already revealed" }
        revealed = true
        if (accept.phoneNonce.size != 32 || accept.phoneId == pcId) throw ProtocolFormatException("Invalid pairing response")
        val shared = SyncCrypto.agree(keys.private, SyncCrypto.decodePublicKey(accept.phonePublicKey))
        val result = derive(shared, pcId, accept.phoneId, publicKey, accept.phonePublicKey, nonce, accept.phoneNonce,
            accept.phoneId, accept.phoneName)
        return SyncMessage.PairReveal(publicKey, nonce) to result
    }
}

class PhonePairing(private val phoneId: String, private val phoneName: String, private val start: SyncMessage.PairStart) {
    private val keys: KeyPair = SyncCrypto.generateKeyPair()
    private val nonce = SyncCrypto.randomBytes(32)
    private val publicKey = SyncCrypto.encodePublicKey(keys.public)
    private var finished = false

    init { if (start.commitment.size != 32 || start.pcId == phoneId) throw ProtocolFormatException("Invalid pairing start") }

    fun accept(): SyncMessage.PairAccept = SyncMessage.PairAccept(phoneId, phoneName, publicKey, nonce)

    fun onReveal(reveal: SyncMessage.PairReveal): PairingResult {
        check(!finished) { "Pairing attempt already finished" }
        finished = true
        if (!SyncCrypto.constantTimeEquals(commitment(start.pcId, reveal.pcPublicKey, reveal.pcNonce), start.commitment))
            throw ProtocolFormatException("PC key does not match its commitment")
        val shared = SyncCrypto.agree(keys.private, SyncCrypto.decodePublicKey(reveal.pcPublicKey))
        return derive(shared, start.pcId, phoneId, reveal.pcPublicKey, publicKey, reveal.pcNonce, nonce, start.pcId, start.pcName)
    }
}
