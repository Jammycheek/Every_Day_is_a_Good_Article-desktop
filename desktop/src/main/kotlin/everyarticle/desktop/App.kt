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
import everyarticle.desktop.sync.DecisionKind
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
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusBar(view, busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                view.message?.let { Text(it) }
                when {
                    view.paired == null || view.pairingInProgress -> PairingPanel(view, controller)
                    view.decision != null -> DecisionPanel(view, controller)
                    else -> SessionPanel(view, controller)
                }
            }
        }
    }
}

@Composable
private fun StatusBar(view: ClientView, busy: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("USB/MTP: " + if (view.transportReady) "接続" else "未接続")
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!view.sessionActive) Button(enabled = view.transportReady, onClick = { controller.run("接続中") { connect() } }) { Text("接続して取り込む") }
        else OutlinedButton(onClick = { controller.run("終了") { release() } }) { Text("編集を終えてスマホのロックを解除") }
        OutlinedButton(onClick = { controller.run("解除") { forgetPairing() } }) { Text("このPCのペアリングを解除") }
    }
    if (!view.sessionActive) {
        view.pending?.let { PendingNotice(view, controller) }
        return
    }
    var selected by remember(view.articles) { mutableStateOf(view.pending?.articleId ?: view.articles.firstOrNull()?.articleId) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.width(300.dp).fillMaxHeight()) {
            items(view.articles, key = { it.articleId }) { a ->
                val pendingId = view.pending?.articleId
                val locked = pendingId != null && pendingId != a.articleId
                Column(Modifier.fillMaxWidth().clickable(enabled = !locked) { selected = a.articleId }.padding(8.dp)) {
                    Text("${a.articleDate}  ${a.title ?: "（見出しなし）"}", fontWeight = if (a.articleId == selected) FontWeight.Bold else FontWeight.Normal)
                    Text(a.topicName, fontSize = 12.sp)
                }
            }
        }
        val article = view.articles.firstOrNull { it.articleId == selected }
        if (article != null) Editor(article, view, controller)
    }
}

@Composable
private fun Editor(article: ArticleSnapshot, view: ClientView, controller: AppController) {
    val pending = view.pending?.takeIf { it.articleId == article.articleId }
    var title by remember(article.articleId) { mutableStateOf(pending?.title ?: article.title ?: "") }
    var body by remember(article.articleId) { mutableStateOf(pending?.body ?: article.body) }
    val submitted = pending?.submitted == true
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it; controller.draftChanged(article.articleId, it.ifEmpty { null }, body) },
            label = { Text("見出し") }, enabled = !submitted, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it; controller.draftChanged(article.articleId, title.ifEmpty { null }, it) },
            label = { Text("本文") }, enabled = !submitted, modifier = Modifier.fillMaxWidth().weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.resubmitAllowed && submitted) Button(onClick = { controller.run("再提出") { resubmit() } }) { Text("未反映を確認済み：再提出") }
            else Button(enabled = !submitted, onClick = { controller.submit(article.articleId, title.ifEmpty { null }, body) }) { Text("スマホへ提出") }
            Text("入力は自動で暗号化保存されます。提出後は結果が確定するまで変更できません。", fontSize = 12.sp)
        }
    }
}

@Composable
private fun PendingNotice(view: ClientView, controller: AppController) {
    val p = view.pending ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("PCに未反映の編集が保護されています（記事 ${p.articleId}、${if (p.submitted) "提出済み・結果未確定" else "未提出"}）")
        Text("接続すると、まずスマホに状態を照会します。結果が確定するまで自動では再送しません。", fontSize = 12.sp)
        ExportTextButton(controller)
    }
}

@Composable
private fun DecisionPanel(view: ClientView, controller: AppController) {
    var discardPreview by remember { mutableStateOf<PendingDiscardPreview?>(null) }
    var quarantinePreview by remember { mutableStateOf<PendingQuarantinePreview?>(null) }
    val decision = view.decision ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(decisionTitle(decision, view.decisionReason), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(decisionExplanation(decision, view.decisionReason))
        view.pending?.let { Text("保護中の編集: 記事 ${it.articleId}「${it.title ?: "見出しなし"}」") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.run("再照会") { connect() } }) { Text("もう一度照会する") }
            if (decision == DecisionKind.UNREADABLE)
                OutlinedButton(onClick = { controller.run("隔離の準備") { quarantinePreview = preparePendingQuarantine() } }) { Text("隔離の準備") }
            else {
                ExportTextButton(controller)
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
private fun ExportTextButton(controller: AppController) {
    OutlinedButton(onClick = {
        // The chooser runs on the UI thread; only the file write runs on the sync worker.
        val chooser = JFileChooser().apply { dialogTitle = "編集内容をテキストとして保存（平文）"; selectedFile = File("every-article-edit.txt") }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            controller.run("書き出し") {
                val text = pendingPlainText() ?: return@run
                check(!file.exists()) { "既存のファイルは上書きしません" }
                file.writeText(text, Charsets.UTF_8)
            }
        }
    }) { Text("内容をテキストで保存…") }
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
