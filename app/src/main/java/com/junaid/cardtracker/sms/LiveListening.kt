package com.junaid.cardtracker.sms

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.junaid.cardtracker.data.Prefs

/**
 * Optional live capture. When OFF the receiver component is disabled in the package manager,
 * so Android never starts the app for incoming SMS: nothing runs in the background.
 */
object LiveListening {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun setEnabled(context: Context, prefs: Prefs, enabled: Boolean) {
        val on = enabled && hasPermission(context)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, SmsReceiver::class.java),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        prefs.liveListening = on
    }

    /** On app start: keep the component state in line with the setting (e.g. permission revoked in Settings). */
    fun reconcile(context: Context, prefs: Prefs) = setEnabled(context, prefs, prefs.liveListening)
}
