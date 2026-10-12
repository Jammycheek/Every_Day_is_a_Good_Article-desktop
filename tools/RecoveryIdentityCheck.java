import poc.*;
import java.nio.file.*;
import java.util.Arrays;

// Synthetic application identities only; real MTP metadata is never used as identity.
class RecoveryIdentityCheck {
    static long now() { return System.nanoTime() / 1_000_000; }
    static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new IllegalStateException("Mismatch was accepted");
    }
    public static void main(String[] args) throws Exception {
        Path helper = Path.of(args[0]), directory = Path.of(args[1]);
        byte[] before = Files.readAllBytes(directory.resolve("pending.dpapi"));
        RecoveryController pc = new RecoveryController(new WindowsDeltaJournal(directory, new DpapiProtector(helper)));
        RecoveryEdit expected = new RecoveryEdit(new PendingEdit("fake-monitor-phone", 1, "fake-monitor-op"), "SYNTHETIC-MONITOR-DELTA");
        if (!expected.equals(pc.getPending())) throw new IllegalStateException("Requires known synthetic journal");
        TransportCoordinator link = new TransportCoordinator(pc, 3000);
        try {
            long started = now();
            link.observe(1, new WindowsTransportReader(helper).sample(), started, now());
            if (link.getState() != TransportState.USB_MTP_READY) throw new IllegalStateException("Transport not ready");
            rejected(() -> pc.recoveryCandidate("different-fake-phone", 1));
            rejected(() -> pc.recoveryCandidate("fake-monitor-phone", 2));
            rejected(() -> pc.acceptGrant(new Grant("different-fake-phone", "mock-mismatch", 1, now()+60000), now()));
            rejected(() -> pc.acceptGrant(new Grant("fake-monitor-phone", "mock-mismatch", 2, now()+60000), now()));
            if (pc.canEdit(now()) || pc.getHasSnapshot()) throw new IllegalStateException("Mismatch enabled editing");
            System.out.println("wrong-device-rejected=true;wrong-revision-rejected=true;mock-can-edit=false;snapshot=false");
            pc.acceptGrant(new Grant("fake-monitor-phone", "mock-matching", 1, now()+60000), now());
            if (!pc.canEdit(now()) || pc.getHasSnapshot() || !expected.equals(pc.recoveryCandidate("fake-monitor-phone",1)))
                throw new IllegalStateException("Explicit matching mock grant failed");
            System.out.println("explicit-matching-mock-grant-accepted=true;snapshot=false;pending=true");
            link.stop();
            if (pc.canEdit(now()) || pc.getHasSnapshot() || !expected.equals(pc.getPending())
                || !Arrays.equals(before, Files.readAllBytes(directory.resolve("pending.dpapi"))))
                throw new IllegalStateException("Pending preservation failed");
            System.out.println("recovery-identity-verified=1;encrypted-journal-unchanged=true");
        } finally { link.stop(); }
    }
}
