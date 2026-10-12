import poc.*;
import java.nio.file.Path;

// Local hardware fixture: calls the CI-built controller, never writes phone files.
class LeaseExpirySmoke {
    static long now() { return System.nanoTime() / 1_000_000; }
    static void show(TransportCoordinator link, RecoveryController pc, boolean pending) {
        System.out.println("transport=" + link.getState() + ";mock-can-edit=" + pc.canEdit(now())
            + ";snapshot=" + pc.getHasSnapshot() + ";pending=" + pending);
    }
    public static void main(String[] args) throws Exception {
        Path helper = Path.of(args[0]);
        try (WindowsDeltaJournal journal = new WindowsDeltaJournal(Path.of(args[1]), new DpapiProtector(helper))) {
        RecoveryController pc = new RecoveryController(journal);
        TransportCoordinator link = new TransportCoordinator(pc, 3000);
        WindowsTransportReader reader = new WindowsTransportReader(helper);
        RecoveryEdit before = pc.getPending();
        if (before == null) throw new IllegalStateException("Missing synthetic pending fixture");
        long seq = 0, started = now();
        link.observe(++seq, reader.sample(), started, now());
        if (link.getState() != TransportState.USB_MTP_READY) throw new IllegalStateException("Transport not ready");
        long expires = now() + 15000;
        pc.acceptGrant(new Grant("fake-monitor-phone", "mock-expiry-session", 1, expires, "fake-dataset-v1"), now());
        pc.loadSyntheticSnapshot(new byte[]{1,2,3}, now());
        show(link, pc, true);
        boolean expired = false;
        try {
            while (now() < expires + 5000) {
                started = now();
                link.observe(++seq, reader.sample(), started, now());
                if (link.getState() != TransportState.USB_MTP_READY) throw new IllegalStateException("Transport loss confounds expiry");
                link.tick(now());
                if (!expired && !pc.canEdit(now())) {
                    if (now() < expires || pc.getHasSnapshot()) throw new IllegalStateException("Unexpected expiry state");
                    expired = true;
                    show(link, pc, true);
                }
                Thread.sleep(100);
            }
            if (!expired || pc.canEdit(now()) || pc.getHasSnapshot() || !before.equals(pc.getPending()))
                throw new IllegalStateException("Expiry or preservation failed");
            System.out.println("lease-expiry-verified=1;pending-reloaded-equal=true");
        } finally { link.stop(); }
    }
}
