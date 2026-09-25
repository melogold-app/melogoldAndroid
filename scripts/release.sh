#!/usr/bin/env bash
# The release of the app (REWRITE §4.14): the signed APK of the version in gradle.properties
# (`melogold.version`) and the `update.json` the self-update reads, in dist/. With --publish, also
# the GitHub release v<version> of melogold-app/melogoldAndroid with both files: from then on every
# installed Melogold offers it in Settings.
#
#   scripts/release.sh             build into dist/
#   scripts/release.sh --publish   build and publish
#
# "What's new": release-notes/<version>.ru.md and release-notes/<version>.en.md (optional).
# The signing key: ~/.gradle/gradle.properties (melogold.release.*) or MELOGOLD_RELEASE_* variables;
# it never enters the repository.
set -euo pipefail
cd "$(dirname "$0")/.."

publish=false
[[ "${1:-}" == "--publish" ]] && publish=true

version=$(sed -n 's/^melogold.version=//p' gradle.properties)
IFS=. read -r major minor patch <<<"${version%%-*}"
version_code=$((major * 10000 + minor * 100 + patch))
file="Melogold-$version.apk"

./gradlew --no-daemon --console=plain :app:assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
if [[ ! -f "$apk" ]]; then
    echo "No signed APK: the release key is not configured (melogold.release.* in ~/.gradle/gradle.properties)" >&2
    exit 1
fi

mkdir -p dist
cp "$apk" "dist/$file"
sha256=$(shasum -a 256 "dist/$file" | cut -d' ' -f1)
size=$(stat -f%z "dist/$file" 2>/dev/null || stat -c%s "dist/$file")

python3 - "$version" "$version_code" "$file" "$size" "$sha256" >dist/update.json <<'PY'
import datetime, json, os, sys

version, code, file, size, sha256 = sys.argv[1:]
notes = {}
for lang in ("ru", "en"):
    path = f"release-notes/{version}.{lang}.md"
    if os.path.exists(path):
        notes[lang] = open(path, encoding="utf-8").read().strip()
print(json.dumps({
    "version": version,
    "versionCode": int(code),
    "fileName": file,
    "sizeBytes": int(size),
    "sha256": sha256,
    "notes": notes,
    "publishedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
}, ensure_ascii=False, indent=2))
PY

build_tools=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* | sort -V | tail -1)
echo "dist/$file · $size bytes · sha256 $sha256"
"$build_tools/apksigner" verify --print-certs "dist/$file" | grep -E "SHA-256 digest" || true

if $publish; then
    notes_file=$(mktemp)
    for lang in ru en; do
        [[ -f "release-notes/$version.$lang.md" ]] && { cat "release-notes/$version.$lang.md"; echo; } >>"$notes_file"
    done
    gh release create "v$version" "dist/$file" dist/update.json \
        --repo melogold-app/melogoldAndroid \
        --title "Melogold $version" \
        --notes-file "$notes_file"
    rm -f "$notes_file"
fi
