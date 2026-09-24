# Копии баз настоящих ViMusic, ViTune и RiMusic

Три резервные копии сделаны самими приложениями на эмуляторе, через их пункт «Backup / Save to backup» (SAF в `Download/`). Их используют тесты импортёра (`REWRITE.md` §4.5, §4.13, задача R2.2). Записала задача R1.2, 2026-09-24.

- **Эмулятор:** `Pixel_9`, Android 16 (API 36), `sdk_gphone64_arm64`, локаль `en-US`.
- **APK:** release-сборки с официальных страниц GitHub Releases. После выгрузки приложения удалены с эмулятора.
- **Файлы:** байты не менялись, это ровно то, что записало приложение. У всех трёх в заголовке `journal_mode = wal`: Room экспортирует файл в WAL-режиме, без файлов `-wal` и `-shm`. Импортёр переключает свою копию на `DELETE` (§4.5.2, п. 1). Тесты тоже копируют базу во временный каталог и только потом открывают: SQLite, открыв такую базу на месте, создаёт рядом `-shm` и `-wal` (они в `.gitignore`), а macOS-сборка `sqlite3` их не удаляет.

| Файл | Приложение | APK (sha256) | `user_version` | `identity_hash` | Размер | sha256 файла |
|---|---|---|---|---|---|---|
| `vitune-1.2.4.db` | ViTune 1.2.4 (`app.vitune.android`, versionCode 25) | [app.vitune.android_25.apk](https://github.com/bartoostveen/ViTune/releases/tag/v1.2.4) `dd24192a2da461a4c7235e48d6313d604e557b3f5fd05017719e5b68257fcc76` | 30 | `17e6383ea8e4d6c4774898994501f11a` | 167 936 | `5254d8d4cd4975b609e93fe1006cdfa41518ed79115b38fb5a8e10222c646cb3` |
| `vimusic-0.5.4.db` | ViMusic 0.5.4 (`it.vfsfitvnm.vimusic`, versionCode 20) | [app-release.apk](https://github.com/vfsfitvnm/ViMusic/releases/tag/v0.5.4) `353893b95f45e3387745981f09c28c54be315d3732bbe861d08bccc560c3284f` | 23 | `205c24811149a247279bcbfdc2d6c396` | 151 552 | `7d0b5004652929a17b0b5b61c8daf14892cac4c3c0a5499cb0ea948f427acead` |
| `rimusic-0.6.72.1.db` | RiMusic 0.6.72.1 (`it.fast4x.rimusic`, versionCode 85, foss) | [app-foss-release.apk](https://github.com/fast4x/RiMusic/releases/tag/v0.6.72.1) `e2d793b96a347e246f6d26db000a25cae98506cf37cea1ef94af9d5b68db720b` | 27 | `2cc96339f3c4d18e5f5475a3975033c5` | 151 552 | `28cc15bc694ef2c11163a2e109fad5a031a8db6929189cc8db9495b969f81277` |

Почему RiMusic 0.6.72.1: это последний релиз с APK на GitHub. У 0.6.73–0.6.76 файлов в релизе нет, они распространялись только через F-Droid и IzzyOnDroid. ViMusic 0.5.4 — последний релиз архивного репозитория.

## Что заведено

Во всех трёх копиях библиотека собрана одинаково:

1. **Плейлист, связанный с YouTube** — импорт публичного плейлиста `PLurittKGarBvW6lCCJYlTGd6U2bBfP8FE` («Кино - Группа крови (Moroz Records, MR 96097 CD, 1988/1996)», 11 видео канала Gavrik's Archive). `Playlist.browseId = "VLPLurittKGarBvW6lCCJYlTGd6U2bBfP8FE"`, **с префиксом `VL`**.
2. **Свой плейлист `Road trip`** — 10 песен Daft Punk из поиска YTM, добавленных по одной.
3. **Ещё 9 треков**: в ViTune — прослушанные из радио и 1 лайк, в ViMusic и RiMusic — лайки из поиска «the weeknd».
4. Лайки, прослушивания, скрытый трек или дизлайк — по возможностям приложения (таблица ниже).
5. Поисковые запросы — только тестовые, набранные агентом: `daft punk random access memories`, `the weeknd`, у ViMusic ещё `kino gruppa krovi`.

**Обезличивание.** Вход в аккаунты YouTube и Piped не выполнялся, `PipedSession` в ViTune пуст (0 строк). В `SearchQuery` только перечисленные выше тестовые запросы. Личных данных в копиях нет, поэтому строки не переписывались.

## Ожидаемые счётчики

Каждое значение — `SELECT count(*) FROM <таблица> [WHERE …]`. Тест сверяет их до импорта.

| Запрос | ViTune 1.2.4 | ViMusic 0.5.4 | RiMusic 0.6.72.1 |
|---|---|---|---|
| `Song` | 30 | 30 | 30 |
| `Song WHERE id` — 11 символов `[A-Za-z0-9_-]` | 30 | 30 | 30 |
| `Song WHERE likedAt > 0` | 6 | 13 | 11 |
| `Song WHERE likedAt < 0` (дизлайк) | 0 | 0 | 1 |
| `Song WHERE totalPlayTimeMs > 0` | 9 | 0 | 4 |
| `Song WHERE blacklisted = 1` | 1 | — (нет колонки) | — (нет колонки) |
| `Song WHERE explicit = 1` | 3 | — | — |
| `Song WHERE title LIKE 'e:%'` | 0 | 0 | 5 |
| `Playlist` | 2 | 2 | 2 |
| `Playlist WHERE browseId IS NOT NULL` | 1 | 1 | 1 |
| `SongPlaylistMap` | 21 (11 + 10) | 21 (11 + 10) | 21 (11 + 10) |
| `Event` | 9 | 0 | 6 |
| `Artist` | 19 | 0 | 10 |
| `Artist WHERE bookmarkedAt IS NOT NULL` | 1 (Daft Punk) | 0 | 0 |
| `Album` | 11 | 0 | 10 |
| `SongArtistMap` | 40 | 0 | 37 |
| `SongAlbumMap` | 19 | 0 | 19 |
| `SearchQuery` | 2 | 3 | 2 |
| `Format` | 12 | 0 | 5 |
| `QueuedMediaItem` | 11 | 0 | 0 |
| `Lyrics` | 0 | 0 | 0 |
| `PipedSession` | 0 | — (нет таблицы) | — (нет таблицы) |

Позиции в `SongPlaylistMap` у всех трёх плотные: 0..10 и 0..9.

## Особенности каждой копии (важно для импортёра)

### ViTune 1.2.4 (v30)

- **Скрытый трек:** `Counting Stars` (`_GWKkqNoyEA`), `blacklisted = 1`. По §4.5.3 он становится `ContentBlock(track, hide)`.
- **Прослушивания:** 9 событий по 9 разным трекам, `playTime` от 7 505 до 77 880 мс. ViTune пишет событие от 5 с воспроизведения. Часть запусков упала с ошибкой приложения «Requested video ID doesn't match returned video ID». Такие треки (`Starboy`) попали в `Song` с `totalPlayTimeMs = 0` и без события.
- **Лайк без прослушивания и без плейлиста:** `Espresso` (`kIft-LUHHVA`).
- `Playlist.thumbnail` (v30) заполнен у связанного плейлиста: URL `i.ytimg.com/…/hq720.jpg?sqp=…&rs=…`.
- `QueuedMediaItem` — 11 строк Parcel-BLOB (последняя очередь), импортёр их пропускает.

### ViMusic 0.5.4 (v23)

Сетевая часть ViMusic 0.5.4 в 2026 году почти не работает. Это видно по данным, и это полезно для тестов:
- **`durationText` у всех 30 треков — не длительность**, а число прослушиваний (`"94M plays"`, `"1.2B plays"`). Позиционный разбор выдачи YTM съехал. `durationMs` из такой строки должен получиться `NULL`, а не ошибка.
- **`artistsText = NULL`** у всех 30 треков. `Artist`, `Album`, `SongArtistMap`, `SongAlbumMap` пусты.
- **Прослушиваний и скрытого трека нет.** Воспроизведение не запускается: запрос `player` отвечает `FAILED_PRECONDITION`. ViMusic пишет `Song` только после успешного `player`, а событие — от 30 с воспроизведения. «Скрыть» в ViMusic — это `totalPlayTimeMs = 0` у прослушанного трека, отдельного флага нет. Без воспроизведения скрыть трек нельзя.
- **Связанный плейлист** создан импортом по ссылке, но страницу плейлиста ViMusic не разобрал (заголовок «Unknown», 0 песен). Поэтому имя набрано вручную латиницей: `Kino - Gruppa krovi`. 11 песен добавлены из поиска «kino gruppa krovi»: официальные треки «Кино», кириллица в `title`. Содержимое связанного плейлиста **не совпадает** с плейлистом на YouTube. Первое обновление связи в режиме `append` допишет недостающие видео.

### RiMusic 0.6.72.1 (форк, своя схема v27)

- **`user_version = 27`, но схема не совпадает с v27 семейства ViMusic/ViTune.** Распознавать можно только по `identity_hash` (§4.5.2, п. 4), по `user_version` нельзя. Иначе по правилу «незнакомый хэш» это совместимый форк. В `Song` нет `blacklisted` и `explicit`. В `Playlist` есть лишние колонки `isEditable`, `isYoutubePlaylist`: у связанного плейлиста `isYoutubePlaylist = 0`. В `SongPlaylistMap` есть `setVideoId` и `dateAdded`. Есть view `SortedSongPlaylistMap`.
- **Дизлайк:** `Wicked Games` (`g8TLU_JxCjc`), `likedAt = -1`. По §4.5.3 он становится `ContentBlock(not_interested)`. Проверено на реальной копии: RiMusic переключает сердечко по кругу «нет → лайк → `-1` → нет».
- **Explicit в названии:** у 5 треков `title` начинается с `e:` (`e:Wicked Games`, `e:Starboy (feat. Daft Punk)`…). Так RiMusic хранит пометку explicit (`cleanPrefix()` в интерфейсе). Импортёру нужно снять префикс и выставить `explicit = 1`. Этого правила в §4.5.3 пока нет — запрос к R2.2/R2.6.
- **Каждое прослушивание записано дважды.** 3 прослушивания (`11. Легенда`, `10. Дальше действовать будем мы`, `09. Прохожий`) дали 6 строк `Event`: одинаковые `songId` и `playTime`, `timestamp` отличается на 1–8 мс. `totalPlayTimeMs` тоже удвоен. Похоже, в RiMusic два слушателя `PlaybackStats`. Уникальный индекс `(songId, timestamp)` такие дубли не отсекает. Решение для R2.6: схлопывать события с одинаковыми `songId` и `playTime`, если `timestamp` отличается меньше чем на 1 с.
- **Скрытого трека нет:** в схеме RiMusic нет флага. «Hide» делает `totalPlayTimeMs = 0` (как в ViMusic), переносить нечего. Дизлайк покрывает случай `ContentBlock`.
- `08. Попробуй спеть вместе со мной`: `totalPlayTimeMs = 31460` (удвоенные 15,7 с) без события, потому что порог события в RiMusic — 20 с.

## Как пересоздать

1. Под блокировкой эмулятора поставить APK из таблицы и выдать `POST_NOTIFICATIONS`.
2. Открыть ссылку `https://music.youtube.com/playlist?list=PLurittKGarBvW6lCCJYlTGd6U2bBfP8FE` (`am start -a android.intent.action.VIEW -p <пакет>`) и импортировать плейлист.
3. Собрать `Road trip` из поиска «daft punk random access memories», поставить лайки, прослушать треки.
4. Выгрузить копию через «Backup», забрать `adb pull /sdcard/Download/<файл>.db`, затем удалить копию с эмулятора и `adb uninstall <пакет>`.

Громкость медиапотока эмулятора на время прослушиваний — 0.
