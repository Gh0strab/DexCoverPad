#!/bin/sh
# Gradle wrapper — downloads the Gradle distribution on first run.
set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)
WRAPPER_PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

# Parse distributionUrl (unescape backslash-colons from .properties format)
DIST_URL=$(grep "^distributionUrl=" "$WRAPPER_PROPS" | cut -d= -f2- | sed 's/\\://g' | tr -d '\r')
DIST_BASE=$(grep "^distributionBase=" "$WRAPPER_PROPS" | cut -d= -f2 | tr -d '\r')
DIST_PATH=$(grep "^distributionPath=" "$WRAPPER_PROPS" | cut -d= -f2 | tr -d '\r')

if [ "$DIST_BASE" = "GRADLE_USER_HOME" ]; then
    GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
    DIST_ROOT="$GRADLE_USER_HOME"
else
    DIST_ROOT="$APP_HOME"
fi

DIST_FILENAME=$(basename "$DIST_URL")
DIST_NAME="${DIST_FILENAME%.zip}"
DIST_DIR="$DIST_ROOT/$DIST_PATH/$DIST_NAME"

if [ ! -d "$DIST_DIR" ]; then
    echo "Downloading $DIST_URL ..."
    mkdir -p "$DIST_DIR"
    TMP_ZIP="$DIST_DIR/${DIST_FILENAME}"
    if command -v curl >/dev/null 2>&1; then
        curl -fL -o "$TMP_ZIP" "$DIST_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$TMP_ZIP" "$DIST_URL"
    else
        echo "Error: curl or wget required to download Gradle" >&2
        exit 1
    fi
    unzip -q "$TMP_ZIP" -d "$DIST_DIR"
    rm -f "$TMP_ZIP"
fi

# The zip extracts to a single top-level directory (e.g. gradle-8.7/)
GRADLE_EXTRACTED=$(find "$DIST_DIR" -maxdepth 1 -type d -name "gradle-*" | head -1)
if [ -z "$GRADLE_EXTRACTED" ]; then
    echo "Error: Could not find extracted Gradle directory in $DIST_DIR" >&2
    exit 1
fi

exec "$GRADLE_EXTRACTED/bin/gradle" "$@"
