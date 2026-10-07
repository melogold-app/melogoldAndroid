# Выпустить 0.1.18 (делается там, где ключ подписи)

Статус: открыто. `main` готов 07.10.2026: версия 0.1.18 в `gradle.properties`, заметки `release-notes/0.1.18.{ru,en}.md`.
В выпуске задания 0024 (обрыв на первом мегабайте), 0025 (нет сети — трек ждёт), 0026 (перенос на другое устройство с
той же секунды). Unit-тесты `NetworkWaitTest`, `DirectStreamsTest`, `RemoteCommandsTest` проходят. На ThinkPad
(Fedora) ключа подписи нет — ни в `~/.gradle/gradle.properties`, ни в сейфе `cdn`.

## Сделать — на Маке (ключ подписи есть только там)

1. Положить ключ в сейф cdn, чтобы дальше Android выпускал и ThinkPad:
   ```bash
   cd ~/Documents/VPN/cdn && git pull && \
   f=$(sed -n 's/^melogold.release.storeFile=//p' ~/.gradle/gradle.properties); f="${f/#\~/$HOME}"; \
   mkdir -p ~/.secrets/melogold-android && cp "$f" ~/.secrets/melogold-android/release.jks && \
   grep '^melogold.release\.' ~/.gradle/gradle.properties > ~/.secrets/melogold-android/gradle.properties && \
   chmod 600 ~/.secrets/melogold-android/* && agent/vault.sh push && \
   git add agent/vault && git commit -m "сейф: ключ подписи Melogold Android" && git push
   ```
   Если ключ на Маке задан иначе (переменные `MELOGOLD_RELEASE_*`), положить те же четыре значения.
   В публичные репозитории `melogold-app` ключ не класть никогда, даже зашифрованным. Потом удалить
   пункт «ОТКРЫТО» про ключ в `cdn/agent/WORKSPACE.md`.
2. Выпустить отсюда же: `git switch main && git pull --ff-only && scripts/release.sh --publish`.

Если выпуск делает ThinkPad: `cdn/agent/vault.sh pull`, строки из `~/.secrets/melogold-android/gradle.properties`
перенести в `~/.gradle/gradle.properties`, а `storeFile` указать на `~/.secrets/melogold-android/release.jks`.
Подпись должна совпасть с 0.1.17 (SHA-256 `1735ecb3…bf33`): иначе обновление не встанет поверх установленного
приложения.

## Проверка

- Релиз `v0.1.18` на GitHub с APK и `update.json`; установленная 0.1.17 предлагает обновиться.
- Pixel: песня не обрывается после первой минуты; выключить сеть на 20 с посреди трека — трек ждёт и продолжает
  с того же места; «Устройство» → другое устройство — там та же песня с той же секунды (нужен сервер 0.1.3).
- Отметить здесь «сделано» и в `tasks/README.md`.
