# Данные спроса

Источники рейтинга спроса, на которые ссылается `docs/REWRITE.md` («рейтинг №N», «П 211 (#4)»). Файлы перенесены задачей R1.3 без изменений: содержимое совпадает побайтно с исходниками, по которым писалась спецификация. Тексты внутри файлов на английском.

| Файл | Что внутри | Размер выборки |
|---|---|---|
| `demand-clusters.json` | `clusters[]`: кластеры запросов из issues ViTune и ViMusic. Поля: `id`, `theme`, `kind` (`bug`, `feature`, `ux`, `performance`, `compat`), `area`, `description`, `popularity`, `urls`, `code_hint` | 86 кластеров |
| `demand-verified.json` | объект `verify:<id кластера>` → `{id, status, evidence, fix_idea, effort}`: сверка кластера с кодом. `status`: `present` (функция есть) или `partially` (есть частично) | 45 кластеров |
| `demand-competitors.json` | `items[]`: запросы из трекеров конкурентов (`title`, `url`, `source`, `kind`, `area`, `summary`, `reactions`, `state`); `notes` — метод и список 21 клиента, чьи README прочитаны; `total_seen` — сколько issues просмотрено | 80 запросов, 21 клиент, 1090 issues |

**Как читать ссылки из спецификации:**
- «П 211 (#4)» — поле `popularity` кластера и его место среди 86 кластеров по убыванию `popularity`;
- «рейтинг №N» — строка N рейтинга в `docs/REWRITE.md` §1.2–§1.6, собранного из этих файлов;
- префиксы issues: VT — ViTune, VM — ViMusic, MT — Metrolist, IT — InnerTune, OT — OuterTune.

**Контрольные суммы (SHA-256):**

```
7705445348088afa1c57df248f500b8a3bfbb4679d297b242cd5312b4780b55d  demand-clusters.json
f3d23cf3d4955be1a55580da257e1f5b77d3a239c55e7c3b31035d5bc5d3769e  demand-verified.json
07a1d0a6c487cf8f0f7b246290f7c14536c16aa1a8486c42afe646506428fa62  demand-competitors.json
```

Файлы — снимок на 2026-09-23. Правки делаются отдельным коммитом с пересчётом сумм. Если меняется рейтинг, его нужно поправить и в `docs/REWRITE.md`.
