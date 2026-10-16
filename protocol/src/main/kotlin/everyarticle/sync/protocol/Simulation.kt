package everyarticle.sync.protocol

/**
 * In-memory implementations for tests and the desktop's simulated-phone mode. Synthetic data only;
 * the real phone uses its own database-backed stores.
 */
class InMemoryAuthorityStore(articles: List<ArticleText> = emptyList(), state: SyncState = SyncState.initial()) : AuthorityStore {
    private var committedState = state
    private val committedArticles = articles.associateBy { it.articleId }.toMutableMap()
    private val committedOperations = LinkedHashMap<String, OperationRecord>()
    /** Throws inside the next transaction after the given number of article writes (simulated crash/IO failure). */
    var failNextWrite = false
    /** Runs at the start of the next transaction, before any read (simulates waiting for the database). */
    var beforeNextTransaction: (() -> Unit)? = null

    @Synchronized override fun <T> transaction(block: (AuthorityTransaction) -> T): T {
        beforeNextTransaction?.let { beforeNextTransaction = null; it() }
        val tx = Tx(committedState, LinkedHashMap(committedArticles), LinkedHashMap(committedOperations))
        val result = block(tx)
        committedState = tx.state; committedArticles.clear(); committedArticles.putAll(tx.articles)
        committedOperations.clear(); committedOperations.putAll(tx.operations)
        return result
    }

    /** A local phone edit: must be called inside WriteGate.localWrite, like the real repository. */
    @Synchronized fun localEdit(articleId: Long, body: String, wallMillis: Long) = transaction { tx ->
        tx.replaceArticleText(articleId, tx.article(articleId)!!.title, body, wallMillis)
        tx.saveState(tx.state().let { it.copy(revision = it.revision + 1) })
    }

    /** Simulated backup restore: dataset replaced, new incarnation. */
    @Synchronized fun restore(articles: List<ArticleText>) = transaction { tx ->
        (tx as Tx).articles.clear(); tx.articles.putAll(articles.associateBy { it.articleId })
        tx.saveState(SyncState.replaced(tx.state()))
    }

    @Synchronized fun snapshotState() = committedState
    @Synchronized fun article(id: Long) = committedArticles[id]
    @Synchronized fun operationCount() = committedOperations.size

    private inner class Tx(var state: SyncState, val articles: MutableMap<Long, ArticleText>,
        val operations: LinkedHashMap<String, OperationRecord>) : AuthorityTransaction {
        override fun state() = state
        override fun saveState(state: SyncState) {
            require(state.revision >= this.state.revision && state.epoch >= this.state.epoch) { "Counters never decrease" }
            this.state = state
        }
        override fun operation(operationId: String) = operations[operationId]
        override fun insertOperation(record: OperationRecord) { check(operations.put(record.operationId, record) == null) }
        override fun pruneOperations(keepNewest: Int, appliedBeforeWallMillis: Long): Long? {
            val ordered = operations.values.sortedBy { it.resultingRevision }
            val removable = ordered.dropLast(keepNewest).filter { it.appliedAtWallMillis < appliedBeforeWallMillis }
            var highest: Long? = null
            for (record in ordered.dropLast(keepNewest)) {
                if (record !in removable) break // strictly ascending: stop at the first record that must stay
                operations.remove(record.operationId); highest = record.resultingRevision
            }
            return highest
        }
        override fun articles() = articles.values.sortedBy { it.articleId }
        override fun article(articleId: Long) = articles[articleId]
        override fun replaceArticleText(articleId: Long, title: String?, body: String, wallMillis: Long) {
            if (failNextWrite) { failNextWrite = false; throw IllegalStateException("Simulated write failure") }
            articles[articleId] = articles.getValue(articleId).copy(title = title, body = body, updatedAtMillis = wallMillis)
        }
    }
}

class InMemoryPeerStore : PeerStore {
    private val map = LinkedHashMap<String, PairedPeer>()
    @Synchronized override fun peer(peerId: String) = map[peerId]
    @Synchronized override fun peers() = map.values.toList()
    @Synchronized override fun save(peer: PairedPeer) { map[peer.peerId] = peer }
    @Synchronized override fun remove(peerId: String) { map.remove(peerId) }
}

class InMemoryReplayGuard(private val capacity: Int = 4096) : ReplayGuard {
    private val seen = LinkedHashSet<String>()
    @Synchronized override fun firstSeen(peerId: String, messageId: String): Boolean {
        val added = seen.add("$peerId/$messageId")
        while (seen.size > capacity) seen.remove(seen.first())
        return added
    }
}

class ManualClock(var elapsed: Long = 1_000, var wall: Long = 1_760_000_000_000) : AuthorityClock {
    override fun elapsedMillis() = elapsed
    override fun wallMillis() = wall
    fun advance(millis: Long) { elapsed += millis; wall += millis }
}
