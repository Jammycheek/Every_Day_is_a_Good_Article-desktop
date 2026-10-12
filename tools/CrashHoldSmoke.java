import poc.*;
import java.nio.file.Path;

// Dedicated child process: save one synthetic edit, then wait for external termination.
class CrashHoldSmoke {
    static long now() { return System.nanoTime() / 1_000_000; }
    public static void main(String[] args) throws Exception {
        Path helper = Path.of(args[0]);
        try (WindowsDeltaJournal journal = new WindowsDeltaJournal(Path.of(args[1]), new DpapiProtector(helper))) {
            RecoveryController pc = new RecoveryController(journal);
            if (pc.getPending() != null) throw new IllegalStateException("Use a fresh synthetic journal directory");
            TransportCoordinator link = new TransportCoordinator(pc, 3000);
            long started = now();
            link.observe(1, new WindowsTransportReader(helper).sample(), started, now());
            if (link.getState() != TransportState.USB_MTP_READY) throw new IllegalStateException("Transport not ready");
            pc.acceptGrant(new Grant("fake-monitor-phone", "mock-crash-session", 1, now() + 180000, "fake-dataset-v1"), now());
            pc.loadSyntheticSnapshot(new byte[]{1,2,3}, now());
            pc.stage("fake-monitor-op", "SYNTHETIC-MONITOR-DELTA", now());
            if (!pc.canEdit(now()) || !pc.getHasSnapshot() || pc.getPending() == null)
                throw new IllegalStateException("Fixture not staged");
            System.out.println("crash-fixture-ready=1;mock-can-edit=true;snapshot=true;pending=true");
            System.out.flush();
            // Intentionally no shutdown cleanup: the parent terminates this dedicated process.
            Thread.sleep(60000);
            throw new IllegalStateException("Expected external forced termination");
        }
    }
}
