package everyarticle.desktop

import everyarticle.desktop.sync.ClientView
import everyarticle.desktop.sync.PcSyncClient
import everyarticle.desktop.sync.SyncTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the single sync worker. Every client call (network-like mailbox IO, DPAPI, journal writes) runs there,
 * so the Compose thread never blocks. The UI observes [view], [busy] and [error] only.
 */
class AppController(private val clientFactory: ((ClientView) -> Unit) -> PcSyncClient, private val onClose: () -> Unit = {}) {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "everyarticle-sync").apply { isDaemon = true } }
    private val mutableView = MutableStateFlow(ClientView())
    private val mutableBusy = MutableStateFlow<String?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    val view: StateFlow<ClientView> = mutableView
    val busy: StateFlow<String?> = mutableBusy
    val error: StateFlow<String?> = mutableError

    private lateinit var client: PcSyncClient
    private var pendingDraft: ScheduledFuture<*>? = null

    init {
        worker.execute {
            client = clientFactory { mutableView.value = it }
            mutableView.value = client.view
            guarded { client.initialize() }
        }
        worker.scheduleWithFixedDelay({
            guarded {
                if (client.view.pairingInProgress) client.pollPairing() else client.tick()
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun guarded(block: () -> Unit) {
        try { block() } catch (t: Throwable) { mutableError.value = describe(t) }
    }

    fun run(label: String, action: PcSyncClient.() -> Unit) {
        worker.execute {
            mutableBusy.value = label; mutableError.value = null
            try { client.action() } catch (t: Throwable) { mutableError.value = describe(t) } finally { mutableBusy.value = null }
        }
    }

    fun startPairing() = run("ペアリング開始") { startPairing() }
    fun cancelPairing() = run("中止") { cancelPairing() }

    /** Debounced autosave: the newest text is encrypted to the journal ~0.7 s after typing stops. */
    fun draftChanged(articleId: Long, title: String?, body: String) {
        pendingDraft?.cancel(false)
        pendingDraft = worker.schedule({
            guarded { client.saveDraft(articleId, title, body) }
        }, 700, TimeUnit.MILLISECONDS)
    }

    /** Flush any scheduled autosave before submitting so the submitted text is exactly what is on screen. */
    fun submit(articleId: Long, title: String?, body: String) {
        pendingDraft?.cancel(false)
        run("提出中") { saveDraft(articleId, title, body); submit() }
    }

    fun close() { worker.execute { guarded { client.release() } }; worker.shutdown(); worker.awaitTermination(5, TimeUnit.SECONDS); onClose() }

    private fun describe(t: Throwable): String = when (t) {
        is SyncTimeoutException -> "スマホから応答がありません。アプリが前面にあるか確認し、再接続してください（再送はしていません）"
        is IllegalStateException -> t.message ?: "操作できない状態です"
        else -> "${t::class.simpleName}: ${t.message ?: ""}"
    }
}
