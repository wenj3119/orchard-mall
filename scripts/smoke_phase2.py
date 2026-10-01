"""Live Phase 2 checkout smoke test against the local backend and real MySQL.

Creates isolated records through HTTP only, verifies the admin/customer closed
loop, then races two consumers for one unit of stock. No payment is attempted.
"""
import base64
import concurrent.futures
import json
import os
import pathlib
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
config = dict(line.split("=", 1) for line in (ROOT / ".env").read_text().splitlines()
              if line and not line.startswith("#") and "=" in line)
BASE = os.environ.get("SMOKE_API_BASE", "http://127.0.0.1:8080")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(method, path, payload=None, token=None, content_type="application/json"):
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode() if content_type == "application/json" else payload
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    call = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    try:
        with opener.open(call, timeout=15) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw and "json" in response.headers.get("Content-Type", "") else raw
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw else None


def expect(method, path, payload=None, token=None, status=200, content_type="application/json"):
    actual, body = request(method, path, payload, token, content_type)
    assert actual == status, (method, path, actual, body)
    return body


def customer(name):
    return expect("POST", "/api/dev/consumer-login", {"platform": "WECHAT", "externalUserId": name})["token"]


def address(token, name):
    return expect("POST", "/api/customer/addresses", {
        "recipient": name, "mobile": "13800000001", "provinceCode": "610000", "provinceName": "陕西省",
        "cityCode": "610100", "cityName": "西安市", "districtCode": "610102", "districtName": "新城区",
        "detail": "端到端验收地址"
    }, token)["id"]


def add_and_quote(token, address_id, sku_id):
    cart = expect("POST", "/api/customer/cart", {"skuId": sku_id, "quantity": 1}, token)[0]
    quote = expect("POST", "/api/customer/checkout/quote", {"addressId": address_id, "cartItemIds": [cart["id"]]}, token)
    assert quote["purchasable"] and quote["shippingAmountFen"] == 800, quote
    return cart["id"], quote


admin = expect("POST", "/api/auth/login", {"username": "admin", "password": config["ADMIN_INIT_PASSWORD"]})["token"]
suffix = uuid.uuid4().hex[:10]
category = expect("POST", "/api/admin/categories", {"name": "二期验收" + suffix, "sortOrder": 200, "enabled": True}, admin)
supplier = expect("POST", "/api/admin/suppliers", {"name": "二期果园" + suffix, "contactName": "测试", "contactPhone": "13800000000", "sourceType": "SELF", "enabled": True}, admin)
origin = expect("POST", "/api/admin/origins", {"supplierId": supplier["id"], "label": "验收仓", "provinceCode": "610000", "province": "陕西省", "cityCode": "610100", "city": "西安市", "districtCode": "610102", "district": "新城区", "address": "测试仓", "contactName": "测试", "contactPhone": "13800000000", "isDefault": True, "enabled": True}, admin)
product = expect("POST", "/api/admin/products", {"categoryId": category["id"], "title": "二期苹果" + suffix, "description": "Phase 2 live smoke"}, admin)
sku = expect("POST", f"/api/admin/products/{product['id']}/skus", {
    "code": "P2-" + suffix, "specJson": json.dumps({"包装": "一箱"}, ensure_ascii=False),
    "retailPriceFen": 5000, "netWeightG": 1200, "billableWeightG": 1500, "active": True
}, admin)
template = expect("POST", "/api/admin/shipping-templates", {"name": "验收模板" + suffix, "freeThresholdFen": 10000, "enabled": True}, admin)
expect("POST", f"/api/admin/shipping-templates/{template['id']}/rules", {
    "regionCode": "610000", "blocked": False, "firstWeightG": 1000, "firstFeeFen": 700,
    "stepWeightG": 500, "stepFeeFen": 100
}, admin)
supply = expect("POST", f"/api/admin/skus/{sku['id']}/supplies", {
    "supplierId": supplier["id"], "originId": origin["id"], "shippingTemplateId": template["id"], "supplyPriceFen": 3000, "isDefault": True
}, admin)
expect("POST", f"/api/admin/inventory/{supply['id']}/adjust", {"delta": 5, "reason": "Phase 2 端到端验收"}, admin)

png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lXcAAAAASUVORK5CYII=")
boundary = "----phase2" + suffix
multipart = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"phase2.png\"\r\nContent-Type: image/png\r\n\r\n".encode()
             + png + f"\r\n--{boundary}--\r\n".encode())
media = expect("POST", "/api/admin/media", multipart, admin, 200, f"multipart/form-data; boundary={boundary}")
expect("PUT", f"/api/admin/products/{product['id']}/images", [media["id"]], admin, 200)
expect("PUT", f"/api/admin/products/{product['id']}/publication", {"published": True}, admin, 200)

