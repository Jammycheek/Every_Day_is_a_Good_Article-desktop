package everyarticle.desktop.sync

import poc.DatasetIdentity
import poc.DeltaJournal
import poc.RecoveryEdit

/**
 * Non-persistent journal for tests and the simulated-phone demo on non-Windows machines.
 * It protects nothing across process exit; the Windows app always uses the DPAPI journal.
 */
class VolatileJournal : DeltaJournal {
    var edit: RecoveryEdit? = null
    val floors = HashMap<DatasetIdentity, Long>()
    val epochs = HashMap<DatasetIdentity, Long>()
    var failSave = false
    override fun load() = edit
    override fun save(edit: RecoveryEdit) { check(!failSave) { "Simulated disk failure" }; check(this.edit == null); this.edit = edit }
    override fun replace(edit: RecoveryEdit) { check(!failSave) { "Simulated disk failure" }; this.edit = edit }
    override fun clear() { edit = null }
    override fun revisionFloor(identity: DatasetIdentity) = floors[identity]
    override fun recordVerifiedRevision(identity: DatasetIdentity, revision: Long) { floors[identity] = maxOf(floors[identity] ?: 0, revision) }
    override fun verifiedEpoch(identity: DatasetIdentity) = epochs[identity]
    override fun recordVerifiedEpoch(identity: DatasetIdentity, epoch: Long) { epochs[identity] = maxOf(epochs[identity] ?: 0, epoch) }
    override fun recordVerifiedGrant(identity: DatasetIdentity, revision: Long, epoch: Long) {
        recordVerifiedRevision(identity, revision); recordVerifiedEpoch(identity, epoch)
    }
}
