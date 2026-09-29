package app.melogold.android.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService

/** Offers [text] to the apps the person shares with (tasks/0017): the message is "Title — Artist" and the link. */
fun Context.shareText(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Puts [text] on the clipboard as a link; Android 13 and later show their own "Copied" panel. */
fun Context.copyLink(label: String, text: String) {
    getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(label, text))
}
