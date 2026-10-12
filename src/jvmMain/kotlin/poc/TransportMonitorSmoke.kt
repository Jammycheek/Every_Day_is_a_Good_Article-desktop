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
        val pc = RecoveryController(WindowsDeltaJournal(Path.of(args[1]), DpapiProtector(helper)))
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
            val state = "transport=${link.state};mock-can-edit=${pc.canEdit(time)};snapshot=${pc.hasSnapshot};pending=${hasPending}"
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
                                    pc.acceptGrant(Grant("fake-monitor-phone", "mock-monitor-session", 1, now() + 180000), now())
                                    pc.loadSyntheticSnapshot(byteArrayOf(1, 2, 3), now())
                                    if (!hasPending) { pc.stage("fake-monitor-op", "SYNTHETIC-MONITOR-DELTA", now()); hasPending = true }
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
            events.submit { link.stop(); show() }.get(5, TimeUnit.SECONDS)
            events.shutdownNow(); probes.shutdownNow()
        }
        println("monitor-stopped=1")
    }
}
