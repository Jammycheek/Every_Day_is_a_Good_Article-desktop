package poc

import java.nio.file.Path

/** Separate-process lock fixture. Synthetic local files only, no device access. */
object JournalLockSmoke {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3)
        val journal = try { WindowsDeltaJournal(Path.of(args[1]), DpapiProtector(Path.of(args[0]))) }
        catch (_: JournalInUseException) {
            check(args[2] == "busy")
            println("BUSY")
            return
        }
        journal.use {
            check(args[2] != "busy") { "Second process unexpectedly acquired ownership" }
            val identity = DatasetIdentity("fake-lock-phone", "fake-dataset-v1")
            when (args[2]) {
                "hold" -> {
                    it.recordVerifiedRevision(identity, 7)
                    it.recordVerifiedEpoch(identity, 9)
                    it.save(RecoveryEdit(PendingEdit("fake-lock-phone", 7, "op-owner", "fake-dataset-v1"), "SYNTHETIC-OWNER"))
                    println("HELD"); System.out.flush()
                    check(readlnOrNull() == "release")
                }
                "verify" -> {
                    check(it.revisionFloor(identity) == 7L)
                    it.recordVerifiedRevision(identity, 6) // delayed older proposal must not lower the floor
                    check(it.verifiedEpoch(identity) == 9L)
                    it.recordVerifiedEpoch(identity, 8)
                    check(it.verifiedEpoch(identity) == 9L)
                    check(it.revisionFloor(identity) == 7L)
                    check(it.load()?.syntheticDelta == "SYNTHETIC-OWNER")
                    println("VERIFIED")
                }
                else -> error("Unknown lock fixture")
            }
        }
    }
}
