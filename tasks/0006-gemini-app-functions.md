# Gemini: «найди и включи» в Melogold

Статус: сделано

Код и проверка на эмуляторе готовы; сторону Gemini подтвердить на телефоне пользователя (§5).

## 1. Что нужно пользователю

Попросить Gemini голосом найти песню в Melogold и включить её, как Siri на iPhone (App Intents в клиенте Apple).

Сейчас так не выходит (Pixel 7 Pro, Android 17 beta, 2026-09-26). На «включи … в Melogold» Gemini отвечает, что не может управлять внешними приложениями, и к тому же слышит название как «Mellow Gold». Стандартный голосовой вход Android у приложения есть (`MEDIA_PLAY_FROM_SEARCH`, `onPlayFromSearch` в медиасессии), но Gemini им не пользуется. Им пользуются Google Assistant и Android Auto.

## 2. Решение

AppFunctions (Android 16+, `androidx.appfunctions`): приложение объявляет функции, которые Gemini вызывает напрямую, без распознавания названия приложения на слух.

- **Функции:**
  - `playSong(query)`: поиск по YouTube Music, затем YouTube; играет лучший результат, дальше автовоспроизведение похожих. Правило то же, что у голосового запроса (`VoiceQueryResolver`, REWRITE §3.14.3).
  - `playArtist(name)`, `playAlbum(name)`, `playPlaylist(name)`: свои плейлисты сначала ищутся в Библиотеке.
  - `playFavorites()` и `shuffleFavorites()`.
  - `pause()`, `resume()`, `next()`.
  - `searchSongs(query)`: возвращает список треков, чтобы Gemini мог переспросить.
- **Описания функций и параметров** — на русском и английском: по ним Gemini выбирает, что вызвать.
- **Если AppFunctions у Gemini в регионе недоступны,** остаются голосовой ввод в Поиске приложения и Google Assistant, если он включён вместо Gemini.

## 3. Проверка

- Юнит-тесты функций на фиктивном каталоге.
- На телефоне пользователя: «Окей, Google, включи „Звезда по имени Солнце“ в Melogold» и «включи моё Избранное в Melogold».
- Если Gemini функции не видит, выяснить почему (версия Gemini, регион, флаги) и записать сюда.

**Почему Gemini может не вызвать функции даже с этим кодом.** По документации Google (май 2026, пост об Android 17 от 2026-06-16) интеграция AppFunctions с Gemini — закрытое превью для доверенных тестировщиков; доступ только через Early Access Program (goo.gle/eap-af), отбор не гарантирован. «Gemini Intelligence» к тому же требует флагман с 12 ГБ+ ОЗУ и Gemini Nano v3 (Pixel 10, Galaxy S26), Pixel 7 Pro туда не входит; связаны ли с этим вызовы сторонних AppFunctions, Google не пишет. Функции всё равно сделаны: они проверяются через adb и заработают, как только доступ откроют.

## 4. Что сделано

