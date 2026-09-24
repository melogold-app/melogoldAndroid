package app.melogold.android.utils

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import app.melogold.android.Database
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.models.Format
import app.melogold.android.service.LOCAL_KEY_PREFIX
import app.melogold.android.service.PlayerService
import app.melogold.android.service.PrecacheService
import app.melogold.android.service.downloadState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

@OptIn(UnstableApi::class)
@Composable
fun isCached(
    mediaId: String,
    key: Any? = Unit,
    binder: PlayerService.Binder? = LocalPlayerServiceBinder.current
): Boolean {
    if (mediaId.startsWith(LOCAL_KEY_PREFIX)) return true

    var format: Format? by remember { mutableStateOf(null) }

    LaunchedEffect(mediaId, key) {
        Database
            .format(mediaId)
            .distinctUntilChanged()
            .collect { format = it }
    }

    return remember(mediaId, binder, format, key) {
        format?.contentLength?.let { len ->
            binder?.cache?.isCached(mediaId, 0, len)
        } ?: false
    }
}

@OptIn(UnstableApi::class)
class ConditionalCacheDataSourceFactory(
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val upstreamDataSourceFactory: DataSource.Factory,
    private val shouldCache: (DataSpec) -> Boolean
) : DataSource.Factory {
    init {
        cacheDataSourceFactory.setUpstreamDataSourceFactory(upstreamDataSourceFactory)
    }

    override fun createDataSource() = object : DataSource {
        private lateinit var selectedFactory: DataSource.Factory
        private val transferListeners = mutableListOf<TransferListener>()

        private fun createSource(factory: DataSource.Factory = selectedFactory) =
            factory.createDataSource().apply {
                transferListeners.forEach { addTransferListener(it) }
            }

        private val open = AtomicBoolean(false)
        private var source by object : ReadWriteProperty<Any?, DataSource> {
            var s: DataSource? = null

            override fun getValue(thisRef: Any?, property: KProperty<*>) = s ?: run {
                val newSource = runCatching {
                    createSource()
                }.getOrElse {
                    if (it is UninitializedPropertyAccessException) throw PlaybackException(
                        /* message = */ "Illegal access of data source methods before calling open()",
                        /* cause = */ it,
                        /* errorCode = */ PlaybackException.ERROR_CODE_UNSPECIFIED
                    ) else throw it
                }
                s = newSource
                newSource
            }

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: DataSource) {
                s = value
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) =
            source.read(buffer, offset, length)

        override fun addTransferListener(transferListener: TransferListener) {
            if (::selectedFactory.isInitialized) source.addTransferListener(transferListener)

            transferListeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            selectedFactory =
                if (shouldCache(dataSpec)) cacheDataSourceFactory else upstreamDataSourceFactory

            // Source is still considered 'open' even when an error occurs. See DataSource::close
            open.set(true)
            return source.open(dataSpec)
        }

        override fun getUri() = if (open.get()) source.uri else null
        override fun close() = if (open.compareAndSet(true, false)) {
            source.close()
        } else Unit
    }
}
