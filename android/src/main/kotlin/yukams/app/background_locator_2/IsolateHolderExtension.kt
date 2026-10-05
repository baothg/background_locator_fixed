package yukams.app.background_locator_2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.LocationRequest
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import yukams.app.background_locator_2.IsolateHolderService.Companion.isServiceInitialized
import yukams.app.background_locator_2.provider.LocationRequestOptions
import java.lang.RuntimeException
import androidx.core.content.ContextCompat

internal fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

internal fun Context.hasBackgroundLocationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

internal fun IsolateHolderService.startLocatorService(context: Context) {
    // start synchronized block to prevent multiple service instant
    synchronized(IsolateHolderService::class.java) {
        this.context = context
        // resetting the background engine to avoid being stuck after an app crash
        if (!IsolateHolderService.hasPendingBackgroundCalls()) {
            IsolateHolderService.backgroundEngine?.destroy()
            IsolateHolderService.backgroundEngine = null
            isServiceInitialized = false
        } else {
            isServiceInitialized = true
        }
        try {
            if (context.hasLocationPermission() && IsolateHolderService.backgroundEngine == null) {
                // We need flutter engine to handle callback, so if it is not available we have to create a
                // Flutter engine without any view
                Log.e("IsolateHolderService", "startLocatorService: Start Flutter Engine")
                val engine = FlutterEngine(context)
                IsolateHolderService.backgroundEngine = engine

                val callbackHandle = context.getSharedPreferences(
                    Keys.SHARED_PREFERENCES_KEY,
                    Context.MODE_PRIVATE
                )
                    .getLong(Keys.CALLBACK_DISPATCHER_HANDLE_KEY, 0)
                val callbackInfo =
                    FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)

                if (callbackInfo == null) {
                    Log.e("IsolateHolderExtension", "Fatal: failed to find callback")
                    engine.destroy()
                    IsolateHolderService.backgroundEngine = null
                    return
                }

                val args = DartExecutor.DartCallback(
                    context.assets,
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    callbackInfo
                )
                engine.dartExecutor.executeDartCallback(args)
                isServiceInitialized = true
                Log.e("IsolateHolderExtension", "service initialized")
            }
        } catch (e: Exception) {
            Log.e("IsolateHolderExtension", "Unable to initialize background Flutter engine", e)
            IsolateHolderService.backgroundEngine?.destroy()
            IsolateHolderService.backgroundEngine = null
            isServiceInitialized = false
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
            IsolateHolderService.backgroundEngine?.destroy()
            IsolateHolderService.backgroundEngine = null
            isServiceInitialized = false
        }
    }

    IsolateHolderService.backgroundEngine?.let { engine ->
        backgroundChannel = MethodChannel(
            engine.dartExecutor.binaryMessenger,
            Keys.BACKGROUND_CHANNEL_ID
        )
        try {
            if (context.hasLocationPermission()) {
                backgroundChannel.setMethodCallHandler(this)
            }
        } catch (e: RuntimeException) {
            e.printStackTrace()
        }
    }
}

fun getLocationRequest(intent: Intent): LocationRequestOptions {
    val interval: Long = (intent.getIntExtra(Keys.SETTINGS_INTERVAL, 10) * 1000).toLong()
    val accuracyKey = intent.getIntExtra(Keys.SETTINGS_ACCURACY, 4)
    val accuracy = getAccuracy(accuracyKey)
    val distanceFilter = intent.getDoubleExtra(Keys.SETTINGS_DISTANCE_FILTER, 0.0)

    return LocationRequestOptions(interval, accuracy, distanceFilter.toFloat())
}

fun getAccuracy(key: Int): Int {
    return when (key) {
        0 -> LocationRequest.PRIORITY_NO_POWER
        1 -> LocationRequest.PRIORITY_LOW_POWER
        2 -> LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        3 -> LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        4 -> LocationRequest.PRIORITY_HIGH_ACCURACY
        else -> LocationRequest.PRIORITY_HIGH_ACCURACY
    }
}