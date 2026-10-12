package poc

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.*
import kotlin.test.*

class RevisionHistoryRecoveryTest {
    private val identity = DatasetIdentity("fake-phone", "fake-dataset-v1")
    private val other = DatasetIdentity("fake-phone", "fake-dataset-v2")
    private fun protector() = DpapiProtector(Path.of(System.getenv("POC_HELPER") ?: error("POC_HELPER required")))
    private fun trial(action: (Path, DpapiProtector) -> Unit) {
        val directory = Files.createTempDirectory("ea-poc-history-recovery")
        try { action(directory, protector()) } finally {
            Files.newDirectoryStream(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
    private fun grant(revision: Long, instance: String = identity.incarnation) =
        Grant(identity.device, "fresh-synthetic-$revision-$instance", revision, 100, instance)

    @Test fun confirmedScopedForgetAllowsRollbackOnlyThroughExplicitNewBaselineAndKeepsWarning() = trial { dir, protector ->
        WindowsDeltaJournal(dir, protector).use { journal ->
            val pc = RecoveryController(journal); pc.observeTransport(true)
            pc.acceptGrant(grant(10), 0); pc.loadSyntheticSnapshot(byteArrayOf(1), 0)
            journal.recordAppliedRevision(other, 20)
            val preview = pc.prepareForgetRevisionHistory(identity)!!
            assertEquals(10L, preview.previousFloor); assertEquals(identity, preview.identity)
            assertTrue(pc.canEdit(0)); assertTrue(pc.hasSnapshot)
            assertEquals(HistoryRecoveryResult.RECOVERED, pc.recoverRevisionHistory(preview.confirmation))
            assertFalse(pc.canEdit(0)); assertFalse(pc.hasSnapshot)
            assertNull(journal.revisionFloor(identity)); assertEquals(20L, journal.revisionFloor(other))
            assertTrue(pc.revisionHistoryIncomplete); assertTrue(pc.revisionHistoryNeedsBaseline(identity))
            assertFalse(pc.revisionHistoryNeedsBaseline(other))
            assertFailsWith<IllegalStateException> { pc.acceptGrant(grant(3), 0) }
            Files.write(dir.resolve("history-incomplete.warning"), byteArrayOf(9, 8, 7))
            assertTrue(pc.revisionHistoryIncomplete); assertTrue(pc.revisionHistoryNeedsBaseline(identity))
        }
        WindowsDeltaJournal(dir, protector).use { journal ->
            val pc = RecoveryController(journal); pc.observeTransport(true)
            assertTrue(pc.revisionHistoryNeedsBaseline(identity)); assertTrue(pc.revisionHistoryIncomplete)
            assertFailsWith<IllegalStateException> { pc.acceptGrant(grant(3), 0) }
            pc.acceptFreshHistoryBaseline(grant(3), 0)
            assertTrue(pc.canEdit(0)); assertFalse(pc.hasSnapshot)
            assertFalse(pc.revisionHistoryNeedsBaseline(identity)); assertTrue(pc.revisionHistoryIncomplete)
            assertEquals(3L, journal.revisionFloor(identity))
            pc.suspendSession()
        }
        WindowsDeltaJournal(dir, protector).use { journal ->
            val pc = RecoveryController(journal); pc.observeTransport(true)
            assertTrue(pc.revisionHistoryIncomplete)
            assertFailsWith<IllegalStateException> { pc.acceptGrant(grant(2), 0) }
            pc.acceptGrant(grant(3), 0); assertTrue(pc.canEdit(0)); pc.suspendSession()
        }
    }

    @Test fun quarantinePreservesEncryptedBytesAclAndPendingAndRequiresEachUnknownIdentityBaseline() = trial { dir, protector ->
        val damaged = byteArrayOf(1, 2, 3, 4)
        val edit = RecoveryEdit(PendingEdit(identity.device, 7, "op-pending", identity.incarnation), "SYNTHETIC-PENDING")
        WindowsDeltaJournal(dir, protector).use { journal ->
            journal.save(edit); journal.recordAppliedRevision(identity, 7)
            val pendingBytes = Files.readAllBytes(dir.resolve("pending.dpapi"))
            val floorFile = dir.resolve("revision-floors.dpapi")
            val acl = Files.getFileAttributeView(floorFile, java.nio.file.attribute.AclFileAttributeView::class.java).acl
            Files.write(floorFile, damaged)
            val pc = RecoveryController(journal)
            assertFails { pc.acceptGrant(grant(7), 0) }
            val preview = pc.prepareQuarantineRevisionHistory()
            assertEquals(HistoryRecoveryKind.QUARANTINE_ALL, preview.kind); assertNull(preview.identity)
            assertEquals(HistoryRecoveryResult.RECOVERED, pc.recoverRevisionHistory(preview.confirmation))
            assertFalse(Files.exists(floorFile)); assertEquals(edit, pc.pending)
            assertContentEquals(pendingBytes, Files.readAllBytes(dir.resolve("pending.dpapi")))
            val archives = Files.newDirectoryStream(dir, "revision-floors-quarantine-*.dpapi").use { it.toList() }
            assertEquals(1, archives.size); assertContentEquals(damaged, Files.readAllBytes(archives.single()))
            assertEquals(acl, Files.getFileAttributeView(archives.single(), java.nio.file.attribute.AclFileAttributeView::class.java).acl)
            pc.observeTransport(true)
            assertFails { pc.acceptFreshHistoryBaseline(grant(8), 0) } // pending compatibility still blocks
            assertEquals(edit, pc.pending); assertTrue(pc.revisionHistoryNeedsBaseline(identity))
            assertEquals(PendingDiscardResult.DISCARDED, pc.discardPending(pc.preparePendingDiscard()!!.confirmation))
            pc.acceptFreshHistoryBaseline(grant(8), 0); pc.suspendSession()
            assertTrue(pc.revisionHistoryNeedsBaseline(other)) // one restored identity cannot unblock all others
            assertFails { pc.acceptGrant(grant(1, other.incarnation), 0) }
            pc.acceptFreshHistoryBaseline(grant(1, other.incarnation), 0); pc.suspendSession()
        }
        WindowsDeltaJournal(dir, protector).use { journal ->
            assertEquals(8L, journal.revisionFloor(identity)); assertEquals(1L, journal.revisionFloor(other))
            assertTrue(journal.historyIsIncomplete())
            assertEquals(1, Files.newDirectoryStream(dir, "revision-floors-quarantine-*.dpapi").use { it.count() })
        }
    }

    @Test fun cancelledForeignReusedAndChangedPreviewsNeverForgetHistory() = trial { dir, protector ->
        WindowsDeltaJournal(dir, protector).use { journal ->
            journal.recordAppliedRevision(identity, 10)
            val pc = RecoveryController(journal)
            val first = pc.prepareForgetRevisionHistory(identity)!!
            val second = pc.prepareForgetRevisionHistory(identity)!!
            trial { foreignDir, foreignProtector ->
                WindowsDeltaJournal(foreignDir, foreignProtector).use { foreign ->
                    foreign.recordAppliedRevision(identity, 99)
                    foreign.prepareForgetHistory(identity)
                    assertEquals(HistoryRecoveryResult.INVALID_CONFIRMATION, foreign.recoverHistory(second.confirmation))
                    assertEquals(99L, foreign.revisionFloor(identity))
                }
            }
            assertEquals(HistoryRecoveryResult.INVALID_CONFIRMATION, pc.recoverRevisionHistory(first.confirmation))
            assertEquals(HistoryRecoveryResult.INVALID_CONFIRMATION, pc.recoverRevisionHistory(HistoryRecoveryConfirmation()))
            pc.cancelRevisionHistoryRecovery(second.confirmation)
            assertEquals(HistoryRecoveryResult.INVALID_CONFIRMATION, pc.recoverRevisionHistory(second.confirmation))
            val changed = pc.prepareForgetRevisionHistory(identity)!!
            journal.recordAppliedRevision(identity, 11)
            assertEquals(HistoryRecoveryResult.CHANGED, pc.recoverRevisionHistory(changed.confirmation))
            assertEquals(HistoryRecoveryResult.INVALID_CONFIRMATION, pc.recoverRevisionHistory(changed.confirmation))
            assertEquals(11L, journal.revisionFloor(identity)); assertFalse(journal.historyIsIncomplete())
            assertFails { pc.prepareQuarantineRevisionHistory() } // healthy history cannot be globally reset
            assertNull(pc.prepareForgetRevisionHistory(other))
        }
    }

    @Test fun fullHistoryRequiresChosenEntryRemovalAndNeverAutomaticallyEvicts() = trial { dir, protector ->
        WindowsDeltaJournal(dir, protector).use { journal ->
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeInt(0x45414631); output.writeInt(256)
                repeat(256) { output.writeUTF(identity.device); output.writeUTF("fake-dataset-$it"); output.writeLong(7) }
            }
            Files.write(dir.resolve("revision-floors.dpapi"), protector.transform(buffer.toByteArray(), true))
            val original = Files.readAllBytes(dir.resolve("revision-floors.dpapi"))
            val pc = RecoveryController(journal); pc.observeTransport(true)
            assertEquals(256, pc.revisionHistoryEntries().size)
            assertFails { pc.acceptGrant(grant(1, "new-instance"), 0) }
            assertFalse(pc.canEdit(0)); assertContentEquals(original, Files.readAllBytes(dir.resolve("revision-floors.dpapi")))
            val selected = DatasetIdentity(identity.device, "fake-dataset-0")
            assertEquals(HistoryRecoveryResult.RECOVERED,
                pc.recoverRevisionHistory(pc.prepareForgetRevisionHistory(selected)!!.confirmation))
            assertNull(journal.revisionFloor(selected)); assertEquals(255, pc.revisionHistoryEntries().size)
            assertFails { pc.acceptGrant(grant(1, "new-instance"), 0) }
            pc.acceptFreshHistoryBaseline(grant(1, "new-instance"), 0); pc.suspendSession()
            assertEquals(256, pc.revisionHistoryEntries().size)
            assertEquals(7L, journal.revisionFloor(DatasetIdentity(identity.device, "fake-dataset-1")))
        }
    }

    @Test fun baselineSaveFailureNeverEnablesEditingOrLosesRecoveryRequirement() = trial { dir, protector ->
        WindowsDeltaJournal(dir, protector).use { journal ->
            journal.recordAppliedRevision(identity, 10)
            val failing = object : RevisionHistoryRecoveryJournal by journal {
                override fun recordAppliedRevision(identity: DatasetIdentity, revision: Long) { error("Injected save failure") }
            }
            val pc = RecoveryController(failing); pc.observeTransport(true)
            assertEquals(HistoryRecoveryResult.RECOVERED,
                pc.recoverRevisionHistory(pc.prepareForgetRevisionHistory(identity)!!.confirmation))
            assertFails { pc.acceptFreshHistoryBaseline(grant(3), 0) }
            assertFalse(pc.canEdit(0)); assertFalse(pc.hasSnapshot)
            assertTrue(journal.historyRecoveryRequired(identity)); assertNull(journal.revisionFloor(identity))
        }
        WindowsDeltaJournal(dir, protector).use { journal -> assertTrue(journal.historyRecoveryRequired(identity)) }
    }

    @Test fun oversizedUnreadableHistoryCanBeQuarantinedWithoutLoadingAndChangedMetadataRejects() = trial { dir, protector ->
        WindowsDeltaJournal(dir, protector).use { journal ->
            val file = dir.resolve("revision-floors.dpapi")
            val oversized = ByteArray(131073) { 42 }
            Files.write(file, oversized)
            val pc = RecoveryController(journal); val preview = pc.prepareQuarantineRevisionHistory()
            Files.write(file, byteArrayOf(43), StandardOpenOption.APPEND)
            assertEquals(HistoryRecoveryResult.CHANGED, pc.recoverRevisionHistory(preview.confirmation))
            assertTrue(Files.exists(file)); assertFalse(journal.historyIsIncomplete())
            val changedBytes = Files.readAllBytes(file)
            assertEquals(HistoryRecoveryResult.RECOVERED,
                pc.recoverRevisionHistory(pc.prepareQuarantineRevisionHistory().confirmation))
            val archive = Files.newDirectoryStream(dir, "revision-floors-quarantine-*.dpapi").use { it.single() }
            assertContentEquals(changedBytes, Files.readAllBytes(archive))
            assertTrue(journal.historyRecoveryRequired(identity))
        }
    }

    @Test fun markerWriteFailureRetainsOriginalAndConsumesTokenWithoutQuarantine() = trial { dir, protector ->
        val obstruction = dir.resolve("history-incomplete.warning")
        WindowsDeltaJournal(dir, protector).use { journal ->
            val floorFile = dir.resolve("revision-floors.dpapi"); val damaged = byteArrayOf(9, 8, 7)
            Files.write(floorFile, damaged)
            val pc = RecoveryController(journal); val preview = pc.prepareQuarantineRevisionHistory()
            Files.createDirectory(obstruction); Files.write(obstruction.resolve("synthetic-obstruction"), byteArrayOf(1))
            try {
                assertFails { pc.recoverRevisionHistory(preview.confirmation) }
                assertFalse(pc.canEdit(0)); assertContentEquals(damaged, Files.readAllBytes(floorFile))
                assertEquals(HistoryRecoveryResult.INVALID_CONFIRMATION, pc.recoverRevisionHistory(preview.confirmation))
                assertEquals(0, Files.newDirectoryStream(dir, "revision-floors-quarantine-*.dpapi").use { it.count() })
            } finally {
                Files.delete(obstruction.resolve("synthetic-obstruction")); Files.delete(obstruction)
            }
            assertEquals(HistoryRecoveryResult.RECOVERED, pc.recoverRevisionHistory(pc.prepareQuarantineRevisionHistory().confirmation))
        }
    }
}
