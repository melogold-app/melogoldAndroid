package app.melogold.android.ui.components.themed

import android.os.Parcelable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.toImmutableList
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * The tabs of a pre-redesign screen, shown by [Scaffold] as a row of M3 tabs under the bar.
 */
class TabsBuilder @PublishedApi internal constructor() {
    companion object {
        @Composable
        inline fun rememberTabs(crossinline content: TabsBuilder.() -> Unit) = rememberSaveable(
            saver = listSaver(
                save = { it },
                restore = { it.toImmutableList() }
            )
        ) {
            TabsBuilder().apply(content).tabs.values.toImmutableList()
        }
    }

    @PublishedApi
    internal val tabs = mutableMapOf<String, Tab>()

    fun tab(
        key: Int,
        @StringRes
        title: Int,
        @DrawableRes
        icon: Int,
        canHide: Boolean = true
    ): Tab = tab(key.toString(), title, icon, canHide)

    fun tab(
        key: String,
        @StringRes
        title: Int,
        @DrawableRes
        icon: Int,
        canHide: Boolean = true
    ): Tab {
        require(key.isNotBlank()) { "key cannot be blank" }
        require(!tabs.containsKey(key)) { "key already exists" }
        require(icon != 0) { "icon is 0" }

        val ret = Tab.ResourcesTab(
            key = key,
            titleRes = title,
            icon = icon,
            canHide = canHide
        )
        tabs += key to ret
        return ret
    }

    fun tab(
        key: Int,
        title: String,
        @DrawableRes
        icon: Int,
        canHide: Boolean = true
    ): Tab = tab(key.toString(), title, icon, canHide)

    fun tab(
        key: String,
        title: String,
        @DrawableRes
        icon: Int,
        canHide: Boolean = true
    ): Tab {
        require(key.isNotBlank()) { "key cannot be blank" }
        require(title.isNotBlank()) { "title cannot be blank" }
        require(!tabs.containsKey(key)) { "key already exists" }
        require(icon != 0) { "icon is 0" }

        val ret = Tab.StaticTab(
            key = key,
            titleText = title,
            icon = icon,
            canHide = canHide
        )
        tabs += key to ret
        return ret
    }
}

@Parcelize
sealed class Tab : Parcelable {
    abstract val key: String

    @IgnoredOnParcel
    abstract val title: @Composable () -> String

    @get:DrawableRes
    abstract val icon: Int
    abstract val canHide: Boolean

    data class ResourcesTab(
        override val key: String,
        @param:StringRes
        private val titleRes: Int,
        @param:DrawableRes
        override val icon: Int,
        override val canHide: Boolean
    ) : Tab() {
        @IgnoredOnParcel
        override val title: @Composable () -> String = { stringResource(titleRes) }
    }

    data class StaticTab(
        override val key: String,
        private val titleText: String,
        @param:DrawableRes
        override val icon: Int,
        override val canHide: Boolean
    ) : Tab() {
        @IgnoredOnParcel
        override val title: @Composable () -> String = { titleText }
    }
}
