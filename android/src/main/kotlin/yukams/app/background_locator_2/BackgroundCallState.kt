package yukams.app.background_locator_2

internal class BackgroundCallState {
    private var completed = false
    private var timeoutReported = false

    @Synchronized
    fun complete(): Boolean {
        if (completed) return false
        completed = true
        return true
    }

    @Synchronized
    fun reportTimeout(): Boolean {
        if (completed || timeoutReported) return false
        timeoutReported = true
        return true
    }
}

internal class RestoreRetryBackoff {
    private var delay = 1000L

    fun nextDelay(): Long {
        val current = delay
        delay = (delay * 2).coerceAtMost(60000L)
        return current
    }

    fun reset() {
        delay = 1000L
    }
}
