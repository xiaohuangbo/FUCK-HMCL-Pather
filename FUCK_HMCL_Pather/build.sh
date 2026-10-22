#!/bin/sh
# Build FUCK_HMCL_Pather (jar + self-contained sh + Windows exe).
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"

VERSION=1.1.0
BUILD="$ROOT/build"
DIST="$ROOT/dist"
GO=${GO:-go}

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$DIST"

echo "[1/4] 编译 Java 补丁程序..."
find "$ROOT/src/main/java" -name '*.java' -exec \
    javac --release 17 -encoding UTF-8 -d "$BUILD/classes" {} +
cp -R "$ROOT/go/payload" "$BUILD/classes/payload"
jar --create --file "$DIST/FUCK_HMCL_Pather.jar" \
    --main-class fuck.hmcl.pather.FuckHmclPather \
    -C "$BUILD/classes" .
echo "      -> dist/FUCK_HMCL_Pather.jar"

echo "[2/4] 生成自包含的 .sh..."
{
    cat <<'HEADER'
#!/bin/sh
# FUCK_HMCL_Pather - HMCL 下载限制解除补丁
# 用法: ./FUCK_HMCL_Pather.sh [HMCL.jar] [--restore]
set -eu

SELF=$0
SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$SELF")" && pwd)
FUCK_HMCL_PATHER_DIR=$SELF_DIR
export FUCK_HMCL_PATHER_DIR

PAYLOAD_LINE=$(awk '/^__FUCK_HMCL_PAYLOAD_BELOW__$/ { print NR + 1; exit }' "$SELF")
if [ -z "${PAYLOAD_LINE:-}" ]; then
    echo "[FUCK_HMCL_Pather] 补丁数据损坏：找不到 payload 标记" >&2
    exit 1
fi

TMP=$(mktemp -d "${TMPDIR:-/tmp}/fuck_hmcl_pather.XXXXXX")
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

decode_base64() {
    if base64 --decode </dev/null >/dev/null 2>&1; then
        base64 --decode
    elif base64 -D </dev/null >/dev/null 2>&1; then
        base64 -D
    elif base64 -d </dev/null >/dev/null 2>&1; then
        base64 -d
    elif command -v openssl >/dev/null 2>&1; then
        openssl base64 -d -A
    else
        echo "[FUCK_HMCL_Pather] 找不到 base64 解码工具" >&2
        return 1
    fi
}

if ! tail -n +"$PAYLOAD_LINE" "$SELF" | decode_base64 > "$TMP/FUCK_HMCL_Pather.jar"; then
    echo "[FUCK_HMCL_Pather] 解出补丁程序失败" >&2
    exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA=$JAVA_HOME/bin/java
elif command -v java >/dev/null 2>&1; then
    JAVA=java
else
    echo "[FUCK_HMCL_Pather] 找不到 Java。HMCL 需要 Java 17 或更高版本。" >&2
    exit 1
fi

"$JAVA" -jar "$TMP/FUCK_HMCL_Pather.jar" "$@"
STATUS=$?
exit $STATUS
__FUCK_HMCL_PAYLOAD_BELOW__
HEADER
    base64 "$DIST/FUCK_HMCL_Pather.jar"
} > "$DIST/FUCK_HMCL_Pather.sh"
chmod +x "$DIST/FUCK_HMCL_Pather.sh"
echo "      -> dist/FUCK_HMCL_Pather.sh"

echo "[3/4] 编译 Windows 原生 .exe..."
if command -v "$GO" >/dev/null 2>&1; then
    (
        cd "$ROOT/go"
        CGO_ENABLED=0 GOOS=windows GOARCH=amd64 "$GO" build \
            -trimpath -ldflags "-s -w" -o "$DIST/FUCK_HMCL_Pather.exe" .
    )
    echo "      -> dist/FUCK_HMCL_Pather.exe"
else
    echo "      跳过：找不到 Go（$GO），保留 dist/FUCK_HMCL_Pather.exe 的现有版本。"
fi

echo "[4/4] 完成。"
ls -lh "$DIST"
