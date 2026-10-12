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
                controller.acceptGrant(Grant("fake-restart-phone", "mock-session-1", 5, 100, "fake-dataset-v1"), 0)
                controller.loadSyntheticSnapshot(byteArrayOf(1, 2, 3), 0)
                controller.stage("fake-restart-operation", "SYNTHETIC-RESTART-DELTA", 1)
                controller.suspendSession()
                check(!controller.hasSnapshot)
                println("synthetic-delta-protected=true")
            }
            "recover" -> {
                check(!controller.hasSnapshot && !controller.canEdit(2))
                check(controller.recoveryCandidate("fake-restart-phone", 5, "fake-dataset-v1")?.syntheticDelta == "SYNTHETIC-RESTART-DELTA")
                controller.observeTransport(true)
                check(!controller.canEdit(3))
                controller.tick(100)
                val edit = controller.pending!!
                // A lost applied reply is queried after expiry; phone revision has advanced 5 -> 6.
                check(controller.reconcileApplied(AppliedReceipt("fake-restart-phone", 5,
                    "fake-restart-operation", edit.contentDigest, 6, "fake-dataset-v1")) == ReconciliationResult.CLEARED)
                check(journal.load() == null)
                check(!controller.canEdit(101) && !controller.hasSnapshot)
                controller.acceptGrant(Grant("fake-restart-phone", "mock-session-2", 6, 200, "fake-dataset-v1"), 101)
                controller.suspendSession()
                println("separate-jvm-recovery-verified=true")
            }
            "verify-floor" -> {
                check(journal.load() == null && !controller.hasSnapshot && !controller.canEdit(0))
                controller.observeTransport(true)
                var rejected = false
                try { controller.acceptGrant(Grant("fake-restart-phone", "stale", 5, 100, "fake-dataset-v1"), 0) }
                catch (_: IllegalStateException) { rejected = true }
                check(rejected)
                controller.acceptGrant(Grant("fake-restart-phone", "fresh", 6, 100, "fake-dataset-v1"), 0)
                controller.suspendSession()
                println("separate-jvm-revision-floor-verified=true")
            }
            else -> error("Unknown fixture")
        }
    }
}
