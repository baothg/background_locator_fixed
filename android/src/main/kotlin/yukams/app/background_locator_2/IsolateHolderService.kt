package yukams.app.background_locator_2

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import yukams.app.background_locator_2.pluggables.DisposePluggable
import yukams.app.background_locator_2.pluggables.InitPluggable
import yukams.app.background_locator_2.pluggables.Pluggable
import yukams.app.background_locator_2.provider.*
import java.util.HashMap

class IsolateHolderService : MethodChannel.MethodCallHandler, LocationUpdateListener, Service() {
    companion object {
        @JvmStatic
        val ACTION_SHUTDOWN = "SHUTDOWN"

        @JvmStatic
        val ACTION_START = "START"

        @JvmStatic
        val ACTION_UPDATE_NOTIFICATION = "UPDATE_NOTIFICATION"

        @JvmStatic
        private val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"

        @JvmStatic
        var backgroundEngine: FlutterEngine? = null

        @JvmStatic
        private val notificationId = 2038471

        @JvmStatic
        @Volatile
        var isServiceRunning = false

        @JvmStatic
        var isServiceInitialized = false

        @JvmStatic
        @Volatile
        var isDispatcherReady = false

        fun getBinaryMessenger(context: Context?): BinaryMessenger? {
            val messenger = backgroundEngine?.dartExecutor?.binaryMessenger
            return messenger
                ?: if (context != null) {
                    backgroundEngine = FlutterEngine(context)
                    backgroundEngine?.dartExecutor?.binaryMessenger
                } else {
                    messenger
                }
        }

        private var pendingBackgroundCalls = 0

        internal fun hasPendingBackgroundCalls(): Boolean =
            synchronized(IsolateHolderService::class.java) { pendingBackgroundCalls > 0 }

        fun invokeBackgroundMethod(
            context: Context,
            method: String,
            arguments: Any?,
            dispatcherRetries: Int = 20
        ) {
            // Messages sent before the Dart dispatcher registers its handler get
            // a notImplemented reply and would be silently dropped — retry
            // briefly instead, so a fresh engine cannot swallow BCM_INIT or the
            // first location updates after a process-kill restart
            if (!isDispatcherReady && dispatcherRetries > 0) {
                Handler(context.mainLooper).postDelayed({
                    invokeBackgroundMethod(context, method, arguments, dispatcherRetries - 1)
                }, 500)
                return
            }
            if (!isDispatcherReady) {
                ErrorReporter.report(
                    context, "channel", "DISPATCHER_NOT_READY",
                    "Dropped $method: dispatcher never became ready", null
                )
                return
            }
            val engine = synchronized(IsolateHolderService::class.java) {
                val currentEngine = backgroundEngine ?: return
                pendingBackgroundCalls++
                currentEngine
            }
            val channel = MethodChannel(
                engine.dartExecutor.binaryMessenger,
                Keys.BACKGROUND_CHANNEL_ID
            )
            val handler = Handler(context.mainLooper)
            val response = object : MethodChannel.Result {
                private var completed = false
                val timeout = Runnable {
                    error("BACKGROUND_LOCATOR_TIMEOUT", "Background callback timed out: $method", null)
                }

                private fun complete(): Boolean {
                    val isFirstCompletion = synchronized(this) {
                        if (completed) {
                            false
                        } else {
                            completed = true
                            true
                        }
                    }
                    if (!isFirstCompletion) {
                        return false
                    }
                    handler.removeCallbacks(timeout)
                    val shouldDestroy = synchronized(IsolateHolderService::class.java) {
                        pendingBackgroundCalls = (pendingBackgroundCalls - 1).coerceAtLeast(0)
                        !isServiceRunning && pendingBackgroundCalls == 0 &&
                            backgroundEngine === engine
                    }
                    if (shouldDestroy) {
                        scheduleBackgroundEngineDestroy(engine)
                    }
                    return true
                }

                override fun success(result: Any?) {
                    complete()
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    if (complete() && method != Keys.BCM_ERROR) {
                        ErrorReporter.report(
                            context, "dart_callback", errorCode, errorMessage,
                            errorDetails?.toString()
                        )
                    }
                }

                override fun notImplemented() {
                    complete()
                }
            }
            // Force-complete if the Dart side never replies, so pendingBackgroundCalls
            // cannot stay stuck and block engine cleanup forever
            handler.postDelayed(response.timeout, 10000)
            handler.post {
                try {
                    channel.invokeMethod(method, arguments, response)
                } catch (e: Exception) {
                    if (method != Keys.BCM_ERROR) {
                        ErrorReporter.report(context, "channel", "INVOKE_FAILED", e)
                    }
                    response.error("BACKGROUND_LOCATOR_ERROR", e.message, null)
                }
            }
        }

        private fun scheduleBackgroundEngineDestroy(engine: FlutterEngine) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val shouldDestroy = synchronized(IsolateHolderService::class.java) {
                        if (isServiceRunning || pendingBackgroundCalls > 0 || backgroundEngine !== engine) {
                            false
                        } else {
                            backgroundEngine = null
                            isServiceInitialized = false
                            isDispatcherReady = false
                            true
                        }
                    }
                    if (shouldDestroy) {
                        engine.destroy()
                    }
                } catch (e: Exception) {
                    Log.e("IsolateHolderService", "Unable to destroy background engine", e)
                }
            }, 1000)
        }
    }

    private var notificationChannelName = "Flutter Locator Plugin"
    private var notificationTitle = "Start Location Tracking"
    private var notificationMsg = "Track location in background"
    private var notificationBigMsg =
        "Background location is on to keep the app up-tp-date with your location. This is required for main features to work properly when the app is not running."
    private var notificationIconColor = 0
    private var icon = android.R.drawable.ic_menu_mylocation
    private var wakeLockTime = 60 * 60 * 1000L // 1 hour default wake lock time
    private var wakeLock: PowerManager.WakeLock? = null
    private var locatorClient: BLLocationProvider? = null
    private var locationRequest: LocationRequestOptions? = null
    private var locationErrorRetries = 0
    internal lateinit var backgroundChannel: MethodChannel
    internal var context: Context? = null
    private var pluggables: ArrayList<Pluggable> = ArrayList()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(notificationId, getNotification())
        } catch (e: Exception) {
            ErrorReporter.report(this, "service_create", "START_FOREGROUND_FAILED", e)
        }
        try {
            startLocatorService(this)
        } catch (e: Exception) {
            ErrorReporter.report(this, "service_create", "ENGINE_INIT_FAILED", e)
        }
    }

    private fun start() {
        acquireWakeLock()

        // Starting Service as foreground with a notification prevent service from closing
        val notification = getNotification()
        startForeground(notificationId, notification)

        pluggables.forEach {
            context?.let { it1 -> it.onServiceStart(it1) }
        }
    }

    private fun getNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Notification channel is available in Android O and up
            val channel = NotificationChannel(
                Keys.CHANNEL_ID, notificationChannelName,
                NotificationManager.IMPORTANCE_LOW
            )

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val mainActivityClass = getMainActivityClass(this)
        val intent = mainActivityClass?.let { Intent(this, it) }
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent()
        intent.action = Keys.NOTIFICATION_ACTION

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, Keys.CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationMsg)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notificationBigMsg)
            )
            .setSmallIcon(icon)
            .setColor(notificationIconColor)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true) // so when data is updated don't make sound and alert in android 8.0+
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val commandIntent = try {
            intent ?: restoreStartIntent()
        } catch (e: Exception) {
            ErrorReporter.report(this, "service_restore", "RESTORE_FAILED", e)
            null
        }
        Log.d("IsolateHolderService", "onStartCommand => intent.action : ${commandIntent?.action}")
        if (commandIntent == null) {
            isServiceRunning = false
            PreferencesManager.setTrackingEnabled(this, false)
            PreferencesManager.setSessionActive(this, false)
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            when (commandIntent.action) {
                ACTION_SHUTDOWN -> shutdownHolderService()
                ACTION_START -> {
                    if (!hasLocationPermission()) {
                        throw SecurityException("Location permission was revoked")
                    }
                    if (!isServiceInitialized) {
                        throw IllegalStateException("Call BackgroundLocator.initialize() before starting location updates")
                    }
                    if (!isServiceRunning) {
                        startHolderService(commandIntent)
                        isServiceRunning = true
                    }
                }
                ACTION_UPDATE_NOTIFICATION -> {
                    if (isServiceRunning) {
                        updateNotification(commandIntent)
                    } else {
                        stopForeground(true)
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                }
                else -> {
                    stopForeground(true)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
        } catch (e: Exception) {
            ErrorReporter.report(this, "service_command", "COMMAND_FAILED", e)
            isServiceRunning = false
            PreferencesManager.setTrackingEnabled(this, false)
            PreferencesManager.setSessionActive(this, false)
            stopLocationResources()
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun restoreStartIntent(): Intent? {
        if (!PreferencesManager.isTrackingEnabled(this) || !hasLocationPermission() ||
            !hasBackgroundLocationPermission()
        ) {
            return null
        }
        val settings = PreferencesManager.getSettings(this)[Keys.ARG_SETTINGS] as? Map<*, *>
            ?: return null
        return BackgroundLocatorPlugin.createStartIntent(this, settings)
    }

    private fun startHolderService(intent: Intent) {
        Log.d("IsolateHolderService", "startHolderService")
        // If a previous session never got a graceful shutdown, the process was
        // killed: flag it so the init callback can report wasKilled to Dart
        PreferencesManager.setWasKilled(this, PreferencesManager.isSessionActive(this))
        PreferencesManager.setSessionActive(this, true)
        notificationChannelName =
            intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME)
                ?: notificationChannelName
        notificationTitle =
            intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE) ?: notificationTitle
        notificationMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG) ?: notificationMsg
        notificationBigMsg =
            intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG) ?: notificationBigMsg
        val iconName = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_ICON)
            ?.takeIf { it.isNotBlank() } ?: "ic_launcher"
        val configuredIcon = resources.getIdentifier(iconName, "mipmap", packageName)
        icon = if (configuredIcon != 0) configuredIcon else android.R.drawable.ic_menu_mylocation
        notificationIconColor =
            intent.getLongExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR, 0).toInt()
        wakeLockTime = intent.getIntExtra(Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME, 60)
            .coerceAtLeast(1) * 60 * 1000L

        startForeground(notificationId, getNotification())
        val request = getLocationRequest(intent)
        locationRequest = request
        locationErrorRetries = 0
        locatorClient = getLocationClient(this)
        locatorClient?.requestLocationUpdates(request)

        pluggables.clear()
        if (intent.hasExtra(Keys.SETTINGS_INIT_PLUGGABLE)) {
            pluggables.add(InitPluggable())
        }
        if (intent.hasExtra(Keys.SETTINGS_DISPOSABLE_PLUGGABLE)) {
            pluggables.add(DisposePluggable())
        }

        start()
    }

    private fun acquireWakeLock() {
        try {
            // Reuse the same WakeLock object: release + re-acquire refreshes its
            // timeout, so the lock stays valid while location updates keep flowing
            // and dies on its own if updates stop for wakeLockTime
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                .apply { setReferenceCounted(false) }
            if (lock.isHeld) {
                lock.release()
            }
            lock.acquire(wakeLockTime)
            wakeLock = lock
        } catch (e: SecurityException) {
            Log.w("IsolateHolderService", "Wake lock permission is unavailable", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun stopLocationResources() {
        val client = locatorClient
        locatorClient = null
        try {
            client?.removeLocationUpdates()
        } catch (e: Exception) {
            ErrorReporter.report(this, "service_cleanup", "REMOVE_UPDATES_FAILED", e)
        }
        try {
            releaseWakeLock()
        } catch (e: Exception) {
            ErrorReporter.report(this, "service_cleanup", "RELEASE_WAKELOCK_FAILED", e)
        }
    }

    private fun shutdownHolderService() {
        Log.d("IsolateHolderService", "shutdownHolderService")
        isServiceRunning = false
        PreferencesManager.setTrackingEnabled(this, false)
        PreferencesManager.setSessionActive(this, false)
        PreferencesManager.setWasKilled(this, false)
        stopLocationResources()
        stopForeground(true)
        stopSelf()

        pluggables.forEach {
            context?.let { it1 -> it.onServiceDispose(it1) }
        }
        pluggables.clear()
    }

    private fun updateNotification(intent: Intent) {
        Log.e("IsolateHolderService", "updateNotification")
        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE)) {
            notificationTitle =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG)) {
            notificationMsg =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG)) {
            notificationBigMsg =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG).toString()
        }

        val notification = getNotification()
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    private fun getMainActivityClass(context: Context): Class<*>? {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val className = launchIntent?.component?.className ?: return null

        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
            null
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != Keys.METHOD_SERVICE_INITIALIZED) {
            result.notImplemented()
            return
        }
        isServiceInitialized = true
        isDispatcherReady = true
        result.success(null)

        // The dispatcher is up: flush errors queued while no engine was alive
        ErrorReporter.flushQueue(this)
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopLocationResources()
        backgroundEngine?.let { scheduleBackgroundEngineDestroy(it) }
        super.onDestroy()
    }


    private fun getLocationClient(context: Context): BLLocationProvider {
        return when (PreferencesManager.getLocationClient(context)) {
            LocationClient.Google -> GoogleLocationProviderClient(context, this)
            LocationClient.Android -> AndroidLocationProviderClient(context, this)
        }
    }

    override fun onLocationUpdated(location: HashMap<Any, Any>?) {
        try {
            //https://github.com/flutter/plugins/pull/1641
            //https://github.com/flutter/flutter/issues/36059
            //https://github.com/flutter/plugins/pull/1641/commits/4358fbba3327f1fa75bc40df503ca5341fdbb77d
            // new version of flutter can not invoke method from background thread
            if (location != null) {
                locationErrorRetries = 0
                acquireWakeLock()
                val callback = context?.let {
                    PreferencesManager.getCallbackHandle(it, Keys.CALLBACK_HANDLE_KEY)
                } ?: return
                val result: HashMap<Any, Any> = hashMapOf(
                    Keys.ARG_CALLBACK to callback,
                    Keys.ARG_LOCATION to location
                )
                sendLocationEvent(result)
            }
        } catch (e: Exception) {
            context?.let {
                ErrorReporter.report(it, "location_dispatch", "DISPATCH_FAILED", e)
            }
        }
    }

    override fun onLocationError(error: Exception) {
        Handler(mainLooper).post {
            if (!isServiceRunning) {
                return@post
            }
            if (error is SecurityException || locationErrorRetries >= 3) {
                ErrorReporter.report(this, "location_provider", "LOCATION_ERROR", error)
                shutdownHolderService()
            } else {
                locationErrorRetries++
                Log.w("IsolateHolderService", "Location updates failed, retrying", error)
                try {
                    locationRequest?.let { locatorClient?.requestLocationUpdates(it) }
                } catch (e: Exception) {
                    onLocationError(e)
                }
            }
        }
    }

    private fun sendLocationEvent(result: HashMap<Any, Any>) {
        //https://github.com/flutter/plugins/pull/1641
        //https://github.com/flutter/flutter/issues/36059
        //https://github.com/flutter/plugins/pull/1641/commits/4358fbba3327f1fa75bc40df503ca5341fdbb77d
        // new version of flutter can not invoke method from background thread

        val currentContext = context ?: return
        invokeBackgroundMethod(currentContext, Keys.BCM_SEND_LOCATION, result)
    }
}