- **Библиотека.** `androidx.appfunctions:appfunctions` и `appfunctions-compiler` (KSP) 1.0.0-alpha12. Ей нужны compileSdk 37 и AGP 9.1+, у проекта уже compileSdk 37 и AGP 9.4.1, так что менять сборку не пришлось. minSdk остался 24: ниже Android 16 сервис выключен в манифесте (`@bool/enablePlatformAppFunctionService` библиотеки, `true` только в `values-v36`): там нет ни разрешения `BIND_APP_FUNCTION_SERVICE`, ни базового класса сервиса. KSP собирает `MelogoldAppFunctionService` из `appfunctions/BaseMelogoldAppFunctionService.kt` и кладёт в assets индекс `melogold_app_functions.xml` (v2, Android 17) и `melogold_app_functions-v1.xml` (индекс Android 16). Сервис и `res/xml/app_metadata.xml` объявлены в манифесте.
- **Описания.** KDoc каждой функции и параметра — описание для агента, по-английски и по-русски в одной строке: локализовать их нельзя. В `app_metadata.xml` — что умеет приложение, порядок вызовов («неоднозначно — `searchSongs`, затем `playSong` с `videoId`») и имена, которые Gemini слышит вместо Melogold: «Mellow Gold», «Melo Gold», «Мелоголд», «Мело Голд», «Мелогольд». Описание для человека (`displayDescription`) локализовано в `strings_voice.xml`.
- **Одно правило для всех входов.** `:core:domain` → `app.melogold.domain.voice.VoiceCommands` (правила), `VoiceQuery` (разбор `mediaFocus`), `NameMatch` (сравнение названий: регистр, «ё», пунктуация не важны; точное > целые слова > часть). Приложение даёт каталог и плеер: `playback/session/VoiceQueryResolver.kt` (Room + Innertube + `PlayerService`). Туда же теперь идут `MEDIA_PLAY_FROM_SEARCH` активити, `onPlayFromSearch` обеих медиасессий и AppFunctions:
  - песня — лучший результат YouTube Music, иначе первое видео YouTube, дальше похожие; без сети — из Библиотеки; поиск ограничен 8 с;
  - исполнитель — микс YouTube Music; альбом и плейлист — с первого трека; своё из Библиотеки первым (плейлист — по любой части названия, исполнитель и альбом — по целым словам); плейлист «Избранное» — это Избранное, если нет своего с таким названием;
  - пустой запрос продолжает последнюю очередь;
  - фокус «исполнитель», «альбом», «плейлист» у Google Assistant и Android Auto теперь включает исполнителя, альбом, плейлист, а не песню с таким названием.
- **Ошибки для агента.** Ничего не нашлось — `AppFunctionElementNotFoundException` (код 1500), неверный запрос — `AppFunctionInvalidArgumentException` (1001), нет сети или YouTube не ответил — `AppFunctionAppUnknownException` (3000; `NotSupported` значил бы «никогда не умеет»). Сообщения на русском и английском.
- **Игра в фоне.** Если система не дала запустить сервис переднего плана (приложение в фоне, Android 12+), приложение больше не падает: `PlayerService.goForeground` ловит отказ, а ответ функции несёт `openApp` — PendingIntent, который открывает Melogold и продолжает очередь.
- **Исправлено попутно.**
  - Трек, впервые сохранённый без обложки, исполнителей и длительности (играл по ссылке: dQw4w9WgXcQ, jNQXAC9IVRw), навсегда оставался без обложки: `Database.insert` игнорировал конфликт. Теперь при повторной вставке с метаданными строка получает недостающее (`fillMissing`), имеющееся не перезаписывается.
  - Поиск YouTube Music без результатов считался «YouTube не ответил»: теперь это «не найдено».
- **Отладочная команда** (только debug-сборка): `AppFunction` вызывает функции приложения через систему, как агент (своё приложение вызывать свои функции может):
  ```
  adb shell am broadcast -a app.melogold.android.debug.CMD --es cmd AppFunction --es arg list \
    -n app.melogold.android.debug/app.melogold.android.debug.DebugReceiver
  adb shell "am broadcast -a app.melogold.android.debug.CMD --es cmd AppFunction --es arg playSong \
    --es query 'Звезда по имени Солнце' -n app.melogold.android.debug/app.melogold.android.debug.DebugReceiver"
  ```
