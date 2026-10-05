package yukams.app.background_locator_2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        try {
            BackgroundLocatorPlugin.registerAfterBoot(context)
        } catch (e: RuntimeException) {
            Log.e("BootBroadcastReceiver", "Unable to restart locator after boot", e)
        }
    }
}
