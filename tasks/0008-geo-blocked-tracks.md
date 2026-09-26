# Трек закрыт в стране: понятная ошибка вместо «не удалось получить поток»

Статус: сделано

Те же задания: `melogoldWindows/tasks/0010-geo-blocked-tracks.md`, `melogoldiOSmacOS/tasks/0010-geo-blocked-tracks.md`,
`melogoldLinux/tasks/0001-geo-blocked-tracks.md`.

## 1. Что случилось

Пользователь в России: Saba «Photosynthesis» (`cYKAr38pZcY`, «Saba - Topic») не играет, приложение пишет «Не удалось
получить поток. Возможно, нужно обновить компонент извлечения». С VPN через Хельсинки тоже не играет: Google считает
этот адрес российским (Gemini там тоже не работает).

Причина: правообладатель открыл трек в 122 странах, России среди них нет. yt-dlp отвечает только «Video unavailable»,
поэтому приложение показывало общую ошибку.

Пользователь: «нужно сделать логирование этой ошибки, чтобы человек понимал, что к чему, причём сразу на всех
платформах».

## 2. Решение (одинаково на всех клиентах)

Когда поток не получен, клиент один раз спрашивает YouTube, почему:

1. **Запрос:** `POST https://youtubei.googleapis.com/youtubei/v1/player`, клиент `WEB` (как `DefaultYouTubeWeb`),
   тело `{context, videoId}`, без проверки ответа. Таймаут 8 с.
2. **Из ответа:**
   - `playabilityStatus.status` и `.reason`;
   - `microformat.playerMicroformatRenderer.availableCountries` (у `WEB_REMIX` это `microformatDataRenderer`) — где
     трек открыт; YouTube присылает его даже для трека, который не играет;
   - `responseContext.visitorData` — страна, в которой YouTube видит устройство: base64 (url-safe, `%3D` → `=`)
     protobuf, поле 6 — сообщение, в его поле 1 — код страны (`…MigKAk5M…` → `NL`). Это страна по мнению YouTube:
     за VPN — та, к которой YouTube относит сервер VPN.
3. **Классификация** (по порядку):
   - страна известна, список не пуст, страны в списке нет → **закрыт в стране** (страна, число стран);
   - в сообщении yt-dlp или `reason` есть `available in your country` / `not made this video available in your
     country` / `blocked it in your country` → **закрыт в стране** (без числа);
   - `confirm your age`, `age-restricted`, `inappropriate for some users` → **нужен вход (возраст)**;
   - `Private video`, `has been removed`, `account associated with this video has been terminated`,
     `no longer available` → **удалено или закрыто**;
   - иначе — прежняя общая ошибка.
4. **Текст в карточке ошибки** (кнопки «Повторить · Пропустить · Другие версии» остаются; «Другие версии» часто
   находит доступную загрузку того же трека):

   | Случай | Русский | English |
   |---|---|---|
   | страна и число | Недоступно в стране «Россия»: YouTube считает, что вы там, а правообладатель открыл трек в 122 других странах. С VPN выберите сервер другой страны: некоторые серверы YouTube тоже относит к стране «Россия». | Unavailable in Russia: YouTube places you there, and the rights holder opened this track in 122 other countries. With a VPN, pick a server in another country: YouTube counts some VPN servers as Russia too. |
   | только страна | Недоступно в стране «Россия»: YouTube считает, что вы там, а правообладатель закрыл трек для этой страны. С VPN выберите сервер другой страны: … | Unavailable in Russia: YouTube places you there, and the rights holder closed this track for it. With a VPN, … |
   | без страны | Недоступно в вашей стране | Unavailable in your country |

   Название страны — по коду в языке интерфейса; число — с формами множественного числа («в 121 другой стране», «в 122
   других странах»).
5. **Журнал:** одна строка на отказ — id трека, итог, статус и причина YouTube, страна, число стран, последняя строка
   yt-dlp. Её видно в логах и в «Диагностике», если она есть.

## 3. Android (сделано, 0.1.8)

- `providers/innertube`: `PlayerResponse.responseContext`, `.microformat`; `utils/VisitorData.kt` (`visitorCountry`);
  `requests/Playability.kt` (`Innertube.playability`).
- `service/Unavailability.kt` (`unavailability`), `PlayerService.whyUnavailable` вместо `VideoIdMismatchException`.
- `RestrictedVideoException(country, availableCountries)`, текст — `PlaybackError.kt`, строки
  `player_error_geo_country`, `player_error_geo_country_open` (plurals).
- Тесты: `VisitorDataTest` (настоящие `visitorData` NL и DE), `UnavailabilityTest`, `GeoMessageTest`.
