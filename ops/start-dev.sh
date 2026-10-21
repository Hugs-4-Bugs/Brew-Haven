#!/bin/bash
# =============================================================================
# Cafe Management System — one-command development startup
# =============================================================================
# Usage: ./start-dev.sh        (idempotent — safe to re-run)
#
# Starts, in order:
#   1. MariaDB          (preserved datadir tools/mariadb-data, :3306 + unix socket)
#   2. Spring Boot API  (target jar, :8000; waits until a REAL login succeeds)
#   3. Vite frontend    (:3000 = platform preview default; /api,/user,/product,/category,/bill,/dashboard proxied to :8000)
#
# Every service is daemonized with `setsid --fork` so it re-parents to PID 1
# and survives the invoking shell exiting. (In this sandbox, plain `cmd &`
# children are killed when the spawning shell session ends — verified
# empirically; setsid --fork is the only reliable escape.)
#
# Pidfiles + logs: <stack>/logs/     Stop: ./stop-dev.sh    Status: ./status-dev.sh
# =============================================================================
set -u

ROOT="${CAFEMS_ROOT:-/tmp/my-project}"
TOOLS="$ROOT/tools"
REPO="$ROOT/repo"
FE="$REPO/frontend"
LOGS="$ROOT/logs"
RUN="$LOGS/run"
DB_SOCKET="$TOOLS/mariadb-sock/mysqld.sock"
PY=/home/z/.venv/bin/python3
[ -x "$PY" ] || PY=python3
export LD_LIBRARY_PATH="$TOOLS/libaio-extracted/usr/lib/x86_64-linux-gnu:$TOOLS/mariadb/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# --- backend runtime configuration (matches the verified E2E configuration) ---
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3306/CafeMgt?createDatabaseIfNotExist=true"
export SPRING_DATASOURCE_USERNAME="${DB_USERNAME:-cafe}"
export SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD:?DB_PASSWORD must be set - see ops/env.example}"
export SPRING_JPA_SHOW_SQL="false"
export SPRING_MAIL_HOST="${SPRING_MAIL_HOST:-localhost}"       # dev placeholder; no SMTP server by design
export SPRING_MAIL_PORT="${SPRING_MAIL_PORT:-1025}"
export SPRING_MAIL_USERNAME="${SPRING_MAIL_USERNAME:-dev-local}"
export SPRING_MAIL_PASSWORD="${SPRING_MAIL_PASSWORD:-dev-local}"
export CAFE_BILL_STORE_LOCATION="$ROOT/artifacts/bills"
export CAFE_UPI_PAYEE_VPA="${CAFE_UPI_PAYEE_VPA:-cafe@ybl}"    # required for UPI QR (fails safe 503 if unset)
export CAFE_UPI_PAYEE_NAME="${CAFE_UPI_PAYEE_NAME:-Cafe Management System}"
export JAVA_HOME="$TOOLS/jdk-17.0.20.1+1"

ADMIN_EMAIL="${CAFEMS_ADMIN_EMAIL:-admin@cafe.local}"
ADMIN_PASSWORD="${CAFEMS_ADMIN_PASSWORD:?CAFEMS_ADMIN_PASSWORD must be set - see ops/env.example}"

mkdir -p "$LOGS" "$RUN" "$TOOLS/mariadb-sock" "$CAFE_BILL_STORE_LOCATION"

