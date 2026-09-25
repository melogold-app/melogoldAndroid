#!/usr/bin/env bash
# Записывает фикстуры InnerTube (YouTube Music и обычный YouTube) для парсеров и debug-сборки.
#
#   scripts/fixtures/record.sh            # всё: hl=ru/gl=RU и hl=en/gl=US
#   ONLY=web scripts/fixtures/record.sh   # только WEB (ytm | web | player)
#   ONLY=index scripts/fixtures/record.sh # без сети: пересобрать INDEX.md, проверки и debug-копию
#
# Нужны curl и jq. Сеть — прямая или через VPN. Ответы обезличиваются (anonymize ниже),
# пишутся в providers/innertube/src/test/resources/fixtures/{ytm,web}/ и копируются
# в app/src/debug/assets/fixtures/. Рядом генерируются index.json (машиночитаемый список
# для MockEngine) и INDEX.md (таблица для людей). Спецификация — docs/REWRITE.md §4.8, §6.2 (R1.2).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/providers/innertube/src/test/resources/fixtures"
DEBUG_OUT="$ROOT/app/src/debug/assets/fixtures"
ONLY=${ONLY:-all}
TODAY=$(date -u +%Y-%m-%d)

# Версии клиентов. Текущие значения берутся со страниц music.youtube.com и www.youtube.com
# (INNERTUBE_CLIENT_VERSION); переопределяются переменными окружения.
WEB_REMIX_VERSION=${WEB_REMIX_VERSION:-1.20260922.09.00}
WEB_VERSION=${WEB_VERSION:-2.20260922.06.00}
IOS_VERSION=${IOS_VERSION:-20.10.4}

UA_DESKTOP='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36'
UA_IOS="com.google.ios.youtube/$IOS_VERSION (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

# visitorData из ответов заменяется этой константой (base64 от «\n\x0bANONYMIZED…»), чтобы в
# репозиторий не попал идентификатор посетителя. Формат строки сохранён: 24 символа base64 + %3D%3D.
VISITOR_PLACEHOLDER='CgtBTk9OWU1JWkVEMCiAgICABg%3D%3D'

# Запросы и идентификаторы (см. INDEX.md). Кино и Daft Punk — чтобы были кириллица и латиница.
Q_RU='кино группа крови'
Q_EN='daft punk'
Q_LIVE='lofi'
SUGGEST_RU='кино'
SUGGEST_EN='daft p'
ALBUM_ID='MPREb_OLmD8O5IYNS'            # Кино — «Группа крови» (1988)
ARTIST_ID='UCL9NQ06h7I0CRUcGxPWMtkQ'     # Кино (исполнитель YTM)
UGC_CHANNEL_ID='UCy_vnPBNh9FqtyH9Qc-aiSA' # Gavrik's Archive: обычный канал без музыкальных секций
WEB_CHANNEL_ID='UC_kRDKYrUlrbtrSiyu5Tflg' # Daft Punk (канал YouTube)
PLAYLIST_ID='VLPLurittKGarBvW6lCCJYlTGd6U2bBfP8FE' # 11 видео; тот же плейлист, что в legacy-copies
LONG_PLAYLIST_ID='VLPL85973FA7E35D0D96'  # ~2067 треков, все страницы продолжений (hl=ru)
SONG_ID='xtxjm7ciwmc'                    # Кино — «Группа крови» (ATV)
CHANNEL_VIDEOS_PARAMS='EgZ2aWRlb3PyBgQKAjoA'

# Фильтры YTM (как Innertube.SearchFilter) и WEB (§4.8.1).
YTM_FILTERS='songs=EgWKAQIIAWoOEAMQBBAJEAoQBRAQEBU%3D
videos=EgWKAQIQAWoOEAMQBBAJEAoQBRAQEBU%3D
albums=EgWKAQIYAWoOEAMQBBAJEAoQBRAQEBU%3D
artists=EgWKAQIgAWoOEAMQBBAJEAoQBRAQEBU%3D
community-playlists=EgeKAQQoAEABag4QAxAEEAkQChAFEBAQFQ%3D%3D
featured-playlists=EgeKAQQoADgBag4QAxAEEAkQChAFEBAQFQ%3D%3D'
WEB_FILTERS='videos=EgIQAQ%3D%3D
channels=EgIQAg%3D%3D
live=EgJAAQ%3D%3D
playlists=EgIQAw%3D%3D'

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
INDEX_LINES="$TMP/index.jsonl"
: > "$INDEX_LINES"

