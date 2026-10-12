package everyarticle.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import everyarticle.desktop.sync.CloseState
import everyarticle.desktop.sync.InMemoryPcPeerStore
import everyarticle.desktop.sync.PcSyncClient
import everyarticle.desktop.sync.ProtectedPcPeerStore
import everyarticle.desktop.sync.VolatileJournal
import everyarticle.desktop.sync.WindowsTransport
import everyarticle.desktop.sync.WpdMailbox
import everyarticle.sync.protocol.ArticleText
import everyarticle.sync.protocol.AuthorityClock
import everyarticle.sync.protocol.DirectoryMailbox
import everyarticle.sync.protocol.InMemoryAuthorityStore
import everyarticle.sync.protocol.InMemoryPeerStore
import everyarticle.sync.protocol.InMemoryReplayGuard
import everyarticle.sync.protocol.PhoneAuthority
import everyarticle.sync.protocol.PhoneEndpoint
import everyarticle.sync.protocol.PhonePairingState
import everyarticle.sync.protocol.WriteGate
import poc.DpapiProtector
import poc.RecoveryController
import poc.WindowsDeltaJournal
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Windows: real USB/MTP mailbox + DPAPI journal under %LOCALAPPDATA% (never the E: drive).
 * -Deveryarticle.simulatedPhone=<dir>: synthetic in-process phone over a plain folder, for UI work on any OS.
 */
fun main() {
    val simulated = System.getProperty("everyarticle.simulatedPhone")
    val controller = if (simulated != null) simulatedController(File(simulated)) else windowsController()
    application(exitProcessOnExit = false) {
        // Closing first saves the newest editor text and releases the phone; unsaved text blocks the exit (see App).
        val closeState by controller.closeState.collectAsState()
        LaunchedEffect(closeState) {
            // Confirm and exit in one UI-thread step, so no keystroke can slip in between.
            if (closeState == CloseState.READY && controller.confirmExit()) exitApplication()
        }
        Window(onCloseRequest = controller::requestClose, title = "Every Article Desktop",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp))) {
            App(controller)
        }
    }
    controller.shutdown() // after the window is gone: the worker has nothing left to save
}

private fun windowsController(): AppController {
    check(System.getProperty("os.name").startsWith("Windows")) { "Use -Deveryarticle.simulatedPhone=<dir> outside Windows" }
    val helper = Path.of(System.getenv("EVERYARTICLE_HELPER") ?: "wpd-probe.exe")
    val base = Path.of(System.getenv("LOCALAPPDATA") ?: error("LOCALAPPDATA missing"), "EveryArticleDesktop")
    val protector = DpapiProtector(helper)
    val journal = WindowsDeltaJournal(base.resolve("journal"), protector) // exclusive owner for the app lifetime
    val peers = ProtectedPcPeerStore(base.resolve("pairing"), protector)
    val transport = WindowsTransport(helper)
    val mailbox = WpdMailbox(helper) { transport.selector }
    val pcName = System.getenv("COMPUTERNAME") ?: "Windows PC"
    return AppController({ onChange ->
        PcSyncClient(pcName, mailbox, peers, RecoveryController(journal), transportReady = { transport.sample() }, onChange = onChange)
    }, onClose = { journal.close() })
}

/** Synthetic articles only. The simulated phone auto-confirms pairing codes; real phones never do. */
private fun simulatedController(root: File): AppController {
    root.mkdirs()
    val clock = object : AuthorityClock {
        override fun elapsedMillis() = System.nanoTime() / 1_000_000
        override fun wallMillis() = System.currentTimeMillis()
    }
    val store = InMemoryAuthorityStore(listOf(
        ArticleText(1, "合成TOPIC", "2026-10-12", "合成記事1", "これは合成データです。", System.currentTimeMillis()),
        ArticleText(2, "合成TOPIC", "2026-10-13", null, "見出しなしの合成記事", System.currentTimeMillis())))
    val gate = WriteGate(clock)
    val authority = PhoneAuthority("simulated-phone", store, gate, clock).apply { startProcess() }
    lateinit var endpoint: PhoneEndpoint
    endpoint = PhoneEndpoint("simulated-phone", "模擬スマホ", DirectoryMailbox.forPhone(root), InMemoryPeerStore(),
        InMemoryReplayGuard(), authority, clock) { state -> if (state is PhonePairingState.CompareCode && !state.userConfirmed) endpoint.confirmPairingCode() }
    endpoint.beginPairing()
    val phoneLoop = Executors.newSingleThreadScheduledExecutor { Thread(it, "simulated-phone").apply { isDaemon = true } }
    phoneLoop.scheduleWithFixedDelay({ runCatching { endpoint.poll(); gate.tick() } }, 0, 300, TimeUnit.MILLISECONDS)
    val peers = InMemoryPcPeerStore()
    val journal = VolatileJournal()
    return AppController({ onChange ->
        PcSyncClient("模擬PC", DirectoryMailbox.forPc(root), peers, RecoveryController(journal), transportReady = { true }, onChange = onChange)
    }, onClose = { phoneLoop.shutdownNow() })
}
