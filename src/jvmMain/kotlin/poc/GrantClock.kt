package poc
import java.security.SecureRandom
internal actual fun freshRequestNonce(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
    .joinToString("") { "%02x".format(it.toInt() and 255) }
internal actual fun localMonotonicMillis(): Long = System.nanoTime() / 1_000_000
