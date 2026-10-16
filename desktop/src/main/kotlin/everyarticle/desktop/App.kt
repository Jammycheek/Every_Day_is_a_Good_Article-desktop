package everyarticle.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import everyarticle.desktop.sync.ClientView
import everyarticle.desktop.sync.ConnectionStep
import everyarticle.desktop.sync.CloseState
import everyarticle.desktop.sync.PcSyncClient
import everyarticle.desktop.sync.DecisionKind
import everyarticle.desktop.sync.PairingStoreProblem
import everyarticle.sync.protocol.ArticleSnapshot
import everyarticle.sync.protocol.RefusalReason
import poc.PendingDiscardPreview
import poc.PendingQuarantinePreview
import poc.RecoveryFailureReason
import java.io.File
import javax.swing.JFileChooser

@Composable
fun App(controller: AppController) {
    val view by controller.view.collectAsState()
    val busy by controller.busy.collectAsState()
    val error by controller.error.collectAsState()
    val connecting by controller.connecting.collectAsState()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusBar(view, busy)
                if (connecting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(when (view.connectionStep) {
                        ConnectionStep.CHECKING_USB -> "1/5 USB/MTPの接続を確認しています"
                        ConnectionStep.CHECKING_PHONE -> "2/5 スマホの応答を待っています"
                        ConnectionStep.CHECKING_PENDING -> "3/5 未反映の編集を照合しています"
                        ConnectionStep.REQUESTING_PERMISSION -> "4/5 編集許可を確認しています"
                        ConnectionStep.IMPORTING_ARTICLES -> "5/5 記事を取り込んでいます"
                        null -> "接続の準備をしています"
                    })
                }
                view.transportProblem?.let { Text(it) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                view.message?.let { Text(it) }
                UnsavedNotice(view, controller)
                when {
                    view.pairingStoreProblem != null -> PairingStorePanel(view, controller)
                    view.paired == null || view.pairingInProgress -> PairingPanel(view, controller)
                    view.decision != null -> DecisionPanel(view, controller)
                    else -> SessionPanel(view, controller)
                }
            }
        }
        val closeState by controller.closeState.collectAsState()
        if (closeState == CloseState.BLOCKED_BY_UNSAVED) CloseBlockedDialog(controller)
    }
}

/** Latest editor text that could not be encrypted to the journal: shown outside the editor until exported or discarded. */
@Composable
private fun UnsavedNotice(view: ClientView, controller: AppController) {
    val u = view.unsaved ?: return
    var confirmDiscard by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("保存できなかった最新の入力があります（記事 ${u.articleId}「${u.title ?: "見出しなし"}」）", fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error)
        Text("編集許可が終わったなどの理由で暗号化保存できていません。このPCのメモリにだけあり、アプリを閉じると失われます。" +
            "同じ版のまま再接続できれば編集に戻します。", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportTextButton(controller, "入力をテキストで保存…") { unsavedPlainText() }
            OutlinedButton(onClick = { confirmDiscard = true }) { Text("この入力を破棄…") }
        }
    }
    if (confirmDiscard) ConfirmDialog("保存できなかった入力を破棄しますか？", "破棄した入力は復元できません。保護済みの編集とスマホのデータは変わりません。",
        confirm = "破棄する", onConfirm = { controller.run("破棄") { discardUnsaved(u) }; confirmDiscard = false },
        onDismiss = { confirmDiscard = false })
}

