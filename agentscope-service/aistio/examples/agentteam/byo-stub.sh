# Minimal BYO teammate stub (HTTP contract)
#
# Register against aistiod, advertise team-coordination, and accept team_join.
# Replace hooks with a real HarnessAgent + TeamSessionStarter in production.

set -euo pipefail
PORT="${PORT:-18080}"
AGENT_NAME="${AGENT_NAME:-byo-writer}"
NS="${NS:-default}"
AISTIO_URL="${AISTIO_URL:-http://localhost:8081}"
INTERNAL_TOKEN="${INTERNAL_TOKEN:-builder-internal-dev-token}"
INSTANCE_ID="${INSTANCE_ID:-byo-writer-1}"

python3 - <<'PY' &
from http.server import BaseHTTPRequestHandler, HTTPServer
import json

JOINED = {}

class H(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/agentscope/health":
            return self._json(200, {"status": "ok"})
        if self.path == "/agentscope/info":
            return self._json(200, {
                "name": "byo-writer",
                "runtime": "agentscope-java",
                "contractLevel": 3,
                "capabilities": ["session-reporting", "session-command", "team-coordination"],
            })
        if self.path == "/agentscope/sessions":
            return self._json(200, {"sessions": [{"id": s, "phase": "idle"} for s in JOINED]})
        self.send_error(404)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n) if n else b"{}"
        if self.path == "/agentscope/teams/join":
            body = json.loads(raw or b"{}")
            sid = body.get("sessionId") or ""
            JOINED[sid] = body.get("params")
            print("team_join", sid, flush=True)
            return self._json(200, {"ok": True})
        self.send_error(404)

    def log_message(self, fmt, *args):
        return

HTTPServer(("0.0.0.0", int(__import__("os").environ.get("PORT", "18080"))), H).serve_forever()
PY
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
sleep 0.5

BASE_URL="${PUBLIC_BASE_URL:-http://127.0.0.1:${PORT}}"
echo "Registering $AGENT_NAME @ $BASE_URL"
curl -fsS -X POST "$AISTIO_URL/api/v1/dataplanes/register" \
  -H "Content-Type: application/json" \
  -H "X-Builder-Internal-Token: $INTERNAL_TOKEN" \
  -d "{
    \"agentName\": \"$AGENT_NAME\",
    \"namespace\": \"$NS\",
    \"instanceId\": \"$INSTANCE_ID\",
    \"baseUrl\": \"$BASE_URL\",
    \"runtime\": \"stub\",
    \"contractLevel\": 3,
    \"capabilities\": [\"session-reporting\", \"session-command\", \"team-coordination\"],
    \"healthy\": true
  }"
echo
echo "BYO stub listening on :$PORT (ctrl-c to stop)"
wait $PID
