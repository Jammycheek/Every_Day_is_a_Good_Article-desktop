package everyarticle.desktop.sync

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** What the editor currently shows. Handed from the UI thread to the sync worker. */
data class EditorText(val articleId: Long, val title: String?, val body: String)

/** OPEN → CLOSING → READY → EXITING, or → BLOCKED_BY_UNSAVED (back to OPEN on cancel). */
enum class CloseState { OPEN, CLOSING, BLOCKED_BY_UNSAVED, READY, EXITING }

/**
 * Owns the single sync worker. Every client call (mailbox IO, DPAPI, journal writes) runs there, so the UI thread
 * never blocks. The newest editor text is held in one slot and every worker task flushes it before doing anything
 * else, so ending the session, switching articles, connecting or closing can never overtake the debounced autosave.
 * Text that cannot be saved any more stays visible as [ClientView.unsaved] instead of being dropped.
 *
 * Submit and close run concurrently with the editor, so both freeze input the moment they are requested
 * ([onInputFrozen], called synchronously on the caller's thread) and only finish after every input accepted
 * until then has been saved or set aside. Input that still arrives (an event already in flight) is never dropped:
 * it is kept as unsaved input and, for close, turns READY back into BLOCKED_BY_UNSAVED.
 */
class SyncWorker(
    clientFactory: ((ClientView) -> Unit) -> PcSyncClient,
    private val onView: (ClientView) -> Unit,
    private val onBusy: (String?) -> Unit = {},
    private val onError: (String?) -> Unit = {},
    private val onCloseState: (CloseState) -> Unit = {},
    private val onInputFrozen: (Boolean) -> Unit = {},
    private val autosaveDelayMillis: Long = 700,
    tickPeriodMillis: Long = 1_000
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "everyarticle-sync").apply { isDaemon = true } }
    private val latest = AtomicReference<EditorText?>(null)
    @Volatile private var scheduledSave: ScheduledFuture<*>? = null
    private lateinit var client: PcSyncClient

    /** Guards [closeState], [submitting] and the hand-off between [latest] and the close decision. */
    private val lock = Any()
    private var closeState = CloseState.OPEN
    private var submitting = false

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

    private fun setCloseState(state: CloseState) { closeState = state; onCloseState(state) }
    private fun publishFrozen() = onInputFrozen(submitting || closeState != CloseState.OPEN)

    fun run(label: String, action: PcSyncClient.() -> Unit) {
        worker.execute {
            onBusy(label); onError(null)
            try { flush(); client.action() } catch (t: Throwable) { onError(describe(t)) } finally { onBusy(null) }
        }
    }

    /**
     * Debounced autosave: the newest text is encrypted to the journal ~0.7 s after typing stops (or earlier, see
     * class doc). Never dropped, even while frozen; text arriving after close was decided reopens the decision.
     */
    fun editorChanged(text: EditorText) {
        synchronized(lock) {
            latest.set(text)
            if (closeState == CloseState.READY) { setCloseState(CloseState.BLOCKED_BY_UNSAVED); publishFrozen() }
        }
        scheduledSave?.cancel(false)
        scheduledSave = worker.schedule({ flush() }, autosaveDelayMillis, TimeUnit.MILLISECONDS)
    }

    /** Before the editor shows another article: queue the current text's save ahead of anything typed next. */
    fun flushNow() {
        scheduledSave?.cancel(false)
        val text = latest.getAndSet(null) ?: return
        worker.execute { guarded { client.saveDraft(text.articleId, text.title, text.body) } }
    }

    /**
     * Submit exactly the on-screen [text]; if it cannot be saved, nothing is sent. Input is frozen from the click
     * until the reply is handled. Anything typed after the click is newer than the submission, so it is never
     * written over it nor overwritten by it: it is saved afterwards if still possible, otherwise kept as unsaved.
     */
    fun submit(text: EditorText) {
        scheduledSave?.cancel(false)
        synchronized(lock) { submitting = true; latest.set(null); publishFrozen() }
        worker.execute {
            onBusy("提出中"); onError(null)
            try {
                client.saveDraft(text.articleId, text.title, text.body)
                client.submit()
            } catch (t: Throwable) { onError(describe(t)) }
            finally {
                flush() // input that arrived after the click
                synchronized(lock) { submitting = false; publishFrozen() }
                onBusy(null)
            }
        }
    }

    /**
     * Window close: freeze input, save the newest text, release the phone, and close only when no accepted input is
     * left unsaved. Otherwise [CloseState.BLOCKED_BY_UNSAVED] lets the UI offer export / discard / cancel.
     */
    fun requestClose() {
        scheduledSave?.cancel(false)
        synchronized(lock) { setCloseState(CloseState.CLOSING); publishFrozen() }
        worker.execute {
            flush()
            guarded { client.release() }
            settleClose()
        }
    }

    /** Decide READY only when the input slot is empty at the moment of the decision (under [lock]). */
    private fun settleClose() {
        while (true) {
            flush()
            synchronized(lock) {
                if (closeState != CloseState.CLOSING) return
                if (latest.get() == null) {
                    setCloseState(if (client.view.unsaved == null) CloseState.READY else CloseState.BLOCKED_BY_UNSAVED)
                    return
                }
            }
        }
    }

    /**
     * After the user exported or explicitly chose to drop the unsaved text [shown] in the dialog. Only that text is
     * discarded; anything newer that arrived meanwhile becomes the unsaved input and blocks the close again.
     */
    fun closeDiscardingUnsaved(shown: UnsavedInput?) {
        synchronized(lock) { setCloseState(CloseState.CLOSING); publishFrozen() }
        worker.execute {
            flush()
            guarded { client.discardUnsaved(shown) }
            settleClose()
        }
    }

    fun cancelClose() = synchronized(lock) {
        if (closeState == CloseState.BLOCKED_BY_UNSAVED || closeState == CloseState.CLOSING) {
            setCloseState(CloseState.OPEN); publishFrozen()
        }
    }

    /**
     * Call on the UI thread right before exiting, in the same task as the exit itself: true only if close is still
     * READY (no input arrived since the decision). Afterwards nothing can reopen it.
     */
    fun confirmExit(): Boolean = synchronized(lock) {
        if (closeState != CloseState.READY) return false
        setCloseState(CloseState.EXITING)
        true
    }

    /** Stop the worker after exit; call off the worker thread. */
    fun shutdown() { worker.shutdown(); worker.awaitTermination(5, TimeUnit.SECONDS) }

    /** Tests: wait until every task queued so far has run. */
    fun awaitIdle() { worker.submit {}.get(30, TimeUnit.SECONDS) }

    private fun describe(t: Throwable): String = when (t) {
        is SyncTimeoutException -> "スマホから応答がありません。アプリが前面にあるか確認し、再接続してください（再送はしていません）"
        is IllegalStateException -> t.message ?: "操作できない状態です"
        else -> "${t::class.simpleName}: ${t.message ?: ""}"
    }
}
