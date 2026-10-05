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
import io.flutter.FlutterInjector
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
        private val notificationId = 1

        @JvmStatic
        @Volatile
        var isServiceRunning = false

        @JvmStatic
        var isServiceInitialized = false

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

        fun invokeBackgroundMethod(context: Context, method: String, arguments: Any?) {
            val engine = synchronized(IsolateHolderService::class.java) {
                val currentEngine = backgroundEngine ?: return
                pendingBackgroundCalls++
                currentEngine
            }
            val channel = MethodChannel(
                engine.dartExecutor.binaryMessenger,
                Keys.BACKGROUND_CHANNEL_ID
            )
            Handler(context.mainLooper).post {
                val response = object : MethodChannel.Result {
                    private var completed = false

                    private fun complete() {
                        val isFirstCompletion = synchronized(this) {
                            if (completed) {
                                false
                            } else {
                                completed = true
                                true
                            }
                        }
                        if (!isFirstCompletion) {
                            return
                        }
                        val shouldDestroy = synchronized(IsolateHolderService::class.java) {
                            pendingBackgroundCalls = (pendingBackgroundCalls - 1).coerceAtLeast(0)
                            !isServiceRunning && pendingBackgroundCalls == 0 &&
                                backgroundEngine === engine
                        }
                        if (shouldDestroy) {
                            scheduleBackgroundEngineDestroy(engine)
                        }
                    }

                    override fun success(result: Any?) = complete()

                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) =
                        complete()

                    override fun notImplemented() = complete()
                }
                try {
                    channel.invokeMethod(method, arguments, response)
                } catch (e: Exception) {
                    Log.e("IsolateHolderService", "Unable to send background callback", e)
                    response.error("BACKGROUND_LOCATOR_ERROR", e.message, null)
                }
            }
        }

        private fun scheduleBackgroundEngineDestroy(engine: FlutterEngine) {
            Handler(Looper.getMainLooper()).postDelayed({
                val shouldDestroy = synchronized(IsolateHolderService::class.java) {
                    if (isServiceRunning || pendingBackgroundCalls > 0 || backgroundEngine !== engine) {
                        false
                    } else {
                        backgroundEngine = null
                        isServiceInitialized = false
                        true
                    }
                }
                if (shouldDestroy) {
                    engine.destroy()
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
    internal lateinit var backgroundChannel: MethodChannel
    internal var context: Context? = null
    private var pluggables: ArrayList<Pluggable> = ArrayList()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(notificationId, getNotification())
        startLocatorService(this)
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
        val commandIntent = intent ?: restoreStartIntent()
        Log.d("IsolateHolderService", "onStartCommand => intent.action : ${commandIntent?.action}")
        if (commandIntent == null) {
            isServiceRunning = false
            PreferencesManager.setTrackingEnabled(this, false)
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
            Log.e("IsolateHolderService", "Unable to process service command", e)
            isServiceRunning = false
            PreferencesManager.setTrackingEnabled(this, false)
            locatorClient?.removeLocationUpdates()
            releaseWakeLock()
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
        locatorClient = getLocationClient(this)
        locatorClient?.requestLocationUpdates(getLocationRequest(intent))

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
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire(wakeLockTime)
                }
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

    private fun shutdownHolderService() {
        Log.d("IsolateHolderService", "shutdownHolderService")
        isServiceRunning = false
        PreferencesManager.setTrackingEnabled(this, false)
        releaseWakeLock()
        locatorClient?.removeLocationUpdates()
        locatorClient = null
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
        result.success(null)
    }

    override fun onDestroy() {
        isServiceRunning = false
        releaseWakeLock()
        locatorClient?.removeLocationUpdates()
        locatorClient = null
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
            context?.let {
                FlutterInjector.instance().flutterLoader().ensureInitializationComplete(
                    it, null
                )
            }

            //https://github.com/flutter/plugins/pull/1641
            //https://github.com/flutter/flutter/issues/36059
            //https://github.com/flutter/plugins/pull/1641/commits/4358fbba3327f1fa75bc40df503ca5341fdbb77d
            // new version of flutter can not invoke method from background thread
            if (location != null) {
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
            Log.e("IsolateHolderService", "Unable to dispatch location update", e)
        }
    }

    override fun onLocationError(error: Exception) {
        Handler(mainLooper).post {
            if (isServiceRunning) {
                Log.e("IsolateHolderService", "Location updates failed", error)
                shutdownHolderService()
            }
        }
    }

    private fun sendLocationEvent(result: HashMap<Any, Any>) {
        //https://github.com/flutter/plugins/pull/1641
        //https://github.com/flutter/flutter/issues/36059
        //https://github.com/flutter/plugins/pull/1641/commits/4358fbba3327f1fa75bc40df503ca5341fdbb77d
        // new version of flutter can not invoke method from background thread

        val currentContext = context ?: return
        Log.d("plugin", "sendLocationEvent $result")
        invokeBackgroundMethod(currentContext, Keys.BCM_SEND_LOCATION, result)
    }
}