log() { printf '%s\n' "$*" >&2; }

# Обезличивание и чистка шума. Удаляются трекинговые токены (trackingParams и т. п.),
# рекламные и служебные блоки плеера, прямые ссылки на потоки (в них IP и подпись);
# visitorData заменяется константой; в ссылках googlevideo обезличены хост узла CDN, ip и
# initcwndbps. $strip_menu=1 дополнительно убирает контекстные меню (`menu.menuRenderer`) — только
# у самых больших ответов: страницы продолжений длинного плейлиста, настроение, новые релизы.
ANON_JQ='
def scrub:
  (if test("[?&]ip=") then gsub("(?<k>[?&]ip=)[^&]*"; "\(.k)0.0.0.0") else . end)
  | (if test("googlevideo\\.com") then gsub("rr[0-9]+---sn-[a-z0-9-]+\\.googlevideo\\.com"; "rr1---sn-anonymized.googlevideo.com") else . end)
  | (if test("initcwndbps=") then gsub("initcwndbps=[0-9]+"; "initcwndbps=0") else . end);
walk(
  if type == "object" then
    del(.trackingParams, .clickTrackingParams, .loggingDirectives, .serviceTrackingParams,
        .trackingParam, .responseId, .consistencyTokenJar, .adSignalsInfo, .playbackTracking,
        .attestation, .adPlacements, .playerAds, .adSlots, .adBreakHeartbeatParams, .heartbeatParams)
    | (if has("visitorData") then .visitorData = $vd else . end)
    | (if has("itag") and has("mimeType") then del(.url, .signatureCipher, .cipher) else . end)
    | (if $strip_menu == "1" and (.menu? | type) == "object" and (.menu | has("menuRenderer")) then del(.menu) else . end)
  elif type == "string" then scrub
  else . end)
| (if (.streamingData? | type) == "object"
   then .streamingData |= del(.hlsManifestUrl, .dashManifestUrl, .serverAbrStreamingUrl)
   else . end)
# Текст песни защищён авторским правом: в ответах вкладки «Текст» ($lyrics=1) строки заменяются
# заглушками «lyrics line N», пустые строки и число строк сохраняются, источник (footer) остаётся.
| walk(if $lyrics == "1" and type == "object" and has("musicDescriptionShelfRenderer")
       then .musicDescriptionShelfRenderer.description.runs |= map(.text |= (split("\n")
            | to_entries | map(if .value == "" then "" else "lyrics line \(.key + 1)" end) | join("\n")))
       else . end)
# Сниппеты описаний видео в выдаче WEB часто цитируют текст песни: текст заменяется заглушкой,
# структура runs (жирные фрагменты) сохраняется.
| walk(if type == "object" and (has("snippetText") or has("descriptionSnippet"))
       then (if has("snippetText") then .snippetText.runs |= map(.text = "description snippet") else . end)
            | (if has("descriptionSnippet") then .descriptionSnippet.runs |= map(.text = "description snippet") else . end)
       else . end)
'

# context CLIENT HL GL
context() {
  local client=$1 hl=$2 gl=$3
  case $client in
    WEB_REMIX) jq -nc --arg v "$WEB_REMIX_VERSION" --arg hl "$hl" --arg gl "$gl" \
      '{client:{clientName:"WEB_REMIX",clientVersion:$v,platform:"DESKTOP",hl:$hl,gl:$gl}}' ;;
    WEB) jq -nc --arg v "$WEB_VERSION" --arg hl "$hl" --arg gl "$gl" \
      '{client:{clientName:"WEB",clientVersion:$v,platform:"DESKTOP",hl:$hl,gl:$gl}}' ;;
    IOS) jq -nc --arg v "$IOS_VERSION" --arg hl "$hl" --arg gl "$gl" \
      '{client:{clientName:"IOS",clientVersion:$v,deviceMake:"Apple",deviceModel:"iPhone16,2",osName:"iPhone",osVersion:"18.3.2.22D82",hl:$hl,gl:$gl}}' ;;
  esac
}

