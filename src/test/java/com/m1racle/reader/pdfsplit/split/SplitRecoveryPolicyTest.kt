package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitRecoveryPolicyTest {
    @Test fun onlyFailedChaptersAreRetried() {
        val verified = booleanArrayOf(true, true, false, true, false)
        assertEquals(listOf(2, 4), SplitRecoveryPolicy.failedIndices(verified))
    }

    @Test fun ordinaryFailureRetainsEveryFinishedChapter() {
        val verified = booleanArrayOf(true, true, false, false, true)
        assertEquals(setOf(0, 1, 4), SplitRecoveryPolicy.retainedIndices(verified, cancelledOrAborted = false))
    }

    @Test fun cancellationDoesNotRetainNewPartialRun() {
        val verified = booleanArrayOf(true, true, false, false)
        assertTrue(SplitRecoveryPolicy.retainedIndices(verified, cancelledOrAborted = true).isEmpty())
    }

    @Test fun allSucceededMeansNoRetryItems() {
        val verified = booleanArrayOf(true, true, true)
        assertTrue(SplitRecoveryPolicy.failedIndices(verified).isEmpty())
    }

    @Test fun noSuccessesMeansAllPartsRemainRetryable() {
        val verified = booleanArrayOf(false, false, false)
        assertEquals(listOf(0, 1, 2), SplitRecoveryPolicy.failedIndices(verified))
    }
}
