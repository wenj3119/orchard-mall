"""Ship a test order through admin APIs after miniapp page payment."""
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

order_id = int(sys.argv[1])
target_task_id = int(sys.argv[2]) if len(sys.argv) > 2 else None
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
order = call("GET", f"/api/admin/orders/{order_id}", token=admin)
if order["order"]["status"] != "PAID":
    raise RuntimeError("Order must be paid by the miniapp dev simulator first")
tasks = call("GET", "/api/admin/fulfillment-tasks", token=admin)
task = next(x for x in tasks if x["orderNo"] == order["order"]["orderNo"]
            and (target_task_id is None or x["id"] == target_task_id))
detail = call("GET", f"/api/admin/fulfillment-tasks/{task['id']}", token=admin)
items = [{"taskItemId": item["id"], "quantity": item["requiredQty"] - item["shippedQty"]
          - item["frozenQty"] - item["cancelledQty"]}
         for item in detail["items"]
         if item["requiredQty"] > item["shippedQty"] + item["frozenQty"] + item["cancelledQty"]]
if not items:
    raise RuntimeError("No remaining items to ship")
parcel = call("POST", f"/api/admin/fulfillment-tasks/{task['id']}/parcels", {
    "idempotencyKey": f"weapp-page-parcel-{order_id}-{task['id']}",
    "carrierCode": "TEST", "carrierName": "测试物流",
    "trackingNo": f"LOCAL-WEAPP-{order_id}-{task['id']}", "items": items
}, admin)
print(json.dumps({"orderId": order_id, "taskId": task["id"],
                  "parcelId": parcel["id"], "trackingNo": parcel["trackingNo"]},
                 ensure_ascii=False))