# call CLIENT ENDPOINT BODYFILE OUTFILE -> печатает HTTP-статус
call() {
  local client=$1 endpoint=$2 body=$3 out=$4 host name version ua origin
  case $client in
    WEB_REMIX) host=music.youtube.com; name=67; version=$WEB_REMIX_VERSION; ua=$UA_DESKTOP ;;
    WEB)       host=www.youtube.com;   name=1;  version=$WEB_VERSION;       ua=$UA_DESKTOP ;;
    IOS)       host=www.youtube.com;   name=5;  version=$IOS_VERSION;       ua=$UA_IOS ;;
  esac
  origin="https://$host"
  curl -sS --compressed --retry 2 --max-time 60 -o "$out" -w '%{http_code}' \
    "https://$host/youtubei/v1/$endpoint?prettyPrint=false" \
    -H 'Content-Type: application/json' \
    -H "User-Agent: $ua" \
    -H "Origin: $origin" -H "Referer: $origin/" \
    -H "X-Youtube-Client-Name: $name" -H "X-Youtube-Client-Version: $version" \
    --data-binary @"$body"
}

host_of() { case $1 in WEB_REMIX) echo music.youtube.com ;; *) echo www.youtube.com ;; esac; }
version_of() { case $1 in WEB_REMIX) echo "$WEB_REMIX_VERSION" ;; WEB) echo "$WEB_VERSION" ;; IOS) echo "$IOS_VERSION" ;; esac; }

# record CLIENT ENDPOINT FILE HL GL REQUEST_JSON [STRIP_MENU] [NOTE]
# FILE — путь относительно fixtures/ без языка, например ytm/search-songs; итог ytm/search-songs.ru.json
record() {
  local client=$1 endpoint=$2 file=$3 hl=$4 gl=$5 request=$6 strip=${7:-0} note=${8:-}
  local lang=${hl%%-*} ctx status target rel
  rel="$file.$lang.json"
  target="$OUT/$rel"
  mkdir -p "$(dirname "$target")"
  ctx=$(context "$client" "$hl" "$gl")
  jq -nc --argjson ctx "$ctx" --argjson req "$request" '{context:$ctx} + $req' > "$TMP/body.json"
  status=$(call "$client" "$endpoint" "$TMP/body.json" "$TMP/raw.json") || status=000
  if ! jq empty "$TMP/raw.json" 2>/dev/null; then
    log "!! $rel: HTTP $status, ответ не JSON"; return 1
  fi
  local lyrics=0
  case $file in */lyrics) lyrics=1 ;; esac
  jq -c --arg vd "$VISITOR_PLACEHOLDER" --arg strip_menu "$strip" --arg lyrics "$lyrics" "$ANON_JQ" "$TMP/raw.json" > "$target"
  jq -nc --arg file "$rel" --arg host "$(host_of "$client")" --arg endpoint "/youtubei/v1/$endpoint" \
    --arg client "$client" --arg version "$(version_of "$client")" --arg hl "$hl" --arg gl "$gl" \
    --argjson request "$request" --arg status "$status" --arg date "$TODAY" \
    --arg bytes "$(wc -c < "$target" | tr -d ' ')" --arg strip "$strip" --arg note "$note" \
    '{file:$file, host:$host, endpoint:$endpoint, client:$client, clientVersion:$version, hl:$hl, gl:$gl,
      request:$request, httpStatus:($status|tonumber), recordedAt:$date, bytes:($bytes|tonumber)}
     + (if $strip == "1" then {stripped:["menu"]} else {} end)
     + (if $note != "" then {note:$note} else {} end)' >> "$INDEX_LINES"
  log "ok $rel ($status, $(wc -c < "$target" | tr -d ' ') B)"
  cp "$target" "$TMP/last.json"
}

