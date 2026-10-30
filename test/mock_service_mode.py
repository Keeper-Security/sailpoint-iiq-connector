"""Mock Keeper Commander Service Mode API v2 for offline connector tests.

Usage: python3 -I mock_service_mode.py <port> <fixtures-dir> <command-log> [whoami-403]

Serves read commands from fixtures, records every command it receives and
applies simple state changes (invite, lock, unlock) so read-backs are realistic.
The API key must be "test-key"; anything else returns HTTP 401.
"""
import json
import shlex
import sys
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1])
FIXTURES = sys.argv[2]
LOG = sys.argv[3]
WHOAMI_FAILS = len(sys.argv) > 4 and sys.argv[4] == "whoami-403"
API_KEY = "test-key"
NOT_PERMITTED = {"status": "error", "error": "Not permitted to perform this function"}


def load(name):
    with open(f"{FIXTURES}/{name}") as f:
        return json.load(f)


USERS = load("users.json")["data"]
LISTS = {
    "--teams": load("teams.json")["data"],
    "--roles": load("roles.json")["data"],
    "--nodes": load("nodes.json")["data"],
}
TREE = load("tree.json")["data"]
WHOAMI = load("whoami.json")
RESULTS = {}
POLLS = {}


def set_status(email, status):
    for user in USERS:
        if user["email"] == email:
            user["status"] = status


def execute(command):
    with open(LOG, "a") as f:
        f.write(command + "\n")
    args = shlex.split(command)
    name = args[0]
    if name == "whoami":
        return (403, NOT_PERMITTED) if WHOAMI_FAILS else (200, WHOAMI)
    if name == "enterprise-info":
        if "--users" in args:
            pattern = args[1] if not args[1].startswith("-") else None
            users = [u for u in USERS if pattern is None or pattern.lower() in u["email"].lower()]
            return 200, {"status": "success", "data": users}
        for flag, data in LISTS.items():
            if flag in args:
                return 200, {"status": "success", "data": data}
    if name == "tree":
        return 200, {"status": "success", "data": TREE}
    if name == "enterprise-user" and "--add" in args:
        USERS.append({"email": args[1], "name": args[args.index("--name") + 1], "status": "Invited",
                      "user_id": 999000111, "node": {"node_id": args[args.index("--node") + 1]},
                      "teams": [], "roles": [], "alias": [], "job_title": ""})
    if name == "enterprise-user" and "--lock" in args:
        set_status(args[1], "Locked")
    if name == "enterprise-user" and "--unlock" in args:
        set_status(args[1], "Active")
    if name == "share-record" and "nosuchrecord" in command:
        return 200, {"status": "error", "message": "record not found"}
    return 200, {"status": "success", "data": None}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, body):
        raw = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        if self.headers.get("api-key") != API_KEY:
            return self.reply(401, {"status": "error", "error": "Invalid API key"})
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        code, result = execute(body["command"])
        if code != 200:
            return self.reply(code, result)
        request_id = str(uuid.uuid4())
        RESULTS[request_id] = result
        POLLS[request_id] = 0
        self.reply(202, {"request_id": request_id, "status": "queued", "success": True})

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, {"status": "ok"})
        request_id = self.path.rsplit("/", 1)[-1]
        if request_id not in RESULTS:
            return self.reply(404, {"status": "error", "error": "unknown request"})
        POLLS[request_id] += 1
        if POLLS[request_id] == 1:  # first poll is still pending, exercises the 202 path
            return self.reply(202, {"status": "processing"})
        self.reply(200, RESULTS.pop(request_id))


HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
