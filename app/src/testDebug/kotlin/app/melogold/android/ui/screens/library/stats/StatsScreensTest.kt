package app.melogold.android.ui.screens.library.stats

import android.content.Context
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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.LocalAppContainer
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.MainApplication
import app.melogold.android.data.stats.StatsPeriod
import app.melogold.android.ui.screens.library.collections.HistoryDevice
import app.melogold.android.ui.screens.library.collections.HistoryDeviceEntry
import app.melogold.android.ui.theme.brandColorScheme
import app.melogold.core.ui.theme.MelogoldTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Insights as they are drawn (tasks/0016), without a database or a server: a month, a year, all time, an empty period,
 * the year in review card by card, and the picture "Share" makes. With `-Pmelogold.screenshots=<dir>` the pictures are
 * saved there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class StatsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 9, 30)

    private class Calls {
        val periods = mutableListOf<StatsPeriod>()
        val steps = mutableListOf<Int>()
        val devices = mutableListOf<HistoryDevice>()
        var wrapped: Int? = null
        var played: Pair<Int, Int>? = null
    }

    private var shown by mutableStateOf<app.melogold.android.data.stats.ListeningStats?>(null)

    /** Shows [stats]; to show others afterwards set [shown]. */
    private fun show(
        stats: app.melogold.android.data.stats.ListeningStats?,
        period: StatsPeriod,
        calls: Calls = Calls(),
        devices: List<HistoryDeviceEntry> = emptyList(),
        device: HistoryDevice = HistoryDevice.All
    ) {
        shown = stats
        render {
            StatsContent(
                stats = shown,
                query = StatsQuery(period = period, offset = shown?.window?.offset ?: 0, device = device),
                devices = persistentListOf(*devices.toTypedArray()),
                playingId = null,
                onBack = {},
                onPeriod = { calls.periods += it },
                onDevice = { calls.devices += it },
                onStep = { calls.steps += it },
                onPlayTracks = { list, index -> calls.played = list.size to index },
                onTrackMenu = {},
                onArtist = {},
                onAlbum = {},
                onWrapped = { calls.wrapped = it },
                today = today
            )
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("stats_list").performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    @Test
    fun `a month shows the time with the comparison, the numbers, the tops and when`() {
        val stats = StatsFixtures.stats(StatsPeriod.Month)
        val calls = Calls()
        show(stats, StatsPeriod.Month, calls)

        compose.onNodeWithText("Итоги").assertExists()
        compose.onNodeWithTag("stats_window_title").assertExists()
        compose.onNodeWithText("Сентябрь 2026").assertExists()
        compose.onNodeWithText("Время прослушивания").assertExists()
        compose.onNodeWithTag("stats_time_value").assertExists()
        compose.onNodeWithTag("stats_plays").assertExists()
        compose.onNodeWithTag("stats_tracks").assertExists()
        compose.onNodeWithTag("stats_artists").assertExists()
        assertTrue(stats.changePercent != null, "the month before has plays to compare with")
        compose.onNodeWithTag("stats_change").assertExists()
        // No year in review from a month, and no device filter without other devices
        compose.onAllNodesWithTag("stats_wrapped").assertCountEquals(0)
        shoot("stats-month")

        scrollTo("stats_chart")
        compose.onNodeWithText("Когда слушал").assertExists()
        compose.onNodeWithTag("stats_chart").assertExists()
        shoot("stats-month-when")

        scrollTo("stats_hours")
        compose.onNodeWithText("Время суток").assertExists()
        shoot("stats-month-hours")
    }

    @Test
    fun `the tops show ten and open to fifty, a tap on a track plays the top from there`() {
        val calls = Calls()
        val stats = StatsFixtures.stats(StatsPeriod.Year)
        show(stats, StatsPeriod.Year, calls)

        compose.onNodeWithText("Лучшие треки").assertExists()
        scrollTo("stats_tracks_all")
        compose.onNodeWithText("Показать все").assertExists()
        shoot("stats-year-tops")

        compose.onNodeWithTag("stats_tracks_all").performClick()
        scrollTo("stats_tracks_all")
        compose.onNodeWithText("Свернуть").assertExists()
        compose.onNodeWithTag("stats_tracks_all").performClick()
        scrollTo("stats_tracks_all")
        compose.onNodeWithText("Показать все").assertExists()
        compose.onNodeWithTag("stats_list").performScrollToNode(hasTestTag("stats_time"))

        // A tap on the first row plays the whole top, from the first
        val title = stats.topTracks.first().title
        compose.onAllNodesWithText(title).onFirst().performClick()
        assertEquals(stats.topTracks.size to 0, calls.played)
    }

    @Test
    fun `a year offers the year in review`() {
        val calls = Calls()
        show(StatsFixtures.stats(StatsPeriod.Year), StatsPeriod.Year, calls)

        compose.onNodeWithText("2026").assertExists()
        compose.onNodeWithTag("stats_wrapped").performClick()
        assertEquals(2026, calls.wrapped)
        shoot("stats-year")
    }

    @Test
    fun `the arrows walk back and forward, forward stops at the current period`() {
        val calls = Calls()
        val current = StatsFixtures.stats(StatsPeriod.Month)
        show(current, StatsPeriod.Month, calls)

        compose.onNodeWithTag("stats_next").assertIsNotEnabled()
        compose.onNodeWithTag("stats_previous").assertIsEnabled().performClick()
        assertEquals(listOf(-1), calls.steps)

        shown = StatsFixtures.stats(StatsPeriod.Month, offset = -1)
        compose.waitForIdle()
        compose.onNodeWithText("Август 2026").assertExists()
        compose.onNodeWithTag("stats_next").assertIsEnabled().performClick()
        assertEquals(listOf(-1, 1), calls.steps)
    }

    @Test
    fun `the periods are chips and a chip picks its period`() {
        val calls = Calls()
        show(StatsFixtures.stats(StatsPeriod.Month), StatsPeriod.Month, calls)

        compose.onNodeWithText("Неделя").assertExists()
        compose.onNodeWithText("Месяц").assertExists()
        compose.onNodeWithText("Год").assertExists()
        compose.onNodeWithText("Всё время").assertExists()
        compose.onNodeWithTag("stats_period_Week").performClick()
        compose.onNodeWithTag("stats_period_AllTime").performClick()
        assertEquals(listOf(StatsPeriod.Week, StatsPeriod.AllTime), calls.periods)
    }

    @Test
    fun `a week has its days on the arrows`() {
        show(StatsFixtures.stats(StatsPeriod.Week), StatsPeriod.Week)

        val title = compose.onNodeWithTag("stats_window_title").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        assertEquals("28 сент – 4 окт", title)
        scrollTo("stats_chart")
        shoot("stats-week")
    }

    @Test
    fun `an empty period says so and keeps the arrows`() {
        show(StatsFixtures.empty(StatsPeriod.Month), StatsPeriod.Month)

        compose.onNodeWithText("За этот период прослушиваний нет").assertExists()
        compose.onNodeWithTag("stats_previous").assertExists()
        compose.onAllNodesWithTag("stats_time").assertCountEquals(0)
        shoot("stats-empty")
    }

    @Test
    fun `all time has no arrows and says how long the server keeps the history`() {
        show(StatsFixtures.stats(StatsPeriod.AllTime), StatsPeriod.AllTime)

        compose.onAllNodesWithTag("stats_previous").assertCountEquals(0)
        scrollTo("stats_all_time_note")
        compose.onNodeWithText("Сервер хранит историю 400 дней: на новом устройстве видно столько, сколько пришло с сервера").assertExists()
        shoot("stats-all-time")
    }

    @Test
    fun `the plays of another device can be picked`() {
        val calls = Calls()
        show(
            StatsFixtures.stats(StatsPeriod.Month),
            StatsPeriod.Month,
            calls,
            devices = listOf(HistoryDeviceEntry("mac-id", "MacBook Air", "macos"))
        )

        compose.onNodeWithText("Все устройства").assertExists().performClick()
        compose.onNodeWithText("MacBook Air").assertExists().performClick()
        assertEquals(listOf<HistoryDevice>(HistoryDevice.Other("mac-id")), calls.devices)
        shoot("stats-devices")
    }

    @Test
    fun `while it is counted there is a loader and nothing else`() {
        show(null, StatsPeriod.Month)

        compose.onNodeWithText("Итоги").assertExists()
        compose.onAllNodesWithTag("stats_time").assertCountEquals(0)
        compose.onAllNodesWithTag("stats_empty").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun `the same screen in English`() {
        show(StatsFixtures.stats(StatsPeriod.Month), StatsPeriod.Month)

        compose.onNodeWithText("Insights").assertExists()
        compose.onNodeWithText("September 2026").assertExists()
        compose.onNodeWithText("Listening time").assertExists()
        compose.onNodeWithText("Week").assertExists()
        compose.onNodeWithText("All time").assertExists()
        shoot("stats-month-en")
    }

    @Test
    fun `the words of the comparison are the month it is against`() {
        val ru = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("сентябрь 2026", statsWindowTitle(StatsFixtures.stats(StatsPeriod.Month).window, today, Locale.forLanguageTag("ru"), "").lowercase())
        assertEquals("+12 %", signedPercent(12, Locale.forLanguageTag("ru")))
        assertEquals("−8 %", signedPercent(-8, Locale.forLanguageTag("ru")))
        assertEquals("0 %", signedPercent(0, Locale.forLanguageTag("ru")))
        assertEquals("+12%", signedPercent(12, Locale.US))
        assertEquals("−8%", signedPercent(-8, Locale.US))
        assertTrue(ru.getString(app.melogold.android.R.string.stats_change, "+12 %", "августу") == "+12 % к августу")
    }

    // region The year in review

    private var pageStats by mutableStateOf<app.melogold.android.data.stats.ListeningStats?>(null)

    private fun wrapped(page: Int, name: String) {
        val year = StatsFixtures.stats(StatsPeriod.Year, today = LocalDate.of(2026, 12, 20))
        pageStats = year
        render { WrappedContent(stats = pageStats, year = 2026, onClose = {}, onShare = {}, initialPage = page) }
        shoot(name)
    }

    @Test
    fun `the year in review has six cards`() {
        val year = StatsFixtures.stats(StatsPeriod.Year, today = LocalDate.of(2026, 12, 20))

        assertEquals(WrappedCard.entries, wrappedCards(year))
        assertTrue(year.minutes > 1_000, "a year of listening is thousands of minutes: ${year.minutes}")
    }

    @Test
    fun `card 1 - the minutes of the year`() {
        wrapped(0, "wrapped-1-minutes")
        compose.onNodeWithTag("wrapped_card_Minutes").assertExists()
        compose.onNodeWithTag("wrapped_minutes").assertExists()
        compose.onNodeWithText("Итоги 2026").assertExists()
    }

    @Test
    fun `card 2 - the track of the year`() {
        wrapped(1, "wrapped-2-track")
        compose.onNodeWithTag("wrapped_card_TrackOfYear").assertExists()
        compose.onNodeWithText("Трек года").assertExists()
        compose.onNodeWithTag("wrapped_track_title").assertExists()
    }

    @Test
    fun `card 3 - five artists`() {
        wrapped(2, "wrapped-3-artists")
        compose.onNodeWithText("Лучшие исполнители").assertExists()
    }

    @Test
    fun `card 4 - five tracks`() {
        wrapped(3, "wrapped-4-tracks")
        compose.onNodeWithText("Лучшие треки").assertExists()
    }

    @Test
    fun `card 5 - the favorite month and time of day`() {
        wrapped(4, "wrapped-5-favorite")
        compose.onNodeWithText("Любимый месяц").assertExists()
        compose.onNodeWithTag("wrapped_month").assertExists()
        compose.onNodeWithText("Любимое время суток").assertExists()
        compose.onNodeWithText("Вечер").assertExists()
    }

    @Test
    fun `card 6 - the discoveries`() {
        wrapped(5, "wrapped-6-discoveries")
        compose.onNodeWithText("Новое в этом году").assertExists()
        compose.onNodeWithTag("wrapped_discovered").assertExists()
    }

    @Test
    fun `the cards are moved by the arrows and by a tap on the right or the left`() {
        val year = StatsFixtures.stats(StatsPeriod.Year, today = LocalDate.of(2026, 12, 20))
        render { WrappedContent(stats = year, year = 2026, onClose = {}, onShare = {}) }

        compose.onNodeWithTag("wrapped_card_Minutes").assertExists()
        compose.onNodeWithTag("wrapped_back").assertIsNotEnabled()
        compose.onNodeWithTag("wrapped_next").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("wrapped_card_TrackOfYear").assertExists()
        compose.onNodeWithTag("wrapped_back").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("wrapped_card_Minutes").assertExists()
    }

    @Test
    fun `the last card has no way forward`() {
        val year = StatsFixtures.stats(StatsPeriod.Year, today = LocalDate.of(2026, 12, 20))
        render { WrappedContent(stats = year, year = 2026, onClose = {}, onShare = {}, initialPage = 5) }

        compose.onNodeWithTag("wrapped_next").assertIsNotEnabled()
        compose.onNodeWithTag("wrapped_back").assertIsEnabled()
    }

    @Test
    fun `Close and Share are there and do their job`() {
        var closed = 0
        var shared = 0
        val year = StatsFixtures.stats(StatsPeriod.Year, today = LocalDate.of(2026, 12, 20))
        render { WrappedContent(stats = year, year = 2026, onClose = { closed++ }, onShare = { shared++ }) }

        compose.onNodeWithTag("wrapped_close").performClick()
        compose.onNodeWithTag("wrapped_share").performClick()
        assertEquals(1, closed)
        assertEquals(1, shared)
    }

    @Test
    fun `a year without plays says so instead of showing cards`() {
        render { WrappedContent(stats = StatsFixtures.empty(StatsPeriod.Year), year = 2026, onClose = {}, onShare = {}) }

        compose.onNodeWithTag("wrapped_empty").assertExists()
        compose.onNodeWithText("За этот год пока нечего показать").assertExists()
        compose.onAllNodesWithTag("wrapped_share").assertCountEquals(0)
        shoot("wrapped-empty")
    }

    @Test
    fun `a year with few plays leaves out the cards it has nothing for`() {
        val few = StatsFixtures.stats(StatsPeriod.Year, today = LocalDate.of(2026, 12, 20)).let {
            it.copy(topArtists = emptyList(), discoveries = it.discoveries?.copy(count = 0, top = emptyList()))
        }

        assertEquals(
            listOf(WrappedCard.Minutes, WrappedCard.TrackOfYear, WrappedCard.TopTracks, WrappedCard.FavoriteTime),
            wrappedCards(few)
        )
    }

    // endregion

    /** Renders [content] in the Melogold theme. */
    private fun render(content: @Composable () -> Unit) = compose.setContent {
        MelogoldTheme(scheme = brandColorScheme(isDark = false), isBrandScheme = true) {
            CompositionLocalProvider(
                LocalPlayerAwareWindowInsets provides WindowInsets(0),
                LocalAppContainer provides ApplicationProvider.getApplicationContext<MainApplication>().container
            ) { content() }
        }
    }

    /** Saves what is on the screen as [name].png when a folder is given. */
    private fun shoot(name: String) {
        compose.waitForIdle()
        val folder = System.getProperty("melogold.screenshots")?.takeIf { it.isNotBlank() } ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder).apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