- **Тесты.** `:core:domain` — `VoiceCommandsTest` (27: каждая функция на фиктивном каталоге и плеере без сети), `NameMatchTest`, `VoiceQueryTest`. `:app` — `AppFunctionsRegistrationTest` (индекс перечисляет ровно 10 функций генерированного сервиса, у каждой функции и параметра описание на двух языках, v1-индекс, сервис в манифесте только для системы, описание приложения с «Mellow Gold», ошибки для агента) и `SongFillMissingTest`. Библиотечный `appfunctions-testing` не подключён: его правило — фейк Robolectric со старым API в документации и тянет robolectric-beta.
- **Эмулятор** (emulator-5554, Android 16, API 36; `cmd app_function` там нет, он с Android 17):
  - `dumpsys app_function` перечисляет все 10 функций `app.melogold.android.debug` — и у debug, и у staging (R8);
  - `AppFunction list`: система знает все 10, все включены; `searchAppFunctions` на этой сборке пуст — полного индекса v2 для агентов в Android 16 нет;
  - `playSong("Звезда по имени Солнце")` через систему вернул `PlaybackResult{title: Звезда по имени Солнце, artists: Кино, source: youtube_music}`, без `openApp` (сервис переднего плана разрешён), трек сразу на паузе, звук 0; `pause` через систему — тоже; неизвестный исполнитель — код 1500, `searchSongs` без `query` — 1001.

- **После ревью** (8 подтверждённых замечаний, все исправлены):
  - `playArtist` отвечает, когда микс уже в очереди: в ответе первый трек, а не имя исполнителя; пустой или неудачный микс — «не найдено» или «нет связи», а не «играет»; `openApp` известен до ответа;
  - холодный старт: функция ждёт, пока сервис вернёт сохранённую очередь (до 2 с), и сохранённая очередь не перебивает ту, что поставила функция; «Продолжи» после холодного старта продолжает сохранённую;
  - после отказа в переднем плане сервис не просит его на каждом событии плеера — только при новой очереди, новом «играть» или когда приложение открыли;
  - «ё» и «е» в Библиотеке равны и в запросе SQLite («еще» находит «Ещё раз»);
  - свой плейлист не выигрывает, если его короткое имя — часть другого слова («Ска» ≠ «русская классика»);
  - в описании приложения для агента — какая функция на песню, исполнителя, альбом, плейлист, Избранное;
  - дозаполнение трека берёт и название, если было только id (трек из ссылки);
  - ниже Android 16 сервис выключен.
  Эмулятор (debug через `AppFunction`, звук 0): `playArtist("Кино")` → «Спокойная ночь», Кино; после `force-stop` `playSong("Группа крови")` → «Группа крови» остаётся в плеере и через 3 с; после `force-stop` `resume` → «Группа крови»; неизвестная группа → 1500. Staging (R8) запускается, `dumpsys app_function` видит функции.

## 5. Как проверить на Pixel (Android 17)

1. Поставить сборку с этим кодом, открыть Melogold один раз.
2. Функции видны системе:
   ```
   adb shell cmd app_function list-app-functions | grep -A10 app.melogold.android
   ```
   Должны быть 10 функций `app.melogold.android.appfunctions.BaseMelogoldAppFunctionService#…` с описаниями.
3. Вызов без Gemini (громкость пониже):
   ```
   adb shell "cmd app_function execute-app-function --package app.melogold.android \
     --function 'app.melogold.android.appfunctions.BaseMelogoldAppFunctionService#playSong' \
     --parameters '{\"query\": \"Звезда по имени Солнце Кино\"}'"
   adb shell "cmd app_function execute-app-function --package app.melogold.android \
     --function 'app.melogold.android.appfunctions.BaseMelogoldAppFunctionService#playFavorites' --parameters '{}'"
   ```
   Для debug- и staging-сборки пакет — `app.melogold.android.debug`.
4. Игра в фоне на Android 17: `adb shell cmd audio set-enable-hardening throw`, свернуть Melogold, повторить шаг 3. Если звук не пошёл, в ответе должен быть `openApp`.
5. Gemini: «Окей, Google, включи „Звезда по имени Солнце“ в Melogold», «включи моё Избранное в Melogold».
6. Если Gemini отвечает, что не умеет: записать сюда версию приложения Google (Gemini), регион и язык, включена ли «История действий в приложениях Gemini» (Keep Activity), статус заявки в EAP (goo.gle/eap-af). Без EAP это ожидаемо (§3).
