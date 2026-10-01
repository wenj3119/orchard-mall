"""Live Phase 3 HTTP smoke test. Uses only the development payment channel and real local MySQL."""
import base64, json, os, pathlib, urllib.error, urllib.request, uuid

ROOT=pathlib.Path(__file__).resolve().parents[1]
config=dict(line.split("=",1) for line in (ROOT/".env").read_text().splitlines() if line and not line.startswith("#") and "=" in line)
BASE=os.environ.get("SMOKE_API_BASE","http://127.0.0.1:8080")
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(method,path,payload=None,token=None,content_type="application/json",expected=200):
    data=None if payload is None else (json.dumps(payload,ensure_ascii=False).encode() if content_type=="application/json" else payload)
    headers={"Content-Type":content_type}
    if token: headers["Authorization"]="Bearer "+token
    try:
        with opener.open(urllib.request.Request(BASE+path,data=data,headers=headers,method=method),timeout=20) as r:
            raw=r.read();body=json.loads(raw) if raw and "json" in r.headers.get("Content-Type","") else raw
            assert r.status==expected,(path,r.status,body);return body
    except urllib.error.HTTPError as e:
        raw=e.read();raise AssertionError((path,e.code,raw.decode(errors="replace")))

suffix=uuid.uuid4().hex[:8]
admin=call("POST","/api/auth/login",{"username":"admin","password":config["ADMIN_INIT_PASSWORD"]})["token"]
category=call("POST","/api/admin/categories",{"name":"三期验收"+suffix,"sortOrder":300,"enabled":True},admin)
supplier=call("POST","/api/admin/suppliers",{"name":"三期供应商"+suffix,"contactName":"验收","contactPhone":"13800000000","sourceType":"FARMER","enabled":True},admin)
origin=call("POST","/api/admin/origins",{"supplierId":supplier["id"],"label":"验收仓","provinceCode":"610000","province":"陕西省","cityCode":"610100","city":"西安市","districtCode":"610102","district":"新城区","address":"仅验收","contactName":"验收","contactPhone":"13800000000","isDefault":True,"enabled":True},admin)
product=call("POST","/api/admin/products",{"categoryId":category["id"],"title":"三期苹果"+suffix,"description":"Phase 3 dev-only smoke"},admin)
sku=call("POST",f"/api/admin/products/{product['id']}/skus",{"code":"P3-"+suffix,"specJson":"{\"包装\":\"三件装\"}","retailPriceFen":3000,"netWeightG":1500,"billableWeightG":1500,"active":True},admin)
template=call("POST","/api/admin/shipping-templates",{"name":"三期模板"+suffix,"freeThresholdFen":None,"enabled":True},admin)
call("POST",f"/api/admin/shipping-templates/{template['id']}/rules",{"regionCode":"610000","blocked":False,"firstWeightG":1000,"firstFeeFen":700,"stepWeightG":500,"stepFeeFen":100},admin)
supply=call("POST",f"/api/admin/skus/{sku['id']}/supplies",{"supplierId":supplier["id"],"originId":origin["id"],"shippingTemplateId":template["id"],"supplyPriceFen":1800,"isDefault":True},admin)
call("POST",f"/api/admin/inventory/{supply['id']}/adjust",{"delta":10,"reason":"Phase 3 E2E"},admin)
png=base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lXcAAAAASUVORK5CYII=")
boundary="----phase3"+suffix
multipart=(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"phase3.png\"\r\nContent-Type: image/png\r\n\r\n".encode()+png+f"\r\n--{boundary}--\r\n".encode())
media=call("POST","/api/admin/media",multipart,admin,f"multipart/form-data; boundary={boundary}")
call("PUT",f"/api/admin/products/{product['id']}/images",[media["id"]],admin)
call("PUT",f"/api/admin/products/{product['id']}/publication",{"published":True},admin)

buyer=call("POST","/api/dev/consumer-login",{"platform":"WECHAT","externalUserId":"p3-buyer-"+suffix})["token"]
address=call("POST","/api/customer/addresses",{"recipient":"三期买家","mobile":"13800000001","provinceCode":"610000","provinceName":"陕西省","cityCode":"610100","cityName":"西安市","districtCode":"610102","districtName":"新城区","detail":"端到端验收地址"},buyer)["id"]
cart=call("POST","/api/customer/cart",{"skuId":sku["id"],"quantity":3},buyer)[0]
quote=call("POST","/api/customer/checkout/quote",{"addressId":address,"cartItemIds":[cart["id"]]},buyer)
created=call("POST","/api/customer/orders",{"addressId":address,"cartItemIds":[cart["id"]],"quoteHash":quote["quoteHash"],"idempotencyKey":"phase3-e2e-"+suffix},buyer,expected=201)
order=created["order"]["id"]
attempt=call("POST",f"/api/customer/orders/{order}/payments",{"channel":"DEV_SIMULATOR"},buyer)
call("POST",f"/api/customer/payment-attempts/{attempt['attemptNo']}/simulate",{"result":"SUCCESS","eventKey":"e2e-success-"+suffix},buyer)
paid=call("GET",f"/api/customer/orders/{order}",token=buyer)
assert paid["order"]["status"]=="PAID" and paid["order"]["fulfillmentStatus"]=="PENDING",paid
assert any(x["orderNo"]==paid["order"]["orderNo"] for x in call("GET","/api/admin/payments",token=admin))
task=next(x for x in call("GET","/api/admin/fulfillment-tasks",token=admin) if x["orderNo"]==paid["order"]["orderNo"])

supplier_password="Supplier-"+suffix+"-Pass"
call("POST","/api/admin/supplier-users",{"supplierId":supplier["id"],"username":"supplier-"+suffix,"password":supplier_password,"enabled":True},admin,expected=201)
supplier_token=call("POST","/api/supplier/auth/login",{"username":"supplier-"+suffix,"password":supplier_password})["token"]
call("POST",f"/api/supplier/tasks/{task['id']}/accept",token=supplier_token)
detail=call("GET",f"/api/supplier/tasks/{task['id']}",token=supplier_token);item=detail["items"][0]["id"]
call("POST",f"/api/supplier/tasks/{task['id']}/parcels",{"idempotencyKey":"parcel-a-"+suffix,"carrierCode":"SF","carrierName":"顺丰","trackingNo":"SF"+suffix,"items":[{"taskItemId":item,"quantity":1}]},supplier_token)
partial=call("GET",f"/api/customer/orders/{order}",token=buyer);assert partial["order"]["fulfillmentStatus"]=="PARTIALLY_SHIPPED",partial
call("POST",f"/api/admin/fulfillment-tasks/{task['id']}/parcels",{"idempotencyKey":"parcel-b-"+suffix,"carrierCode":"YT","carrierName":"圆通","trackingNo":"YT"+suffix,"items":[{"taskItemId":item,"quantity":2}]},admin)
parcels=call("GET",f"/api/customer/orders/{order}/parcels",token=buyer)
final=call("GET",f"/api/customer/orders/{order}",token=buyer)
assert final["order"]["fulfillmentStatus"]=="SHIPPED" and len(parcels)==2,(final,parcels)
print("PASS: order -> dev payment -> admin payment/task -> supplier accepts/partial parcel -> admin parcel -> consumer sees two parcels")
