package yukams.app.background_locator_2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

class BackgroundLocatorPlugin
    : MethodCallHandler, FlutterPlugin, PluginRegistry.NewIntentListener, ActivityAware {
    var context: Context? = null
    private var activity: Activity? = null
    private var channel: MethodChannel? = null

    companion object {
        private var pendingStops = 0

        private fun commandReceiver(context: Context, result: Result, onCompleted: () -> Unit): ResultReceiver {
            val handler = Handler(context.mainLooper)
            val state = BackgroundCallState()
            val timeout = Runnable {
                if (state.complete()) {
                    onCompleted()
                    result.error("SERVICE_COMMAND_TIMEOUT", "Service did not acknowledge the command", null)
                }
            }
            handler.postDelayed(timeout, 30000)
            return object : ResultReceiver(handler) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (!state.complete()) return
                    handler.removeCallbacks(timeout)
                    onCompleted()
                    if (resultCode == 0) {
                        result.success(true)
                    } else {
                        result.error("SERVICE_COMMAND_FAILED", resultData?.getString("message"), null)
                    }
                }
            }
        }

        private fun sendCommand(context: Context, intent: Intent, result: Result) {
            val stopping = intent.action == IsolateHolderService.ACTION_SHUTDOWN
            if (stopping) pendingStops++
            val receiver = commandReceiver(context, result) {
                if (stopping) pendingStops--
            }
            intent.putExtra(IsolateHolderService.COMMAND_RESULT, receiver)
            try {
                if (intent.action == IsolateHolderService.ACTION_SHUTDOWN) {
                    stopIsolateService(context, intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            } catch (e: Exception) {
                ErrorReporter.report(context, "service_command", "SERVICE_COMMAND_FAILED", e)
                receiver.send(1, Bundle().apply { putString("message", e.message) })
            }
        }

        @JvmStatic
        private fun registerLocator(
            context: Context,
            args: Map<Any, Any>,
            result: Result?
        ) {
            val settings = args[Keys.ARG_SETTINGS] as? Map<*, *>
            if (settings == null) {
                result?.error("INVALID_SETTINGS", "Missing location settings.", null)
                return
            }

            if (!context.hasLocationPermission()) {
                result?.error(
                    "LOCATION_PERMISSION_DENIED",
                    "registerLocationUpdate requires location permission.",
                    null
                )
                return
            }

            val callbackHandle = (args[Keys.ARG_CALLBACK] as Number).toLong()
            PreferencesManager.setCallbackHandle(
                context,
                Keys.CALLBACK_HANDLE_KEY,
                callbackHandle
            )
            val notificationCallback =
                (args[Keys.ARG_NOTIFICATION_CALLBACK] as? Number)?.toLong()
            PreferencesManager.setCallbackHandle(
                context,
                Keys.NOTIFICATION_CALLBACK_HANDLE_KEY,
                notificationCallback
            )

            val initCallback = (args[Keys.ARG_INIT_CALLBACK] as? Number)?.toLong()
            PreferencesManager.setCallbackHandle(
                context,
                Keys.INIT_CALLBACK_HANDLE_KEY,
                initCallback
            )
            PreferencesManager.setDataCallback(
                context,
                Keys.INIT_DATA_CALLBACK_KEY,
                args[Keys.ARG_INIT_DATA_CALLBACK] as? Map<*, *>
            )
            val disposeCallback = (args[Keys.ARG_DISPOSE_CALLBACK] as? Number)?.toLong()
            PreferencesManager.setCallbackHandle(
                context,
                Keys.DISPOSE_CALLBACK_HANDLE_KEY,
                disposeCallback
            )

            PreferencesManager.saveSettings(context, args)

            // Service is already running: apply the new callbacks and settings
            // through ACTION_START, and acknowledge only after they are applied
            PreferencesManager.setTrackingEnabled(context, true)
            val intent = createStartIntent(context, settings)
            // The START intent may be dispatched before the service is created;
            // its result comes from the service rather than a one-second guess.
            // A command timeout reports failure without disabling tracking or
            // shutting down a newer registration that may already be running.
            if (result != null) {
                sendCommand(context, intent, result)
            } else {
                startIsolateService(context, settings)
            }
        }

        @JvmStatic
        private fun startIsolateService(context: Context, settings: Map<*, *>) {
            Log.e("BackgroundLocatorPlugin", "startIsolateService")
            ContextCompat.startForegroundService(
                context,
                createStartIntent(context, settings)
            )
        }

        internal fun createStartIntent(context: Context, settings: Map<*, *>): Intent {
            return Intent(context, IsolateHolderService::class.java).apply {
                action = IsolateHolderService.ACTION_START
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME] as? String
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE] as? String
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_MSG,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_MSG] as? String
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG] as? String
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_ICON,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_ICON] as? String
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR,
                    (settings[Keys.SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR] as? Number)?.toLong() ?: 0L
                )
                putExtra(
                    Keys.SETTINGS_INTERVAL,
                    (settings[Keys.SETTINGS_INTERVAL] as? Number)?.toInt() ?: 5
                )
                putExtra(
                    Keys.SETTINGS_ACCURACY,
                    (settings[Keys.SETTINGS_ACCURACY] as? Number)?.toInt() ?: 4
                )
                putExtra(
                    Keys.SETTINGS_DISTANCE_FILTER,
                    (settings[Keys.SETTINGS_DISTANCE_FILTER] as? Number)?.toDouble() ?: 0.0
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME,
                    (settings[Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME] as? Number)?.toInt() ?: 60
                )
                if (PreferencesManager.getCallbackHandle(context, Keys.INIT_CALLBACK_HANDLE_KEY) != null) {
                    putExtra(Keys.SETTINGS_INIT_PLUGGABLE, true)
                }
                if (PreferencesManager.getCallbackHandle(context, Keys.DISPOSE_CALLBACK_HANDLE_KEY) != null) {
                    putExtra(Keys.SETTINGS_DISPOSABLE_PLUGGABLE, true)
                }
            }
        }

        @JvmStatic
        private fun stopIsolateService(context: Context, intent: Intent) {
            Log.d("BackgroundLocatorPlugin", "stopIsolateService => Shutting down locator plugin")
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: RuntimeException) {
                // FGS-start is restricted on Android 12+ when the app is in the
                // background and no service is up yet; a shutdown does not need
                // foreground, so a plain startService is a safe fallback
                ErrorReporter.report(context, "unregister", "SHUTDOWN_FGS_DENIED", e)
                context.startService(intent)
            }
        }

        @JvmStatic
        private fun initializeService(context: Context, args: Map<Any, Any>) {
            val callbackHandle =
                (args[Keys.ARG_CALLBACK_DISPATCHER] as Number).toLong()
            setCallbackDispatcherHandle(context, callbackHandle)
        }

        @JvmStatic
        private fun unRegisterPlugin(context: Context, result: Result?) {
            val wasTracking = PreferencesManager.isTrackingEnabled(context)
            PreferencesManager.setTrackingEnabled(context, false)
            if (!IsolateHolderService.isServiceCreated && !IsolateHolderService.isServiceRunning && !wasTracking && pendingStops == 0) {
                // The service is not running and no start is pending
                Log.d("BackgroundLocatorPlugin", "Locator service is not running, nothing to stop")
                result?.success(true)
                return
            }

            val intent = Intent(context, IsolateHolderService::class.java).apply {
                action = IsolateHolderService.ACTION_SHUTDOWN
            }

            // Wait for actual native shutdown instead of guessing when the service detaches.
            // Dart dispose callbacks may continue on the retained background engine.
            // The command deadline reports failure; it never fabricates a successful stop.
            if (result != null) sendCommand(context, intent, result) else stopIsolateService(context, intent)
        }

        @JvmStatic
        private fun isServiceRunning(result: Result?) {
            result?.success(IsolateHolderService.isServiceRunning)
        }

        @JvmStatic
        private fun updateNotificationText(context: Context, args: Map<Any, Any>) {
            val intent = Intent(context, IsolateHolderService::class.java)
            intent.action = IsolateHolderService.ACTION_UPDATE_NOTIFICATION
            if (args.containsKey(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE)) {
                intent.putExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE,
                        args[Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE] as String)
            }
            if (args.containsKey(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG)) {
                intent.putExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG,
                        args[Keys.SETTINGS_ANDROID_NOTIFICATION_MSG] as String)
            }
            if (args.containsKey(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG)) {
                intent.putExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG,
                        args[Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG] as String)
            }

            ContextCompat.startForegroundService(context, intent)
        }

        @JvmStatic
        private fun setCallbackDispatcherHandle(context: Context, handle: Long) {
            context.getSharedPreferences(Keys.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(Keys.CALLBACK_DISPATCHER_HANDLE_KEY, handle)
                    .apply()
        }

        @JvmStatic
        fun registerAfterBoot(context: Context) {
            val args = PreferencesManager.getSettings(context)
            val callbackDispatcher =
                (args[Keys.ARG_CALLBACK_DISPATCHER] as? Number)?.toLong() ?: 0L
            if (!PreferencesManager.isTrackingEnabled(context) || callbackDispatcher == 0L ||
                !context.hasLocationPermission() || !context.hasBackgroundLocationPermission()
            ) {
                return
            }

            initializeService(context, args)
            val settings = args[Keys.ARG_SETTINGS] as? Map<*, *> ?: return
            try {
                startIsolateService(context, settings)
            } catch (e: Exception) {
                ErrorReporter.report(context, "boot", "BOOT_RESTART_FAILED", e)
            }
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val currentContext = context
        if (currentContext == null) {
            result.error("PLUGIN_NOT_READY", "The plugin is not attached to an engine.", null)
            return
        }

        try {
            when (call.method) {
                Keys.METHOD_PLUGIN_INITIALIZE_SERVICE -> {
                    val args = call.arguments<Map<Any, Any>>()
                        ?: throw IllegalArgumentException("Missing initialization arguments")
                    PreferencesManager.saveCallbackDispatcher(currentContext, args)
                    initializeService(currentContext, args)
                    result.success(true)
                }
                Keys.METHOD_PLUGIN_REGISTER_LOCATION_UPDATE -> {
                    val args = call.arguments<Map<Any, Any>>()
                        ?: throw IllegalArgumentException("Missing location settings")
                    registerLocator(currentContext, args, result)
                }
                Keys.METHOD_PLUGIN_UN_REGISTER_LOCATION_UPDATE -> {
                    unRegisterPlugin(currentContext, result)
                }
                Keys.METHOD_PLUGIN_IS_REGISTER_LOCATION_UPDATE,
                Keys.METHOD_PLUGIN_IS_SERVICE_RUNNING -> isServiceRunning(result)
                Keys.METHOD_PLUGIN_UPDATE_NOTIFICATION -> {
                    if (!IsolateHolderService.isServiceRunning) {
                        result.success(false)
                    } else {
                        val args = call.arguments<Map<Any, Any>>()
                            ?: throw IllegalArgumentException("Missing notification settings")
                        updateNotificationText(currentContext, args)
                        result.success(true)
                    }
                }
                Keys.METHOD_PLUGIN_SET_ERROR_CALLBACK -> {
                    val handle = (call.arguments as? Map<*, *>)
                        ?.get(Keys.ARG_ERROR_CALLBACK) as? Number
                    PreferencesManager.setCallbackHandle(
                        currentContext,
                        Keys.ERROR_CALLBACK_HANDLE_KEY,
                        handle?.toLong()
                    )
                    // A reporter was just registered while the engine is up:
                    // deliver anything queued in the meantime
                    if (handle != null && IsolateHolderService.isDispatcherReady) {
                        ErrorReporter.flushQueue(currentContext)
                    }
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            ErrorReporter.report(currentContext, "method_call", call.method, e)
            result.error("BACKGROUND_LOCATOR_ERROR", e.message, null)
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        context = null
    }

    private fun onAttachedToEngine(context: Context, messenger: BinaryMessenger) {
        this.context = context

        channel = MethodChannel(messenger, Keys.CHANNEL_ID)
        channel?.setMethodCallHandler(this)
    }

    override fun onNewIntent(intent: Intent): Boolean {
        if (intent.action != Keys.NOTIFICATION_ACTION) {
            // this is not our notification
            return false
        }

        if (activity == null) {
            return true
        }
        val currentContext = context ?: return true
        val notificationCallback = PreferencesManager.getCallbackHandle(
            currentContext,
            Keys.NOTIFICATION_CALLBACK_HANDLE_KEY
        )
        if (notificationCallback != null) {
            IsolateHolderService.invokeBackgroundMethod(
                currentContext,
                Keys.BCM_NOTIFICATION_CLICK,
                hashMapOf(Keys.ARG_NOTIFICATION_CALLBACK to notificationCallback)
            )
        }
        return true
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }


}
