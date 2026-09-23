<p align="center">
  <img src=".github/melogold-icon.png" width="128" height="128" alt="Melogold">
</p>

<h1 align="center">Melogold</h1>

<p align="center">Музыка из YouTube Music на всех ваших устройствах — с общим избранным, библиотекой и плейлистами.</p>

> [!NOTE]
> Melogold — форк [ViTune](https://github.com/bartoostveen/ViTune) (автор — Bart Oostveen),
> который, в свою очередь, основан на [ViMusic](https://github.com/vfsfitvnm/ViMusic).
> Оригинальный ViTune больше не развивается, и Melogold продолжает его как самостоятельный проект.
> История коммитов оригинала сохранена, лицензия та же — [GPL-3.0](./LICENSE).

## Как это устроено

- **Музыка играет напрямую из YouTube Music** на каждом устройстве — как в ViTune.
- **Melogold Server хранит только метаданные**: аккаунт, избранное, библиотеку, плейлисты и список
  ваших устройств. Аудиофайлов на сервере нет.
- **Сервер можно выбрать**: пользоваться официальным или поднять свой.
- **Приложения обновляются сами** из [GitHub Releases](https://github.com/melogold-app/melogoldAndroid/releases) —
  без ручного скачивания APK.

## Статус

Проект на ранней стадии. Сейчас приложение по функциям совпадает с ViTune; в работе:

- [x] Новая иконка
- [ ] Новое имя внутри приложения
- [ ] Автообновление из GitHub Releases
- [ ] Аккаунт: регистрация и вход по логину и паролю
- [ ] Синхронизация избранного, библиотеки и плейлистов между устройствами
- [ ] Список подключённых устройств с возможностью отключить любое
- [ ] Собственный сервер (self-hosting)
- [ ] Клиенты для macOS, Windows и Linux

## Платформы

| Платформа | Репозиторий | Состояние |
|---|---|---|
| Android | этот репозиторий | в разработке |
| Сервер | [melogoldServer](https://github.com/melogold-app/melogoldServer) | в разработке |
| iOS и macOS | [melogoldiOSmacOS](https://github.com/melogold-app/melogoldiOSmacOS) | запланирован |
| Windows | [melogoldWindows](https://github.com/melogold-app/melogoldWindows) | запланирован |
| Linux | [melogoldLinux](https://github.com/melogold-app/melogoldLinux) | запланирован |

## Возможности

Унаследованы от ViTune:

- Поиск и воспроизведение песен, альбомов, исполнителей, видео и плейлистов из YouTube Music
- Воспроизведение музыки с устройства
- Фоновое воспроизведение и кэш для офлайн-прослушивания
- Подборки по настроению и жанру, импорт плейлистов из YouTube
- Обычные и синхронизированные тексты песен
- Нормализация громкости, SponsorBlock
- Android Auto
- Открытие ссылок YouTube и YouTube Music
- Динамическая тема и Material You

## Установка

Первый релиз Melogold ещё не вышел. Сборки будут публиковаться в
[Releases](https://github.com/melogold-app/melogoldAndroid/releases).

## Сборка из исходников

Понадобятся:

- **JDK 25** — ровно эта версия, путь в `JAVA_HOME` (автоматический поиск toolchain отключён)
- **Android SDK**: platform 37, NDK `29.0.14206865`, CMake `4.1.2` — путь в `local.properties`
  (`sdk.dir=…`) или `ANDROID_HOME`
- **Python 3** — для встроенного yt-dlp (Chaquopy)
- Доступ в интернет во время сборки: QuickJS скачивается с bellard.org, yt-dlp — с PyPI

```bash
./gradlew :app:assembleDebug
```

APK появится в `app/build/outputs/apk/debug/`. Убедитесь, что в него попал QuickJS — без него
воспроизведение не работает (если строки нет, запустите сборку ещё раз):

```bash
unzip -l app/build/outputs/apk/debug/*.apk | grep libqjs
```

## Благодарности

- [ViTune](https://github.com/bartoostveen/ViTune) и [ViMusic](https://github.com/vfsfitvnm/ViMusic) —
  проекты, на которых основан Melogold
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) — получение аудиопотоков
- [YouTube-Internal-Clients](https://github.com/zerodytrash/YouTube-Internal-Clients) — исследование
  внутренних клиентов YouTube API
- [ionicons](https://github.com/ionic-team/ionicons) — иконки интерфейса

## Лицензия

[GPL-3.0](./LICENSE). Авторские права на исходный код ViTune и ViMusic принадлежат их авторам.

## Отказ от ответственности

Melogold не связан с YouTube, Google LLC или их дочерними компаниями, не финансируется, не одобрен
и не поддерживается ими. Все товарные знаки принадлежат их правообладателям.

<details>
<summary>English</summary>

**Melogold** is a fork of [ViTune](https://github.com/bartoostveen/ViTune) (based on
[ViMusic](https://github.com/vfsfitvnm/ViMusic)), an Android YouTube Music client. ViTune is no
longer maintained; Melogold continues it with accounts, cross-device sync of favorites, library and
playlists through Melogold Server (metadata only — no audio is stored), a connected-devices list,
self-hosting, in-app auto-updates from GitHub Releases and upcoming macOS, Windows and Linux
clients. Early stage: the app currently matches ViTune. Licensed under [GPL-3.0](./LICENSE). Not
affiliated with YouTube or Google LLC.

</details>
