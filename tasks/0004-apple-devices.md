# Устройства Apple в аккаунте: значки и одобрение входа по коду

Статус: сделано

То же для Windows — `melogoldWindows/tasks/0006-apple-devices.md` (там таблица, тексты и ошибки). Значения `platform`: `ios`, `ipados`, `macos`, `visionos`, `watchos` (API §1.6 сервера).

## Решение

- **Значки.** Одна функция `platform → значок`:
  - `android`, `ios` — телефон;
  - `ipados` — планшет;
  - `macos`, `windows`, `linux` — компьютер;
  - `watchos` — часы;
  - `visionos` — гарнитура (`head_mounted_device`);
  - прочее — компьютер.

  Сейчас `AccountScreens.kt` знает только телефон (`MOBILE_PLATFORMS`) и компьютер. Значок нужен в списке устройств, в фильтре Истории и в карточке одобрения.
- **«Добавить устройство»** в Аккаунте: одобрить вход по коду (API §4.6, `request`) — им входят часы. Шаги: код → `resolve` → карточка устройства → три числа из `verifyChoices` → `approve` или `deny`.

**Сделано:**
- `DeviceKind.of(platform)` (`core/domain`, `domain/server/DeviceKind.kt`) — одна таблица на всё приложение; `deviceIcon()` и `deviceKindName()` (`ui/kit/DeviceIcons.kt`) дают значок Material Symbols и подпись для TalkBack. Новые значки: `ms_tablet`, `ms_watch`, `ms_head_mounted_device` (Rounded, 400, как остальные).
- Значки — в списке устройств Аккаунта (у значка подпись «Часы», «Планшет»…), в чипе и меню фильтра «Устройство» Истории и в карточке одобрения.
- `UserCode.normalize()` (`core/domain`) — ввод по API §1.6, вывод `XXXX-XXXX`, то же правило, что `normalizeCrockfordCode` сервера.
- Аккаунт › «Добавить устройство» (`AddDeviceScreen.kt`): поле кода («Продолжить» — только для кода) → `links/resolve` → карточка: значок, имя, модель · система, «В той же сети…» / «В другой сети — убедитесь…», «Код действует ещё N мин» → три больших числа и «Отклонить». Итог — снекбар «„…“ вошло в аккаунт», возврат к списку, список читается заново. Ошибки — словами Windows; число не совпало, код устарел — снова поле кода с причиной; код, введённый снова после конца связи (`resolve` отдаёт её как есть: отклонена, устарела, использована), тоже — «Код устарел…» или «Этот код уже использован»; лимит устройств — снекбар и возврат к списку устройств.
- `Account.resolveLink/approveLink/denyLink`, DTO `LinkDetails`, `LinkDeviceInfo`, `LinkDecision` в `MelogoldApi.kt`.
- Тесты: `UserCodeTest`, `DeviceKindTest`, `DeviceIconsTest`; `AddDeviceScreenTest` (Robolectric + Compose: шаг кода, карточка часов, выбор числа, «Отклонить», значки всех видов в списке; с `-Pmelogold.screenshots=<папка>` сохраняет картинки); `LinkDeviceLiveTest` (с `-Pmelogold.testServer=http://127.0.0.1:8787`: временный аккаунт, «часы» просятся войти, код, число, вход; неверное число и «Отклонить» отказывают; аккаунт удаляется).
- Скриншоты — из `AddDeviceScreenTest`: на эмуляторе в staging нельзя входить в аккаунт (там чужая история).

## Проверка

- Юнит-тест таблицы значков и нормализации `UserCode`.
- Скриншот списка устройств с iPhone, iPad и часами: их можно создать запросом `POST /auth/login` на локальном сервере.
