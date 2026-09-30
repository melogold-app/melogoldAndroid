package app.melogold.android.ui.screens.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.LocalAppContainer
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.MainApplication
import app.melogold.android.ui.theme.brandColorScheme
import app.melogold.android.utils.nextTrackIndex
import app.melogold.core.ui.theme.MelogoldTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The swipe of the mini player pages the tracks like a pager (2026-09-30: it pushed the whole row off to one side and
 * brought the new track back from the same side). A real player with a queue, the real row: what comes in stands one
 * width away on the far side, the finger drags both, and what is let go past the middle pages.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class MiniPlayerSwipeTest {
    @get:Rule
    val compose = createComposeRule()

    private var player: ExoPlayer? = null
    private var expanded = 0

    @After
    fun release() {
        compose.runOnUiThread { player?.release() }
    }

    private fun item(id: String, title: String, artist: String) = MediaItem.Builder()
        .setMediaId(id)
        .setUri("https://example.invalid/$id")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
        .build()

    private val queue = listOf(
        item("a", "Группа крови", "Кино"),
        item("b", "Come As You Are", "Nirvana"),
        item("c", "Smells Like Teen Spirit", "Nirvana")
    )

    /** The mini player over a real [ExoPlayer] that is not prepared: queue and index are there, no data is loaded. */
    private fun render(items: List<MediaItem> = queue, start: Int = 0) {
        val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<MainApplication>()).build()
        player = exo
        exo.setMediaItems(items, start, 0)
        compose.setContent {
            MelogoldTheme(scheme = brandColorScheme(isDark = false), isBrandScheme = true) {
                CompositionLocalProvider(
                    LocalPlayerAwareWindowInsets provides WindowInsets(0),
                    LocalAppContainer provides ApplicationProvider.getApplicationContext<MainApplication>().container
                ) {
                    Playing(exo)
                }
            }
        }
        compose.waitForIdle()
    }

    /** The caller of the real app: the metadata of the current item follows the player. */
    @Composable
    private fun Playing(exo: Player) {
        var metadata by remember { mutableStateOf(exo.mediaMetadata) }
        DisposableEffect(exo) {
            val listener = object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    metadata = mediaMetadata
                }
            }
            exo.addListener(listener)
            metadata = exo.mediaMetadata
            onDispose { exo.removeListener(listener) }
        }
        MiniPlayerContent(
            player = exo,
            metadata = metadata,
            explicit = false,
            shouldBePlaying = false,
            onExpand = { expanded++ },
            onMenu = {}
        )
    }

    private fun index() = compose.runOnUiThread { player!!.currentMediaItemIndex }

    // region The decision

    @Test
    fun `the decision pages past 40 percent, with the speed of the finger counted in`() {
        val w = 1000f
        // Slowly to 50 % of the width: pages; to 30 %: comes back
        assertEquals(MiniSwipe.Next, decideMiniSwipe(-500f, 0f, w, hasPrevious = true, hasNext = true))
        assertEquals(MiniSwipe.Previous, decideMiniSwipe(500f, 0f, w, hasPrevious = true, hasNext = true))
        assertEquals(MiniSwipe.Stay, decideMiniSwipe(-300f, 0f, w, hasPrevious = true, hasNext = true))
        // A short fast flick pages: 15 % of the way at 2500 px/s
        assertEquals(MiniSwipe.Next, decideMiniSwipe(-150f, -2500f, w, hasPrevious = true, hasNext = true))
        assertEquals(MiniSwipe.Previous, decideMiniSwipe(150f, 2500f, w, hasPrevious = true, hasNext = true))
        // A far drag with the finger going back comes back
        assertEquals(MiniSwipe.Stay, decideMiniSwipe(-450f, 2500f, w, hasPrevious = true, hasNext = true))
        // Where there is no track on that side — always back
        assertEquals(MiniSwipe.Stay, decideMiniSwipe(-900f, -3000f, w, hasPrevious = true, hasNext = false))
        assertEquals(MiniSwipe.Stay, decideMiniSwipe(900f, 3000f, w, hasPrevious = false, hasNext = true))
        assertEquals(MiniSwipe.Stay, decideMiniSwipe(-900f, -3000f, 0f, hasPrevious = true, hasNext = true))
    }

    // endregion

    @Test
    fun `the neighbours stand one width away, the current track in the middle`() {
        render()
        val width = widthOf("mini_player_current")
        assertTrue(width > 300f, "the track area has a width: $width")
        assertEquals(left("mini_player_current") + width, left("mini_player_next"), "the next one is a width to the right")
        assertEquals(left("mini_player_current") - width, left("mini_player_previous"), "the previous one a width to the left")
        compose.onNodeWithText("Группа крови").assertIsDisplayed()
    }

    @Test
    fun `during the drag the old track leaves on one side and the new one comes from the other`() {
        render()
        val home = left("mini_player_current")
        val width = widthOf("mini_player_current")
        slot("mini_player_current").performTouchInput {
            down(center)
            moveBy(Offset(-width / 2f, 0f))
        }
        compose.waitForIdle()
        // The finger moved half a width (less the touch slop, which a drag does not carry): the current track follows it
        // to the left, and the next one comes in from the right, always exactly one width behind it
        val current = left("mini_player_current")
        assertTrue(current < home - width * 0.4f, "the current track follows the finger to the left: $current from $home")
        assertEquals(current + width, left("mini_player_next"), 1f, "the next one comes in from the right, a width behind")
        assertEquals(current - width, left("mini_player_previous"), 1f, "the previous one is a width before it")
        assertEquals(0, index(), "nothing has switched yet")
        slot("mini_player_current").performTouchInput { up() }
    }

    @Test
    fun `a swipe to the left pages to the next track and lands on it without a jump`() {
        render()
        val home = left("mini_player_current")
        slot("mini_player_current").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, index())
        // Landed: the new track stands where the old one stood, nothing is left half off to the side
        assertEquals(home, left("mini_player_current"), 1f, "landed in place")
        compose.onNodeWithText("Come As You Are").assertIsDisplayed()
        // …and the row is ready for the next page: its neighbours are a width away again
        assertEquals(home + widthOf("mini_player_current"), left("mini_player_next"), 1f)
    }

    @Test
    fun `a swipe to the right pages to the previous track`() {
        render(start = 1)
        val home = left("mini_player_current")
        slot("mini_player_current").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(0, index())
        assertEquals(home, left("mini_player_current"), 1f, "landed in place")
        compose.onNodeWithText("Группа крови").assertIsDisplayed()
    }

    @Test
    fun `a swipe that starts over the play button pages too, and a tap still expands`() {
        render()
        val home = left("mini_player_current")
        // From where the play button is (near the right end of the row) across most of the row
        compose.onNodeWithTag("mini_player").performTouchInput {
            swipe(start = Offset(width * 0.86f, centerY), end = Offset(width * 0.2f, centerY), durationMillis = 200)
        }
        compose.waitForIdle()
        assertEquals(1, index(), "the swipe over the button paged")
        assertEquals(home, left("mini_player_current"), 1f, "landed in place")
        assertEquals(0, expanded, "a swipe is not a tap")
        slot("mini_player_current").performClick()
        compose.waitForIdle()
        assertEquals(1, expanded, "a tap on the track still expands the player")
        assertEquals(1, index(), "and does not page")
    }

    @Test
    fun `a short slow drag comes back and the track stays`() {
        render()
        val home = left("mini_player_current")
        slot("mini_player_current").performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.15f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(0, index())
        assertEquals(home, left("mini_player_current"), 1f, "back in place")
        compose.onNodeWithText("Группа крови").assertIsDisplayed()
    }

    @Test
    fun `a queue of one has no neighbours and the swipe comes back`() {
        render(items = queue.take(1))
        assertEquals(0, compose.onAllNodesWithTagCount("mini_player_next"))
        assertEquals(0, compose.onAllNodesWithTagCount("mini_player_previous"))
        val home = left("mini_player_current")
        slot("mini_player_current").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, index())
        assertEquals(home, left("mini_player_current"), 1f)
        assertNull(player!!.nextTrackIndexForTest())
    }

    /** `positionInRoot` is not clipped by the row like `boundsInRoot` is: a track off screen still has its place. */
    private fun left(tag: String) = slot(tag).fetchSemanticsNode().positionInRoot.x

    private fun widthOf(tag: String) = slot(tag).fetchSemanticsNode().size.width.toFloat()

    /** The row is clickable, so its children are merged into it: the slots are read from the unmerged tree. */
    private fun slot(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun Player.nextTrackIndexForTest() = nextTrackIndex()
}
