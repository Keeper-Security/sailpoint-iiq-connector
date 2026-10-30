"""Turns captured Commander Service Mode responses into anonymized test fixtures.

Usage: python3 -I sanitize_fixtures.py <capture-dir> <fixtures-dir> <service-account-email>

Emails, user names, job titles, node, team, role, folder and record names,
Keeper UIDs, numeric IDs and the SSO domain are all replaced with mock values.
Each real value always maps to the same mock value, so the data stays
internally consistent.
"""
import json
import re
import sys

src, dst, service_email = sys.argv[1:4]
FILES = {
    "users.json": "users.json",
    "enterprise_info___teams.json": "teams.json",
    "enterprise_info___roles.json": "roles.json",
    "enterprise_info___nodes.json": "nodes.json",
    "tree__s.json": "tree.json",
}
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")

emails = {service_email.lower(): "service.account@example.com"}
node_names = {}
titles = {}


def map_email(match):
    key = match.group(0).lower()
    if key not in emails:
        emails[key] = f"user{len(emails)}@example.com"
    return emails[key]


def scrub(value):
    """Replaces emails and node names inside any string."""
    if isinstance(value, dict):
        return {k: scrub(v) for k, v in value.items()}
    if isinstance(value, list):
        return [scrub(v) for v in value]
    if isinstance(value, str):
        value = EMAIL.sub(map_email, value)
        for real, fake in sorted(node_names.items(), key=lambda kv: -len(kv[0])):
            value = value.replace(real, fake)
    return value


def load(name):
    with open(f"{src}/{name}") as f:
        return json.load(f)


data = {name: load(name) for name in FILES}

# Node names first, so node paths in teams and roles are rewritten consistently.
for i, node in enumerate(data["enterprise_info___nodes.json"]["data"]):
    node_names[node["name"]] = "Example Org" if node.get("parent_id") is None else f"Node {i}"
data["enterprise_info___nodes.json"]["data"] = [
    dict(scrub(n), name=node_names[n["name"]]) for n in data["enterprise_info___nodes.json"]["data"]]

users = []
for i, user in enumerate(data["users.json"]["data"], 1):
    user = scrub(user)
    user["name"] = f"User {i}"
    if user.get("job_title"):
        user["job_title"] = "Engineer"
    users.append(user)
data["users.json"]["data"] = users

for name in ("enterprise_info___teams.json", "enterprise_info___roles.json"):
    data[name]["data"] = scrub(data[name]["data"])


def rename_tree(node, parent_path):
    kind = node.get("kind", "")
    if parent_path is None:
        new_name, path = "My Vault", "/"
    else:
        counter = titles.setdefault(kind, [0])
        counter[0] += 1
        label = "Record" if "record" in kind else "Folder"
        new_name = f"{label} {kind.replace('_', ' ')} {counter[0]}"
        path = parent_path.rstrip("/") + "/" + new_name
    out = scrub({k: v for k, v in node.items() if k != "children"})
    out["name"], out["path"] = new_name, path
    if node.get("children") is not None:
        out["children"] = [rename_tree(c, path) for c in node["children"]]
    return out


tree = data["tree__s.json"]
tree["data"]["tree"] = rename_tree(tree["data"]["tree"], None)
tree["command"] = "tree -s -ns -r -v"

UID_KEYS = {"uid", "team_uid"}
NUMERIC_ID = re.compile(r"^\d{13,}$")
ids = {}
uids = {}
labels = {}
for i, team in enumerate(data["enterprise_info___teams.json"]["data"], 1):
    labels[team["name"]] = f"Team {i}"
for i, role in enumerate(data["enterprise_info___roles.json"]["data"], 1):
    if role.get("name"):
        labels[role["name"]] = f"Role {i}"


def mock_ids(value, key=None):
    """Replaces numeric IDs, UIDs, team and role names and the SSO domain."""
    if isinstance(value, dict):
        return {k: mock_ids(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [mock_ids(v, key) for v in value]
    if isinstance(value, bool):
        return value
    if isinstance(value, int) or (isinstance(value, str) and NUMERIC_ID.match(value)):
        if not NUMERIC_ID.match(str(value)):
            return value
        mock = ids.setdefault(str(value), str(1000000000000001 + len(ids)))
        return int(mock) if isinstance(value, int) else mock
    if isinstance(value, str):
        if key in UID_KEYS:
            return uids.setdefault(value, f"mockuid{len(uids) + 1:015d}")
        if key == "sso_provisioning":
            return "sso.example.com"
        return labels.get(value, value)
    return value


# Nodes first, so the root node gets the first mock ID.
for name in ("enterprise_info___nodes.json", "enterprise_info___roles.json", "enterprise_info___teams.json",
             "users.json", "tree__s.json"):
    data[name] = mock_ids(data[name])

for name, out in FILES.items():
    with open(f"{dst}/{out}", "w") as f:
        json.dump(data[name], f, indent=1, sort_keys=True)
with open(f"{dst}/whoami.json", "w") as f:
    json.dump({"status": "success", "data": {"user": "service.account@example.com"}}, f, indent=1)
print(f"wrote {len(FILES) + 1} fixtures, {len(emails)} emails, {len(ids)} IDs and {len(uids)} UIDs anonymized")