@Composable
private fun CloseBlockedDialog(controller: AppController) {
    AlertDialog(onDismissRequest = controller::cancelClose,
        title = { Text("保存できなかった入力があります") },
        text = { Text("このまま閉じると、暗号化保存できなかった最新の入力が失われます。先にテキストで保存するか、破棄して閉じるかを選んでください。") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportTextButton(controller, "テキストで保存…") { unsavedPlainText() }
                TextButton(onClick = controller::closeDiscardingUnsaved) { Text("破棄して閉じる", color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { Button(onClick = controller::cancelClose) { Text("閉じずに戻る") } })
}

/** Pairing data could not be read at start: retry, or (confirmed) keep it aside and pair again. Edits are untouched. */
@Composable
private fun PairingStorePanel(view: ClientView, controller: AppController) {
    val problem = view.pairingStoreProblem ?: return
    var confirmReset by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ペアリング情報を読み取れません", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(when (problem) {
            PairingStoreProblem.TRANSIENT -> "保護機能の一時的な失敗か、ファイルが使用中の可能性があります。少し待ってから再試行してください。"
            PairingStoreProblem.KEY_STATE -> "Windowsが鍵の状態の問題を報告しました。パスワードを再設定した場合は、以前のパスワードやアカウントの鍵を戻すと読めるようになる可能性があります。"
            PairingStoreProblem.DATA_REJECTED -> "Windowsが保護データを受け付けませんでした。破損しているか、別のアカウント・PCで保護された可能性があります。"
            PairingStoreProblem.INVALID_FORMAT -> "復号はできましたが形式が正しくありません（破損またはアプリの版の違い）。アプリの更新で読める場合は退避は不要です。"
        })
        Text("未反映の編集と履歴は別に保存されており、ここでの操作では変わりません。", fontSize = 12.sp)
        view.pending?.let { PendingNotice(view, controller) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.run("再試行") { openPairingStore() } }) { Text("再試行") }
            OutlinedButton(onClick = { confirmReset = true }) { Text("退避して再ペアリング…") }
        }
    }
    if (confirmReset) ConfirmDialog("ペアリング情報を退避して、ペアリングし直しますか？",
        "読めないファイルは削除せず、別名で同じフォルダに残します。このPCは新しいPCとしてスマホとペアリングし直します" +
            "（スマホ側に残る古いPCの登録は、スマホの「PCと連携」から削除できます）。未反映の編集と履歴は変わりません。",
        confirm = "退避する", onConfirm = { controller.run("退避") { resetPairingStore() }; confirmReset = false },
        onDismiss = { confirmReset = false })
}

@Composable
private fun StatusBar(view: ClientView, busy: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("USB/MTP: " + if (view.transportReady) "接続" else "接続未確認")
        Text("ペアリング: " + (view.paired?.displayName ?: "未"))
        Text("編集許可: " + (view.leaseRemainingMillis?.let { "残り ${it / 60_000}分${(it / 1000) % 60}秒" } ?: "なし"))
        view.pending?.let { Text(if (it.submitted) "未確定の提出あり" else "未提出の編集あり（暗号化保存済み）") }
        busy?.let { Text("処理中: $it", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun PairingPanel(view: ClientView, controller: AppController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("スマホとペアリング", fontSize = 20.sp)
        Text("1. スマホをUSBで接続し「ファイル転送」を選びます。\n2. スマホのEvery Articleで 設定 → PCと連携 → ペアリング を開きます。\n3. 下のボタンを押します。")
        val code = view.pairingCode
        if (code == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = controller::startPairing) { Text("ペアリング開始") }
                if (view.pairingInProgress) OutlinedButton(onClick = controller::cancelPairing) { Text("中止") }
            }
        } else {
            Text(code.chunked(3).joinToString(" "), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("スマホに同じ6桁が表示されている場合だけ「一致する」を押してください。違う場合は中止してください。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !view.pairingConfirmedLocally, onClick = { controller.run("確認") { confirmPairingCode() } }) { Text("一致する") }
                OutlinedButton(onClick = controller::cancelPairing) { Text("一致しない（中止）") }
            }
        }
    }
}

@Composable
private fun SessionPanel(view: ClientView, controller: AppController) {
    val connecting by controller.connecting.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // A failed background probe must not disable the action that performs a fresh probe.
        // connect() still refuses status/permission requests unless that fresh probe succeeds.
        if (!view.sessionActive) Button(enabled = !connecting, onClick = controller::connect) { Text("接続して取り込む") }
        else OutlinedButton(enabled = !connecting, onClick = { controller.run("終了") { release() } }) { Text("編集を終えてスマホのロックを解除") }
        OutlinedButton(enabled = !connecting, onClick = { controller.run("解除") { forgetPairing() } }) { Text("このPCのペアリングを解除") }
    }
    if (!view.sessionActive) {
        view.pending?.let { PendingNotice(view, controller) }
        return
    }
    var selected by remember(view.articles) { mutableStateOf(view.pending?.articleId ?: view.articles.firstOrNull()?.articleId) }
    // Typing in an article locks the others even before the first autosave lands, like a pending edit does.
    var typedIn by remember(view.articles) { mutableStateOf<Long?>(null) }
    val editingId = view.pending?.articleId ?: typedIn
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.width(300.dp).fillMaxHeight()) {
            items(view.articles, key = { it.articleId }) { a ->
                val locked = editingId != null && editingId != a.articleId
                Column(Modifier.fillMaxWidth().clickable(enabled = !locked) { controller.flushEditor(); selected = a.articleId }.padding(8.dp)) {
                    Text("${a.articleDate}  ${a.title ?: "（見出しなし）"}", fontWeight = if (a.articleId == selected) FontWeight.Bold else FontWeight.Normal)
                    Text(a.topicName, fontSize = 12.sp)
                }
            }
        }
        val article = view.articles.firstOrNull { it.articleId == selected }
        if (article != null) Editor(article, view, controller) { typedIn = article.articleId }
    }
}

