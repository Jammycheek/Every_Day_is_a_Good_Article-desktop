import poc.*;
import java.nio.file.Path;

// Separate-JVM hardware fixture: no grants, acknowledgements or phone writes.
class RecoveryRestartCheck {
    static long now() { return System.nanoTime() / 1_000_000; }
    public static void main(String[] args) {
        Path helper = Path.of(args[0]);
        try (WindowsDeltaJournal journal = new WindowsDeltaJournal(Path.of(args[1]), new DpapiProtector(helper))) {
            RecoveryController pc = new RecoveryController(journal);
            RecoveryEdit expected = new RecoveryEdit(new PendingEdit("fake-monitor-phone", 1, "fake-monitor-op", "fake-dataset-v1"), "SYNTHETIC-MONITOR-DELTA");
            if (!expected.equals(pc.getPending()) || pc.canEdit(now()) || pc.getHasSnapshot())
                throw new IllegalStateException("Unexpected restart state");
            System.out.println("restart-pending-equal=true;mock-can-edit=false;snapshot=false");
            TransportCoordinator link = new TransportCoordinator(pc, 3000);
            try {
                long started = now();
                link.observe(1, new WindowsTransportReader(helper).sample(), started, now());
                if (link.getState() != TransportState.USB_MTP_READY)
                    throw new IllegalStateException("Connected recovery trial requires ready transport");
                link.tick(now());
                if (pc.canEdit(now()) || pc.getHasSnapshot() || !expected.equals(pc.getPending()))
                    throw new IllegalStateException("Transport improperly restored session or changed pending edit");
                System.out.println("transport=USB_MTP_READY;mock-can-edit=false;snapshot=false;pending=true");
                System.out.println("process-restart-recovery-verified=1");
            } finally { link.stop(); }
        }
    }
}
