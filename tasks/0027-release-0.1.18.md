# Выпустить 0.1.18 (делается там, где ключ подписи)

Статус: открыто. `main` готов 07.10.2026: версия 0.1.18 в `gradle.properties`, заметки `release-notes/0.1.18.{ru,en}.md`.
В выпуске задания 0024 (обрыв на первом мегабайте), 0025 (нет сети — трек ждёт), 0026 (перенос на другое устройство с
той же секунды). Unit-тесты `NetworkWaitTest`, `DirectStreamsTest`, `RemoteCommandsTest` проходят. На ThinkPad
(Fedora) ключа подписи нет — ни в `~/.gradle/gradle.properties`, ни в сейфе `cdn`.

## Сделать

0. Агент на Mac кладёт ключ подписи в сейф cdn (`~/.secrets/melogold-android/`, пункт «ОТКРЫТО» в
   `cdn/agent/WORKSPACE.md`). После этого выпуск идёт с ThinkPad: `cdn/agent/vault.sh pull`, в
   `~/.gradle/gradle.properties` — строки из `~/.secrets/melogold-android/gradle.properties`, `storeFile` указывает на
   `~/.secrets/melogold-android/release.jks`. Подпись должна совпасть с 0.1.17 (SHA-256 `1735ecb3…bf33`), иначе
   обновление не встанет поверх установленного приложения.

```bash
git switch main && git pull --ff-only
scripts/release.sh --publish        # нужен ключ: melogold.release.* в ~/.gradle/gradle.properties
```

## Проверка

- Релиз `v0.1.18` на GitHub с APK и `update.json`; установленная 0.1.17 предлагает обновиться.
- Pixel: песня не обрывается после первой минуты; выключить сеть на 20 с посреди трека — трек ждёт и продолжает
  с того же места; «Устройство» → другое устройство — там та же песня с той же секунды (нужен сервер 0.1.3).
- Отметить здесь «сделано» и в `tasks/README.md`.
