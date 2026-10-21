#!/bin/bash
# Clean shutdown of the CafeMS dev stack (opposite of start-dev.sh).
# Order: frontend -> backend -> database (reverse of startup).
set -u
ROOT="${CAFEMS_ROOT:-/tmp/my-project}"
TOOLS="$ROOT/tools"; LOGS="$ROOT/logs"; RUN="$LOGS/run"
export LD_LIBRARY_PATH="$TOOLS/libaio-extracted/usr/lib/x86_64-linux-gnu:$TOOLS/mariadb/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

say() { printf '[stop] %s\n' "$*"; }

# --- frontend ---
if [ -f "$RUN/frontend.pid" ] && kill -0 "$(cat "$RUN/frontend.pid")" 2>/dev/null; then
  FPID=$(cat "$RUN/frontend.pid")
  # vite runs as npm-run-dev child chain; kill the whole tree rooted at the pid
  pkill -TERM -P "$FPID" 2>/dev/null; kill -TERM "$FPID" 2>/dev/null
  sleep 1; kill -0 "$FPID" 2>/dev/null && kill -KILL "$FPID" 2>/dev/null
  say "frontend stopped (pid $FPID)"
fi
pgrep -f "vite.*3000" >/dev/null 2>&1 && { pkill -TERM -f "vite.*3000"; say "stray vite processes terminated"; }
rm -f "$RUN/frontend.pid"

# --- backend ---
if [ -f "$RUN/backend.pid" ] && kill -0 "$(cat "$RUN/backend.pid")" 2>/dev/null; then
  BPID=$(cat "$RUN/backend.pid"); kill -TERM "$BPID" 2>/dev/null
  # Spring Boot has graceful shutdown enabled (server.shutdown=graceful, 20s)
  for i in $(seq 1 25); do kill -0 "$BPID" 2>/dev/null || break; sleep 1; done
  kill -0 "$BPID" 2>/dev/null && kill -KILL "$BPID" 2>/dev/null
  say "backend stopped (pid $BPID)"
fi
pgrep -f "Cafeboookingsystem.*\.jar" >/dev/null 2>&1 && { pkill -TERM -f "Cafeboookingsystem.*\.jar"; say "stray backend terminated"; }
rm -f "$RUN/backend.pid"

# --- database (graceful, so InnoDB flushes cleanly) ---
if [ -f "$RUN/mariadb.pid" ] && kill -0 "$(cat "$RUN/mariadb.pid")" 2>/dev/null; then
  "$TOOLS/mariadb/bin/mariadb" --socket="$TOOLS/mariadb-sock/mysqld.sock" -u root -e "SHUTDOWN" 2>/dev/null
  for i in $(seq 1 20); do kill -0 "$(cat "$RUN/mariadb.pid")" 2>/dev/null || break; sleep 1; done
  say "database stopped"
else
  "$TOOLS/mariadb/bin/mariadb" --socket="$TOOLS/mariadb-sock/mysqld.sock" -u root -e "SHUTDOWN" 2>/dev/null \
    && say "database stopped (was running without pidfile)"
fi
rm -f "$RUN/mariadb.pid"
say "all services stopped (restart with ./start-dev.sh)"
