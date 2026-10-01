"""Live Phase 1 smoke test against local Docker infrastructure and Spring Boot.

Reads ADMIN_INIT_PASSWORD from the ignored root .env file. Creates uniquely named
development records, uploads a real PNG, publishes and unpublishes the product.
"""
import base64
import json
import pathlib
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
config = dict(
    line.split("=", 1) for line in (ROOT / ".env").read_text().splitlines()
    if line and not line.startswith("#") and "=" in line
)
BASE = "http://127.0.0.1:8080"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
token = ""


def request(method, path, payload=None, content_type="application/json", auth=True):
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode() if content_type == "application/json" else payload
    headers = {"Content-Type": content_type}
    if token and auth:
        headers["Authorization"] = "Bearer " + token
    call = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    try:
        with opener.open(call, timeout=10) as response:
            raw = response.read()
            is_json = "json" in response.headers.get("Content-Type", "")
            return response.status, json.loads(raw) if raw and is_json else raw
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw else None


assert request("POST", "/api/admin/categories", {"name": "blocked", "sortOrder": 0, "enabled": True})[0] == 403
status, auth = request("POST", "/api/auth/login", {"username": "admin", "password": config["ADMIN_INIT_PASSWORD"]})
assert status == 200, (status, auth)
token = auth["token"]
suffix = uuid.uuid4().hex[:8]

status, category = request("POST", "/api/admin/categories", {"name": "验收分类" + suffix, "sortOrder": 100, "enabled": True})
assert status == 200, (status, category)
status, supplier = request("POST", "/api/admin/suppliers", {"name": "验收农户" + suffix, "contactName": "测试", "contactPhone": "13800000000", "enabled": True})
assert status == 200, (status, supplier)
assert supplier["sourceType"] == "FARMER"
status, suppliers = request("GET", "/api/admin/suppliers")
assert status == 200 and any(s["name"] == "演示果园" and s["sourceType"] == "SELF" for s in suppliers)
status, origin = request("POST", "/api/admin/origins", {"supplierId": supplier["id"], "label": "验收发货地", "provinceCode": "610000", "province": "陕西省", "cityCode": "610600", "city": "延安市", "districtCode": "610602", "district": "宝塔区", "address": "测试地址", "contactName": "测试", "contactPhone": "13800000000", "isDefault": True, "enabled": True})
assert status == 200, (status, origin)
status, template = request("POST", "/api/admin/shipping-templates", {"name": "验收模板" + suffix, "freeThresholdFen": None, "enabled": True})
assert status == 200, (status, template)
status, product = request("POST", "/api/admin/products", {"categoryId": category["id"], "title": "验收苹果" + suffix, "description": "本地发布闭环验证"})
assert status == 200, (status, product)
product_id = product["id"]

status, sku = request("POST", f"/api/admin/products/{product_id}/skus", {
    "code": "SMOKE-" + suffix, "specJson": json.dumps({"包装": "5斤装"}, ensure_ascii=False),
    "retailPriceFen": 3990, "active": True
})
assert status == 200, (status, sku)
status, supply = request("POST", f"/api/admin/skus/{sku['id']}/supplies", {
    "supplierId": supplier["id"], "originId": origin["id"], "shippingTemplateId": template["id"], "supplyPriceFen": 2500, "isDefault": True
})
assert status == 200, (status, supply)
assert request("PUT", f"/api/admin/products/{product_id}/publication", {"published": True})[0] == 400

png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lXcAAAAASUVORK5CYII=")
boundary = "----orchard" + suffix
multipart = (
    f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"smoke.png\"\r\n"
    "Content-Type: image/png\r\n\r\n"
).encode() + png + f"\r\n--{boundary}--\r\n".encode()
status, media = request("POST", "/api/admin/media", multipart, f"multipart/form-data; boundary={boundary}")
assert status == 200, (status, media)
assert request("GET", media["url"], auth=False)[0] == 404
assert request("PUT", f"/api/admin/products/{product_id}/images", [media["id"]])[0] == 200
assert request("PUT", f"/api/admin/products/{product_id}/publication", {"published": True})[0] == 200
status, public = request("GET", f"/api/public/products/{product_id}", auth=False)
assert status == 200 and public["title"] == product["title"] and public["skus"][0]["retailPriceFen"] == 3990
assert "supplyPriceFen" not in public["skus"][0]
assert request("GET", media["url"], auth=False)[0] == 200
assert request("PUT", f"/api/admin/products/{product_id}/publication", {"published": False})[0] == 200
assert request("GET", f"/api/public/products/{product_id}", auth=False)[0] == 404
assert request("GET", media["url"], auth=False)[0] == 404
print("PASS: unauthorized write blocked; create → image upload → publish → public product/image → unpublish → 404")
