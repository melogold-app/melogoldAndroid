package app.melogold.android.appfunctions

import android.app.PendingIntent
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import app.melogold.domain.voice.VoiceException
import app.melogold.domain.voice.VoiceResult
import app.melogold.domain.voice.VoiceTrack

/**
 * A track found in Melogold, for the user to choose from.
 * Найденный в Melogold трек, чтобы пользователь выбрал.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class TrackResult(
    /** Id of the track: pass it to playSong as videoId. / Id трека: передайте его в playSong как videoId. */
    val videoId: String,
    /** Title. / Название. */
    val title: String,
    /** Artists, or the channel of a video. / Исполнители или канал видео. */
    val artists: String?,
    /** Length, e.g. "3:45". / Длительность, например «3:45». */
    val duration: String?,
    /** The user liked or played it before. / Пользователь уже слушал или лайкал этот трек. */
    val inLibrary: Boolean
) {
    companion object {
        fun of(track: VoiceTrack) = TrackResult(track.id, track.title, track.artists, track.durationText, track.inLibrary)
    }
}

/**
 * What plays in Melogold after the command.
 * Что играет в Melogold после команды.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PlaybackResult(
    /** Title of the track that plays; for an artist's mix, the artist. / Название трека; для микса — исполнитель. */
    val title: String,
    /** Artists of the track. / Исполнители трека. */
    val artists: String?,
    /**
     * The album, artist or playlist that plays, or "Избранное / Favorites"; none for one song.
     * Альбом, исполнитель или плейлист, который играет, или «Избранное / Favorites»; для одной песни — нет.
     */
    val collection: String?,
    /**
     * Where it was found: "library" (the user's own), "youtube_music", "youtube"; "queue" for pause, resume, next.
     * Где найдено: «library» (своё), «youtube_music», «youtube»; «queue» для паузы, продолжения и следующего трека.
     */
    val source: String,
    /**
     * Set when Android did not let Melogold play in the background: offer the user to open Melogold with it, the
     * music then continues there. / Если Android не дал Melogold играть в фоне — предложите открыть Melogold этим
     * интентом, музыка продолжится там.
     */
    val openApp: PendingIntent?
) {
    companion object {
        fun of(result: VoiceResult, openApp: PendingIntent?) = PlaybackResult(
            title = result.title,
            artists = result.artists,
            collection = result.collection,
            source = result.source.code,
            openApp = openApp
        )
    }
}

/**
 * The error an agent understands: nothing matches, a wrong request, or the app could not do it now (no network,
 * YouTube did not answer). The message, in Russian and English, is for the agent to tell the person.
 */
internal fun VoiceException.toAppFunctionException(): AppFunctionException = when (kind) {
    VoiceException.Kind.NotFound -> AppFunctionElementNotFoundException(message)
    VoiceException.Kind.InvalidArgument -> AppFunctionInvalidArgumentException(message)
    VoiceException.Kind.Unavailable -> AppFunctionAppUnknownException(message)
}