log()  { printf '[starter] %s\n' "$*"; }
fail() { printf '[starter] FAILED: %s\n' "$*" >&2; exit 1; }
alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }
port_listening() { ss -tln 2>/dev/null | grep -q ":$1 "; }
jsontoken() { printf '%s' "$1" | "$PY" -c 'import sys,json
try: print(json.load(sys.stdin).get("token",""))
except Exception: print("")' 2>/dev/null; }
probe() { curl -4 -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 4 "$1" 2>/dev/null; }

# =============================================================================
log "1/6 prerequisites"
[ -d "$TOOLS/mariadb" ]              || fail "tools/mariadb missing under $ROOT"
[ -d "$TOOLS/mariadb-data/CafeMgt" ] || fail "datadir tools/mariadb-data/CafeMgt missing (refusing an empty datadir)"
[ -x "$JAVA_HOME/bin/java" ]         || fail "JDK17 missing at $JAVA_HOME"
JAR=$(ls "$REPO"/target/Cafeboookingsystem-*.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || fail "backend jar missing — build first: cd $REPO && tools/maven/bin/mvn -DskipTests package"
[ -f "$FE/package.json" ]            || fail "frontend missing at $FE"
[ -x "$FE/node_modules/.bin/vite" ]  || fail "frontend deps missing — run: cd $FE && npm ci"
command -v curl >/dev/null           || fail "curl not found"
log "  jar=$JAR"

# =============================================================================
log "2/6 database (MariaDB, preserved data at tools/mariadb-data)"
if alive "$RUN/mariadb.pid" && port_listening 3306; then
  log "  already running (pid $(cat "$RUN/mariadb.pid"))"
else
  rm -f "$RUN/mariadb.pid" "$DB_SOCKET"
  setsid --fork "$TOOLS/mariadb/bin/mysqld" \
    --basedir="$TOOLS/mariadb" --datadir="$TOOLS/mariadb-data" \
    --socket="$DB_SOCKET" --port=3306 --skip-networking=0 --bind-address=127.0.0.1 \
    --log-error="$LOGS/mariadb.err" </dev/null >/dev/null 2>&1 \
    || fail "mysqld failed to launch"
  MPID=$(pgrep -n -f "mysqld .*mariadb-data")
  [ -n "$MPID" ] && echo "$MPID" > "$RUN/mariadb.pid"
  for i in $(seq 1 45); do
    "$TOOLS/mariadb/bin/mariadb" --socket="$DB_SOCKET" -u root -e "SELECT 1" >/dev/null 2>&1 && break
    sleep 1
  done
  "$TOOLS/mariadb/bin/mariadb" --socket="$DB_SOCKET" -u root -e "SELECT 1" >/dev/null 2>&1 \
    || fail "MariaDB not ready in 45s — tail: $(tail -5 "$LOGS/mariadb.err" 2>/dev/null)"
  log "  started (pid $(cat "$RUN/mariadb.pid")), ready"
fi
# real connectivity check as the application user over TCP (the exact path the backend uses)
"$TOOLS/mariadb/bin/mariadb" -h 127.0.0.1 -P 3306 -u "$SPRING_DATASOURCE_USERNAME" -p"$SPRING_DATASOURCE_PASSWORD" -e "USE CafeMgt; SELECT 1;" >/dev/null 2>&1 \
  || fail "TCP login as 'cafe' failed — check mysql.user grants in tools/mariadb-data"

# =============================================================================
log "3/6 backend (Spring Boot :8000)"
if alive "$RUN/backend.pid" && port_listening 8000; then
  log "  already running (pid $(cat "$RUN/backend.pid"))"
else
  rm -f "$RUN/backend.pid"
  setsid --fork env JAVA_HOME="$JAVA_HOME" "$JAVA_HOME/bin/java" -jar "$JAR" \
    </dev/null >"$LOGS/backend.log" 2>&1 || fail "java failed to launch"
  BPID=$(pgrep -n -f "Cafeboookingsystem.*\.jar")
  [ -n "$BPID" ] && echo "$BPID" > "$RUN/backend.pid"
fi
log "  waiting for a real end-to-end login (Tomcat + security + service + DAO + JDBC + MariaDB)"
TOKEN=""
for i in $(seq 1 90); do
  RESP=$(curl -4 -s --noproxy '*' --max-time 4 -X POST http://127.0.0.1:8000/user/login \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" 2>/dev/null)
  TOKEN=$(jsontoken "$RESP"); [ -n "$TOKEN" ] && break; sleep 1
done
[ -n "$TOKEN" ] || fail "backend login failed in 90s — tail: $(tail -15 "$LOGS/backend.log" 2>/dev/null)"
log "  healthy — real login OK (token len ${#TOKEN})"

# =============================================================================
log "4/6 frontend (Vite :3000 — platform preview default port)"
if alive "$RUN/frontend.pid" && port_listening 3000; then
  log "  already running (pid $(cat "$RUN/frontend.pid"))"
else
  rm -f "$RUN/frontend.pid"
  setsid --fork sh -c "cd '$FE' && exec npm run dev -- --port 3000 --strictPort --host 127.0.0.1" \
    </dev/null >"$LOGS/frontend.log" 2>&1 || fail "vite failed to launch"
  for i in $(seq 1 45); do
    FPID=$(pgrep -n -f "vite.*3000"); [ -n "$FPID" ] && { echo "$FPID" > "$RUN/frontend.pid"; break; }
    sleep 1
  done
fi
up=0
for i in $(seq 1 60); do [ "$(probe http://127.0.0.1:3000/)" != "000" ] && { up=1; break; }; sleep 1; done
if [ "$up" = 0 ]; then sleep 5; [ "$(probe http://127.0.0.1:3000/)" != "000" ] && up=1; fi
[ "$up" = 1 ] || fail "frontend :3000 not answering — diagnostics: $(ss -tln | grep 3000 || echo 'port not bound') | procs: $(pgrep -af vite | head -2 || echo none) | tail: $(tail -10 "$LOGS/frontend.log" 2>/dev/null)"
log "  up (pid $(cat "$RUN/frontend.pid" 2>/dev/null)) — HTTP $(probe http://127.0.0.1:3000/)"

# =============================================================================
log "5/6 frontend->backend proxy check (:3000 -> vite proxy -> :8000)"
PRESP=$(curl -4 -s --noproxy '*' --max-time 6 -X POST http://127.0.0.1:3000/user/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" 2>/dev/null)
PTOKEN=$(jsontoken "$PRESP")
[ -n "$PTOKEN" ] || fail "proxy login through :3000 failed — vite proxy not reaching :8000 (check $FE/vite.config.ts)"
log "  proxy login OK — token via :3000 (len ${#PTOKEN})"

# =============================================================================
log "6/6 ALL SERVICES UP"
log "  Database : MariaDB 127.0.0.1:3306 db=CafeMgt (preserved data: tools/mariadb-data)"
log "  Backend  : http://127.0.0.1:8000  (OpenAPI /v3/api-docs, UI /swagger-ui/index.html)"
log "  Frontend : http://127.0.0.1:3000  (platform preview default; proxy: /api,/user,/product,/category,/bill,/dashboard/* -> :8000)"
log "  Admin    : $ADMIN_EMAIL / $ADMIN_PASSWORD"
log "  Stop: ./stop-dev.sh   Status: ./status-dev.sh"
exit 0
