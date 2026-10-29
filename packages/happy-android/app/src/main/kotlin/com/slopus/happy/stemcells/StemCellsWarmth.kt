package com.slopus.happy.stemcells

/**
 * Which conversations a phone reads ahead and keeps, so opening one shows
 * its transcript at once: desktop's read-ahead (`connectHappyAgent.ts`
 * `recentAgents`, `preloadRecentSessions`, `prefetchSession`,
 * `releaseSession`) and its retention (`happyAgentConversationRetention.ts`),
 * with the signals a phone's sidebar has. happy-ios `StemCellsWarmth`.
 *
 * Desktop ranks every active agent the sidebar can show, running ones
 * first, then the most recently updated, and reads the top 12 one at a
 * time once nothing else is loading. The phone has no agent `updatedAt` in
 * its rows, and shows a dozen of a hundred at a time, so what stands for
 * "recently updated" here is what a thumb can reach: the rows on screen,
 * top first, then news the person hasn't read, then the conversations they
 * last left, then the rest in the sidebar's order.
 */
object StemCellsWarmth {
    object Limits {
        /** `PRELOADED_SESSIONS`: read ahead from the ranking. */
        const val PRELOADED = 12
        /** `PREFETCHED_SESSION_LIMIT`: read on a finger's intent and never opened, newest kept. */
        const val PREFETCHED = 6
        /** `RELEASED_SESSION_LIMIT`: opened and left, kept for a quick return. */
        const val RELEASED = 16
        /** Data Saver, battery saver or a hot phone: only the top few. */
        const val CONSTRAINED = 3
        /** `PRELOAD_DELAY_MS`: after the sidebar lands, so its first paint and an open go first. */
        const val DELAY_MS = 1_500L
        /** `PRELOAD_BUSY_WAIT_MS`: how often a read-ahead looks again while a conversation someone opened is still loading. */
        const val BUSY_WAIT_MS = 100L
    }

    /** One conversation the sidebar can open. */
    data class Candidate(
        val agentId: String,
        /** Working, waiting or asking: desktop's `status !== "idle"`. */
        val running: Boolean,
        val unread: Boolean,
    )

    /** The sidebar's conversations, once each, in its order: every row that opens one, folded away or not. */
    fun candidates(rows: List<StemCellsRow>): List<Candidate> {
        val seen = HashSet<String>()
        val found = ArrayList<Candidate>()
        for (row in rows) {
            if (row.kind != StemCellsRow.Kind.Space && row.kind != StemCellsRow.Kind.Session) continue
            if (row.targetKind == "surface") continue
            val agentId = row.agentId ?: continue
            if (!seen.add(agentId)) continue
            found += Candidate(agentId, row.status != StemCellsRow.Status.None, row.unread)
        }
        return found
    }

    /**
     * The `limit` most likely opened next, most likely first. `left` are
     * the conversations the person opened and left, the last left first;
     * `visible` those whose rows are on screen.
     */
    fun ranked(candidates: List<Candidate>, left: List<String>, visible: Set<String>, limit: Int): List<String> {
        val recency = HashMap<String, Int>()
        left.forEachIndexed { index, id -> recency.putIfAbsent(id, index) }
        val order = candidates.withIndex().sortedWith { a, b ->
            val l = a.value
            val r = b.value
            if (l.running != r.running) return@sortedWith if (l.running) -1 else 1
            val lv = l.agentId in visible
            val rv = r.agentId in visible
            if (lv != rv) return@sortedWith if (lv) -1 else 1
            // On screen, top first, as the eye and thumb go.
            if (lv) return@sortedWith a.index.compareTo(b.index)
            if (l.unread != r.unread) return@sortedWith if (l.unread) -1 else 1
            val ll = recency[l.agentId]
            val rl = recency[r.agentId]
            if (ll != rl) {
                if (ll == null) return@sortedWith 1
                if (rl == null) return@sortedWith -1
                return@sortedWith ll.compareTo(rl)
            }
            a.index.compareTo(b.index)
        }
        return order.take(maxOf(0, limit)).map { it.value.agentId }
    }

    /**
     * How many kept conversations also keep their transcripts laid out,
     * ready to draw: as many as are read ahead, so every row on screen
     * opens drawn. Desktop sizes what it keeps rendered by memory, 4GB
     * assumed where unknown; a phone with less lays out one per gigabyte,
     * at least two.
     */
    fun laidOut(physicalMemory: Long): Int {
        val gigabytes = (physicalMemory / 1_000_000_000L).toInt()
        return if (gigabytes >= 4) Limits.PRELOADED else maxOf(2, gigabytes)
    }

    /**
     * A conversation read and kept: on screen (`held`), or when it was
     * last left and last reached for, in a count that only grows.
     */
    data class Kept(
        val agentId: String,
        val held: Boolean = false,
        /** Opened and left; null if never opened. */
        val left: Int? = null,
        /** Read on a finger's intent; null if never. */
        val intent: Int? = null,
    )

    /**
     * What stays of `kept`: what is on screen and what the ranking wants,
     * the `released` last left (`releaseSession`), and the `prefetched`
     * last reached for and never opened (`prefetchSession`). The rest goes.
     */
    fun retained(kept: List<Kept>, wanted: List<String>, released: Int = Limits.RELEASED, prefetched: Int = Limits.PREFETCHED): Set<String> {
        val stays = wanted.toHashSet().apply { retainAll(kept.map { it.agentId }.toSet()) }
        for (one in kept) if (one.held) stays += one.agentId
        val left = kept.filter { it.left != null }.sortedByDescending { it.left!! }.take(maxOf(0, released))
        val reached = kept.filter { it.left == null && it.intent != null }.sortedByDescending { it.intent!! }.take(maxOf(0, prefetched))
        for (one in left) stays += one.agentId
        for (one in reached) stays += one.agentId
        return stays
    }

    /**
     * Which kept conversations also keep their rows laid out, `budget` of
     * them: the last reached for first, as a finger on a row is the
     * likeliest open, then the one just left, for a way back, then the
     * ranking's order, then the rest left.
     */
    fun planned(kept: List<Kept>, ranked: List<String>, budget: Int): List<String> {
        val known = kept.map { it.agentId }.toSet()
        val reached = kept.filter { it.intent != null && !it.held }.sortedByDescending { it.intent!! }.map { it.agentId }
        val left = kept.filter { it.left != null && !it.held }.sortedByDescending { it.left!! }.map { it.agentId }
        val seen = HashSet<String>()
        val order = ArrayList<String>()
        for (id in reached + left.take(1) + ranked + left) {
            if (id !in known || !seen.add(id)) continue
            if (kept.firstOrNull { it.agentId == id }?.held == true) continue
            order += id
        }
        return order.take(maxOf(0, budget))
    }
}
