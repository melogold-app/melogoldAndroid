package app.melogold.android.service

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import app.melogold.android.MainActivity
import app.melogold.android.MainApplication
import app.melogold.android.R

private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L
private const val WORK_NAME = "melogold-downloads"

/**
 * Runs the downloads in the foreground (REWRITE §4.7.2): the manager and its state live in
 * [app.melogold.android.data.downloads.Downloads]; the scheduler resumes them when the network
 * comes back.
 */
@OptIn(UnstableApi::class)
class DownloadsService : DownloadService(
    /* foregroundNotificationId = */ ServiceNotifications.download.notificationId!!,
    /* foregroundNotificationUpdateInterval = */ NOTIFICATION_UPDATE_INTERVAL_MS,
    /* channelId = */ ServiceNotifications.download.id,
    /* channelNameResourceId = */ R.string.downloads_channel,
    /* channelDescriptionResourceId = */ 0
) {
    private val notifications by lazy { DownloadNotificationHelper(this, ServiceNotifications.download.id) }

    override fun getDownloadManager() = (application as MainApplication).container.downloads.manager

    override fun getScheduler() = WorkManagerScheduler(this, WORK_NAME)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int) =
        notifications.buildProgressNotification(
            /* context = */ this,
            /* smallIcon = */ R.drawable.download,
            /* contentIntent = */ PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ),
            /* message = */ null,
            /* downloads = */ downloads,
            /* notMetRequirements = */ notMetRequirements
        )
}
