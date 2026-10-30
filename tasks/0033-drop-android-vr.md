# Поток: убрать запасной клиент ANDROID_VR — песня с его адреса обрывалась на ~30-й секунде

Статус: сделано (30.10.2026) — `config/stream-clients.json` (у всех со следующего запуска, без выпуска),
`StreamClient.builtIn` — в следующем выпуске; тест `StreamClientTest` (файл на main = встроенный список).

## Почему

Проверка 30.10.2026 (6-минутная песня, itag 251, чтение кусками по 512 КиБ, как у плеера):

| Клиент | Ответ | Файл целиком |
|---|---|---|
| VISIONOS | OK, прямые адреса | да |
| ANDROID_VR | OK, прямые адреса | **нет — 403 на втором куске (0,5 МиБ, ~30 с звука)** |
| IOS, ANDROID | OK, прямые адреса | нет — 403 на втором куске |
| IOS_MUSIC, ANDROID_MUSIC, ANDROID_CREATOR, IOS_CREATOR, ANDROID_UNPLUGGED, IOS_UNPLUGGED | LOGIN_REQUIRED | — |
| TVHTML5, ANDROID_TESTSUITE, ANDROID_EMBEDDED_PLAYER | нет потока / 404 | — |

Без PO-токена googlevideo отдаёт этим клиентам только начало файла. ANDROID_VR отвечал «OK», поэтому `resolveDirect`
брал его адрес и до yt-dlp не доходил: когда VISIONOS не отвечал, песня играла ~30 с и обрывалась. Замены нет — файл
целиком сейчас отдаёт только VISIONOS (как у Linux и Windows, у них в списке только он).

## Что теперь

Один клиент VISIONOS. Проверка на бота у него — понятная ошибка «вы не бот» сразу; другие отказы — yt-dlp. Apple — то же
в `melogoldiOSmacOS` (конфиг и встроенный список).
