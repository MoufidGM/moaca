#!/bin/bash
#
# Builds CSLSM.app — a double-clickable macOS application with its own bundled
# Java runtime, so the app runs without IntelliJ and without a JDK installed.
#
#   ./package-mac.sh              build the app into dist/
#   ./package-mac.sh --install    build, then copy it to /Applications
#
# The database and the CSLSM folders stay in this project directory: the app is
# launched with -Dcslsm.home pointing here, so nothing has to be moved.
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_NAME="CSLSM"
DIST_DIR="$PROJECT_DIR/dist"

cd "$PROJECT_DIR"

# ---------- locate a JDK 21+ (jpackage lives inside it) ----------
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/jpackage" ]]; then
  if /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  elif /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home)"
  fi
fi
export JAVA_HOME

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/jpackage" ]]; then
  echo "ERROR: no JDK 21+ with jpackage found."
  echo "IntelliJ ships one; point JAVA_HOME at it, for example:"
  echo "  JAVA_HOME=/Applications/IntelliJ\\ IDEA.app/Contents/jbr/Contents/Home ./package-mac.sh"
  exit 1
fi
echo "==> Using JDK: $JAVA_HOME"

# ---------- locate maven (IntelliJ bundles one if it is not on PATH) ----------
if command -v mvn >/dev/null 2>&1; then
  MVN="mvn"
else
  BUNDLED_MVN="$(ls -d /Applications/IntelliJ*IDEA*.app/Contents/plugins/maven/lib/maven3/bin/mvn 2>/dev/null | head -1 || true)"
  if [[ -n "$BUNDLED_MVN" ]]; then
    MVN="$BUNDLED_MVN"
  else
    echo "ERROR: maven not found. Install it with:  brew install maven"
    exit 1
  fi
fi
echo "==> Using Maven: $MVN"

# ---------- build ----------
echo "==> Building the jar and collecting dependencies…"
"$MVN" -q clean package -DskipTests

MAIN_JAR="$(cd target/app && ls -1 *.jar | head -1)"
if [[ -z "$MAIN_JAR" ]]; then
  echo "ERROR: no jar produced in target/app."
  exit 1
fi
echo "==> Main jar: $MAIN_JAR"

# ---------- package ----------
echo "==> Running jpackage (this takes a minute)…"
rm -rf "$DIST_DIR/$APP_NAME.app"
mkdir -p "$DIST_DIR"

"$JAVA_HOME/bin/jpackage" \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "1.0.0" \
  --vendor "CSLSM" \
  --input target/app \
  --main-jar "$MAIN_JAR" \
  --main-class com.cslsm.app.Launcher \
  --java-options "-Dcslsm.home=$PROJECT_DIR" \
  --java-options "-Xmx1g" \
  --dest "$DIST_DIR"

# Gatekeeper: the app is unsigned, so clear the quarantine flag to avoid
# "CSLSM is damaged" / "unidentified developer" warnings on first launch.
xattr -cr "$DIST_DIR/$APP_NAME.app" 2>/dev/null || true

echo
echo "==> Done: $DIST_DIR/$APP_NAME.app"

if [[ "${1:-}" == "--install" ]]; then
  echo "==> Installing to /Applications…"
  rm -rf "/Applications/$APP_NAME.app"
  cp -R "$DIST_DIR/$APP_NAME.app" /Applications/
  xattr -cr "/Applications/$APP_NAME.app" 2>/dev/null || true
  echo "==> Installed: /Applications/$APP_NAME.app"
  echo "    Open it once from Finder, then keep it in your Dock."
else
  echo "    Double-click it, or re-run with --install to copy it to /Applications."
fi
