#!/bin/bash
# Live status of the CafeMS dev stack — processes, ports, real probes, DB counts.
set -u
ROOT="${CAFEMS_ROOT:-/tmp/my-project}"
TOOLS="$ROOT/tools"; LOGS="$ROOT/logs"; RUN="$LOGS/run"
RUNNING="RUNNING"; STOPPED="NOT RUNNING"
ok() { printf '  %-10s %s\n' "$1" "$2"; }
pstat() { [ -f "$RUN/$1.pid" ] && kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null && echo "$RUNNING (pid $(cat "$RUN/$1.pid"))" || echo "$STOPPED"; }

echo "== CafeMS stack status =="
ok "database:" "$(pstat mariadb)"
ok "  tcp:" "$(ss -tln 2>/dev/null | grep -q ':3306 ' && echo 'listening :3306' || echo 'not listening')"
ok "backend:" "$(pstat backend)"
ok "  http:" "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:8000/v3/api-docs 2>/dev/null | grep -q '^0' && echo down || curl -s -o /dev/null -w '%{http_code} on :8000' --max-time 3 http://127.0.0.1:8000/v3/api-docs 2>/dev/null)"
ok "frontend:" "$(pstat frontend)"
ok "  http:" "$(curl -s -o /dev/null -w '%{http_code} on :3000' --max-time 3 http://127.0.0.1:3000/ 2>/dev/null || echo down)"
echo "-- end-to-end login through the frontend proxy (:3000 -> :8000 -> MariaDB) --"
RESP=$(curl -s --max-time 5 -X POST http://127.0.0.1:3000/user/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"${CAFEMS_ADMIN_EMAIL:-admin@cafe.local}\",\"password\":\"${CAFEMS_ADMIN_PASSWORD:?see ops/env.example}\"}" 2>/dev/null)
T=$(printf '%s' "$RESP" | /home/z/.venv/bin/python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("token",""))
except Exception: print("")' 2>/dev/null)
[ -n "$T" ] && ok "login:" "PASS (token len ${#T})" || { ok "login:" "FAIL"; printf '  response: %s\n' "${RESP:0:120}"; }
if ss -tln 2>/dev/null | grep -q ':3306 '; then
  echo "-- database content (preserved data) --"
  "$TOOLS/mariadb/bin/mariadb" -h 127.0.0.1 -P 3306 -u "${DB_USERNAME:-cafe}" -p"${DB_PASSWORD:?see ops/env.example}" -N -e \
    "USE CafeMgt; SELECT CONCAT('users=',(SELECT COUNT(*) FROM user),' categories=',(SELECT COUNT(*) FROM category),' products=',(SELECT COUNT(*) FROM product),' bills=',(SELECT COUNT(*) FROM bill),' payments=',(SELECT COUNT(*) FROM payments));" 2>/dev/null
fi
echo "logs: $LOGS/{mariadb.err,backend.log,frontend.log}"
