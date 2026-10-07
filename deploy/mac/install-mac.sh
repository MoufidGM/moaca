#!/bin/bash
#
# Trial deployment of the CSLSM web app on this Mac.
#
#   deploy/mac/install-mac.sh                  first install, or update after a code change
#   deploy/mac/install-mac.sh --with-tests     run the test suite before building the jar
#   deploy/mac/install-mac.sh --fresh-copy     replace the trial database with a new copy of
#                                              data/daily_logs.db (web accounts are lost)
#   deploy/mac/install-mac.sh --new-certificate  make a new certificate (after the Mac's
#                                              name or network address changed)
#
# Everything lives in one folder, ~/cslsm-web (override with CSLSM_HOME):
#   app/      the jar and its start script        config/   cslsm.env (secrets), certificate
#   data/     a COPY of the database              files/    uploaded daily logs and receipts
#   logs/     cslsm-web.log                       backups/  copies made with "cslsm-web backup"
#
# The app runs as a launchd agent (com.cslsm.web): started at login, restarted if it stops,
# reachable at https://<this-mac>.local:8443. The desktop app and data/daily_logs.db are
# never touched — the web app works on its own copy until the real cut-over (M6).
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CSLSM_HOME="${CSLSM_HOME:-$HOME/cslsm-web}"
PORT="${CSLSM_PORT:-8443}"
LABEL="com.cslsm.web"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

WITH_TESTS=no
FRESH_COPY=no
NEW_CERT=no
for arg in "$@"; do
  case "$arg" in
    --with-tests) WITH_TESTS=yes ;;
    --fresh-copy) FRESH_COPY=yes ;;
    --new-certificate) NEW_CERT=yes ;;
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done

# ---------- tools ----------
if ! JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
  echo "ERROR: JDK 21 not found (the app is built and run with Java 21)."
  exit 1
fi
export JAVA_HOME
if command -v mvn >/dev/null 2>&1; then
  MVN="mvn"
else
  MVN="$(ls -d /Applications/IntelliJ*IDEA*.app/Contents/plugins/maven/lib/maven3/bin/mvn 2>/dev/null | head -1 || true)"
  if [[ -z "$MVN" ]]; then
    echo "ERROR: maven not found. Install it with:  brew install maven"
    exit 1
  fi
fi
for tool in keytool openssl sqlite3 curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: $tool not found."; exit 1; }
done
echo "==> JDK: $JAVA_HOME"

# ---------- build ----------
echo "==> Building the jar$([[ $WITH_TESTS == yes ]] && echo ' (with tests)' || echo ' (tests skipped: --with-tests runs them)')…"
if [[ $WITH_TESTS == yes ]]; then
  (cd "$PROJECT_DIR/web" && "$MVN" -q package)
else
  (cd "$PROJECT_DIR/web" && "$MVN" -q package -DskipTests)
fi
JAR="$PROJECT_DIR/web/target/cslsm-web.jar"
[[ -f "$JAR" ]] || { echo "ERROR: $JAR was not produced."; exit 1; }

# ---------- folders and settings ----------
mkdir -p "$CSLSM_HOME"/{app,data,files,config,logs,backups}
ENV_FILE="$CSLSM_HOME/config/cslsm.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "==> Creating $ENV_FILE (secret key and certificate password)"
  SECRET_KEY="$(openssl rand -base64 48 | tr -d '\n')"
  KEYSTORE_PASSWORD="$(openssl rand -hex 16)"
  cat > "$ENV_FILE" <<ENV
# CSLSM web app on this Mac — written by deploy/mac/install-mac.sh. Keep this file private:
# CSLSM_SECRET_KEY encrypts the two-factor secrets in the database. If it is lost, every
# admin has to enroll their authenticator again (cslsm-web user --reset-2fa=NAME).
CSLSM_HOME='$CSLSM_HOME'
CSLSM_PROJECT_DIR='$PROJECT_DIR'
CSLSM_DB_PATH='$CSLSM_HOME/data/daily_logs.db'
CSLSM_FILES_DIR='$CSLSM_HOME/files'
CSLSM_SECRET_KEY='$SECRET_KEY'
CSLSM_KEYSTORE='$CSLSM_HOME/config/cslsm-mac.p12'
CSLSM_KEYSTORE_PASSWORD='$KEYSTORE_PASSWORD'
CSLSM_PORT='$PORT'
JAVA_HOME='$JAVA_HOME'
ENV
  chmod 600 "$ENV_FILE"
fi
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

# ---------- stop the running service (the jar must not change under a running app) ----------
if launchctl print "gui/$UID/$LABEL" >/dev/null 2>&1; then
  echo "==> Stopping the running app"
  launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
  for _ in $(seq 1 30); do
    launchctl print "gui/$UID/$LABEL" >/dev/null 2>&1 || break
    sleep 1
  done
fi

