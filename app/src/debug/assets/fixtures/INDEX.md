# Фикстуры InnerTube

Записаны `scripts/fixtures/record.sh`. Машиночитаемый список с телами запросов — `index.json`
(у продолжений в `request.continuation` лежит токен из предыдущей страницы).

**Обезличивание и чистка.** `visitorData` везде заменён на `CgtBTk9OWU1JWkVEMCiAgICABg%3D%3D`. Удалены
`trackingParams`, `clickTrackingParams`, `loggingDirectives`, `serviceTrackingParams`, `trackingParam`,
`responseId`, `consistencyTokenJar`, рекламные блоки и `playbackTracking`/`attestation`/`heartbeatParams`
плеера. У форматов потоков удалены `url`/`signatureCipher`, у `streamingData` — ссылки на манифесты;
в остальных ссылках `ip=` заменён на `0.0.0.0`, хост узла `rr…---sn-….googlevideo.com` — на
`rr1---sn-anonymized.googlevideo.com`, `initcwndbps` — на 0. У самых больших ответов (`stripped: menu`:
страницы продолжений длинного плейлиста, настроение, новые релизы) удалены контекстные меню `menu.menuRenderer`;
первая страница длинного плейлиста полная. Строки текста песни (`musicDescriptionShelfRenderer`) заменены
заглушками «lyrics line N» из-за авторского права; число строк, пустые строки и источник сохранены.
По той же причине в выдаче WEB тексты `snippetText` и `descriptionSnippet` (фрагменты описаний, где часто
цитируется текст песни) заменены на «description snippet», структура `runs` сохранена.

## Длинный плейлист

`ytm/playlist-long.pNN.ru.json` — публичный плейлист `VLPL85973FA7E35D0D96`, все страницы продолжений.
Шапка: «11 млн просмотров • 2 067 треков • Больше 187 ч.».
Страниц: 20; строк `musicResponsiveListItemRenderer` всего: 1934, из них серых (`MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT`): 198; разных `playlistItemData.videoId`: 1934.
Разница с числом в шапке — треки, которые YouTube Music в ответ не отдаёт вовсе. Плейлист «на 2096 треков»
из REWRITE.md — плейлист живой проверки, его id не сохранился; этот взят как ближайший большой и старый публичный.

## Файлы

