package everyarticle.desktop.sync

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
 * until then has been saved or set aside:
 * - Input that arrives while a submit is pending is held in a separate slot ([afterSubmit]) that no other task
 *   flushes, so it can neither be overwritten by the click-time text nor be saved ahead of it. It moves to the
 *   normal slot only after the submit has finished.
 * - Text taken from the slot but not yet saved is counted ([inFlight]). Close becomes READY only when the slot,
 *   the submit hold and the in-flight count are all empty and no unsaved input exists; the last finishing save
 *   re-runs that decision. Input arriving after READY turns it back into BLOCKED_BY_UNSAVED.
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
    @Volatile private var scheduledSave: ScheduledFuture<*>? = null
    private lateinit var client: PcSyncClient
    /** Latest view, readable from the UI thread for the final exit check. */
    @Volatile private var lastView = ClientView()

    /** Guards every field below; all hand-offs of editor text happen under it. */
    private val lock = Any()
    private var latest: EditorText? = null
    private var afterSubmit: EditorText? = null
    private var inFlight = 0
    private var closeState = CloseState.OPEN
    private var submitting = false
    /** The close task has released the phone; until then no other task may declare the close READY. */
    private var closeReleased = false

    init {
        worker.execute {
            client = clientFactory { lastView = it; onView(it) }
            lastView = client.view; onView(client.view)
            guarded { client.initialize() }
        }
        if (tickPeriodMillis > 0) worker.scheduleWithFixedDelay({
            flush()
            guarded { if (client.view.pairingInProgress) client.pollPairing() else client.tick() }
            settleIfClosing()
        }, tickPeriodMillis, tickPeriodMillis, TimeUnit.MILLISECONDS)
    }

    private fun guarded(block: () -> Unit) {
        try { block() } catch (t: Throwable) { onError(describe(t)) }
    }

    /** Take the newest text out of the slot; it counts as in flight until [save] finishes. */
    private fun take(): EditorText? = synchronized(lock) { latest?.also { latest = null; inFlight++ } }

    /** A failure is recorded by the client as [ClientView.unsaved]. */
    private fun save(text: EditorText) {
        try { guarded { client.saveDraft(text.articleId, text.title, text.body) } }
        finally { synchronized(lock) { inFlight-- } }
    }

    private fun flush() { take()?.let(::save) }

    private fun setCloseState(state: CloseState) { closeState = state; onCloseState(state) }
    private fun publishFrozen() = onInputFrozen(submitting || closeState != CloseState.OPEN)

    fun run(label: String, action: PcSyncClient.() -> Unit) {
        worker.execute {
            onBusy(label); onError(null)
            try { flush(); client.action() } catch (t: Throwable) { onError(describe(t)) } finally { onBusy(null) }
            settleIfClosing()
        }
    }

    /**
     * Debounced autosave: the newest text is encrypted to the journal ~0.7 s after typing stops (or earlier, see
     * class doc). Never dropped, even while frozen; text arriving after close was decided reopens the decision.
     */
    fun editorChanged(text: EditorText) {
        synchronized(lock) {
            if (submitting) afterSubmit = text else latest = text
            if (closeState == CloseState.READY) { setCloseState(CloseState.BLOCKED_BY_UNSAVED); publishFrozen() }
        }
        scheduledSave?.cancel(false)
        scheduledSave = worker.schedule({ flush(); settleIfClosing() }, autosaveDelayMillis, TimeUnit.MILLISECONDS)
    }

    /** Before the editor shows another article: queue the current text's save ahead of anything typed next. */
    fun flushNow() {
        scheduledSave?.cancel(false)
        val text = take() ?: return
        worker.execute { save(text); settleIfClosing() }
    }

    /**
     * Submit exactly the on-screen [text]; if it cannot be saved, nothing is sent. Input is frozen from the click
     * until the reply is handled. Anything typed after the click is held aside until then, so no task that was
     * already queued can save it ahead of the submission, and the submission cannot overwrite it. Afterwards it is
     * saved if still possible, otherwise kept as unsaved input.
     */
    fun submit(text: EditorText) {
        scheduledSave?.cancel(false)
        synchronized(lock) { submitting = true; latest = null; afterSubmit = null; publishFrozen() }
        worker.execute {
            onBusy("提出中"); onError(null)
            try {
                client.saveDraft(text.articleId, text.title, text.body)
                client.submit()
            } catch (t: Throwable) { onError(describe(t)) }
            finally {
                synchronized(lock) {
                    afterSubmit?.let { latest = it }
                    afterSubmit = null; submitting = false; publishFrozen()
                }
                flush() // input that arrived after the click
                onBusy(null)
                settleIfClosing()
            }
        }
    }

    /**
     * Window close: freeze input, save the newest text, release the phone, and close only when no accepted input is
     * left unsaved. Otherwise [CloseState.BLOCKED_BY_UNSAVED] lets the UI offer export / discard / cancel.
     */
    fun requestClose() {
        scheduledSave?.cancel(false)
        synchronized(lock) { closeReleased = false; setCloseState(CloseState.CLOSING); publishFrozen() }
        worker.execute {
            flush()
            guarded { client.release() }
            synchronized(lock) { closeReleased = true }
            settleIfClosing()
        }
    }

    /**
     * Decide while CLOSING (worker thread). READY needs an empty slot, nothing in flight, no pending submit and no
     * unsaved input. If a save or submit is still queued, the decision is left to it: it calls this again when done.
     */
    private fun settleIfClosing() {
        while (true) {
            flush()
            synchronized(lock) {
                if (closeState != CloseState.CLOSING || !closeReleased) return
                if (latest == null) {
                    if (inFlight > 0 || submitting || afterSubmit != null) return
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
            settleIfClosing()
        }
    }

    fun cancelClose() = synchronized(lock) {
        if (closeState == CloseState.BLOCKED_BY_UNSAVED || closeState == CloseState.CLOSING) {
            setCloseState(CloseState.OPEN); publishFrozen()
        }
    }

    /**
     * Call on the UI thread right before exiting, in the same task as the exit itself: true only if close is still
     * READY and, re-checked here, no input is waiting, in flight, held for a submit or shown as unsaved.
     * Otherwise the decision goes back to the worker. Afterwards nothing can reopen it.
     */
    fun confirmExit(): Boolean = synchronized(lock) {
        if (closeState != CloseState.READY) return false
        if (latest != null || inFlight > 0 || submitting || afterSubmit != null || lastView.unsaved != null) {
            setCloseState(CloseState.CLOSING)
            worker.execute { settleIfClosing() }
            return false
        }
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
