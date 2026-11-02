package yukams.app.background_locator_2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // QUICKBOOT_POWERON is sent instead of BOOT_COMPLETED on some OEMs
        // (Xiaomi, HTC); MY_PACKAGE_REPLACED resumes tracking after an app update
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        try {
            BackgroundLocatorPlugin.registerAfterBoot(context)
        } catch (e: Exception) {
            ErrorReporter.report(context, "boot", "BOOT_RESTART_FAILED", e)
        }
    }
}
