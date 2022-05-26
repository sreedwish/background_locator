package rekab.app.background_locator

import com.google.android.gms.location.ActivityTransitionEvent

interface ActivityUpdateListener {

    fun onDeviceActivityChange( event : ActivityTransitionEvent)
}