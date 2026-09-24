# Векторы правил

Правила, которые повторят десктопы, живут в чистом JVM-коде (`:core:domain`, `providers/*`), а их примеры — здесь, в файлах `*.vectors.json` (REWRITE §0, принцип 8; §4.13). Тесты Android читают эти файлы напрямую, сервер и десктопы прогоняют те же файлы через свою реализацию.

## Требования к файлу

- JSON, проходит `jq empty`.
- У каждого случая есть `input` и `expected`. Остальные поля и общую структуру задаёт владелец файла и описывает их в начале файла или в тесте.
- Случай не удаляют и не переписывают молча. Если поведение изменилось, добавляют новый случай и пишут об этом в описании изменения.

## Файлы

| Файл | Правило | Код | Владелец |
|---|---|---|---|
| `youtube-links.vectors.json` | разбор ссылок, §4.9 | `providers/innertube`, `links/YouTubeLinkParser` | R1.2 (первая версия), R2.3 |
| `title-cleaner.vectors.json` | очистка названий, §4.10.8 | `CD/lyrics/TitleCleaner` | R1.2 (первая версия), R2.2 |
| `playlist-ops.vectors.json` | порядок в плейлисте и снимок `append`, §4.3, п. 8 | `CD/playlist/PlaylistOrder` | R2.2 |
| `queue.vectors.json` | правила очереди, §4.10.4 | `CD/queue/QueueRules` | R2.2 |
| `import-ids.vectors.json` | идентификаторы импорта, §4.5 | `CD/importer/ImportIds` | R2.2 |
| `server-address.vectors.json` | адрес сервера и ссылки `melogold://`, §3.5.12, API §7.1–§7.2 | `CD/server/ServerAddressPolicy`, `CD/server/MelogoldLinks` | R2.2 |

`matcher.vectors.json` (§4.6) появится вместе с внешним импортом.
