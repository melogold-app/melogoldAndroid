package app.melogold.android.debug

import android.content.Context
import android.os.Bundle
import app.melogold.android.ui.screens.player.modern.TabletopPreview

/**
 * `--es cmd Tabletop --es arg on|off`: pretends the device is a foldable half-open in tabletop
 * posture, with the fold across the middle of the window, to check the player's tabletop layout.
 */
object TabletopCommand : DebugCommand {
    override suspend fun run(context: Context, arg: String?, extras: Bundle): String {
        TabletopPreview.fold = if (arg == "off") null else 0.49f..0.51f
        return "tabletop ${if (TabletopPreview.fold == null) "off" else "on"}"
    }
}
