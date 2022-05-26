package rekab.app.background_locator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import java.text.SimpleDateFormat
import java.util.*

class ActivityTransitionReceiver() : BroadcastReceiver() {


    private var listener : ActivityUpdateListener? = null


    constructor(listener: ActivityUpdateListener) : this() {
        this.listener = listener
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent)
            result?.let {
                result.transitionEvents.forEach { event ->


                    if (listener != null){
                        listener!!.onDeviceActivityChange(event)
                    }

                    // Info about activity
                    val info =
                            "Transition: " + ActivityTransitionsUtil.toActivityString(event.activityType) +
                                    " (" + ActivityTransitionsUtil.toTransitionType(event.transitionType) + ")" + " " +
                                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())



                }
            }
        }
    }
}
