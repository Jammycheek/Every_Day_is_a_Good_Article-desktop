package poc

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Transport diagnostics only; deliberately has no reference to SessionGate. */
sealed interface ProbeCommand {
    data object SelfTest : ProbeCommand
    data object Devices : ProbeCommand
    data class Children(val device: String, val parent: String) : ProbeCommand
    data class RoundTrip(val device: String, val folder: String, val empty: Boolean) : ProbeCommand
}
sealed interface ProbeResult {
    data object Completed : ProbeResult
    data class Failed(val exitCode: Int) : ProbeResult
    data object TimedOut : ProbeResult
}

/** Local console output can contain private device selectors. Never send it to public logs. */
class WindowsProbe(private val executable: Path) {
    fun run(command: ProbeCommand): ProbeResult {
        check(System.getProperty("os.name").startsWith("Windows")) { "Windows helper only" }
        val path = executable.toAbsolutePath().normalize()
        require(Files.isRegularFile(path)) { "Build the native helper first" }
        val args = when (command) {
            ProbeCommand.SelfTest -> listOf("self-test")
            ProbeCommand.Devices -> listOf("devices")
            is ProbeCommand.Children -> listOf("children", command.device, command.parent)
            is ProbeCommand.RoundTrip -> listOf("roundtrip", command.device, command.folder,
                "--synthetic-write", if (command.empty) "empty" else "64k")
        }
        require(args.all { it.isNotBlank() && '\u0000' !in it })
        // No command shell or interpolation. Inherited IO keeps selectors on the local console.
        val process = ProcessBuilder(listOf(path.toString()) + args).inheritIO().start()
        try {
            if (!process.waitFor(35, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Helper termination failed" }
                return ProbeResult.TimedOut
            }
            return when (val code = process.exitValue()) {
                0 -> ProbeResult.Completed
                124 -> ProbeResult.TimedOut
                else -> ProbeResult.Failed(code)
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

fun main(args: Array<String>) {
    require(args.size >= 2) { "Usage: <helper.exe> devices|self-test|children|roundtrip ..." }
    val command = when (args[1]) {
        "self-test" -> { require(args.size == 2); ProbeCommand.SelfTest }
        "devices" -> { require(args.size == 2); ProbeCommand.Devices }
        "children" -> { require(args.size == 4); ProbeCommand.Children(args[2], args[3]) }
        "roundtrip" -> {
            require(args.size == 6 && args[4] == "--synthetic-write" && args[5] in listOf("empty", "64k"))
            ProbeCommand.RoundTrip(args[2], args[3], args[5] == "empty")
        }
        else -> error("Unknown command")
    }
    val result = WindowsProbe(Path.of(args[0])).run(command)
    check(result == ProbeResult.Completed) { "Probe failed: $result; inspect only the dedicated synthetic folder" }
}
