"""Prepare catalog-only test data for WeChat simulator page acceptance.

This script uses admin APIs for fixture setup. It does not call consumer APIs
or count any consumer step as a page acceptance result.
"""
import base64
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
config = dict(line.split("=", 1) for line in (root / ".env").read_text().splitlines()
              if line and not line.startswith("#") and "=" in line)
base = os.environ.get("SMOKE_API_BASE", "http://127.0.0.1:8080")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method, path, payload=None, token=None, content_type="application/json", expected=200):
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode() if content_type == "application/json" else payload
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    try:
        with opener.open(req, timeout=15) as response:
            raw = response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        raw = error.read()
        status = error.code
    data = json.loads(raw) if raw else None
    if status != expected:
        raise RuntimeError(f"{method} {path}: HTTP {status}: {data}")
    return data


admin = call("POST", "/api/auth/login", {
    "username": "admin", "password": config["ADMIN_INIT_PASSWORD"]
})["token"]
suffix = uuid.uuid4().hex[:8]
category = call("POST", "/api/admin/categories", {
    "name": "微信页面验收" + suffix, "sortOrder": 10, "enabled": True
}, admin)
supplier = call("POST", "/api/admin/suppliers", {
    "name": "微信验收果园" + suffix, "contactName": "测试", "contactPhone": "13800000000",
    "sourceType": "SELF", "enabled": True
}, admin)
origin = call("POST", "/api/admin/origins", {
    "supplierId": supplier["id"], "label": "验收仓", "provinceCode": "610000", "province": "陕西省",
    "cityCode": "610100", "city": "西安市", "districtCode": "610102", "district": "新城区", "address": "测试仓",
    "contactName": "测试", "contactPhone": "13800000000", "isDefault": True, "enabled": True
}, admin)
product = call("POST", "/api/admin/products", {
    "categoryId": category["id"], "title": "微信验收苹果" + suffix,
    "description": "仅用于 Phase 5 小程序页面验收"
}, admin)
template = call("POST", "/api/admin/shipping-templates", {
    "name": "微信验收运费" + suffix, "freeThresholdFen": 10000, "enabled": True
}, admin)
call("POST", f"/api/admin/shipping-templates/{template['id']}/rules", {
    "regionCode": "610000", "blocked": False, "firstWeightG": 1000,
    "firstFeeFen": 700, "stepWeightG": 500, "stepFeeFen": 100
}, admin)

skus = []
for label, price, weight in (("一箱", 5000, 1500), ("加购袋", 1000, 300)):
    sku = call("POST", f"/api/admin/products/{product['id']}/skus", {
        "code": f"W5-{label}-{suffix}", "specJson": json.dumps({"包装": label}, ensure_ascii=False),
        "retailPriceFen": price, "netWeightG": weight, "billableWeightG": weight,
        "active": True
    }, admin)
    supply = call("POST", f"/api/admin/skus/{sku['id']}/supplies", {
        "supplierId": supplier["id"], "originId": origin["id"], "shippingTemplateId": template["id"],
        "supplyPriceFen": price // 2, "isDefault": True
    }, admin)
    call("POST", f"/api/admin/inventory/{supply['id']}/adjust", {
        "delta": 20, "reason": "Phase 5 微信页面验收"
    }, admin)
    skus.append({"id": sku["id"], "code": sku["code"], "label": label})

png = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lXcAAAAASUVORK5CYII="
)
boundary = "----weapp-" + suffix
multipart = (
    f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="weapp-test.png"\r\n'
    f'Content-Type: image/png\r\n\r\n'.encode() + png +
    f'\r\n--{boundary}--\r\n'.encode()
)
media = call("POST", "/api/admin/media", multipart, admin,
             f"multipart/form-data; boundary={boundary}")
call("PUT", f"/api/admin/products/{product['id']}/images", [media["id"]], admin)
call("PUT", f"/api/admin/products/{product['id']}/publication",
     {"published": True}, admin)

manifest = {"suffix": suffix, "productId": product["id"], "categoryId": category["id"],
            "supplierId": supplier["id"], "originId": origin["id"],
            "templateId": template["id"], "skus": skus, "mediaId": media["id"]}
out = root / ".artifacts" / "phase5" / "weapp-catalog.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(json.dumps(manifest, ensure_ascii=False, indent=2))