| Файл | Эндпоинт | Клиент | Запрос (без context) | hl / gl | HTTP | Дата | Примечание |
|---|---|---|---|---|---|---|---|
| `web/channel-videos-daftpunk.en.json` | `www.youtube.com/youtubei/v1/browse` | WEB 2.20260922.06.00 | browseId: UC_kRDKYrUlrbtrSiyu5Tflg, params: EgZ2aWRlb3PyBgQKAjoA | en / US | 200 | 2026-09-24 |  |
| `web/channel-videos-daftpunk.ru.json` | `www.youtube.com/youtubei/v1/browse` | WEB 2.20260922.06.00 | browseId: UC_kRDKYrUlrbtrSiyu5Tflg, params: EgZ2aWRlb3PyBgQKAjoA | ru / RU | 200 | 2026-09-24 |  |
| `web/channel-videos.en.json` | `www.youtube.com/youtubei/v1/browse` | WEB 2.20260922.06.00 | browseId: UCy_vnPBNh9FqtyH9Qc-aiSA, params: EgZ2aWRlb3PyBgQKAjoA | en / US | 200 | 2026-09-24 | вкладка «Видео» канала Gavrik's Archive |
| `web/channel-videos.p01.en.json` | `www.youtube.com/youtubei/v1/browse` | WEB 2.20260922.06.00 | continuation: …WUJBJTNEJTNE | en / US | 200 | 2026-09-24 | продолжение web/channel-videos |
| `web/channel-videos.p01.ru.json` | `www.youtube.com/youtubei/v1/browse` | WEB 2.20260922.06.00 | continuation: …WUJBJTNEJTNE | ru / RU | 200 | 2026-09-24 | продолжение web/channel-videos |
| `web/channel-videos.ru.json` | `www.youtube.com/youtubei/v1/browse` | WEB 2.20260922.06.00 | browseId: UCy_vnPBNh9FqtyH9Qc-aiSA, params: EgZ2aWRlb3PyBgQKAjoA | ru / RU | 200 | 2026-09-24 | вкладка «Видео» канала Gavrik's Archive |
| `web/resolve-url-handle-cyrillic.en.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/@%D0%9A%D0%B8%D0%BD%D0%BE%D0%93%D1%80%D1%83%D0%BF%D0%BF%D0%B0%D0%BA%D1%80%D0%BE%D0%B2%D0%B8-%D0%B74%D0%BC | en / US | 200 | 2026-09-24 |  |
| `web/resolve-url-handle-cyrillic.ru.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/@%D0%9A%D0%B8%D0%BD%D0%BE%D0%93%D1%80%D1%83%D0%BF%D0%BF%D0%B0%D0%BA%D1%80%D0%BE%D0%B2%D0%B8-%D0%B74%D0%BC | ru / RU | 200 | 2026-09-24 |  |
| `web/resolve-url-handle-direct.en.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/@DaftPunkVEVO | en / US | 200 | 2026-09-24 |  |
| `web/resolve-url-handle-direct.ru.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/@DaftPunkVEVO | ru / RU | 200 | 2026-09-24 |  |
| `web/resolve-url-handle.en.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/@daftpunk | en / US | 200 | 2026-09-24 | ответ — urlEndpoint на /daftpunk, нужен второй шаг |
| `web/resolve-url-handle.ru.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/@daftpunk | ru / RU | 200 | 2026-09-24 | ответ — urlEndpoint на /daftpunk, нужен второй шаг |
| `web/resolve-url-legacy-c.en.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/c/daftpunk | en / US | 200 | 2026-09-24 |  |
| `web/resolve-url-legacy-c.ru.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/c/daftpunk | ru / RU | 200 | 2026-09-24 |  |
| `web/resolve-url-legacy-user.en.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/user/daftpunkalive | en / US | 200 | 2026-09-24 |  |
| `web/resolve-url-legacy-user.ru.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/user/daftpunkalive | ru / RU | 200 | 2026-09-24 |  |
| `web/resolve-url-not-found.en.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/@thisdoesnotexist-zz9qx | en / US | 404 | 2026-09-24 | HTTP 404 |
| `web/resolve-url-not-found.ru.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/@thisdoesnotexist-zz9qx | ru / RU | 404 | 2026-09-24 | HTTP 404 |
| `web/resolve-url-vanity.en.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/daftpunk | en / US | 200 | 2026-09-24 |  |
| `web/resolve-url-vanity.ru.json` | `www.youtube.com/youtubei/v1/navigation/resolve_url` | WEB 2.20260922.06.00 | url: https://www.youtube.com/daftpunk | ru / RU | 200 | 2026-09-24 |  |
| `web/search-channels.en.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | query: daft punk, params: EgIQAg%3D%3D | en / US | 200 | 2026-09-24 |  |
| `web/search-channels.p01.en.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | continuation: …ZmVlZA%3D%3D | en / US | 200 | 2026-09-24 | продолжение web/search-channels |
| `web/search-channels.p01.ru.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | continuation: …YXJjaC1mZWVk | ru / RU | 200 | 2026-09-24 | продолжение web/search-channels |
| `web/search-channels.ru.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | query: кино группа крови, params: EgIQAg%3D%3D | ru / RU | 200 | 2026-09-24 |  |
| `web/search-live.en.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | query: lofi, params: EgJAAQ%3D%3D | en / US | 200 | 2026-09-24 |  |
| `web/search-live.p01.en.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | continuation: …ZmVlZA%3D%3D | en / US | 200 | 2026-09-24 | продолжение web/search-live |
| `web/search-live.p01.ru.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | continuation: …ZmVlZA%3D%3D | ru / RU | 200 | 2026-09-24 | продолжение web/search-live |
| `web/search-live.ru.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | query: lofi, params: EgJAAQ%3D%3D | ru / RU | 200 | 2026-09-24 |  |
| `web/search-playlists.en.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | query: daft punk, params: EgIQAw%3D%3D | en / US | 200 | 2026-09-24 |  |
| `web/search-playlists.p01.en.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | continuation: …ZmVlZA%3D%3D | en / US | 200 | 2026-09-24 | продолжение web/search-playlists |
| `web/search-playlists.p01.ru.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | continuation: …NoLWZlZWQ%3D | ru / RU | 200 | 2026-09-24 | продолжение web/search-playlists |
| `web/search-playlists.ru.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | query: кино группа крови, params: EgIQAw%3D%3D | ru / RU | 200 | 2026-09-24 |  |
| `web/search-videos.en.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | query: daft punk, params: EgIQAQ%3D%3D | en / US | 200 | 2026-09-24 |  |
| `web/search-videos.p01.en.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | continuation: …YXJjaC1mZWVk | en / US | 200 | 2026-09-24 | продолжение web/search-videos |
| `web/search-videos.p01.ru.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | continuation: …NoLWZlZWQ%3D | ru / RU | 200 | 2026-09-24 | продолжение web/search-videos |
| `web/search-videos.ru.json` | `www.youtube.com/youtubei/v1/search` | WEB 2.20260922.06.00 | query: кино группа крови, params: EgIQAQ%3D%3D | ru / RU | 200 | 2026-09-24 |  |
| `ytm/album.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: MPREb_OLmD8O5IYNS | en / US | 200 | 2026-09-24 |  |
| `ytm/album.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: MPREb_OLmD8O5IYNS | ru / RU | 200 | 2026-09-24 |  |
| `ytm/artist-ugc-channel.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: UCy_vnPBNh9FqtyH9Qc-aiSA | en / US | 200 | 2026-09-24 | обычный канал: музыкальных секций нет (§4.8.5) |
| `ytm/artist-ugc-channel.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: UCy_vnPBNh9FqtyH9Qc-aiSA | ru / RU | 200 | 2026-09-24 | обычный канал: музыкальных секций нет (§4.8.5) |
| `ytm/artist.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: UCL9NQ06h7I0CRUcGxPWMtkQ | en / US | 200 | 2026-09-24 |  |
| `ytm/artist.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: UCL9NQ06h7I0CRUcGxPWMtkQ | ru / RU | 200 | 2026-09-24 |  |
| `ytm/explore.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: FEmusic_explore | en / US | 200 | 2026-09-24 |  |
| `ytm/explore.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: FEmusic_explore | ru / RU | 200 | 2026-09-24 |  |
| `ytm/lyrics.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: MPLYt_OLmD8O5IYNS-1 | en / US | 200 | 2026-09-24 | вкладка «Текст» из ytm/next; строки текста заменены заглушками |
| `ytm/lyrics.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: MPLYt_OLmD8O5IYNS-1 | ru / RU | 200 | 2026-09-24 | вкладка «Текст» из ytm/next; строки текста заменены заглушками |
| `ytm/mood.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: FEmusic_moods_and_genres_category, params: ggMPOg1uX1JOQWZFeDByc2Jm | en / US | 200 | 2026-09-24 | первое настроение из ytm/moods (без menu) |
| `ytm/mood.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: FEmusic_moods_and_genres_category, params: ggMPOg1uX044Z2o5WERLckpU | ru / RU | 200 | 2026-09-24 | первое настроение из ytm/moods (без menu) |
| `ytm/moods.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: FEmusic_moods_and_genres | en / US | 200 | 2026-09-24 |  |
| `ytm/moods.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: FEmusic_moods_and_genres | ru / RU | 200 | 2026-09-24 |  |
| `ytm/new-releases-albums.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: FEmusic_new_releases_albums | en / US | 200 | 2026-09-24 |  (без menu) |
| `ytm/new-releases-albums.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: FEmusic_new_releases_albums | ru / RU | 200 | 2026-09-24 |  (без menu) |
| `ytm/next-radio.en.json` | `music.youtube.com/youtubei/v1/next` | WEB_REMIX 1.20260922.09.00 | videoId: xtxjm7ciwmc, playlistId: RDAMVMxtxjm7ciwmc, params: wAEB, isAudioOnly: true | en / US | 200 | 2026-09-24 | радио по треку |
| `ytm/next-radio.ru.json` | `music.youtube.com/youtubei/v1/next` | WEB_REMIX 1.20260922.09.00 | videoId: xtxjm7ciwmc, playlistId: RDAMVMxtxjm7ciwmc, params: wAEB, isAudioOnly: true | ru / RU | 200 | 2026-09-24 | радио по треку |
| `ytm/next.en.json` | `music.youtube.com/youtubei/v1/next` | WEB_REMIX 1.20260922.09.00 | videoId: xtxjm7ciwmc, isAudioOnly: true | en / US | 200 | 2026-09-24 |  |
| `ytm/next.ru.json` | `music.youtube.com/youtubei/v1/next` | WEB_REMIX 1.20260922.09.00 | videoId: xtxjm7ciwmc, isAudioOnly: true | ru / RU | 200 | 2026-09-24 |  |
| `ytm/player-ios.en.json` | `www.youtube.com/youtubei/v1/player` | IOS 20.10.4 | videoId: xtxjm7ciwmc, contentCheckOk: true, racyCheckOk: true | en / US | 200 | 2026-09-24 | loudness: playerConfig.audioConfig и adaptiveFormats[].loudnessDb; url/signatureCipher удалены |
| `ytm/player-ios.ru.json` | `www.youtube.com/youtubei/v1/player` | IOS 20.10.4 | videoId: xtxjm7ciwmc, contentCheckOk: true, racyCheckOk: true | ru / RU | 200 | 2026-09-24 | loudness: playerConfig.audioConfig и adaptiveFormats[].loudnessDb; url/signatureCipher удалены |
| `ytm/playlist-editorial.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: VLRDCLAK5uy_nBE4bLuBHUXWZrF59ZrkPEToKt8M_I3Vc | en / US | 200 | 2026-09-24 | первый RDCLAK5uy_ из ytm/mood |
| `ytm/playlist-editorial.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: VLRDCLAK5uy_mkEwQuegHYB8_aAzBO8Q__6gGoaFblISw | ru / RU | 200 | 2026-09-24 | первый RDCLAK5uy_ из ytm/mood |
| `ytm/playlist-long.p00.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: VLPL85973FA7E35D0D96 | en / US | 200 | 2026-09-24 |  |
| `ytm/playlist-long.p00.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: VLPL85973FA7E35D0D96 | ru / RU | 200 | 2026-09-24 |  |
| `ytm/playlist-long.p01.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …MEQ5Ng%3D%3D | ru / RU | 200 | 2026-09-24 | продолжение p00 (без menu) |
| `ytm/playlist-long.p02.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p01 (без menu) |
| `ytm/playlist-long.p03.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p02 (без menu) |
| `ytm/playlist-long.p04.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p03 (без menu) |
| `ytm/playlist-long.p05.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p04 (без menu) |
| `ytm/playlist-long.p06.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p05 (без menu) |
| `ytm/playlist-long.p07.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p06 (без menu) |
| `ytm/playlist-long.p08.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p07 (без menu) |
| `ytm/playlist-long.p09.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p08 (без menu) |
| `ytm/playlist-long.p10.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p09 (без menu) |
| `ytm/playlist-long.p11.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p10 (без menu) |
| `ytm/playlist-long.p12.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p11 (без menu) |
| `ytm/playlist-long.p13.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p12 (без menu) |
| `ytm/playlist-long.p14.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p13 (без menu) |
| `ytm/playlist-long.p15.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p14 (без menu) |
| `ytm/playlist-long.p16.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p15 (без menu) |
| `ytm/playlist-long.p17.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p16 (без menu) |
| `ytm/playlist-long.p18.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p17 (без menu) |
| `ytm/playlist-long.p19.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | continuation: …M1RDBEOTY%3D | ru / RU | 200 | 2026-09-24 | продолжение p18 (без menu) |
| `ytm/playlist.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: VLPLurittKGarBvW6lCCJYlTGd6U2bBfP8FE | en / US | 200 | 2026-09-24 |  |
| `ytm/playlist.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: VLPLurittKGarBvW6lCCJYlTGd6U2bBfP8FE | ru / RU | 200 | 2026-09-24 |  |
| `ytm/related.en.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: MPTRt_OLmD8O5IYNS-1 | en / US | 200 | 2026-09-24 | вкладка «Похожие» из ytm/next |
| `ytm/related.ru.json` | `music.youtube.com/youtubei/v1/browse` | WEB_REMIX 1.20260922.09.00 | browseId: MPTRt_OLmD8O5IYNS-1 | ru / RU | 200 | 2026-09-24 | вкладка «Похожие» из ytm/next |
| `ytm/search-albums.en.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: daft punk, params: EgWKAQIYAWoOEAMQBBAJEAoQBRAQEBU%3D | en / US | 200 | 2026-09-24 |  |
| `ytm/search-albums.ru.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: кино группа крови, params: EgWKAQIYAWoOEAMQBBAJEAoQBRAQEBU%3D | ru / RU | 200 | 2026-09-24 |  |
| `ytm/search-all.en.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: daft punk | en / US | 200 | 2026-09-24 |  |
| `ytm/search-all.ru.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: кино группа крови | ru / RU | 200 | 2026-09-24 |  |
| `ytm/search-artists.en.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: daft punk, params: EgWKAQIgAWoOEAMQBBAJEAoQBRAQEBU%3D | en / US | 200 | 2026-09-24 |  |
| `ytm/search-artists.ru.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: кино группа крови, params: EgWKAQIgAWoOEAMQBBAJEAoQBRAQEBU%3D | ru / RU | 200 | 2026-09-24 |  |
| `ytm/search-community-playlists.en.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: daft punk, params: EgeKAQQoAEABag4QAxAEEAkQChAFEBAQFQ%3D%3D | en / US | 200 | 2026-09-24 |  |
| `ytm/search-community-playlists.ru.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: кино группа крови, params: EgeKAQQoAEABag4QAxAEEAkQChAFEBAQFQ%3D%3D | ru / RU | 200 | 2026-09-24 |  |
| `ytm/search-featured-playlists.en.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: daft punk, params: EgeKAQQoADgBag4QAxAEEAkQChAFEBAQFQ%3D%3D | en / US | 200 | 2026-09-24 |  |
| `ytm/search-featured-playlists.ru.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: кино группа крови, params: EgeKAQQoADgBag4QAxAEEAkQChAFEBAQFQ%3D%3D | ru / RU | 200 | 2026-09-24 |  |
| `ytm/search-songs.en.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: daft punk, params: EgWKAQIIAWoOEAMQBBAJEAoQBRAQEBU%3D | en / US | 200 | 2026-09-24 |  |
| `ytm/search-songs.ru.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: кино группа крови, params: EgWKAQIIAWoOEAMQBBAJEAoQBRAQEBU%3D | ru / RU | 200 | 2026-09-24 |  |
| `ytm/search-suggestions.en.json` | `music.youtube.com/youtubei/v1/music/get_search_suggestions` | WEB_REMIX 1.20260922.09.00 | input: daft p | en / US | 200 | 2026-09-24 |  |
| `ytm/search-suggestions.ru.json` | `music.youtube.com/youtubei/v1/music/get_search_suggestions` | WEB_REMIX 1.20260922.09.00 | input: кино | ru / RU | 200 | 2026-09-24 |  |
| `ytm/search-videos.en.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: daft punk, params: EgWKAQIQAWoOEAMQBBAJEAoQBRAQEBU%3D | en / US | 200 | 2026-09-24 |  |
| `ytm/search-videos.ru.json` | `music.youtube.com/youtubei/v1/search` | WEB_REMIX 1.20260922.09.00 | query: кино группа крови, params: EgWKAQIQAWoOEAMQBBAJEAoQBRAQEBU%3D | ru / RU | 200 | 2026-09-24 |  |