# Токен продолжения из последнего записанного ответа (новый формат, затем старый).
next_token() {
  jq -r '[.. | .continuationItemRenderer? // empty | .continuationEndpoint.continuationCommand.token // empty] | first
         // ([.. | .nextContinuationData? // empty | .continuation] | first) // empty' "$TMP/last.json"
}

q() { jq -nc "$@"; }

record_ytm() {
  local hl=$1 gl=$2 lang=${1%%-*} query suggest line name params token i rel
  if [ "$lang" = ru ]; then query=$Q_RU; suggest=$SUGGEST_RU; else query=$Q_EN; suggest=$SUGGEST_EN; fi

  record WEB_REMIX search ytm/search-all "$hl" "$gl" "$(q --arg q "$query" '{query:$q}')"
  while IFS= read -r line; do
    name=${line%%=*}; params=${line#*=}
    record WEB_REMIX search "ytm/search-$name" "$hl" "$gl" "$(q --arg q "$query" --arg p "$params" '{query:$q, params:$p}')"
    if [ "$name" = songs ]; then
      token=$(next_token)
      [ -n "$token" ] && record WEB_REMIX search ytm/search-songs.p01 "$hl" "$gl" "$(q --arg t "$token" '{continuation:$t}')" 0 "продолжение ytm/search-songs"
    fi
  done <<< "$YTM_FILTERS"

  record WEB_REMIX music/get_search_suggestions ytm/search-suggestions "$hl" "$gl" "$(q --arg q "$suggest" '{input:$q}')"
  record WEB_REMIX browse ytm/album "$hl" "$gl" "$(q --arg b "$ALBUM_ID" '{browseId:$b}')"
  record WEB_REMIX browse ytm/artist "$hl" "$gl" "$(q --arg b "$ARTIST_ID" '{browseId:$b}')"
  record WEB_REMIX browse ytm/artist-ugc-channel "$hl" "$gl" "$(q --arg b "$UGC_CHANNEL_ID" '{browseId:$b}')" 0 "обычный канал: музыкальных секций нет (§4.8.5)"
  record WEB_REMIX browse ytm/playlist "$hl" "$gl" "$(q --arg b "$PLAYLIST_ID" '{browseId:$b}')"

  # Длинный плейлист: все страницы только для ru (≈9 МБ), для en — первая страница.
  record WEB_REMIX browse ytm/playlist-long.p00 "$hl" "$gl" "$(q --arg b "$LONG_PLAYLIST_ID" '{browseId:$b}')"
  if [ "$lang" = ru ]; then
    i=1
    while token=$(next_token) && [ -n "$token" ]; do
      rel=$(printf 'ytm/playlist-long.p%02d' "$i")
      record WEB_REMIX browse "$rel" "$hl" "$gl" "$(q --arg t "$token" '{continuation:$t}')" 1 \
        "продолжение $(printf 'p%02d' $((i - 1)))"
      i=$((i + 1))
      [ "$i" -gt 60 ] && { log "!! слишком много страниц"; break; }
    done
  fi

  record WEB_REMIX browse ytm/explore "$hl" "$gl" '{"browseId":"FEmusic_explore"}'
  record WEB_REMIX browse ytm/new-releases-albums "$hl" "$gl" '{"browseId":"FEmusic_new_releases_albums"}' 1
  record WEB_REMIX browse ytm/moods "$hl" "$gl" '{"browseId":"FEmusic_moods_and_genres"}'
  local mood
  mood=$(jq -c '[.. | .musicNavigationButtonRenderer? // empty | .clickCommand.browseEndpoint | {browseId, params}] | first // empty' "$TMP/last.json")
  [ -n "$mood" ] && record WEB_REMIX browse ytm/mood "$hl" "$gl" "$mood" 1 "первое настроение из ytm/moods"
  local rdclak
  rdclak=$(jq -r '[.. | .browseEndpoint? // empty | .browseId | select(startswith("VLRDCLAK5uy_"))] | first // empty' "$TMP/last.json")
  [ -n "$rdclak" ] && record WEB_REMIX browse ytm/playlist-editorial "$hl" "$gl" "$(q --arg b "$rdclak" '{browseId:$b}')" 0 "первый RDCLAK5uy_ из ytm/mood"

  record WEB_REMIX next ytm/next "$hl" "$gl" "$(q --arg v "$SONG_ID" '{videoId:$v, isAudioOnly:true}')"
  local lyrics related
  lyrics=$(jq -r '[.. | .tabRenderer? // empty | .endpoint.browseEndpoint.browseId // empty | select(startswith("MPLYt"))] | first // empty' "$TMP/last.json")
  related=$(jq -r '[.. | .tabRenderer? // empty | .endpoint.browseEndpoint.browseId // empty | select(startswith("MPTRt"))] | first // empty' "$TMP/last.json")
  record WEB_REMIX next ytm/next-radio "$hl" "$gl" "$(q --arg v "$SONG_ID" --arg p "RDAMVM$SONG_ID" '{videoId:$v, playlistId:$p, params:"wAEB", isAudioOnly:true}')" 0 "радио по треку"
  [ -n "$related" ] && record WEB_REMIX browse ytm/related "$hl" "$gl" "$(q --arg b "$related" '{browseId:$b}')" 0 "вкладка «Похожие» из ytm/next"
  [ -n "$lyrics" ] && record WEB_REMIX browse ytm/lyrics "$hl" "$gl" "$(q --arg b "$lyrics" '{browseId:$b}')" 0 "вкладка «Текст» из ytm/next; строки текста заменены заглушками"
  return 0
}

record_player() {
  local hl=$1 gl=$2
  record IOS player ytm/player-ios "$hl" "$gl" \
    "$(q --arg v "$SONG_ID" '{videoId:$v, contentCheckOk:true, racyCheckOk:true}')" 0 \
    "loudness: playerConfig.audioConfig и adaptiveFormats[].loudnessDb; url/signatureCipher удалены"
}

record_web() {
  local hl=$1 gl=$2 lang=${1%%-*} query line name params token
  if [ "$lang" = ru ]; then query=$Q_RU; else query=$Q_EN; fi
  while IFS= read -r line; do
    name=${line%%=*}; params=${line#*=}
    local qq=$query
    [ "$name" = live ] && qq=$Q_LIVE
    record WEB search "web/search-$name" "$hl" "$gl" "$(q --arg q "$qq" --arg p "$params" '{query:$q, params:$p}')"
    token=$(next_token)
    [ -n "$token" ] && record WEB search "web/search-$name.p01" "$hl" "$gl" "$(q --arg t "$token" '{continuation:$t}')" 0 "продолжение web/search-$name"
  done <<< "$WEB_FILTERS"

  record WEB browse web/channel-videos "$hl" "$gl" "$(q --arg b "$UGC_CHANNEL_ID" --arg p "$CHANNEL_VIDEOS_PARAMS" '{browseId:$b, params:$p}')" 0 "вкладка «Видео» канала Gavrik's Archive"
  token=$(next_token)
  [ -n "$token" ] && record WEB browse web/channel-videos.p01 "$hl" "$gl" "$(q --arg t "$token" '{continuation:$t}')" 0 "продолжение web/channel-videos"
  record WEB browse web/channel-videos-daftpunk "$hl" "$gl" "$(q --arg b "$WEB_CHANNEL_ID" --arg p "$CHANNEL_VIDEOS_PARAMS" '{browseId:$b, params:$p}')"

  record WEB navigation/resolve_url web/resolve-url-handle "$hl" "$gl" '{"url":"https://www.youtube.com/@daftpunk"}' 0 "ответ — urlEndpoint на /daftpunk, нужен второй шаг"
  record WEB navigation/resolve_url web/resolve-url-vanity "$hl" "$gl" '{"url":"https://www.youtube.com/daftpunk"}'
  record WEB navigation/resolve_url web/resolve-url-handle-direct "$hl" "$gl" '{"url":"https://www.youtube.com/@DaftPunkVEVO"}'
  record WEB navigation/resolve_url web/resolve-url-handle-cyrillic "$hl" "$gl" \
    '{"url":"https://www.youtube.com/@%D0%9A%D0%B8%D0%BD%D0%BE%D0%93%D1%80%D1%83%D0%BF%D0%BF%D0%B0%D0%BA%D1%80%D0%BE%D0%B2%D0%B8-%D0%B74%D0%BC"}'
  record WEB navigation/resolve_url web/resolve-url-legacy-c "$hl" "$gl" '{"url":"https://www.youtube.com/c/daftpunk"}'
  record WEB navigation/resolve_url web/resolve-url-legacy-user "$hl" "$gl" '{"url":"https://www.youtube.com/user/daftpunkalive"}'
  record WEB navigation/resolve_url web/resolve-url-not-found "$hl" "$gl" '{"url":"https://www.youtube.com/@thisdoesnotexist-zz9qx"}' 0 "HTTP 404"
  return 0
}

for lang in ru en; do
  if [ "$lang" = ru ]; then hl=ru; gl=RU; else hl=en; gl=US; fi
  case $ONLY in all|ytm) record_ytm "$hl" "$gl" ;; esac
  case $ONLY in all|player) record_player "$hl" "$gl" ;; esac
  case $ONLY in all|web) record_web "$hl" "$gl" ;; esac