@Composable
private fun Editor(article: ArticleSnapshot, view: ClientView, controller: AppController, onTyped: () -> Unit) {
    val pending = view.pending?.takeIf { it.articleId == article.articleId }
    val frozen by controller.inputFrozen.collectAsState() // submit or close in progress: no new input
    var title by remember(article.articleId) { mutableStateOf(pending?.title ?: article.title ?: "") }
    var body by remember(article.articleId) { mutableStateOf(pending?.body ?: article.body) }
    val submitted = pending?.submitted == true
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it; onTyped(); controller.draftChanged(article.articleId, it.ifEmpty { null }, body) },
            label = { Text("見出し") }, enabled = !submitted && !frozen, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it; onTyped(); controller.draftChanged(article.articleId, title.ifEmpty { null }, it) },
            label = { Text("本文") }, enabled = !submitted && !frozen, modifier = Modifier.fillMaxWidth().weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.resubmitAllowed && submitted) Button(onClick = { controller.run("再提出") { resubmit() } }) { Text("未反映を確認済み：再提出") }
            else Button(enabled = !submitted && !frozen, onClick = { controller.submit(article.articleId, title.ifEmpty { null }, body) }) { Text("スマホへ提出") }
            Text("入力は自動で暗号化保存されます（編集終了・記事の切り替え・終了の前にも保存します）。提出後は結果が確定するまで変更できません。", fontSize = 12.sp)
        }
    }
}

@Composable
private fun PendingNotice(view: ClientView, controller: AppController) {
    val p = view.pending ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("PCに未反映の編集が保護されています（記事 ${p.articleId}、${if (p.submitted) "提出済み・結果未確定" else "未提出"}）")
        Text("接続すると、まずスマホに状態を照会します。結果が確定するまで自動では再送しません。", fontSize = 12.sp)
        ExportTextButton(controller, "内容をテキストで保存…") { pendingPlainText() }
    }
}

@Composable
private fun DecisionPanel(view: ClientView, controller: AppController) {
    val connecting by controller.connecting.collectAsState()
    var discardPreview by remember { mutableStateOf<PendingDiscardPreview?>(null) }
    var quarantinePreview by remember { mutableStateOf<PendingQuarantinePreview?>(null) }
    val decision = view.decision ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(decisionTitle(decision, view.decisionReason), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(decisionExplanation(decision, view.decisionReason))
        view.pending?.let { Text("保護中の編集: 記事 ${it.articleId}「${it.title ?: "見出しなし"}」") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !connecting, onClick = controller::connect) { Text("もう一度照会する") }
            if (decision == DecisionKind.UNREADABLE)
                OutlinedButton(onClick = { controller.run("隔離の準備") { quarantinePreview = preparePendingQuarantine() } }) { Text("隔離の準備") }
            else {
                ExportTextButton(controller, "内容をテキストで保存…") { pendingPlainText() }
                OutlinedButton(onClick = { controller.run("破棄の準備") { discardPreview = prepareDiscard() } }) { Text("PC側の編集を破棄…") }
            }
        }
    }
    discardPreview?.let { preview ->
        ConfirmDialog("PC側の未反映の編集を破棄しますか？",
            "破棄した編集は復元できません。スマホのデータは変わらず、スマホで処理中の操作を取り消すこともできません。",
            confirm = "破棄する", onConfirm = { controller.run("破棄") { discard(preview.confirmation) }; discardPreview = null },
            onDismiss = { discardPreview = null })
    }
    quarantinePreview?.let { preview ->
        ConfirmDialog("読めない編集を隔離しますか？",
            guidanceJa(preview.reason) + "\n\n隔離しても暗号文は残りますが、復元できる保証はありません。スマホでの処理を取り消すものでもありません。",
            confirm = "隔離する", onConfirm = { controller.run("隔離") { quarantinePending(preview.confirmation) }; quarantinePreview = null },
            onDismiss = { controller.run("取消") { cancelPendingQuarantine(preview.confirmation) }; quarantinePreview = null })
    }
}

