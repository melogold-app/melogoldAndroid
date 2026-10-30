# Плеер на MediaSessionService из Media3: звук не глушится в фоне на Android 17

Статус: сделано (30.10.2026), проверено на Pixel 7 Pro с Android 17 (бета), выпущено в 0.1.22; ждём проверки Романа на One UI 9 с «Audio hardening level» = MID. `PlayerService` — `MediaSessionService`, `PlayerMediaBrowserService.queueOf`; удалены `ForegroundHold`,
`NotificationActionReceiver`, `NotificationDismissReceiver`.

## Почему

Роман (One UI 9, Android 17), после 0.1.21: «когда песня больше трёх минут, она всё равно тухнет». В «Параметрах
разработчика» One UI 9 он поставил «Audio hardening level» = LOW (Skip hardening) — 11 минут музыки без обрыва. Значит,
звук глушит background audio hardening Android 17 (задание 0032), и на уровне MID (по умолчанию) Melogold его не проходит;
три минуты — отсрочка, которую система даёт приложению в фоне.

`PlayerService` был наследством ViMusic: свой `Service`, платформенная `MediaSession`, своё уведомление с кнопками-рассылками
и передний план вручную (`startForegroundService` + `startForeground` без типа, уход с переднего плана на паузах). Google
под это правило советует Media3 `MediaSessionService`: он сам держит службу `mediaPlayback` (с явным типом), уведомление и
сессию системы в согласии с плеером.

## Как сделано

- `PlayerService : MediaSessionService`. Сессия Media3 на ExoPlayer через `SessionPlayer` (ForwardingPlayer): «следующий» и
  «предыдущий» внешних пультов — как кнопки приложения (`forceSeekToNext/Previous`: по кругу, мимо скрытых треков),
  «стоп» — пауза, скорость — в настройку.
- Передний план, уведомление и сессия системы — Media3: на переднем плане, пока играет или буферизуется, и 10 минут
  после паузы, ошибки или конца очереди (`DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS`). Уведомление — `DefaultMediaNotificationProvider`
  в прежнем канале `default_channel_id` с прежним номером: настройки уведомления у людей не сбрасываются. ♡ и повтор трека —
  `CommandButton` (`setMediaButtonPreferences`) с командами `LIKE`/`LOOP`. Обложка — `ArtworkBitmapLoader`: миниатюра YouTube
  на 512 dp, как раньше.
- Отказ системы в переднем плане (`Listener.onForegroundServiceStartNotAllowedException`): на Android 17 — пауза и
  уведомление «Melogold на паузе» (из 0.1.21), приложение на экране — музыка дальше.
- Экраны приложения привязываются без action и получают свой `Binder` (`onBind`); контроллеры — сессию. `binder.mediaSession`
  — сессия Media3.
- «Включи X» (Assistant, Android Auto) — `onSetMediaItems` без id: голосовые команды играют сами (`VoiceQueryResolver`),
  Media3 запрос отклоняется (он такие отказы молча пропускает). Списки Android Auto по id — `PlayerMediaBrowserService.queueOf`;
  Auto получает токен сессии Media3 (`platformToken`). «Перемешать» теперь действительно перемешивает.
- Манифест: у `PlayerService` фильтр `androidx.media3.session.MediaSessionService`; приёмники кнопок старого уведомления
  убраны. `MediaButtonReceiver` Media3 уже был — теперь он находит сервис, который медиакнопки понимает.
- `targetSdk` остаётся 36: запуск из фона (команда с другого устройства, наушники подключились, агент) права while-in-use
  не получает и с Media3.

## Проверка

- Сборка, `detekt` по изменённым файлам, 389 юнит-тестов — без новых замечаний и падений.
- **На телефоне (до выпуска):** включить песню из приложения, заблокировать экран — уведомление с ♡ и повтором, звук идёт
  больше трёх минут (на One UI 9 — с «Audio hardening level» = MID); пауза из уведомления и наушников, через 10 минут
  пауза — уведомление остаётся, служба уходит; продолжение из уведомления; смахнуть приложение из недавних во время игры —
  играет дальше, на паузе — останавливается; голос «включи X»; Android Auto (если включён в настройках).
- Роман: `adb shell dumpsys audio | grep -i -A3 hardening` — строк с `app.melogold.android` быть не должно.

## Проверено на телефоне (30.10.2026, Pixel 7 Pro, Android 17 beta, `adb`)

- `cmd audio set-hardening enable`, экран заблокирован 4,5 мин: служба на переднем плане (`types=0x2`), звук не
  приглушён (`mutedState` без hardening), раздел «Hardening enforcement» пуст; так же на 0.1.21 — на Pixel обрыв Романа
  (One UI 9, MID) не воспроизводится.
- `set-hardening throw`, экран заблокирован, смена трека — ни одной ошибки записи.
- Уведомление: системное медиауведомление, канал `default_channel_id`, номер 1001, 5 кнопок; ♡ и повтор в медиаплеере
  системы; повтор из уведомления переключается; обложка — через Coil с кэшем (первая версия грузила её Media3 по сети, и
  без VPN её не было).
- Пауза медиакнопкой при заблокированном экране — служба остаётся на переднем плане; продолжение медиакнопкой — играет.
- Экран приложения подключается к сервису (мини-плеер, пауза из приложения).
- Смахнуть из недавних во время игры — играет дальше; на паузе — сервис останавливается; снова открыть — очередь на месте.
- Попутно: на Wi-Fi без VPN `www.youtube.com` не разрешается DNS, новые треки встают в «нет сети» (из кэша играют) —
  текст «нет сети» тут неточен, это не про сервис.
