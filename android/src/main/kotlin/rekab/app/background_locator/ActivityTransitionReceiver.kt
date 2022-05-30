package rekab.app.background_locator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionResult
import java.text.SimpleDateFormat
import java.util.*

class ActivityTransitionReceiver : BroadcastReceiver() {


    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityRecognitionResult.hasResult(intent)){

            val  result = ActivityRecognitionResult.extractResult(intent)
            result?.let {
                val type = ActivityTransitionsUtil.toActivityString(result.mostProbableActivity.type)
                try {
                    IsolateHolderService.onNewUserActivity(type)
                } catch (e: Exception) {
                }

            }

        }

    }
}