# ---------- certificate ----------
HOST_NAME="$(scutil --get LocalHostName 2>/dev/null || hostname -s).local"
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
if [[ ! -f "$CSLSM_KEYSTORE" || $NEW_CERT == yes ]]; then
  echo "==> Making a certificate for $HOST_NAME${LAN_IP:+ and $LAN_IP} (valid 10 years)"
  SAN="dns:$HOST_NAME,dns:localhost,ip:127.0.0.1"
  [[ -n "$LAN_IP" ]] && SAN="$SAN,ip:$LAN_IP"
  rm -f "$CSLSM_KEYSTORE"
  keytool -genkeypair -alias cslsm -keyalg RSA -keysize 2048 -validity 3650 \
          -dname "CN=$HOST_NAME, O=CSLSM" -ext "SAN=$SAN" \
          -keystore "$CSLSM_KEYSTORE" -storetype PKCS12 -storepass "$CSLSM_KEYSTORE_PASSWORD" >/dev/null
  keytool -exportcert -rfc -alias cslsm -keystore "$CSLSM_KEYSTORE" \
          -storepass "$CSLSM_KEYSTORE_PASSWORD" -file "$CSLSM_HOME/config/cslsm-mac.crt" >/dev/null 2>&1
  chmod 600 "$CSLSM_KEYSTORE"
fi

# ---------- database: a consistent copy of the desktop app's file ----------
SOURCE_DB="$PROJECT_DIR/data/daily_logs.db"
if [[ ! -f "$CSLSM_DB_PATH" || $FRESH_COPY == yes ]]; then
  [[ -f "$SOURCE_DB" ]] || { echo "ERROR: $SOURCE_DB not found."; exit 1; }
  if [[ -f "$CSLSM_DB_PATH" ]]; then
    KEEP="$CSLSM_HOME/backups/daily_logs-before-fresh-copy-$(date +%Y%m%d-%H%M%S).db"
    echo "==> Keeping the previous trial database as $KEEP"
    sqlite3 "$CSLSM_DB_PATH" ".backup '$KEEP'"
  fi
  echo "==> Copying $SOURCE_DB"
  rm -f "$CSLSM_DB_PATH" "$CSLSM_DB_PATH-wal" "$CSLSM_DB_PATH-shm"
  sqlite3 "$SOURCE_DB" ".backup '$CSLSM_DB_PATH'"
fi

# ---------- app files ----------
cp "$JAR" "$CSLSM_HOME/app/cslsm-web.jar"
cp "$PROJECT_DIR/deploy/mac/cslsm-web" "$CSLSM_HOME/cslsm-web"
chmod +x "$CSLSM_HOME/cslsm-web"
cat > "$CSLSM_HOME/app/run.sh" <<RUN
#!/bin/bash
# Started by launchd (see ~/Library/LaunchAgents/$LABEL.plist). Settings come from cslsm.env.
set -a
. '$ENV_FILE'
set +a
exec "\$JAVA_HOME/bin/java" -Xmx512m -jar '$CSLSM_HOME/app/cslsm-web.jar' --spring.profiles.active=mac
RUN
chmod +x "$CSLSM_HOME/app/run.sh"

mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>$CSLSM_HOME/app/run.sh</string></array>
  <key>WorkingDirectory</key><string>$CSLSM_HOME</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$CSLSM_HOME/logs/cslsm-web.log</string>
  <key>StandardErrorPath</key><string>$CSLSM_HOME/logs/cslsm-web.log</string>
</dict>
</plist>
PLIST

# ---------- start and wait ----------
echo "==> Starting the app"
launchctl bootstrap "gui/$UID" "$PLIST"
URL="https://$HOST_NAME:$PORT"
UP=no
for _ in $(seq 1 90); do
  if [[ "$(curl -ksS -o /dev/null -w '%{http_code}' "https://127.0.0.1:$PORT/login" 2>/dev/null)" == "200" ]]; then
    UP=yes
    break
  fi
  sleep 1
done
if [[ $UP != yes ]]; then
  echo "ERROR: the app did not answer within 90 s. Last lines of the log:"
  tail -n 40 "$CSLSM_HOME/logs/cslsm-web.log"
  exit 1
fi

echo
echo "==> Running: $URL${LAN_IP:+  (or https://$LAN_IP:$PORT)}"
echo "    The certificate is self-signed: the browser warns once; choose to continue."
echo "    Control: $CSLSM_HOME/cslsm-web  start|stop|restart|status|logs|user|backup|update"
USERS="$(sqlite3 "$CSLSM_DB_PATH" 'SELECT count(*) FROM app_user' 2>/dev/null || echo 0)"
if [[ "$USERS" == "0" ]]; then
  echo
  echo "    No account yet. Create the super admin (the password is asked at a hidden prompt):"
  echo "      $CSLSM_HOME/cslsm-web user --create-user=mo --role=SUPER_ADMIN --name=\"Moufid\""
fi
