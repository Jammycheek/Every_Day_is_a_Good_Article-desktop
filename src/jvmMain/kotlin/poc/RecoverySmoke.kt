package poc
import java.nio.file.*

/** CLI fixtures only. No real phone/session operation occurs. */
object RecoverySmoke {
    // Virtual-time fixture only. New production requests use real elapsed storage time.
    private fun acceptMock(pc: RecoveryController, device: String, session: String, revision: Long,
        localDeadline: Long, instance: String, now: Long) {
        val request = pc.beginGrantRequest(DatasetIdentity(device, instance), localDeadline - now, 0)
        pc.acceptGrant(Grant(device, session, revision, localDeadline - now, instance,
            request.requestNonce, request.minimumEpoch), request)
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3)
        val directory = Path.of(args[1])
        WindowsDeltaJournal(directory, DpapiProtector(Path.of(args[0]))).use { journal ->
            var clock = 0L
            val controller = RecoveryController(journal) { clock }
            when (args[2]) {
                "seed" -> {
                    check(journal.load() == null)
                    controller.observeTransport(true)
                    acceptMock(controller, "fake-restart-phone", "mock-session-1", 5, 100, "fake-dataset-v1", 0)
                    controller.loadSyntheticSnapshot(byteArrayOf(1, 2, 3))
                    clock = 1
                    controller.stage("fake-restart-operation", "SYNTHETIC-RESTART-DELTA")
                    controller.suspendSession()
                    check(!controller.hasSnapshot)
                    println("synthetic-delta-protected=true")
                }
                "recover" -> {
                    check(!controller.hasSnapshot && !controller.canEdit())
                    check(controller.recoveryCandidate("fake-restart-phone", 5, "fake-dataset-v1")?.syntheticDelta == "SYNTHETIC-RESTART-DELTA")
                    controller.observeTransport(true)
                    check(!controller.canEdit())
                    clock = 100; controller.tick()
                    val edit = controller.pending!!
                    // A lost applied reply is queried after expiry; phone revision has advanced 5 -> 6.
                    check(controller.reconcileApplied(AppliedReceipt("fake-restart-phone", 5,
                        "fake-restart-operation", edit.contentDigest, 6, "fake-dataset-v1")) == ReconciliationResult.CLEARED)
                    check(journal.load() == null)
                    check(!controller.canEdit() && !controller.hasSnapshot)
                    clock = 101
                    acceptMock(controller, "fake-restart-phone", "mock-session-2", 6, 200, "fake-dataset-v1", 101)
                    controller.suspendSession()
                    println("separate-jvm-recovery-verified=true")
                }
                "verify-floor" -> {
                    check(journal.load() == null && !controller.hasSnapshot && !controller.canEdit())
                    controller.observeTransport(true)
                    var rejected = false
                    val oldEpochRequest = controller.beginGrantRequest(DatasetIdentity("fake-restart-phone", "fake-dataset-v1"), 100, 0)
                    check(oldEpochRequest.minimumEpoch >= 2)
                    try { controller.acceptGrant(Grant("fake-restart-phone", "old-epoch", 6, 100,
                        "fake-dataset-v1", oldEpochRequest.requestNonce, 0), oldEpochRequest) }
                    catch (_: IllegalArgumentException) { rejected = true }
                    check(rejected); rejected = false
                    try { acceptMock(controller, "fake-restart-phone", "stale", 5, 100, "fake-dataset-v1", 0) }
                    catch (_: IllegalStateException) { rejected = true }
                    check(rejected)
                    acceptMock(controller, "fake-restart-phone", "fresh", 6, 100, "fake-dataset-v1", 0)
                    controller.suspendSession()
                    println("separate-jvm-revision-floor-verified=true")
                }
                else -> error("Unknown fixture")
            }
            controller.suspendSession()
        }
    }
}
