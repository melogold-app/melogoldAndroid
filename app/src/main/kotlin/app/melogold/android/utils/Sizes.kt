package app.melogold.android.utils

import android.content.Context
import app.melogold.android.R

private const val MIB = 1024L * 1024
private const val GIB = 1024L * MIB

/**
 * A size in the binary units the limits are set in: "128 MB", "2 GB", "1.5 GB" (Android's own
 * formatter counts in thousands and would show the 128 MB limit as "134 MB").
 */
fun Context.formatSize(bytes: Long): String = when {
    bytes >= GIB && bytes % GIB == 0L -> getString(R.string.size_gb, (bytes / GIB).toString())
    bytes >= GIB -> getString(R.string.size_gb, "%.1f".format(bytes.toDouble() / GIB))
    else -> getString(R.string.size_mb, (bytes / MIB).toString())
}
