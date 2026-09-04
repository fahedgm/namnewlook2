package com.namamp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

// Confirmed current requirements (checked directly, not assumed, since this
// is exactly the kind of thing that causes a runtime SecurityException
// rather than a build failure if gotten wrong):
//   - Android 11+ requires the "microphone" foreground service type to
//     access the mic from a background context; real-time audio apps
//     typically pair it with "mediaPlayback" for the output side.
//   - Android 14+ requires foregroundServiceType to be declared in the
//     manifest AND (for microphone/camera specifically) passed explicitly
//     to the 3-argument startForeground() overload.
//   - A microphone-type foreground service can only be *started* while the
//     app is visible (this service is started from MainActivity's "Start
//     audio" button while the Activity is on screen, which satisfies this).
//   - As of a very recent Android 17 change, background audio additionally
//     requires the service to have been started from a visible app state to
//     retain "while-in-use" capability — also satisfied by starting it from
//     the visible Activity.
class AudioService : Service() {

    companion object {
        private const val CHANNEL_ID = "nam_amp_audio"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        createChannelIfNeeded()

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Nam Amped")
            .setContentText("Live — processing audio")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannelIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nam Amped audio",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    // Deliberately no onDestroy() cleanup of the audio engine here — the
    // engine is a process-level native singleton, started/stopped
    // explicitly by MainActivity's Start/Stop audio buttons. This service's
    // only job is keeping the process alive and satisfying Android's
    // foreground-service requirements while that's happening.

    // This is the actual fix for "audio kept running after I closed the
    // app": onTaskRemoved() fires specifically when the user removes the
    // app from Recents (a genuine close), as opposed to backgrounding,
    // rotating, or the screen turning off — which are exactly the cases
    // this service exists to survive. NativeAudio is used here (not
    // MainActivity) because the Activity may already be gone by this point.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        NativeAudio.nativeStopAudio()
        stopSelf()
    }
}
