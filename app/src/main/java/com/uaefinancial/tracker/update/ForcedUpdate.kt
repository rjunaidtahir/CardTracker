package com.uaefinancial.tracker.update

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Asks Google Play to install a newer version straight away (Play's "immediate" update screen) whenever one is
 * published, so a fix reaches everyone instead of waiting for people to update by hand. Does nothing when the app was
 * not installed from Play, when Play is unavailable, or when there is no newer version. Create it as a field of the
 * activity (it registers for a result) and call [check] from onResume.
 */
class ForcedUpdate(activity: ComponentActivity) {
    private val appContext = activity.applicationContext
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        // Closed or cancelled: check() asks again the next time the app comes to the front.
    }

    fun check() {
        runCatching {
            val manager = AppUpdateManagerFactory.create(appContext)
            manager.appUpdateInfo.addOnSuccessListener { info ->
                val a = info.updateAvailability()
                val wanted = a == UpdateAvailability.UPDATE_AVAILABLE || a == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                if (wanted && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    runCatching {
                        manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build())
                    }
                }
            }
        }
    }
}
