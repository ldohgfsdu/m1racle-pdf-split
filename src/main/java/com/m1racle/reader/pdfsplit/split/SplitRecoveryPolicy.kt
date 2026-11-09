package com.m1racle.reader.pdfsplit.split

/** Pure partial-failure policy, shared by the split engine and its JVM tests. */
internal object SplitRecoveryPolicy {
    fun failedIndices(completed: BooleanArray): List<Int> =
        completed.indices.filter { !completed[it] }

    fun retainedIndices(completed: BooleanArray, cancelledOrAborted: Boolean): Set<Int> =
        if (cancelledOrAborted) emptySet()
        else completed.indices.filterTo(HashSet()) { completed[it] }
}
