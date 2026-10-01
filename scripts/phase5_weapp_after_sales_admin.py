"""Advance a miniapp-created after-sales case using isolated admin dev APIs."""
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

case_id = int(sys.argv[1])
action = sys.argv[2]
if action not in ("refund", "replacement"):
    raise SystemExit("Use refund or replacement")
root = Path(__file__).resolve().parents[1]
config = dict(line.split("=", 1) for line in (root / ".env").read_text().splitlines()
              if line and not line.startswith("#") and "=" in line)
base = os.environ.get("SMOKE_API_BASE", "http://127.0.0.1:18084")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method, path, payload=None, token=None):
    body = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    try:
        with opener.open(req, timeout=15) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{method} {path}: HTTP {error.code}: {error.read().decode()}") from error


admin = call("POST", "/api/auth/login", {
    "username": "admin", "password": config["ADMIN_INIT_PASSWORD"]
})["token"]
detail = call("GET", f"/api/admin/after-sales/{case_id}", token=admin)
expected_action = "REFUND" if action == "refund" else "REPLACEMENT"
if detail["action"] != expected_action:
    raise RuntimeError(f"Expected {expected_action}, found {detail['action']}")
if detail["status"] != "SUBMITTED":
    raise RuntimeError(f"Expected SUBMITTED, found {detail['status']}")
review = {
    "decision": "APPROVE",
    "comment": "Phase 5 微信页面验收审核",
    "responsibility": "MERCHANT",
    "items": [{"afterSaleItemId": item["id"], "approvedQuantity": item["requestedQty"]}
              for item in detail["items"]]
}
if action == "refund":
    review.update({"refundAmountFen": detail["requestedRefundFen"],
                   "shippingRefundFen": 0, "refundChannel": "DEV_SIMULATOR"})
reviewed = call("POST", f"/api/admin/after-sales/{case_id}/review", review, admin)
if action == "refund":
    refund = reviewed["refunds"][0]
    call("POST", f"/api/admin/refund-attempts/{refund['attemptNo']}/simulate",
         {"result": "SUCCESS", "eventKey": f"weapp-refund-{case_id}"}, admin)
    reviewed = call("GET", f"/api/admin/after-sales/{case_id}", token=admin)
print(json.dumps({"caseId": case_id, "action": action, "status": reviewed["status"],
                  "refunds": [{"refundNo": r["refundNo"], "status": r["status"]}
                              for r in reviewed["refunds"]],
                  "replacementTasks": reviewed["replacementTasks"]}, ensure_ascii=False))