@Composable
private fun ExportTextButton(controller: AppController, label: String, source: PcSyncClient.() -> String?) {
    OutlinedButton(onClick = {
        // The chooser runs on the UI thread; only the file write runs on the sync worker.
        val chooser = JFileChooser().apply { dialogTitle = "編集内容をテキストとして保存（平文）"; selectedFile = File("every-article-edit.txt") }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            controller.run("書き出し") {
                val text = source() ?: return@run
                check(!file.exists()) { "既存のファイルは上書きしません" }
                file.writeText(text, Charsets.UTF_8)
            }
        }
    }) { Text(label) }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { Button(onClick = onDismiss) { Text("キャンセル") } })
}

private fun decisionTitle(kind: DecisionKind, reason: RefusalReason?): String = when (kind) {
    DecisionKind.CONFLICT -> "スマホのデータが先に変わっています（競合）"
    DecisionKind.REJECTED -> if (reason == RefusalReason.INCARNATION_MISMATCH) "スマホのデータが置き換えられています" else "スマホがこの編集を受け付けません"
    DecisionKind.UNKNOWN -> "反映されたか確認できません（不明）"
    DecisionKind.UNREADABLE -> "保護された編集を読み取れません"
}

private fun decisionExplanation(kind: DecisionKind, reason: RefusalReason?): String = when (kind) {
    DecisionKind.CONFLICT -> "この編集の後にスマホ側で変更がありました。上書きを防ぐため、自動では再送しません。内容を保存してから破棄し、新しく取り込み直して編集してください。"
    DecisionKind.REJECTED -> if (reason == RefusalReason.INCARNATION_MISMATCH)
        "バックアップ復元などでスマホのデータが入れ替わりました。この編集は古いデータに対するものなので反映できません。"
        else "理由: ${reason ?: "不明"}。この編集はそのままでは反映できません。"
    DecisionKind.UNKNOWN -> "スマホの操作記録が保持期間を過ぎています。反映済みかどうかを証明できないため、再送しません。スマホで内容を確認してから判断してください。"
    DecisionKind.UNREADABLE -> "暗号化された保存データを復号できませんでした。Windowsのアカウントや鍵の状態を確認してください。"
}

/** V3: guidance switches by the failure reason (hints only, never proof of corruption). */
private fun guidanceJa(reason: RecoveryFailureReason): String = when (reason) {
    RecoveryFailureReason.KEY_STATE -> "Windowsが鍵の状態の問題を報告しました。パスワードを再設定した場合は、以前のパスワードやアカウントの鍵を先に戻すと読めるようになる可能性があります。"
    RecoveryFailureReason.DATA_REJECTED -> "Windowsが保護データを受け付けませんでした。破損しているか、別のアカウント・PCで保護された可能性があります。"
    RecoveryFailureReason.INVALID_FORMAT -> "復号はできましたが形式が正しくありません（破損またはアプリの版の違い）。アプリの更新で読めるなら隔離は不要です。"
    RecoveryFailureReason.LEGACY_REIMPORT_BLOCKED -> "履歴の移行が途中で止まっています。古い履歴は残したまま、自動では読み込みません。"
    RecoveryFailureReason.MIXED -> "読み取りのたびに異なる理由で失敗しました。Windowsのアカウントを確認し、時間をおいて再試行してください。"
}
