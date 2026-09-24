package app.melogold.android.sync

/** At most this many tracks in one `playlist.items.add` (API §4.8). */
private const val MAX_ADD = 500

/** One change of a playlist's tracks, as the item ops of the server express it (API §4.8). */
sealed interface ItemChange {
    data class Remove(val videoId: String) : ItemChange

    /** New tracks as one block: right after [after], else right before [before], else at the end. */
    data class Add(val videoIds: List<String>, val after: String?, val before: String?) : ItemChange

    /** A track that changed its place: right after [after], else right before [before], else at the end. */
    data class Move(val videoId: String, val after: String?, val before: String?) : ItemChange
}

/**
 * The item ops that turn the playlist as the server had it ([before], in its order) into the one on this device
 * ([after]) (REWRITE §4.12a): the removed tracks, then, in the new order, the new tracks in blocks and the moved
 * ones, each placed right after its neighbour in the new order (right before the first track that stays, for those at
 * the start). The tracks on the longest run that kept its order stay where they are, so moving one track is one op.
 *
 * Only what this device changed is sent: a track another device added meanwhile is not in [before], and nothing here
 * removes or moves it.
 */
fun playlistItemChanges(before: List<String>, after: List<String>): List<ItemChange> {
    val changes = mutableListOf<ItemChange>()
    val afterSet = after.toHashSet()
    val beforeIndex = HashMap<String, Int>(before.size * 2)
    before.forEachIndexed { index, videoId -> beforeIndex.putIfAbsent(videoId, index) }

    beforeIndex.keys.sortedBy { beforeIndex.getValue(it) }.filter { it !in afterSet }.forEach { changes += ItemChange.Remove(it) }

    val kept = after.filter { it in beforeIndex }
    val staying = longestIncreasingRun(kept.map { beforeIndex.getValue(it) }).mapTo(HashSet()) { kept[it] }
    val firstStaying = after.firstOrNull { it in staying }

    var previous: String? = null
    val block = mutableListOf<String>()
    var blockAfter: String? = null

    fun flush() {
        var anchor = blockAfter
        block.chunked(MAX_ADD).forEach { chunk ->
            changes += ItemChange.Add(chunk, after = anchor, before = if (anchor == null) firstStaying else null)
            anchor = chunk.last()
        }
        block.clear()
    }

    for (videoId in after) {
        if (videoId !in beforeIndex) {
            if (block.isEmpty()) blockAfter = previous
            block += videoId
        } else {
            flush()
            if (videoId !in staying) {
                changes += ItemChange.Move(videoId, after = previous, before = if (previous == null) firstStaying else null)
            }
        }
        previous = videoId
    }
    flush()
    return changes
}

/** The indices of one longest strictly increasing subsequence of [values] (patience sorting, O(n log n)). */
internal fun longestIncreasingRun(values: List<Int>): List<Int> {
    if (values.isEmpty()) return emptyList()
    val tails = IntArray(values.size)
    val parent = IntArray(values.size) { -1 }
    var length = 0
    values.forEachIndexed { index, value ->
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[tails[middle]] < value) low = middle + 1 else high = middle
        }
        if (low > 0) parent[index] = tails[low - 1]
        tails[low] = index
        if (low == length) length++
    }
    val run = IntArray(length)
    var current = tails[length - 1]
    for (position in length - 1 downTo 0) {
        run[position] = current
        current = parent[current]
    }
    return run.toList()
}
