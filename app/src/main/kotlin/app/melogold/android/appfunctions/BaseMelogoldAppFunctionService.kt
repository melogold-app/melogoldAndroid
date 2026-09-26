package app.melogold.android.appfunctions

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import app.melogold.android.MainActivity
import app.melogold.android.playback.session.VoiceQueryResolver
import app.melogold.android.utils.pendingIntent
import app.melogold.domain.voice.VoiceCommands
import app.melogold.domain.voice.VoiceException
import app.melogold.domain.voice.VoiceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The functions Gemini calls in Melogold (AppFunctions, Android 16+; tasks/0006-gemini-app-functions.md): an agent
 * finds and plays music without having to hear the name of the app. KSP generates [MelogoldAppFunctionService]
 * (declared in the manifest) and the XML index the system reads; the KDoc of each function is its description for
 * the agent. Those texts cannot be localized, so each says it in English and Russian.
 *
 * Every function follows the voice rule of the app ([VoiceQueryResolver], REWRITE §3.14.3). Below Android 16 the
 * service is disabled in the manifest (`@bool/enablePlatformAppFunctionService` of the library): its base class is
 * a platform class of Android 16.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionServiceEntryPoint(
    serviceName = "MelogoldAppFunctionService",
    appFunctionXmlFileName = "melogold_app_functions"
)
abstract class BaseMelogoldAppFunctionService : AppFunctionService() {
    private val player = PlayerConnection(this)
    private val commands by lazy { VoiceQueryResolver.commands(this, player::binder) }

    override fun onDestroy() {
        player.close()
        super.onDestroy()
    }

