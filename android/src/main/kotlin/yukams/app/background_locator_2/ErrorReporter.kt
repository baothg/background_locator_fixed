package yukams.app.background_locator_2

import android.content.Context
import android.util.Log

/**
 * Single funnel for every caught plugin exception. Delivers to the app's
 * error callback over the background channel when the engine is alive,
 * otherwise persists to a queue that is drained when the dispatcher starts.
 * Never throws and never re-reports itself.
 */
internal object ErrorReporter {
    private const val TAG = "ErrorReporter"
    private const val DEDUPE_WINDOW_MS = 60_000L

    private var lastReportCode: String? = null
    private var lastReportAt = 0L

    fun report(context: Context, source: String, code: String, throwable: Throwable) {
        report(context, source, code, throwable.message, throwable.stackTraceToString())
    }

    fun report(
        context: Context,
        source: String,
        code: String,
        message: String?,
        stack: String? = null
    ) {
        try {
            Log.e(TAG, "[$source] $code: $message")
            // Suppress repeats of the same code so a recurring failure cannot
            // flood the reporter or the persisted queue
            val now = System.currentTimeMillis()
            val isDuplicate = synchronized(this) {
                val dup = code == lastReportCode && now - lastReportAt < DEDUPE_WINDOW_MS
                if (!dup) {
                    lastReportCode = code
                    lastReportAt = now
                }
                dup
            }
            if (isDuplicate) {
                return
            }
            val entry = hashMapOf<Any, Any>(
                "source" to source,
                "code" to code,
                "message" to (message ?: ""),
                "stack" to (stack ?: ""),
                "timestamp" to System.currentTimeMillis()
            )
            val handle = PreferencesManager.getCallbackHandle(context, Keys.ERROR_CALLBACK_HANDLE_KEY)
            // Only send when the dispatcher actually registered its handler;
            // an engine that exists but is still warming up would swallow the call
            if (handle == null || IsolateHolderService.backgroundEngine == null ||
                !IsolateHolderService.isDispatcherReady
            ) {
                PreferencesManager.appendError(context, entry)
                return
            }
            IsolateHolderService.invokeBackgroundMethod(
                context,
                Keys.BCM_ERROR,
                hashMapOf(
                    Keys.ARG_ERROR_CALLBACK to handle,
                    Keys.ARG_ERROR to entry
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to report error", e)
        }
    }

    fun flushQueue(context: Context) {
        try {
            val handle = PreferencesManager.getCallbackHandle(context, Keys.ERROR_CALLBACK_HANDLE_KEY)
                ?: return
            PreferencesManager.drainErrors(context).forEach { entry ->
                IsolateHolderService.invokeBackgroundMethod(
                    context,
                    Keys.BCM_ERROR,
                    hashMapOf(
                        Keys.ARG_ERROR_CALLBACK to handle,
                        Keys.ARG_ERROR to entry
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flush error queue", e)
        }
    }
}
