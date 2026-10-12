package everyarticle.sync.protocol

interface PeerStore {
    fun peer(peerId: String): PairedPeer?
    fun peers(): List<PairedPeer>
    fun save(peer: PairedPeer)
    fun remove(peerId: String)
}

/** Durable, bounded record of accepted message IDs per peer; a replayed request file is dropped. */
interface ReplayGuard { fun firstSeen(peerId: String, messageId: String): Boolean }

sealed class PhonePairingState {
    data object Idle : PhonePairingState()
    data object Waiting : PhonePairingState()
    data class CompareCode(val pcName: String, val sas: String, val userConfirmed: Boolean) : PhonePairingState()
    data class Paired(val pcName: String) : PhonePairingState()
    data class Failed(val reason: String) : PhonePairingState()
}

/**
 * Phone side of the mailbox: pairing (only while the user has the pairing screen open) and
 * authenticated request dispatch to [PhoneAuthority]. Call [poll] from one worker at a time.
 */
class PhoneEndpoint(
    private val phoneId: String,
    private val phoneName: String,
    private val mailbox: Mailbox,
    private val peers: PeerStore,
    private val replay: ReplayGuard,
    private val authority: PhoneAuthority,
    private val clock: AuthorityClock,
    private val onPairing: (PhonePairingState) -> Unit = {}
) {
    private var pairing: PhonePairing? = null
    private var result: PairingResult? = null
    private var pcConfirm: SyncMessage.PairConfirm? = null
    private var userConfirmed = false
    private var pairingState: PhonePairingState = PhonePairingState.Idle
        set(value) { field = value; onPairing(value) }
    private val badSince = HashMap<String, Long>()

    fun beginPairing() { resetPairing(); pairingState = PhonePairingState.Waiting }
    fun cancelPairing() { resetPairing(); pairingState = PhonePairingState.Idle }
    private fun resetPairing() { pairing = null; result?.pairingKey?.fill(0); result = null; pcConfirm = null; userConfirmed = false }

    /** The user saw the same 6 digits on both screens. */
    fun confirmPairingCode() {
        val r = result ?: return
        userConfirmed = true
        mailbox.send(MailboxNames.next(clock.wallMillis()),
            Envelope.pairing(phoneId, r.peerId, SyncMessage.PairConfirm(phoneId, r.confirmationMac(Role.PHONE))))
        pairingState = PhonePairingState.CompareCode(r.peerName, r.sas, true)
        finishPairingIfReady()
    }

    private fun finishPairingIfReady() {
        val r = result ?: return
        val confirm = pcConfirm ?: return
        if (!userConfirmed) return
        try {
            peers.save(r.verifyPeerConfirmation(Role.PC, confirm))
            pairingState = PhonePairingState.Paired(r.peerName)
        } catch (failure: ProtocolFormatException) {
            pairingState = PhonePairingState.Failed("confirmation")
        }
        pairing = null; result = null; pcConfirm = null; userConfirmed = false
    }

    /** @return number of files consumed. */
    fun poll(): Int {
        var consumed = 0
        for (file in mailbox.receive()) {
            val opened = try {
                Envelope.open(file.bytes, phoneId, Role.PC) { peers.peer(it) }
            } catch (_: ProtocolFormatException) {
                // Possibly still being written over MTP: give it time, then discard.
                val since = badSince.getOrPut(file.name) { clock.elapsedMillis() }
                if (clock.elapsedMillis() - since > 30_000) { mailbox.delete(file.name); badSince.remove(file.name) }
                continue
            }
            badSince.remove(file.name)
            mailbox.delete(file.name)
            consumed++
            if (!opened.authenticated) { handlePairing(opened); continue }
            if (!replay.firstSeen(opened.senderId, opened.messageId)) continue
            val peer = peers.peer(opened.senderId) ?: continue
            val reply = authority.handle(opened.senderId, opened.message) ?: continue
            mailbox.send(MailboxNames.next(clock.wallMillis()), Envelope.seal(phoneId, Role.PHONE, peer, reply))
        }
        return consumed
    }

    private fun handlePairing(opened: OpenedMessage) {
        if (pairingState == PhonePairingState.Idle) return // pairing only while the user opened the screen
        try {
            when (val m = opened.message) {
                is SyncMessage.PairStart -> {
                    if (m.pcId != opened.senderId) return
                    resetPairing()
                    val p = PhonePairing(phoneId, phoneName, m).also { pairing = it }
                    mailbox.send(MailboxNames.next(clock.wallMillis()), Envelope.pairing(phoneId, m.pcId, p.accept()))
                    pairingState = PhonePairingState.Waiting
                }
                is SyncMessage.PairReveal -> {
                    val r = (pairing ?: return).onReveal(m)
                    if (r.peerId != opened.senderId) throw ProtocolFormatException("Sender mismatch")
                    result = r
                    pairingState = PhonePairingState.CompareCode(r.peerName, r.sas, false)
                }
                is SyncMessage.PairConfirm -> {
                    if (result?.peerId != opened.senderId) return
                    pcConfirm = m
                    finishPairingIfReady()
                }
                else -> Unit
            }
        } catch (failure: ProtocolFormatException) {
            resetPairing(); pairingState = PhonePairingState.Failed("protocol")
        }
    }
}
