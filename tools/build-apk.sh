#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$sdk" ]]; then
    echo 'Укажите ANDROID_SDK_ROOT и установите platforms;android-35 и build-tools;35.0.0.' >&2
    exit 2
fi
platform="$sdk/platforms/android-35/android.jar"
build_tools="$sdk/build-tools/35.0.0"
for path in "$platform" "$build_tools/aapt2" "$build_tools/d8" "$build_tools/apksigner"; do
    [[ -e "$path" ]] || { printf 'Не найдено %s\n' "$path" >&2; exit 2; }
done

java_home=${JAVA_HOME:-}
if [[ -n "$java_home" ]]; then
    java="$java_home/bin/java"
    keytool="$java_home/bin/keytool"
else
    java=java
    keytool=keytool
fi
if [[ -n "$java_home" && -x "$java_home/bin/javac" ]]; then
    compiler=("$java_home/bin/javac")
elif command -v javac >/dev/null; then
    compiler=(javac)
else
    compiler=("$java" -m jdk.compiler/com.sun.tools.javac.Main)
fi

mkdir -p out
out="$PWD/out"
work=$(mktemp -d "$out/.build.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/generated" "$work/classes" "$work/dex"

"$build_tools/aapt2" compile --dir app/src/main/res -o "$work/resources.zip"
sed '1s/<manifest /<manifest package="dev.panelume.reader.next" /' \
    app/src/main/AndroidManifest.xml > "$work/AndroidManifest.xml"
"$build_tools/aapt2" link -I "$platform" --manifest "$work/AndroidManifest.xml" \
    --custom-package dev.panelume.reader \
    --min-sdk-version 26 --target-sdk-version 35 --version-code 5 --version-name 0.2.3 \
    -A app/src/main/assets --java "$work/generated" -o "$work/unsigned.apk" "$work/resources.zip"
mapfile -d '' sources < <(find app/src/main/java "$work/generated" -name '*.java' -print0)
"${compiler[@]}" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$build_tools/core-lambda-stubs.jar:$platform" \
    -Xlint:all,-deprecation,-options -d "$work/classes" "${sources[@]}"
mapfile -d '' classes < <(find "$work/classes" -name '*.class' -print0)
"$build_tools/d8" --lib "$platform" --min-api 26 --output "$work/dex" "${classes[@]}"
(cd "$work/dex" && zip -q "$work/unsigned.apk" classes*.dex)
"$build_tools/zipalign" -f -p 4 "$work/unsigned.apk" "$work/aligned.apk"

keystore=${PANELUME_DEBUG_KEYSTORE:-"$out/panelume-next-debug.keystore"}
if [[ ! -f "$keystore" ]]; then
    "$keytool" -genkeypair -keystore "$keystore" -storepass android -keypass android \
        -alias androiddebugkey -dname 'CN=Panelume Debug,O=Panelume,C=US' \
        -keyalg RSA -keysize 2048 -validity 10000
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$work/Panelume-0.2.3.apk" "$work/aligned.apk"
"$build_tools/apksigner" verify "$work/Panelume-0.2.3.apk"
mv "$work/Panelume-0.2.3.apk" "$out/"
printf '%s\n' "$out/Panelume-0.2.3.apk"
