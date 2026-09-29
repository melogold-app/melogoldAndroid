package app.melogold.android.ui.screens.sharedplaylist

import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.LocalAppContainer
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.MainApplication
import app.melogold.android.models.Song
import app.melogold.android.sync.api.ArtistRef
import app.melogold.android.sync.api.ShareDto
import app.melogold.android.sync.api.TrackDto
import app.melogold.android.ui.screens.settings.account.MySharesContent
import app.melogold.android.ui.screens.settings.account.MySharesState
import app.melogold.android.ui.theme.brandColorScheme
import app.melogold.core.ui.theme.MelogoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals

/**
 * "Playlist by link" and "My links" as they are drawn (tasks/0017), without a server: a playlist that came, a link
 * that is gone, a server that cannot be reached, and the list of the links of the account with copy and delete. With
 * `-Pmelogold.screenshots=<dir>` the pictures are saved there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class ShareScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun track(id: String, title: String, artist: String, duration: String, explicit: Boolean = false) = TrackDto(
        videoId = id,
        title = title,
        artistsText = artist,
        artists = listOf(ArtistRef("UC$id", artist)),
        durationText = duration,
        explicit = explicit
    )

    private val share = ShareDto(
        shareId = "a1B2c3D4e5",
        kind = "playlist",
        name = "Дорога",
        url = "https://music.example.com/s/a1B2c3D4e5",
        tracks = listOf(
            track("aaaaaaaaaa1", "Группа крови", "Кино", "4:45"),
            track("aaaaaaaaaa2", "Кукла колдуна", "Король и Шут", "3:27"),
            track("aaaaaaaaaa3", "Выдыхай", "Noize MC", "3:52", explicit = true),
            track("aaaaaaaaaa4", "Дельфины", "Мумий Тролль", "4:10")
        ),
        createdAt = "2026-09-30T10:00:00.000Z"
    )

    private class Calls {
        var played: Triple<Int, Int, Boolean>? = null
        var saved: ShareDto? = null
        var retried = 0
        var backs = 0
        var menu: Song? = null
    }

    private var state by mutableStateOf<SharedPlaylistState>(SharedPlaylistState.Loading)
    private var saved by mutableStateOf(false)

    private fun show(initial: SharedPlaylistState, calls: Calls = Calls()) {
        state = initial
        render {
            SharedPlaylistContent(
                state = state,
                host = "music.example.com",
                playingId = null,
                saved = saved,
                onBack = { calls.backs++ },
                onRetry = { calls.retried++ },
                onPlay = { songs, index, shuffle -> calls.played = Triple(songs.size, index, shuffle) },
                onMenu = { calls.menu = it },
                onSave = { calls.saved = it }
            )
        }
    }

    @Test
    fun `a playlist by link shows its name, the server it comes from, the tracks and what can be done`() {
        val calls = Calls()
        show(SharedPlaylistState.Loaded(share), calls)

        compose.onNodeWithText("Дорога").assertExists()
        compose.onNodeWithText("Плейлист по ссылке · 4 трека").assertExists()
        compose.onNodeWithTag("shared_playlist_host").assertExists()
        compose.onNodeWithText("На сервере music.example.com").assertExists()
        compose.onNodeWithText("Группа крови").assertExists()
        compose.onNodeWithText("Noize MC").assertExists()
        compose.onNodeWithText("Слушать").assertExists()
        compose.onNodeWithText("Перемешать").assertExists()
        compose.onNodeWithText("Сохранить в Библиотеку").assertExists().assertIsEnabled()
        shoot("shared-playlist")

        compose.onNodeWithText("Слушать").performClick()
        assertEquals(Triple(4, 0, false), calls.played)
        compose.onNodeWithText("Перемешать").performClick()
        assertEquals(Triple(4, 0, true), calls.played)
        compose.onNodeWithText("Кукла колдуна").performClick()
        assertEquals(Triple(4, 1, false), calls.played)
    }

    @Test
    fun `Save to Library is a button of its own and says so when it is done`() {
        val calls = Calls()
        show(SharedPlaylistState.Loaded(share), calls)

        compose.onNodeWithTag("shared_playlist_save").performClick()
        assertEquals("aaaaaaaaaa1", calls.saved?.tracks?.first()?.videoId)

        saved = true
        compose.waitForIdle()
        compose.onNodeWithText("Сохранено в Библиотеку").assertExists()
        compose.onNodeWithTag("shared_playlist_save").assertIsNotEnabled()
        shoot("shared-playlist-saved")
    }

    @Test
    fun `a link that is gone says so, and there is nothing to retry`() {
        val calls = Calls()
        show(SharedPlaylistState.Gone, calls)

        compose.onNodeWithText("Ссылка удалена или неверна").assertExists()
        compose.onAllNodesWithTag("shared_playlist_retry").assertCountEquals(0)
        compose.onNodeWithText("Плейлист по ссылке").assertExists()
        shoot("shared-playlist-gone")
        compose.onNodeWithText("Назад").performClick()
        assertEquals(1, calls.backs)
    }

    @Test
    fun `a server that cannot be reached says so and offers to try again`() {
        val calls = Calls()
        show(SharedPlaylistState.Offline, calls)

        compose.onNodeWithText("Нет связи с сервером ссылки").assertExists()
        compose.onNodeWithTag("shared_playlist_retry").performClick()
        assertEquals(1, calls.retried)
        shoot("shared-playlist-offline")
    }

    @Test
    fun `while it loads there is a title and nothing else`() {
        show(SharedPlaylistState.Loading)

        compose.onNodeWithText("Плейлист по ссылке").assertExists()
        compose.onAllNodesWithTag("shared_playlist").assertCountEquals(0)
        compose.onAllNodesWithTag("shared_playlist_problem").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun `the same screen in English`() {
        show(SharedPlaylistState.Loaded(share))

        compose.onNodeWithText("Playlist by link · 4 tracks").assertExists()
        compose.onNodeWithText("On the server music.example.com").assertExists()
        compose.onNodeWithText("Save to Library").assertExists()
        shoot("shared-playlist-en")
    }

    // region My links

    private val mine = listOf(
        share,
        share.copy(shareId = "z9Y8x7W6v5", name = "Лучшее за лето, которое случилось в этом году и долго не кончалось", tracks = share.tracks.take(2), createdAt = "2026-09-12T08:30:00.000Z"),
        share.copy(shareId = "k3J4h5G6f7", name = "Для бега", tracks = share.tracks.take(1), createdAt = "2026-08-01T20:00:00.000Z")
    )

    private class MyCalls {
        val copied = mutableListOf<String>()
        val deleted = mutableListOf<String>()
    }

    private fun showMine(state: MySharesState, calls: MyCalls = MyCalls()) = render {
        MySharesContent(state = state, onBack = {}, onCopy = { calls.copied += it.shareId }, onDelete = { calls.deleted += it.shareId })
    }

    @Test
    fun `my links list the playlists with their tracks and dates`() {
        val calls = MyCalls()
        showMine(MySharesState.Loaded(mine), calls)

        compose.onNodeWithText("Мои ссылки").assertExists()
        compose.onNodeWithText("Плейлисты, которыми вы поделились. Ссылка работает, пока вы её не удалите").assertExists()
        compose.onNodeWithText("Дорога").assertExists()
        compose.onNodeWithText("Для бега").assertExists()
        compose.onNodeWithTag("my_link_copy_a1B2c3D4e5").performClick()
        assertEquals(listOf("a1B2c3D4e5"), calls.copied)
        shoot("my-links")
    }

    @Test
    fun `deleting a link asks first and only then deletes`() {
        val calls = MyCalls()
        showMine(MySharesState.Loaded(mine), calls)

        compose.onNodeWithTag("my_link_delete_k3J4h5G6f7").performClick()
        compose.onNodeWithText("Удалить ссылку?").assertExists()
        compose.onNodeWithText("Те, у кого она есть, больше не смогут открыть «Для бега». Плейлист останется в вашей библиотеке").assertExists()
        assertEquals(emptyList(), calls.deleted)
        shoot("my-links-delete")

        compose.onNodeWithTag("my_link_delete_confirm").performClick()
        assertEquals(listOf("k3J4h5G6f7"), calls.deleted)
        compose.onAllNodesWithTag("my_link_delete_confirm").assertCountEquals(0)
    }

    @Test
    fun `cancelling the dialog deletes nothing`() {
        val calls = MyCalls()
        showMine(MySharesState.Loaded(mine), calls)

        compose.onNodeWithTag("my_link_delete_a1B2c3D4e5").performClick()
        compose.onNodeWithText("Отмена").performClick()

        assertEquals(emptyList(), calls.deleted)
    }

    @Test
    fun `no links, and a list that could not be loaded, say so`() {
        showMine(MySharesState.Loaded(emptyList()))
        compose.onNodeWithTag("my_links_empty").assertExists()
        compose.onNodeWithText("Вы ещё не делились плейлистами. Выберите «Поделиться» в меню своего плейлиста").assertExists()
        shoot("my-links-empty")
    }

    @Test
    fun `a list that could not be loaded says so`() {
        showMine(MySharesState.Failed)
        compose.onNodeWithText("Не удалось загрузить ссылки").assertExists()
    }

    // endregion

    private fun render(content: @Composable () -> Unit) = compose.setContent {
        MelogoldTheme(scheme = brandColorScheme(isDark = false), isBrandScheme = true) {
            CompositionLocalProvider(
                LocalPlayerAwareWindowInsets provides WindowInsets(0),
                LocalAppContainer provides ApplicationProvider.getApplicationContext<MainApplication>().container
            ) { content() }
        }
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        val folder = System.getProperty("melogold.screenshots")?.takeIf { it.isNotBlank() } ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder).apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
