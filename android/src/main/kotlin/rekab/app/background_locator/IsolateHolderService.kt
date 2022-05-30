package rekab.app.background_locator

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import rekab.app.background_locator.pluggables.DisposePluggable
import rekab.app.background_locator.pluggables.InitPluggable
import rekab.app.background_locator.pluggables.Pluggable
import rekab.app.background_locator.provider.*
import java.util.HashMap

class IsolateHolderService : MethodChannel.MethodCallHandler, LocationUpdateListener,ChangeUserActivityListener,Service() {
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
        private val REQUEST_CODE_INTENT_ACTIVITY_TRANSITION = 122

        @JvmStatic
        var isServiceRunning = false

        var userActivityListener : ChangeUserActivityListener? = null

        @JvmStatic
        fun onNewUserActivity(event : String){
            userActivityListener?.onUserActivityChange(event)

        }
    }

    private var notificationChannelName = "Flutter Locator Plugin"
    private var notificationTitle = "Start Location Tracking"
    private var notificationMsg = "Track location in background"
    private var notificationBigMsg = "Background location is on to keep the app up-tp-date with your location. This is required for main features to work properly when the app is not running."
    private var notificationIconColor = 0
    private var icon = 0
    private var wakeLockTime = 60 * 60 * 1000L // 1 hour default wake lock time
    private var locatorClient: BLLocationProvider? = null
    internal lateinit var backgroundChannel: MethodChannel
    internal lateinit var context: Context
    private var pluggables: ArrayList<Pluggable> = ArrayList()
    private lateinit var client: ActivityRecognitionClient
    private var detectionINTERVALinMILLISECONDS = 10 * 1000L// 10 seconds
    private var lastDetectedUserActivity : String? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        userActivityListener = this
        startLocatorService(this)
        startForeground(notificationId, getNotification())
    }

    private fun start() {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(wakeLockTime)
            }
        }

        // Starting Service as foreground with a notification prevent service from closing
        val notification = getNotification()
        startForeground(notificationId, notification)

        pluggables.forEach {
            it.onServiceStart(context)
        }
    }

    private fun getNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Notification channel is available in Android O and up
            val channel = NotificationChannel(Keys.CHANNEL_ID, notificationChannelName,
                    NotificationManager.IMPORTANCE_LOW)

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
        }

        val intent = Intent(this, getMainActivityClass(this))
        intent.action = Keys.NOTIFICATION_ACTION

        val pendingIntent: PendingIntent = PendingIntent.getActivity(this,
                1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, Keys.CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setContentText(notificationMsg)
                .setStyle(NotificationCompat.BigTextStyle()
                        .bigText(notificationBigMsg))
                .setSmallIcon(icon)
                .setColor(notificationIconColor)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true) // so when data is updated don't make sound and alert in android 8.0+
                .setOngoing(true)
                .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return super.onStartCommand(intent, flags, startId)
        }

        when {
            ACTION_SHUTDOWN == intent.action -> {
                isServiceRunning = false
                shutdownHolderService()
            }
            ACTION_START == intent.action -> {
                if (!isServiceRunning) {
                    isServiceRunning = true
                    startHolderService(intent)
                }
            }
            ACTION_UPDATE_NOTIFICATION == intent.action -> {
                if (isServiceRunning) {
                    updateNotification(intent)
                }
            }
        }

        return START_STICKY
    }

    private fun startHolderService(intent: Intent) {
        notificationChannelName = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME).toString()
        notificationTitle = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE).toString()
        notificationMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG).toString()
        notificationBigMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG).toString()
        val iconNameDefault = "ic_launcher"
        var iconName = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_ICON)
        if (iconName == null || iconName.isEmpty()) {
            iconName = iconNameDefault
        }
        icon = resources.getIdentifier(iconName, "mipmap", packageName)
        notificationIconColor = intent.getLongExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR, 0).toInt()
        wakeLockTime = intent.getIntExtra(Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME, 60) * 60 * 1000L

        locatorClient = getLocationClient(context)
        locatorClient?.requestLocationUpdates(getLocationRequest(intent))

        // The Activity Recognition Client returns a
        // list of activities that a user might be doing
        client = ActivityRecognition.getClient(this)
        //Activity recognition
        requestForUpdates()

        // Fill pluggable list
        if( intent.hasExtra(Keys.SETTINGS_INIT_PLUGGABLE)) {
            pluggables.add(InitPluggable())
        }

        if (intent.hasExtra(Keys.SETTINGS_DISPOSABLE_PLUGGABLE)) {
            pluggables.add(DisposePluggable())
        }

        start()
    }

    private fun shutdownHolderService() {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                if (isHeld) {
                    release()
                }
            }
        }

        locatorClient?.removeLocationUpdates()

        //Deregister activity recognition
        deregisterForUpdates()

        stopForeground(true)
        stopSelf()

        pluggables.forEach {
            it.onServiceDispose(context)
        }
    }

    private fun updateNotification(intent: Intent) {
        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE)) {
            notificationTitle = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG)) {
            notificationMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG)) {
            notificationBigMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG).toString()

            notificationBigMsg = if (lastDetectedUserActivity == null){
                "$notificationBigMsg\nLast Activity : UNKNOWN"
            }else{
                "$notificationBigMsg\nLast Activity : $lastDetectedUserActivity"
            }

        }

        val notification = getNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
        when (call.method) {
            Keys.METHOD_SERVICE_INITIALIZED -> {
                isServiceRunning = true
            }
            else -> result.notImplemented()
        }

        result.success(null)
    }

    override fun onDestroy() {
        isServiceRunning = false
        super.onDestroy()
    }


    private fun getLocationClient(context: Context): BLLocationProvider {
        return when (PreferencesManager.getLocationClient(context)) {
            LocationClient.Google -> GoogleLocationProviderClient(context, this)
            LocationClient.Android -> AndroidLocationProviderClient(context, this)
        }
    }

    override fun onLocationUpdated(location: HashMap<Any, Any>?) {
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(context, null)

        //https://github.com/flutter/plugins/pull/1641
        //https://github.com/flutter/flutter/issues/36059
        //https://github.com/flutter/plugins/pull/1641/commits/4358fbba3327f1fa75bc40df503ca5341fdbb77d
        // new version of flutter can not invoke method from background thread
        if (location != null) {
            val callback = PreferencesManager.getCallbackHandle(context, Keys.CALLBACK_HANDLE_KEY) as Long

            val result: HashMap<Any, Any> =
                    hashMapOf(Keys.ARG_CALLBACK to callback,
                            Keys.ARG_LOCATION to location)

            sendLocationEvent(result)
        }
    }

    private fun sendLocationEvent(result: HashMap<Any, Any>) {
        //https://github.com/flutter/plugins/pull/1641
        //https://github.com/flutter/flutter/issues/36059
        //https://github.com/flutter/plugins/pull/1641/commits/4358fbba3327f1fa75bc40df503ca5341fdbb77d
        // new version of flutter can not invoke method from background thread

        // Log.d("plugin", "sendLocationEvent $result")

        if (backgroundEngine != null) {
            val backgroundChannel =
                    MethodChannel(backgroundEngine?.dartExecutor?.binaryMessenger!!, Keys.BACKGROUND_CHANNEL_ID)
            Handler(context.mainLooper)
                    .post {

                        backgroundChannel.invokeMethod(Keys.BCM_SEND_LOCATION, result)
                    }
        }
    }


    //************* Activity Recognition ***************************************

    private val tag : String = "USER_ACTIVITY"
    override fun onUserActivityChange(event: String) {

        Log.d(tag, "current event $event, last event $lastDetectedUserActivity")

        if (event == "STILL"){


            if (lastDetectedUserActivity == null || lastDetectedUserActivity != "STILL"){

                Log.d(tag, "condition 1")

                //Remove updates
                locatorClient?.removeLocationUpdates()
                //Restart with low power & 1 minute update
                locatorClient?.requestLocationUpdates(getLocationRequestUserStill())

            }


        }else{

            if ( lastDetectedUserActivity == null || (event != "UNKNOWN" && event != lastDetectedUserActivity)){

                Log.d(tag, "condition 2")

                //Remove updates
                locatorClient?.removeLocationUpdates()

                //Restart with user saved settings
                locatorClient?.requestLocationUpdates(getLocationRequestFromPreference(context))

            }

        }


        lastDetectedUserActivity = event



    }

    // To register for changes we have to also supply the requestActivityTransitionUpdates() method
    // with the PendingIntent object that will contain an intent to the component
    // (i.e. IntentService, BroadcastReceiver etc.) that will receive and handle updates appropriately.
    @SuppressLint("MissingPermission")
    private fun requestForUpdates() {

        client.requestActivityUpdates(detectionINTERVALinMILLISECONDS, getPendingIntent())
                .addOnSuccessListener {

            Log.d("activityRecog", "start success")
            //"successful registration"
            //showToast("successful registration")
                    }
                .addOnFailureListener {
                    Log.d("activityRecog", "start fail")
                    //"Unsuccessful registration"
                    //showToast("Unsuccessful registration")
                }

    }

    // Deregister from updates
    // call the removeActivityTransitionUpdates() method
    // of the ActivityRecognitionClient and pass
    // ourPendingIntent object as a parameter
    @SuppressLint("MissingPermission")
    private fun deregisterForUpdates() {

        try {
            client.removeActivityUpdates(getPendingIntent()).addOnSuccessListener {
                getPendingIntent().cancel()
            }.addOnFailureListener{ e: Exception ->
                //showToast("unsuccessful deregistration")
            }
        }catch (e : Exception){

        }



    }

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(this, ActivityTransitionReceiver::class.java)
        return PendingIntent.getBroadcast(
                this,
                REQUEST_CODE_INTENT_ACTIVITY_TRANSITION,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
        )
    }



}


