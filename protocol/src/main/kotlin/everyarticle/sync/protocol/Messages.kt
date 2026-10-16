package everyarticle.sync.protocol

/** Wire protocol version carried in every envelope. Unknown versions are rejected, never guessed. */
const val PROTOCOL_VERSION = 1

private const val MAX_ID = 256
private const val MAX_TEXT = 98_304 // one article title/body; matches the PC journal payload bound
const val MAX_SNAPSHOT_ARTICLES = 2_000

/** Why an authoritative peer refused a request. Reasons never carry article content. */
enum class RefusalReason {
    BUSY, NO_SESSION, SESSION_EXPIRED, STALE_EPOCH, INCARNATION_MISMATCH, REVISION_MISMATCH,
    UNKNOWN_ARTICLE, INVALID_PAYLOAD, OPERATION_ID_REUSED, DIGEST_MISMATCH, TOO_LARGE, LOCAL_EDIT_ACTIVE
}

/**
 * Outcome of an operation as known by the authoritative phone.
 *  - APPLIED: committed exactly once; resultingRevision is set.
 *  - NOT_APPLIED: provably never committed and the base revision is still current. Only this allows resubmission.
 *  - IN_PROGRESS: currently being applied; query again, never resubmit.
 *  - CONFLICT: provably not committed, but the dataset moved on; resubmission would overwrite newer data.
 *  - REJECTED: will never apply (e.g. operation ID reused with other content, dataset replaced).
 *  - UNKNOWN: the phone cannot prove either way (history pruned). Never resubmit automatically.
 */
enum class OperationStatus { APPLIED, NOT_APPLIED, IN_PROGRESS, CONFLICT, REJECTED, UNKNOWN }

enum class SessionMode { ACQUIRE, RENEW }

/** The smallest edit unit of v1: replace one existing article's title and body. */
data class ArticleTextEdit(val articleId: Long, val title: String?, val body: String) {
    init {
        if (articleId <= 0) throw ProtocolFormatException("Invalid article id")
        if (CanonicalWriter.utf8(body).size > MAX_TEXT || (title != null && CanonicalWriter.utf8(title).size > MAX_TEXT))
            throw ProtocolFormatException("Article text too large")
    }
    fun canonicalBytes(): ByteArray = CanonicalWriter().string("EA-article-text-edit-v1").i64(articleId)
        .optionalString(title).string(body).toByteArray()

    companion object {
        fun decode(reader: CanonicalReader): ArticleTextEdit {
            if (reader.string() != "EA-article-text-edit-v1") throw ProtocolFormatException("Unknown edit kind")
            return ArticleTextEdit(reader.i64(), reader.optionalString(MAX_TEXT), reader.string(MAX_TEXT))
        }
    }
}

/** Digest binding operation ID, dataset incarnation, base revision and exact content. */
fun operationDigest(operationId: String, incarnation: String, baseRevision: Long, edit: ArticleTextEdit): ByteArray =
    SyncCrypto.sha256(CanonicalWriter().string("EA-operation-v1").string(operationId).string(incarnation)
        .i64(baseRevision).bytes(edit.canonicalBytes()).toByteArray())

data class ArticleSnapshot(
    val articleId: Long, val topicName: String, val articleDate: String,
    val title: String?, val body: String, val updatedAtMillis: Long
)

sealed class SyncMessage(val type: Int) {
    // ---- pairing (sent in plaintext envelopes; authenticated by the numeric comparison) ----
    data class PairStart(val pcId: String, val pcName: String, val commitment: ByteArray) : SyncMessage(1)
    data class PairAccept(val phoneId: String, val phoneName: String, val phonePublicKey: ByteArray, val phoneNonce: ByteArray) : SyncMessage(2)
    data class PairReveal(val pcPublicKey: ByteArray, val pcNonce: ByteArray) : SyncMessage(3)
    /** Sent only after the local user confirmed the same 6 digits. */
    data class PairConfirm(val senderId: String, val mac: ByteArray) : SyncMessage(4)

    // ---- authenticated session channel ----
    data class StatusRequest(val requestNonce: String) : SyncMessage(10)
    data class StatusReport(val requestNonce: String, val appIdentity: String, val datasetIncarnation: String,
        val revision: Long, val sessionActive: Boolean) : SyncMessage(11)
    data class SessionRequest(val requestNonce: String, val mode: SessionMode, val datasetIncarnation: String,
        val sessionId: String?, val epoch: Long?, val maximumDurationMillis: Long) : SyncMessage(12)
    data class SessionGrant(val requestNonce: String, val sessionId: String, val epoch: Long, val appIdentity: String,
        val datasetIncarnation: String, val baseRevision: Long, val validityMillis: Long) : SyncMessage(13)
    data class SessionRefused(val requestNonce: String, val reason: RefusalReason) : SyncMessage(14)
    data class SessionRelease(val sessionId: String, val epoch: Long) : SyncMessage(15)
    data class SnapshotRequest(val requestNonce: String, val sessionId: String, val epoch: Long) : SyncMessage(20)
    data class Snapshot(val requestNonce: String, val sessionId: String, val epoch: Long, val datasetIncarnation: String,
        val revision: Long, val articles: List<ArticleSnapshot>) : SyncMessage(21)
    data class SubmitEdit(val requestNonce: String, val sessionId: String, val epoch: Long, val operationId: String,
        val datasetIncarnation: String, val baseRevision: Long, val edit: ArticleTextEdit, val digest: ByteArray) : SyncMessage(30)
    data class OperationQuery(val requestNonce: String, val operationId: String, val datasetIncarnation: String,
        val baseRevision: Long, val digest: ByteArray) : SyncMessage(31)
    data class OperationReport(val requestNonce: String, val operationId: String, val status: OperationStatus,
        val resultingRevision: Long?, val reason: RefusalReason?, val currentRevision: Long,
        val datasetIncarnation: String) : SyncMessage(32)

