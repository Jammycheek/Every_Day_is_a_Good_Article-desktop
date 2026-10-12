package poc
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

object DeviceReportParser {
    fun parse(report: String): TransportObservation {
        fun denied() = TransportObservation(TransportState.ERROR)
        if (report.length > 131072 || '\u0000' in report) return denied()
        val lines = report.lineSequence().filter { it.isNotBlank() }.toList()
        val count = lines.firstOrNull()?.removePrefix("wpd-device-count=")?.toIntOrNull() ?: return denied()
        if (lines.first() != "wpd-device-count=$count" || count < 0 || count > 256) return denied()
        if (count == 0) return if (lines.size == 1) TransportObservation(TransportState.NONE) else denied()
        if (count > 1) return TransportObservation(TransportState.AMBIGUOUS)
        val fields = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val split = line.indexOf('=')
            if (split <= 0 || fields.put(line.substring(0, split), line.substring(split + 1)) != null) return denied()
        }
        if (fields.keys != setOf("device-id", "usb-pnp-ancestor", "wpd-open", "usb-declared", "reported-protocol")) {
            if (fields.keys == setOf("device-id", "usb-pnp-ancestor", "wpd-open", "error-hresult") && fields["wpd-open"] == "0")
                return TransportObservation(TransportState.UNAVAILABLE)
            return denied()
        }
        val selector = fields["device-id"]!!
        if (selector.isBlank() || selector.length > 4096) return denied()
        if (listOf("usb-pnp-ancestor", "wpd-open", "usb-declared").any { fields[it] !in setOf("0", "1") }) return denied()
        val mtp = Regex("^MTP(?:\\s*:\\s*[^\\r\\n]+)?$", RegexOption.IGNORE_CASE).matches(fields["reported-protocol"]!!)
        return if (fields["usb-pnp-ancestor"] == "1" && fields["wpd-open"] == "1" && fields["usb-declared"] == "1" && mtp)
            TransportObservation(TransportState.USB_MTP_READY, selector)
        else TransportObservation(TransportState.UNAVAILABLE)
    }
}
class WindowsTransportReader(private val helper: Path) {
    fun sample(): TransportObservation {
        check(System.getProperty("os.name").startsWith("Windows"))
        val workers = Executors.newSingleThreadExecutor()
        var process: Process? = null
        try {
            process = ProcessBuilder(helper.toAbsolutePath().toString(), "devices")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val input = process.inputStream
            val response = workers.submit<String> {
                input.use {
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (true) {
                        val size = it.read(chunk)
                        if (size < 0) break
                        check(buffer.size() + size <= 131072)
                        buffer.write(chunk, 0, size)
                    }
                    buffer.toString(Charsets.UTF_8)
                }
            }
            process.outputStream.close()
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return TransportObservation(TransportState.ERROR)
            return DeviceReportParser.parse(response.get(1, TimeUnit.SECONDS))
        } catch (_: Exception) {
            return TransportObservation(TransportState.ERROR)
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly(); runCatching { it.inputStream.close() } }
            workers.shutdownNow()
        }
    }
}
