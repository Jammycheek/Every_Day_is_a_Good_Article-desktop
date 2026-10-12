package everyarticle.desktop.sync

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** What the editor currently shows. Handed from the UI thread to the sync worker. */
data class EditorText(val articleId: Long, val title: String?, val body: String)

enum class CloseState { OPEN, CLOSING, BLOCKED_BY_UNSAVED, READY }

/**
 * Owns the single sync worker. Every client call (mailbox IO, DPAPI, journal writes) runs there, so the UI thread
 * never blocks. The newest editor text is held in one slot and **every** worker task flushes it before doing anything
 * else, so ending the session, switching articles, connecting or closing can never overtake the debounced autosave.
 * Text that cannot be saved any more stays visible as [ClientView.unsaved] instead of being dropped.
 */
class SyncWorker(
    clientFactory: ((ClientView) -> Unit) -> PcSyncClient,
    private val onView: (ClientView) -> Unit,
    private val onBusy: (String?) -> Unit = {},
    private val onError: (String?) -> Unit = {},
    private val onCloseState: (CloseState) -> Unit = {},
    private val autosaveDelayMillis: Long = 700,
    tickPeriodMillis: Long = 1_000
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "everyarticle-sync").apply { isDaemon = true } }
    private val latest = AtomicReference<EditorText?>(null)
    @Volatile private var scheduledSave: ScheduledFuture<*>? = null
    private lateinit var client: PcSyncClient

    init {
        worker.execute {
            client = clientFactory(onView)
            onView(client.view)
            guarded { client.initialize() }
        }
        if (tickPeriodMillis > 0) worker.scheduleWithFixedDelay({
            flush()
            guarded { if (client.view.pairingInProgress) client.pollPairing() else client.tick() }
        }, tickPeriodMillis, tickPeriodMillis, TimeUnit.MILLISECONDS)
    }

    private fun guarded(block: () -> Unit) {
        try { block() } catch (t: Throwable) { onError(describe(t)) }
    }

    /** Save the newest editor text now, if any. A failure is recorded by the client as [ClientView.unsaved]. */
    private fun flush() {
        val text = latest.getAndSet(null) ?: return
        guarded { client.saveDraft(text.articleId, text.title, text.body) }
    }

    fun run(label: String, action: PcSyncClient.() -> Unit) {
        worker.execute {
            onBusy(label); onError(null)
            try { flush(); client.action() } catch (t: Throwable) { onError(describe(t)) } finally { onBusy(null) }
        }
    }

    /** Debounced autosave: the newest text is encrypted to the journal ~0.7 s after typing stops (or earlier, see class doc). */
    fun editorChanged(text: EditorText) {
        latest.set(text)
        scheduledSave?.cancel(false)
        scheduledSave = worker.schedule({ flush() }, autosaveDelayMillis, TimeUnit.MILLISECONDS)
    }

    /** Before the editor shows another article: queue the current text's save ahead of anything typed next. */
    fun flushNow() {
        scheduledSave?.cancel(false)
        val text = latest.getAndSet(null) ?: return
        worker.execute { guarded { client.saveDraft(text.articleId, text.title, text.body) } }
    }

    /** The submitted text is exactly what is on screen; if it cannot be saved, nothing is sent. */
    fun submit(text: EditorText) {
        scheduledSave?.cancel(false)
        latest.set(null)
        run("提出中") { saveDraft(text.articleId, text.title, text.body); submit() } // a failed save aborts the send
    }

    /**
     * Window close: save the newest text, release the phone, and close only if nothing unsaved remains.
     * Otherwise [CloseState.BLOCKED_BY_UNSAVED] lets the UI offer export / discard / cancel.
     */
    fun requestClose() {
        scheduledSave?.cancel(false)
        onCloseState(CloseState.CLOSING)
        worker.execute {
            flush()
            guarded { client.release() }
            onCloseState(if (client.view.unsaved == null) CloseState.READY else CloseState.BLOCKED_BY_UNSAVED)
        }
    }

    /** After the user exported or explicitly chose to drop the unsaved text. */
    fun closeDiscardingUnsaved() {
        worker.execute { guarded { client.discardUnsaved() }; onCloseState(CloseState.READY) }
    }

    fun cancelClose() = onCloseState(CloseState.OPEN)

    /** Stop the worker after [CloseState.READY]; call off the worker thread. */
    fun shutdown() { worker.shutdown(); worker.awaitTermination(5, TimeUnit.SECONDS) }

    /** Tests: wait until every task queued so far has run. */
    fun awaitIdle() { worker.submit {}.get(30, TimeUnit.SECONDS) }

    private fun describe(t: Throwable): String = when (t) {
        is SyncTimeoutException -> "スマホから応答がありません。アプリが前面にあるか確認し、再接続してください（再送はしていません）"
        is IllegalStateException -> t.message ?: "操作できない状態です"
        else -> "${t::class.simpleName}: ${t.message ?: ""}"
    }
}
