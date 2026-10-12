import poc.*;
import java.nio.file.*;
import java.util.Arrays;

// Synthetic-only disposable copy. Never modifies the source journal or phone files.
class CorruptJournalCheck {
    static long now() { return System.nanoTime() / 1_000_000; }
    public static void main(String[] args) throws Exception {
        Path helper = Path.of(args[0]);
        Path original = Path.of(args[1]).resolve("pending.dpapi");
        Path copiesRoot = Path.of(args[2]);
        Files.createDirectories(copiesRoot);
        byte[] originalBytes = Files.readAllBytes(original);
        Path disposable = Files.createTempDirectory(copiesRoot, "corrupt-journal-");
        WindowsDeltaJournal journal = new WindowsDeltaJournal(disposable, new DpapiProtector(helper));
        RecoveryEdit expected = new RecoveryEdit(new PendingEdit("fake-monitor-phone", 1, "fake-monitor-op", "fake-dataset-v1"), "SYNTHETIC-MONITOR-DELTA");
        RecoveryEdit source = new WindowsDeltaJournal(Path.of(args[1]), new DpapiProtector(helper)).load();
        if (!expected.equals(source)) throw new IllegalStateException("Source must be the synthetic fixture");
        journal.save(source); // New encrypted copy with the journal's owner-only ACL.
        RecoveryController pc = new RecoveryController(journal);
        TransportCoordinator link = new TransportCoordinator(pc, 3000);
        try {
            long started = now();
            link.observe(1, new WindowsTransportReader(helper).sample(), started, now());
            if (link.getState() != TransportState.USB_MTP_READY) throw new IllegalStateException("Transport not ready");
            pc.acceptGrant(new Grant("fake-monitor-phone", "mock-corruption-session", 1, now() + 60000, "fake-dataset-v1"), now());
            pc.loadSyntheticSnapshot(new byte[]{1,2,3}, now());
            if (!pc.canEdit(now()) || !pc.getHasSnapshot()) throw new IllegalStateException("Missing initial mock permission");
            Path damagedFile = disposable.resolve("pending.dpapi");
            byte[] damaged = Files.readAllBytes(damagedFile);
            damaged[damaged.length - 1] ^= (byte)0xff;
            Files.write(damagedFile, damaged);
            boolean rejected = false;
            try { pc.getPending(); } catch (IllegalStateException expectedFailure) { rejected = true; }
            if (!rejected || pc.canEdit(now()) || pc.getHasSnapshot()) throw new IllegalStateException("Corruption did not revoke session");
            if (!Arrays.equals(damaged, Files.readAllBytes(damagedFile))) throw new IllegalStateException("Damaged journal altered");
            if (!Arrays.equals(originalBytes, Files.readAllBytes(original))) throw new IllegalStateException("Source journal altered");
            System.out.println("corrupt-read-rejected=true;mock-can-edit=false;snapshot=false");
            System.out.println("damaged-copy-preserved=true;source-journal-unchanged=true");
            System.out.println("corruption-safety-verified=1");
        } finally { link.stop(); }
    }
}