    fun encode(): ByteArray {
        val w = CanonicalWriter().u8(type)
        when (this) {
            is PairStart -> w.string(pcId).string(pcName).bytes(commitment)
            is PairAccept -> w.string(phoneId).string(phoneName).bytes(phonePublicKey).bytes(phoneNonce)
            is PairReveal -> w.bytes(pcPublicKey).bytes(pcNonce)
            is PairConfirm -> w.string(senderId).bytes(mac)
            is StatusRequest -> w.string(requestNonce)
            is StatusReport -> w.string(requestNonce).string(appIdentity).string(datasetIncarnation).i64(revision).bool(sessionActive)
            is SessionRequest -> w.string(requestNonce).u8(mode.ordinal).string(datasetIncarnation)
                .optionalString(sessionId).optionalLong(epoch).i64(maximumDurationMillis)
            is SessionGrant -> w.string(requestNonce).string(sessionId).i64(epoch).string(appIdentity)
                .string(datasetIncarnation).i64(baseRevision).i64(validityMillis)
            is SessionRefused -> w.string(requestNonce).u8(reason.ordinal)
            is SessionRelease -> w.string(sessionId).i64(epoch)
            is SnapshotRequest -> w.string(requestNonce).string(sessionId).i64(epoch)
            is Snapshot -> {
                w.string(requestNonce).string(sessionId).i64(epoch).string(datasetIncarnation).i64(revision).i32(articles.size)
                articles.forEach { a -> w.i64(a.articleId).string(a.topicName).string(a.articleDate)
                    .optionalString(a.title).string(a.body).i64(a.updatedAtMillis) }
            }
            is SubmitEdit -> w.string(requestNonce).string(sessionId).i64(epoch).string(operationId)
                .string(datasetIncarnation).i64(baseRevision).bytes(edit.canonicalBytes()).bytes(digest)
            is OperationQuery -> w.string(requestNonce).string(operationId).string(datasetIncarnation).i64(baseRevision).bytes(digest)
            is OperationReport -> w.string(requestNonce).string(operationId).u8(status.ordinal).optionalLong(resultingRevision)
                .bool(reason != null).also { if (reason != null) it.u8(reason.ordinal) }.i64(currentRevision).string(datasetIncarnation)
        }
        return w.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): SyncMessage {
            val r = CanonicalReader(bytes)
            fun reason() = RefusalReason.entries.getOrNull(r.u8()) ?: throw ProtocolFormatException("Unknown reason")
            val message = when (val type = r.u8()) {
                1 -> PairStart(r.string(MAX_ID), r.string(MAX_ID), r.bytes(32))
                2 -> PairAccept(r.string(MAX_ID), r.string(MAX_ID), r.bytes(512), r.bytes(32))
                3 -> PairReveal(r.bytes(512), r.bytes(32))
                4 -> PairConfirm(r.string(MAX_ID), r.bytes(32))
                10 -> StatusRequest(r.string(MAX_ID))
                11 -> StatusReport(r.string(MAX_ID), r.string(MAX_ID), r.string(MAX_ID), r.i64(), r.bool())
                12 -> SessionRequest(r.string(MAX_ID), SessionMode.entries.getOrNull(r.u8()) ?: throw ProtocolFormatException("Unknown mode"),
                    r.string(MAX_ID), r.optionalString(MAX_ID), r.optionalLong(), r.i64())
                13 -> SessionGrant(r.string(MAX_ID), r.string(MAX_ID), r.i64(), r.string(MAX_ID), r.string(MAX_ID), r.i64(), r.i64())
                14 -> SessionRefused(r.string(MAX_ID), reason())
                15 -> SessionRelease(r.string(MAX_ID), r.i64())
                20 -> SnapshotRequest(r.string(MAX_ID), r.string(MAX_ID), r.i64())
                21 -> {
                    val head = listOf(r.string(MAX_ID), r.string(MAX_ID))
                    val epoch = r.i64(); val incarnation = r.string(MAX_ID); val revision = r.i64()
                    val count = r.i32()
                    if (count !in 0..MAX_SNAPSHOT_ARTICLES) throw ProtocolFormatException("Too many articles")
                    val articles = List(count) {
                        ArticleSnapshot(r.i64(), r.string(MAX_TEXT), r.string(MAX_ID), r.optionalString(MAX_TEXT), r.string(MAX_TEXT), r.i64())
                    }
                    Snapshot(head[0], head[1], epoch, incarnation, revision, articles)
                }
                30 -> SubmitEdit(r.string(MAX_ID), r.string(MAX_ID), r.i64(), r.string(MAX_ID), r.string(MAX_ID), r.i64(),
                    CanonicalReader(r.bytes(2 * MAX_TEXT + 1024)).let { er -> ArticleTextEdit.decode(er).also { er.end() } }, r.bytes(32))
                31 -> OperationQuery(r.string(MAX_ID), r.string(MAX_ID), r.string(MAX_ID), r.i64(), r.bytes(32))
                32 -> OperationReport(r.string(MAX_ID), r.string(MAX_ID),
                    OperationStatus.entries.getOrNull(r.u8()) ?: throw ProtocolFormatException("Unknown status"),
                    r.optionalLong(), if (r.bool()) reason() else null, r.i64(), r.string(MAX_ID))
                else -> throw ProtocolFormatException("Unknown message type $type")
            }
            r.end()
            return message
        }
    }
}
