package com.derricklee.ankidict

// Round-robins [lists] together, taking [chunkSize] items at a time from each list in turn, so
// every list gets a turn near the top instead of the first list's full length running out before
// the next list contributes anything.
fun <T> interleave(lists: List<List<T>>, chunkSize: Int = 1): List<T> {
    val queues = lists.map { ArrayDeque(it) }
    val result = mutableListOf<T>()
    var addedAny = true
    while (addedAny) {
        addedAny = false
        for (queue in queues) {
            repeat(chunkSize) {
                queue.removeFirstOrNull()?.let {
                    result.add(it)
                    addedAny = true
                }
            }
        }
    }
    return result
}
