package everyarticle.desktop

import everyarticle.desktop.sync.ClientView
import everyarticle.desktop.sync.CloseState
import everyarticle.desktop.sync.EditorText
import everyarticle.desktop.sync.PcSyncClient
import everyarticle.desktop.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Compose-facing wrapper of [SyncWorker]: the UI observes [view], [busy], [error] and [closeState] only.
 * Ordering rules (flush the newest editor text before release, switch, connect and close) live in [SyncWorker].
 */
class AppController(clientFactory: ((ClientView) -> Unit) -> PcSyncClient, private val onClose: () -> Unit = {}) {
    private val mutableView = MutableStateFlow(ClientView())
    private val mutableBusy = MutableStateFlow<String?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableCloseState = MutableStateFlow(CloseState.OPEN)
    val view: StateFlow<ClientView> = mutableView
    val busy: StateFlow<String?> = mutableBusy
    val error: StateFlow<String?> = mutableError
    val closeState: StateFlow<CloseState> = mutableCloseState

    private val worker = SyncWorker(clientFactory, onView = { mutableView.value = it }, onBusy = { mutableBusy.value = it },
        onError = { mutableError.value = it }, onCloseState = { mutableCloseState.value = it })

    fun run(label: String, action: PcSyncClient.() -> Unit) = worker.run(label, action)

    fun startPairing() = run("ペアリング開始") { startPairing() }
    fun cancelPairing() = run("中止") { cancelPairing() }

    fun draftChanged(articleId: Long, title: String?, body: String) = worker.editorChanged(EditorText(articleId, title, body))
    fun flushEditor() = worker.flushNow()
    fun submit(articleId: Long, title: String?, body: String) = worker.submit(EditorText(articleId, title, body))

    fun requestClose() = worker.requestClose()
    fun closeDiscardingUnsaved() = worker.closeDiscardingUnsaved()
    fun cancelClose() = worker.cancelClose()

    /** Only after [closeState] is READY. */
    fun shutdown() { worker.shutdown(); onClose() }
}
