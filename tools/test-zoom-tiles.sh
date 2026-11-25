#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -d "$work" app/src/main/java/dev/panelume/reader/ZoomTiles.java tools/tests/ZoomTilesTest.java
java -cp "$work" dev.panelume.reader.ZoomTilesTest
