package poc
import java.nio.file.*

/** CLI fixtures only. No real phone/session operation occurs. */
object RecoverySmoke {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3)
        val directory = Path.of(args[1])
        val journal = WindowsDeltaJournal(directory, DpapiProtector(Path.of(args[0])))
        val controller = RecoveryController(journal)
        when (args[2]) {
            "seed" -> {
                check(journal.load() == null)
                controller.observeTransport(true)
                controller.acceptGrant(Grant("fake-restart-phone", "mock-session-1", 5, 100), 0)
                controller.loadSyntheticSnapshot(byteArrayOf(1, 2, 3), 0)
                controller.stage("fake-restart-operation", "SYNTHETIC-RESTART-DELTA", 1)
                controller.suspendSession()
                check(!controller.hasSnapshot)
                println("synthetic-delta-protected=true")
            }
            "recover" -> {
                check(!controller.hasSnapshot && !controller.canEdit(2))
                check(controller.recoveryCandidate("fake-restart-phone", 5)?.syntheticDelta == "SYNTHETIC-RESTART-DELTA")
                controller.observeTransport(true)
                check(!controller.canEdit(3))
                controller.acceptGrant(Grant("fake-restart-phone", "mock-session-2", 5, 100), 3)
                controller.acknowledge("fake-restart-phone", "mock-session-2", "fake-restart-operation", 4)
                check(journal.load() == null)
                println("separate-jvm-recovery-verified=true")
            }
            else -> error("Unknown fixture")
        }
    }
}
