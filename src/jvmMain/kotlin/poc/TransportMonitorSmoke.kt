package poc
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit mock-session fixture. Reads hardware metadata; NEVER writes phone files. */
object TransportMonitorSmoke {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4 && args[3] == "--mock-session")
        val duration = args[2].toLong()
        require(duration in 10..120)
        val helper = Path.of(args[0])
        WindowsDeltaJournal(Path.of(args[1]), DpapiProtector(helper)).use { journal ->
            val pc = RecoveryController(journal)
            val link = TransportCoordinator(pc)
            val reader = WindowsTransportReader(helper)
            val events = Executors.newSingleThreadScheduledExecutor()
            val probes = Executors.newSingleThreadExecutor()
            val busy = AtomicBoolean(false)
            val done = CountDownLatch(1)
            var sequence = 0L
            var seeded = false
            var previous = ""
            var hasPending = pc.pending != null
            fun now() = System.nanoTime() / 1_000_000
            fun show() {
                val time = now()
                val state = "transport=${link.state};mock-can-edit=${pc.canEdit()};snapshot=${pc.hasSnapshot};pending=${hasPending}"
                if (state != previous) { println(state); previous = state }
            }
            events.scheduleAtFixedRate({
                try { link.tick(now()); show() }
                catch (_: Exception) { link.stop(); println("monitor-error=1"); done.countDown() }
            }, 0, 100, TimeUnit.MILLISECONDS)
            events.scheduleAtFixedRate({
                if (busy.compareAndSet(false, true)) {
                    val request = ++sequence
                    val started = now()
                    probes.submit {
                        val sample = reader.sample()
                        try {
                            events.execute {
                                try {
                                    link.observe(request, sample, started, now())
                                    if (!seeded && link.state == TransportState.USB_MTP_READY) {
                                        // This fixture models a grant. Transport alone never grants in production.
                                        val bound = pc.beginGrantRequest(DatasetIdentity("fake-monitor-phone", "fake-dataset-v1"), 180000, 0)
                                        pc.acceptGrant(Grant("fake-monitor-phone", "mock-monitor-session", 1, 180000,
                                            "fake-dataset-v1", bound.requestNonce, bound.minimumEpoch), bound)
                                        pc.loadSyntheticSnapshot(byteArrayOf(1, 2, 3))
                                        if (!hasPending) { pc.stage("fake-monitor-op", "SYNTHETIC-MONITOR-DELTA"); hasPending = true }
                                        seeded = true
                                        println("mock-session-seeded=1")
                                    }
                                    show()
                                } catch (_: Exception) { link.stop(); println("monitor-error=1"); done.countDown() }
                                finally { busy.set(false) }
                            }
                        } catch (_: RejectedExecutionException) { busy.set(false) }
                    }
                }
            }, 0, 1, TimeUnit.SECONDS)
            try { done.await(duration, TimeUnit.SECONDS) }
            finally {
                try { events.submit { link.stop(); show() }.get(5, TimeUnit.SECONDS) }
                finally {
                    events.shutdownNow(); probes.shutdownNow()
                    check(events.awaitTermination(10, TimeUnit.SECONDS)) { "Event loop did not stop" }
                    check(probes.awaitTermination(20, TimeUnit.SECONDS)) { "Probe worker did not stop" }
                    pc.suspendSession()
                }
            }
            println("monitor-stopped=1")
        }
    }
}