buyer = customer("p2-buyer-" + suffix)
buyer_address = address(buyer, "验收买家")
cart_id, quote = add_and_quote(buyer, buyer_address, sku["id"])
created = expect("POST", "/api/customer/orders", {
    "addressId": buyer_address, "cartItemIds": [cart_id], "quoteHash": quote["quoteHash"],
    "idempotencyKey": "smoke-main-" + suffix
}, buyer, 201)
order_id = created["order"]["id"]
expect("GET", f"/api/admin/orders/{order_id}", token=admin)
expect("POST", f"/api/customer/orders/{order_id}/cancel", token=buyer)
inventory = next(x for x in expect("GET", "/api/admin/inventory", token=admin) if x["supplyId"] == supply["id"])
assert inventory["reservedQty"] == 0 and inventory["availableQty"] == 5, inventory

# Real-MySQL oversell check: exactly one of two concurrent orders may reserve the last unit.
expect("POST", f"/api/admin/inventory/{supply['id']}/adjust", {"delta": -4, "reason": "并发验收仅保留一件"}, admin)
racers = [customer(f"p2-racer-{n}-{suffix}") for n in (1, 2)]
race_data = []
for n, racer in enumerate(racers, 1):
    addr = address(racer, f"并发买家{n}")
    cid, q = add_and_quote(racer, addr, sku["id"])
    race_data.append((racer, addr, cid, q["quoteHash"], f"smoke-race-{n}-{suffix}"))


def place(data):
    token, addr, cid, quote_hash, key = data
    return request("POST", "/api/customer/orders", {"addressId": addr, "cartItemIds": [cid], "quoteHash": quote_hash, "idempotencyKey": key}, token)


with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(place, race_data))
assert sorted(status for status, _ in results) == [201, 409], results
winner_index = next(i for i, result in enumerate(results) if result[0] == 201)
winner_order = results[winner_index][1]["order"]["id"]
expect("POST", f"/api/customer/orders/{winner_order}/cancel", token=racers[winner_index])
inventory = next(x for x in expect("GET", "/api/admin/inventory", token=admin) if x["supplyId"] == supply["id"])
assert inventory["reservedQty"] == 0 and inventory["availableQty"] == 1, inventory

# Two-source race: loser can reserve supply A before failing on supply B; its whole
# transaction must roll back so A remains reserved only by the winning order.
sku2 = expect("POST", f"/api/admin/products/{product['id']}/skus", {
    "code": "P2-SECOND-" + suffix, "specJson": json.dumps({"包装": "加购袋"}, ensure_ascii=False),
    "retailPriceFen": 1000, "netWeightG": 250, "billableWeightG": 300, "active": True
}, admin)
supply2 = expect("POST", f"/api/admin/skus/{sku2['id']}/supplies", {
    "supplierId": supplier["id"], "originId": origin["id"], "shippingTemplateId": template["id"], "supplyPriceFen": 600, "isDefault": True
}, admin)
expect("POST", f"/api/admin/inventory/{supply2['id']}/adjust", {"delta": 1, "reason": "事务回滚验收库存"}, admin)
expect("POST", f"/api/admin/inventory/{supply['id']}/adjust", {"delta": 1, "reason": "事务回滚验收库存"}, admin)
rollback_racers = [customer(f"p2-rollback-{n}-{suffix}") for n in (1, 2)]
rollback_data = []
for n, racer in enumerate(rollback_racers, 1):
    addr = address(racer, f"回滚买家{n}")
    first = expect("POST", "/api/customer/cart", {"skuId": sku["id"], "quantity": 1}, racer)
    first_id = next(x["id"] for x in first if x["skuId"] == sku["id"])
    both = expect("POST", "/api/customer/cart", {"skuId": sku2["id"], "quantity": 1}, racer)
    second_id = next(x["id"] for x in both if x["skuId"] == sku2["id"])
    q = expect("POST", "/api/customer/checkout/quote", {"addressId": addr, "cartItemIds": [first_id, second_id]}, racer)
    assert len(q["groups"]) == 1 and q["groups"][0]["billableWeightG"] == 1800, q
    rollback_data.append((racer, addr, first_id, second_id, q["quoteHash"], f"smoke-rollback-{n}-{suffix}"))


def place_two(data):
    token, addr, first_id, second_id, quote_hash, key = data
    return request("POST", "/api/customer/orders", {"addressId": addr, "cartItemIds": [first_id, second_id], "quoteHash": quote_hash, "idempotencyKey": key}, token)


with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    rollback_results = list(pool.map(place_two, rollback_data))
assert sorted(status for status, _ in rollback_results) == [201, 409], rollback_results
stocks = {x["supplyId"]: x for x in expect("GET", "/api/admin/inventory", token=admin)}
assert stocks[supply["id"]]["reservedQty"] == 1, stocks[supply["id"]]
assert stocks[supply2["id"]]["reservedQty"] == 1, stocks[supply2["id"]]
rollback_winner = next(i for i, result in enumerate(rollback_results) if result[0] == 201)
expect("POST", f"/api/customer/orders/{rollback_results[rollback_winner][1]['order']['id']}/cancel", token=rollback_racers[rollback_winner])
print("PASS: full checkout/cancel; last-unit no oversell; same-group weight merge; failed multi-source order rolls back its earlier reservation")
