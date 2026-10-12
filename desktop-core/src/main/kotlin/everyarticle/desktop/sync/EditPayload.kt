package everyarticle.desktop.sync

import everyarticle.sync.protocol.ArticleTextEdit
import everyarticle.sync.protocol.CanonicalWriter

/**
 * Text form of one [ArticleTextEdit] stored in the encrypted PC journal:
 *   "EA-edit-v1:" articleId ":" (titleLength | "-") ":" title body
 * Lengths are UTF-16 code units; parsing is strict, so a damaged record is never silently reinterpreted.
 */
object EditPayload {
    private const val PREFIX = "EA-edit-v1:"
    const val MAX_TEXT_BYTES = 98_304

    fun encode(edit: ArticleTextEdit): String {
        val size = CanonicalWriter.utf8(edit.body).size + (edit.title?.let { CanonicalWriter.utf8(it).size } ?: 0)
        require(size <= MAX_TEXT_BYTES) { "Article text is too large for one PC edit" }
        return PREFIX + edit.articleId + ":" + (edit.title?.length?.toString() ?: "-") + ":" + (edit.title ?: "") + edit.body
    }

    fun decode(value: String): ArticleTextEdit {
        require(value.startsWith(PREFIX)) { "Unknown edit payload" }
        val rest = value.substring(PREFIX.length)
        val idEnd = rest.indexOf(':'); require(idEnd > 0)
        val articleId = rest.substring(0, idEnd).toLong()
        val lengthEnd = rest.indexOf(':', idEnd + 1); require(lengthEnd > idEnd)
        val lengthText = rest.substring(idEnd + 1, lengthEnd)
        val textStart = lengthEnd + 1
        return if (lengthText == "-") ArticleTextEdit(articleId, null, rest.substring(textStart))
        else {
            val length = lengthText.toInt(); require(length >= 0 && textStart + length <= rest.length)
            ArticleTextEdit(articleId, rest.substring(textStart, textStart + length), rest.substring(textStart + length))
        }
    }
}