done

# index.json: при частичной перезаписи (ONLY=…) старые записи других файлов сохраняются.
if [ -f "$OUT/index.json" ]; then
  jq -c '.files[]' "$OUT/index.json" > "$TMP/old.jsonl"
else
  : > "$TMP/old.jsonl"
fi
jq -s --slurpfile old <(jq -s '.' "$TMP/old.jsonl") \
  --arg vd "$VISITOR_PLACEHOLDER" '
  (. | map(.file)) as $new
  | {format: "melogold-innertube-fixtures", formatVersion: 1,
     visitorDataPlaceholder: $vd,
     files: (($old[0] | map(select(.file as $f | $new | index($f) | not))) + .) | sort_by(.file)}' \
  "$INDEX_LINES" > "$OUT/index.json"

# INDEX.md
{
  echo '# Фикстуры InnerTube'
  echo
  echo 'Записаны `scripts/fixtures/record.sh`. Машиночитаемый список с телами запросов — `index.json`'
  echo '(у продолжений в `request.continuation` лежит токен из предыдущей страницы).'
  echo
  echo '**Обезличивание и чистка.** `visitorData` везде заменён на `'"$VISITOR_PLACEHOLDER"'`. Удалены'
  echo '`trackingParams`, `clickTrackingParams`, `loggingDirectives`, `serviceTrackingParams`, `trackingParam`,'
  echo '`responseId`, `consistencyTokenJar`, рекламные блоки и `playbackTracking`/`attestation`/`heartbeatParams`'
  echo 'плеера. У форматов потоков удалены `url`/`signatureCipher`, у `streamingData` — ссылки на манифесты;'
  echo 'в остальных ссылках `ip=` заменён на `0.0.0.0`, хост узла `rr…---sn-….googlevideo.com` — на'
  echo '`rr1---sn-anonymized.googlevideo.com`, `initcwndbps` — на 0. У самых больших ответов (`stripped: menu`:'
  echo 'страницы продолжений длинного плейлиста, настроение, новые релизы) удалены контекстные меню `menu.menuRenderer`;'
  echo 'первая страница длинного плейлиста полная. Строки текста песни (`musicDescriptionShelfRenderer`) заменены'
  echo 'заглушками «lyrics line N» из-за авторского права; число строк, пустые строки и источник сохранены.'
  echo 'По той же причине в выдаче WEB тексты `snippetText` и `descriptionSnippet` (фрагменты описаний, где часто'
  echo 'цитируется текст песни) заменены на «description snippet», структура `runs` сохранена.'
  echo
  if ls "$OUT"/ytm/playlist-long.p*.ru.json >/dev/null 2>&1; then
    echo '## Длинный плейлист'
    echo
    echo "\`ytm/playlist-long.pNN.ru.json\` — публичный плейлист \`$LONG_PLAYLIST_ID\`, все страницы продолжений."
    echo "Шапка: «$(jq -r '[.. | .musicResponsiveHeaderRenderer? // empty | .secondSubtitle.runs | map(.text) | join("")] | first' "$OUT/ytm/playlist-long.p00.ru.json")»."
    echo "Страниц: $(ls "$OUT"/ytm/playlist-long.p*.ru.json | wc -l | tr -d ' '); строк \`musicResponsiveListItemRenderer\` всего:" \
      "$(cat "$OUT"/ytm/playlist-long.p*.ru.json | jq -s '[.[] | .. | .musicResponsiveListItemRenderer? // empty] | length')," \
      "из них серых (\`MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT\`):" \
      "$(cat "$OUT"/ytm/playlist-long.p*.ru.json | jq -s '[.[] | .. | .musicResponsiveListItemRenderer? // empty | select(.musicItemRendererDisplayPolicy == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT")] | length');" \
      "разных \`playlistItemData.videoId\`: $(cat "$OUT"/ytm/playlist-long.p*.ru.json | jq -s '[.[] | .. | .musicResponsiveListItemRenderer? // empty | .playlistItemData.videoId] | unique | length')."
    echo 'Разница с числом в шапке — треки, которые YouTube Music в ответ не отдаёт вовсе. Плейлист «на 2096 треков»'
    echo 'из REWRITE.md — плейлист живой проверки, его id не сохранился; этот взят как ближайший большой и старый публичный.'
    echo
  fi
  echo '## Файлы'
  echo
  echo '| Файл | Эндпоинт | Клиент | Запрос (без context) | hl / gl | HTTP | Дата | Примечание |'
  echo '|---|---|---|---|---|---|---|---|'
  jq -r '.files[] |
    [ "`" + .file + "`",
      "`" + .host + .endpoint + "`",
      .client + " " + .clientVersion,
      ( .request | to_entries | map(
          if .key == "continuation" then "continuation: …" + (.value | .[-12:])
          else .key + ": " + (.value | tostring) end) | join(", ") | gsub("\\|"; "\\|") ),
      .hl + " / " + .gl,
      (.httpStatus | tostring),
      .recordedAt,
      ((.note // "") + (if .stripped then " (без menu)" else "" end))
    ] | "| " + join(" | ") + " |"' "$OUT/index.json"
} > "$OUT/INDEX.md"

# Проверки: валидный JSON, нет живого visitorData и IPv4 в строках.
bad=0
for f in $(jq -r '.files[].file' "$OUT/index.json"); do
  jq empty "$OUT/$f" || { log "!! невалидный JSON: $f"; bad=1; }
  if jq -e --arg vd "$VISITOR_PLACEHOLDER" '[.. | objects | select(has("visitorData")) | .visitorData | select(. != $vd)] | length > 0' "$OUT/$f" >/dev/null; then
    log "!! живой visitorData: $f"; bad=1
  fi
  if jq -e '[.. | strings | select(test("(^|[^0-9.])((25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])($|[^0-9.])")) | select(test("0\\.0\\.0\\.0|codecs=") | not)] | length > 0' "$OUT/$f" >/dev/null; then
    log "?? похоже на IPv4, проверьте вручную: $f"
  fi
done
[ "$bad" = 0 ] || exit 1

# Копия для debug-сборки (MockEngine, §6.0.8).
rm -rf "$DEBUG_OUT"
mkdir -p "$DEBUG_OUT"
cp -R "$OUT/." "$DEBUG_OUT/"
log "готово: $(jq '.files | length' "$OUT/index.json") файлов, $(du -sh "$OUT" | cut -f1)"