    /**
     * Plays a song in Melogold, a music player for YouTube Music and YouTube: finds the best match on YouTube Music
     * (a YouTube video when YouTube Music has none), starts it and then keeps playing similar songs. Replaces what
     * is playing. For requests like "play <song> in Melogold".
     * Включает песню в Melogold: лучший результат YouTube Music (или видео YouTube), дальше играют похожие песни.
     * Заменяет то, что играет. Для просьб вроде «включи <песню> в Melogold».
     *
     * @param query What to play as the user said it: song title and/or artist, e.g. "Звезда по имени Солнце Кино".
     *   Not needed when videoId is given. / Что включить, как сказал пользователь: название и/или исполнитель.
     * @param videoId The exact track the user picked from searchSongs (its videoId); then query is ignored. /
     *   Точный трек, выбранный пользователем из searchSongs (его videoId).
     * @return What started playing and where it was found. / Что включилось и где найдено.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playSong(query: String? = null, videoId: String? = null): PlaybackResult =
        play { it.playSong(query, videoId) }

    /**
     * Finds songs in Melogold without playing them, so the user can choose when the request is ambiguous: tracks of
     * the user's library first, then YouTube Music (YouTube videos when YouTube Music has none). Play the chosen one
     * with playSong and its videoId.
     * Ищет песни в Melogold, не включая, чтобы пользователь выбрал: сначала из его библиотеки, затем YouTube Music
     * (или видео YouTube). Выбранную включите через playSong с её videoId.
     *
     * @param query Song title and/or artist as the user said it. / Название и/или исполнитель, как сказал пользователь.
     * @return Up to 10 tracks, the best first. / До 10 треков, лучшие первыми.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun searchSongs(query: String): List<TrackResult> = withContext(Dispatchers.IO) {
        answer { commands.search(query).map(TrackResult::of) }
    }

    /**
     * Plays an artist in Melogold: YouTube Music's shuffled mix of their songs; without a network, their songs from
     * the user's library. Artists the user saved come first. Replaces what is playing. For requests like "play
     * <artist or band> in Melogold".
     * Включает исполнителя в Melogold: перемешанный микс его песен из YouTube Music; без сети — его песни из
     * библиотеки. Сохранённые пользователем исполнители — в первую очередь. Заменяет то, что играет. Для просьб
     * вроде «включи <исполнителя или группу> в Melogold».
     *
     * @param name Artist or band, e.g. "Кино". / Исполнитель или группа, например «Кино».
     * @return What started playing and where it was found. / Что включилось и где найдено.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playArtist(name: String): PlaybackResult = play { it.playArtist(name) }

    /**
     * Plays an album in Melogold from its first track: albums saved in the user's library first, then YouTube Music.
     * Replaces what is playing. For requests like "play the album <album> in Melogold".
     * Включает альбом в Melogold с первого трека: сначала сохранённые в библиотеке, затем YouTube Music. Заменяет
     * то, что играет. Для просьб вроде «включи альбом <название> в Melogold».
     *
     * @param name Album title, optionally with the artist, e.g. "Группа крови Кино". / Название альбома, можно с
     *   исполнителем.
     * @return What started playing and where it was found. / Что включилось и где найдено.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playAlbum(name: String): PlaybackResult = play { it.playAlbum(name) }

    /**
     * Plays a playlist in Melogold from its first track. The user's own playlists come first, matched by name or a
     * part of it; only when none matches, a public playlist of YouTube Music. For Favorites use playFavorites.
     * Replaces what is playing.
     * Включает плейлист в Melogold с первого трека. Сначала свои плейлисты пользователя (по названию или его
     * части), иначе публичный плейлист YouTube Music. Для Избранного — playFavorites. Заменяет то, что играет.
     *
     * @param name Playlist name as the user said it, e.g. "Дорога на дачу". / Название плейлиста, как сказал
     *   пользователь.
     * @return What started playing and where it was found. / Что включилось и где найдено.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playPlaylist(name: String): PlaybackResult = play { it.playPlaylist(name) }

    /**
     * Plays the user's Favorites (liked songs) in Melogold, the last liked first. Replaces what is playing.
     * Включает Избранное пользователя в Melogold (понравившиеся песни), начиная с последних. Заменяет то, что играет.
     *
     * @return What started playing. / Что включилось.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playFavorites(): PlaybackResult = play { it.playFavorites(shuffled = false) }

    /**
     * Plays the user's Favorites (liked songs) in Melogold in random order. Replaces what is playing.
     * Включает Избранное пользователя в Melogold вперемешку. Заменяет то, что играет.
     *
     * @return What started playing. / Что включилось.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun shuffleFavorites(): PlaybackResult = play { it.playFavorites(shuffled = true) }

    /**
     * Pauses the music in Melogold.
     * Ставит музыку в Melogold на паузу.
     *
     * @return What was playing. / Что играло.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun pause(): PlaybackResult = play { it.pause() }

    /**
     * Resumes the music in Melogold where it stopped, also the last queue after the app was closed.
     * Продолжает музыку в Melogold с места остановки, в том числе последнюю очередь после закрытия приложения.
     *
     * @return What plays. / Что играет.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun resume(): PlaybackResult = play { it.resume() }

    /**
     * Skips to the next track in Melogold.
     * Переключает Melogold на следующий трек.
     *
     * @return What plays now. / Что играет теперь.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun next(): PlaybackResult = play { it.next() }

    private suspend fun play(command: suspend (VoiceCommands) -> VoiceResult): PlaybackResult =
        withContext(Dispatchers.IO) {
            answer {
                val result = command(commands)
                PlaybackResult.of(result, openApp = if (result.needsApp) openApp() else null)
            }
        }

    /** Opens Melogold and continues the queue there: MEDIA_PLAY_FROM_SEARCH with nothing to search for. */
    private fun openApp(): PendingIntent = pendingIntent(
        Intent(this, MainActivity::class.java)
            .setAction(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** The errors of the voice commands as the errors an agent understands. */
    private inline fun <T> answer(block: () -> T): T = try {
        block()
    } catch (e: VoiceException) {
        throw e.toAppFunctionException()
    } catch (e: PlayerConnection.Unavailable) {
        throw AppFunctionAppUnknownException(e.message)
    }
}
