#!/bin/sh
# Regenerate payload/*.class from HMCL sources.
#
# Usage:
#   ./tools/build-payload.sh [HMCL.jar] [HMCL-main] [JavaFX-lib-dir]
#
# The two patched classes only depend on HMCL's jar and JavaFX, so compiling
# them standalone is enough. The full HMCL source tree is not required.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

HMCL_JAR=${1:-}
if [ -z "$HMCL_JAR" ]; then
    HMCL_JAR=$(find "$ROOT/.." -maxdepth 1 -name 'HMCL-*.jar' -type f | sort | tail -n 1)
fi
if [ -z "$HMCL_JAR" ] || [ ! -f "$HMCL_JAR" ]; then
    echo "找不到 HMCL jar，请把路径作为第一个参数传入。" >&2
    exit 1
fi

HMCL_SRC=${2:-"$ROOT/../HMCL-main"}
if [ ! -f "$HMCL_SRC/HMCL/src/main/java/org/jackhuang/hmcl/ui/download/VersionsPage.java" ]; then
    echo "找不到 HMCL 源码目录：$HMCL_SRC" >&2
    exit 1
fi

FX_DIR=${3:-}
if [ -z "$FX_DIR" ]; then
    FX_DIR=$(find "$ROOT/../.hmcl/dependencies" "${HOME:-/nonexistent}/.hmcl/dependencies" \
        -type d -name openjfx 2>/dev/null | head -n 1 || true)
fi
if [ -z "$FX_DIR" ] || [ ! -d "$FX_DIR" ]; then
    echo "找不到 JavaFX 目录，请把包含 javafx-*.jar 的目录作为第三个参数传入。" >&2
    exit 1
fi

FX_CP=$(find "$FX_DIR" -maxdepth 1 -name '*.jar' -type f | tr '\n' ':')
if [ -z "$FX_CP" ]; then
    echo "JavaFX 目录中没有 jar：$FX_DIR" >&2
    exit 1
fi

TMP=$(mktemp -d "${TMPDIR:-/tmp}/fuck_hmcl_payload.XXXXXX")
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/src/org/jackhuang/hmcl/ui/download" \
         "$TMP/src/org/jackhuang/hmcl/ui/instances" \
         "$TMP/classes"

cp "$HMCL_SRC/HMCL/src/main/java/org/jackhuang/hmcl/ui/download/VersionsPage.java" \
   "$TMP/src/org/jackhuang/hmcl/ui/download/VersionsPage.java"
cp "$HMCL_SRC/HMCL/src/main/java/org/jackhuang/hmcl/ui/instances/DownloadListPage.java" \
   "$TMP/src/org/jackhuang/hmcl/ui/instances/DownloadListPage.java"

python3 - "$TMP/src" <<'PY'
import sys
from pathlib import Path

root = Path(sys.argv[1])
for path in root.rglob('*.java'):
    text = path.read_text(encoding='utf-8')
    text = text.replace(
        'new HintPane(MessageDialogPane.MessageType.WARNING)',
        'new HintPane(MessageDialogPane.MessageType.INFO)')
    text = text.replace('                root.setDisable(true);\n', '')
    text = text.replace('                pane.setDisable(true);\n', '')
    path.write_text(text, encoding='utf-8')
PY

javac --release 17 -encoding UTF-8 \
    -cp "$HMCL_JAR:$FX_CP" \
    -d "$TMP/classes" \
    "$TMP/src/org/jackhuang/hmcl/ui/download/VersionsPage.java" \
    "$TMP/src/org/jackhuang/hmcl/ui/instances/DownloadListPage.java"

mkdir -p "$ROOT/go/payload/org/jackhuang/hmcl/ui/download" \
         "$ROOT/go/payload/org/jackhuang/hmcl/ui/instances"
cp "$TMP/classes/org/jackhuang/hmcl/ui/download/VersionsPage\$VersionsPageSkin.class" \
   "$ROOT/go/payload/org/jackhuang/hmcl/ui/download/"
cp "$TMP/classes/org/jackhuang/hmcl/ui/instances/DownloadListPage\$ModDownloadListPageSkin.class" \
   "$ROOT/go/payload/org/jackhuang/hmcl/ui/instances/"

echo "已更新 payload："
find "$ROOT/go/payload" -name '*.class' -printf '  %s %p\n'
