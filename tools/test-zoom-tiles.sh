#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -d "$work" app/src/main/java/dev/panelume/reader/ZoomTiles.java app/src/main/java/dev/panelume/reader/Names.java tools/tests/ZoomTilesTest.java tools/tests/SearchAliasesTest.java
java -cp "$work" dev.panelume.reader.ZoomTilesTest

java -cp "$work" dev.panelume.reader.SearchAliasesTest
