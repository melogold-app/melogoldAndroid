package app.melogold.android.ui.screens.library.stats

import app.melogold.android.Database
import app.melogold.android.Dependencies
import app.melogold.android.data.overrides.TrackOverrides
import app.melogold.android.data.stats.ListeningStats
import app.melogold.android.data.stats.StatsPeriod
import app.melogold.android.data.stats.StatsRepository
import app.melogold.android.data.stats.statsWindow
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.screens.library.collections.HistoryDevice
import app.melogold.android.ui.screens.library.collections.HistoryDeviceEntry
import app.melogold.android.ui.screens.library.collections.HistoryDevices
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.ZoneId

private const val KEEP_WHILE_HIDDEN_MS = 5_000L

/** What the Insights screen was asked for: a period, how far back, and whose plays. */
data class StatsQuery(
    val period: StatsPeriod = StatsPeriod.Month,
    /** 0 is the current period, -1 the one before it. */
    val offset: Int = 0,
    val device: HistoryDevice = HistoryDevice.All
)

/**
 * The state of Insights (tasks/0016): the period, the device, and the statistics of both, counted again when the History
 * or the person's own names change. Everything is on the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsModel : ScreenModel() {
    private val account = Dependencies.application.container.account
    private val deviceList = HistoryDevices(scope, account)

    val query = MutableStateFlow(StatsQuery())

    /** The other devices of the account with plays here. */
    val devices: StateFlow<ImmutableList<HistoryDeviceEntry>> = deviceList.devices

    /** Null until the first count is done; then always the last one, so a change of period does not blink. */
    val stats: StateFlow<ListeningStats?> = combine(query, Database.eventCount(), TrackOverrides.all) { query, _, overrides ->
        query to overrides
    }
        .mapLatest { (query, overrides) ->
            withContext(Dispatchers.IO) {
                val zone = ZoneId.systemDefault()
                StatsRepository.load(
                    window = statsWindow(query.period, query.offset, zone = zone),
                    overrides = overrides,
                    zone = zone,
                    includes = { deviceId -> deviceList.includes(query.device, deviceId) }
                )
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    fun select(period: StatsPeriod) = query.update { it.copy(period = period, offset = 0) }

    fun select(device: HistoryDevice) = query.update { it.copy(device = device) }

    /** One period back (-1) or forward (+1); never into the future. */
    fun step(delta: Int) = query.update { it.copy(offset = (it.offset + delta).coerceAtMost(0)) }
}
