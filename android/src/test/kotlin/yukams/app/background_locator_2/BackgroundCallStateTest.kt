package yukams.app.background_locator_2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundCallStateTest {
    @Test
    fun completedCallbackDoesNotReportTimeout() {
        val state = BackgroundCallState()
        assertTrue(state.complete())
        assertFalse(state.reportTimeout())
        assertFalse(state.complete())
    }

    @Test
    fun timeoutDoesNotCompleteRunningCallback() {
        val state = BackgroundCallState()
        var pending = 1
        assertTrue(state.reportTimeout())
        assertEquals(1, pending)
        assertEquals(0, if (state.complete()) pending - 1 else pending)
        assertEquals(1, pending)
        pending--
        assertFalse(state.complete())
    }

    @Test
    fun commandCanOnlyReplyOnceWhetherAckOrDeadlineWins() {
        for (timeoutFirst in listOf(true, false)) {
            val state = BackgroundCallState()
            var replies = 0
            if (state.complete()) replies++
            if (state.reportTimeout()) replies++
            assertEquals(1, replies)
        }
    }

    @Test
    fun restoreBackoffIsExponentialAndCapped() {
        val backoff = RestoreRetryBackoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L),
            List(7) { backoff.nextDelay() })
        repeat(100) { assertEquals(60000L, backoff.nextDelay()) }
        backoff.reset()
        assertEquals(1000L, backoff.nextDelay())
    }
}
