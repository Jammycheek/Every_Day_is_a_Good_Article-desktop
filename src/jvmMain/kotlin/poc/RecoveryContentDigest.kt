package poc

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Versioned, length-prefixed UTF-8 fields and big-endian revision; avoids concatenation ambiguity. */
internal actual fun recoveryContentDigest(metadata: PendingEdit, syntheticDelta: String): String {
    requireWellFormedUtf16(metadata.device)
    requireWellFormedUtf16(metadata.operation)
    metadata.datasetInstance?.let { DatasetIdentity(metadata.device, it) }
    requireWellFormedUtf16(syntheticDelta)
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { output ->
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            try { output.writeInt(bytes.size); output.write(bytes) } finally { bytes.fill(0) }
        }
        field(if (metadata.datasetInstance == null) "EveryArticle-Synthetic-Receipt-v1" else "EveryArticle-Synthetic-Receipt-v2")
        field(metadata.device)
        metadata.datasetInstance?.let { field(it) }
        output.writeLong(metadata.baseRevision)
        field(metadata.operation)
        field(syntheticDelta)
    }
    val bytes = buffer.toByteArray()
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } finally { bytes.fill(0); buffer.reset() }
}
