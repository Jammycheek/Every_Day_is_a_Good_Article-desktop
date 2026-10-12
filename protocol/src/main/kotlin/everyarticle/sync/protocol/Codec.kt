package everyarticle.sync.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Malformed, oversized or non-canonical protocol bytes. Never retried as if it were a transport error. */
class ProtocolFormatException(message: String) : IllegalArgumentException(message)

/**
 * Canonical big-endian encoding. Every value has exactly one byte representation, so the same
 * writer output is used both on the wire and as digest input.
 */
class CanonicalWriter {
    private val out = ByteArrayOutputStream()
    fun u8(value: Int) = apply { require(value in 0..255); out.write(value) }
    fun bool(value: Boolean) = u8(if (value) 1 else 0)
    fun i32(value: Int) = apply { for (shift in intArrayOf(24, 16, 8, 0)) out.write((value ushr shift) and 0xff) }
    fun i64(value: Long) = apply { for (shift in 56 downTo 0 step 8) out.write(((value ushr shift) and 0xff).toInt()) }
    fun bytes(value: ByteArray) = apply { i32(value.size); out.write(value) }
    fun string(value: String) = apply { bytes(utf8(value)) }
    fun optionalString(value: String?) = apply { bool(value != null); if (value != null) string(value) }
    fun optionalLong(value: Long?) = apply { bool(value != null); if (value != null) i64(value) }
    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        /** Strict UTF-8: unpaired surrogates are rejected instead of being replaced. */
        fun utf8(value: String): ByteArray {
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = try { encoder.encode(java.nio.CharBuffer.wrap(value)) }
                catch (_: java.nio.charset.CharacterCodingException) { throw ProtocolFormatException("Malformed UTF-16 text") }
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
    }
}

class CanonicalReader(private val data: ByteArray) {
    private var position = 0
    private fun take(count: Int): ByteArray {
        if (count < 0 || count > data.size - position) throw ProtocolFormatException("Truncated value")
        return data.copyOfRange(position, position + count).also { position += count }
    }
    fun u8(): Int = take(1)[0].toInt() and 0xff
    fun bool(): Boolean = when (u8()) { 0 -> false; 1 -> true; else -> throw ProtocolFormatException("Invalid boolean") }
    fun i32(): Int = take(4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
    fun i64(): Long = take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
    fun bytes(maxBytes: Int): ByteArray {
        val size = i32()
        if (size < 0 || size > maxBytes) throw ProtocolFormatException("Field exceeds $maxBytes bytes")
        return take(size)
    }
    fun string(maxBytes: Int = 4096): String {
        val raw = bytes(maxBytes)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { decoder.decode(ByteBuffer.wrap(raw)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { throw ProtocolFormatException("Malformed UTF-8 text") }
    }
    fun optionalString(maxBytes: Int = 4096): String? = if (bool()) string(maxBytes) else null
    fun optionalLong(): Long? = if (bool()) i64() else null
    fun end() { if (position != data.size) throw ProtocolFormatException("Trailing bytes") }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun String.hexBytes(): ByteArray {
    if (length % 2 != 0 || any { it !in "0123456789abcdef" }) throw ProtocolFormatException("Invalid lowercase hex")
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
