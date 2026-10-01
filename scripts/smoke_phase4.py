"""Live Phase 4 HTTP smoke. Reuses Phase 3's dev-only catalog/payment/shipping setup."""
import runpy, pathlib, uuid

s=runpy.run_path(str(pathlib.Path(__file__).with_name("smoke_phase3.py")))
call=s["call"];admin=s["admin"];buyer=s["buyer"];sku=s["sku"];address=s["address"]
supplier=s["supplier"];suffix="p4"+uuid.uuid4().hex[:8]

def create_paid(quantity,key):
    cart=call("POST","/api/customer/cart",{"skuId":sku["id"],"quantity":quantity},buyer)[0]
    quote=call("POST","/api/customer/checkout/quote",{"addressId":address,"cartItemIds":[cart["id"]]},buyer)
    created=call("POST","/api/customer/orders",{"addressId":address,"cartItemIds":[cart["id"]],"quoteHash":quote["quoteHash"],"idempotencyKey":key},buyer,expected=201)
    order=created["order"]["id"]
    attempt=call("POST",f"/api/customer/orders/{order}/payments",{"channel":"DEV_SIMULATOR"},buyer)
    call("POST",f"/api/customer/payment-attempts/{attempt['attemptNo']}/simulate",{"result":"SUCCESS","eventKey":"pay-"+key},buyer)
    return order

def approve_and_simulate(case,amount,deduction=0):
    detail=call("GET",f"/api/admin/after-sales/{case}",token=admin)
    call("POST",f"/api/admin/after-sales/{case}/review",{
        "decision":"APPROVE","comment":"Phase 4 HTTP smoke","refundAmountFen":amount,
        "shippingRefundFen":0,"responsibility":"MERCHANT" if not deduction else "SUPPLIER",
        "supplierDeductionFen":deduction,"refundChannel":"DEV_SIMULATOR",
        "items":[{"afterSaleItemId":detail["items"][0]["id"],"approvedQuantity":1}]
    },admin)
    reviewed=call("GET",f"/api/admin/after-sales/{case}",token=admin)
    refund=reviewed["refunds"][0]
    call("POST",f"/api/admin/refund-attempts/{refund['attemptNo']}/simulate",{"result":"SUCCESS","eventKey":"refund-"+str(case)},admin)
    final=call("GET",f"/api/customer/after-sales/{case}",token=buyer)
    assert final["status"]=="COMPLETED" and final["refunds"][0]["status"]=="SUCCEEDED",final

# Chain 1: paid -> unshipped quantity frozen/cancelled -> simulated refund -> inventory/ledger checks.
order1=create_paid(1,"phase4-unshipped-"+suffix)
detail1=call("GET",f"/api/customer/orders/{order1}",token=buyer);item1=detail1["items"][0]
case1=call("POST",f"/api/customer/orders/{order1}/after-sales",{"action":"REFUND","reasonCode":"UNSHIPPED_CANCEL","reasonDetail":"HTTP smoke","items":[{"orderItemId":item1["id"],"quantity":1}]},buyer,expected=201)["id"]
task1=next(x for x in call("GET","/api/admin/fulfillment-tasks",token=admin) if x["orderNo"]==detail1["order"]["orderNo"])
frozen=call("GET",f"/api/admin/fulfillment-tasks/{task1['id']}",token=admin)["items"][0]
assert frozen["frozenQty"]==1 and frozen["shippedQty"]==0,frozen
approve_and_simulate(case1,3000)
cancelled=call("GET",f"/api/admin/fulfillment-tasks/{task1['id']}",token=admin)["items"][0]
assert cancelled["cancelledQty"]==1 and cancelled["frozenQty"]==0 and cancelled["shippedQty"]==0,cancelled
line1=next(x for x in call("GET","/api/admin/supplier-ledger",token=admin) if x["orderId"]==order1 and x["type"]=="GOODS")
assert line1["quantity"]==0 and line1["amountFen"]==0,line1

# Chain 2 uses Phase 3's already split-shipped order: receipt -> damage partial refund -> statement -> manual payment.
order2=s["order"]
for parcel in call("GET",f"/api/customer/orders/{order2}/parcels",token=buyer):
    call("POST",f"/api/customer/parcels/{parcel['id']}/confirm-received",token=buyer)
received=call("GET",f"/api/customer/orders/{order2}",token=buyer)
assert received["order"]["completionStatus"]=="COMPLETED",received
item2=received["items"][0]
case2=call("POST",f"/api/customer/orders/{order2}/after-sales",{"action":"REFUND","reasonCode":"BAD_FRUIT","reasonDetail":"partial damage","items":[{"orderItemId":item2["id"],"quantity":1}]},buyer,expected=201)["id"]
approve_and_simulate(case2,500,100)
ledger=[x for x in call("GET","/api/admin/supplier-ledger",token=admin) if x["supplierId"]==supplier["id"] and x["status"]=="AVAILABLE" and x["amountFen"]!=0]
statement=call("POST","/api/admin/settlement-statements",{"supplierId":supplier["id"],"ledgerEntryIds":[x["id"] for x in ledger]},admin)
remaining=statement["totalAmountFen"]
first=max(1,remaining//2)
call("POST",f"/api/admin/settlement-statements/{statement['id']}/payments",{"idempotencyKey":"manual-a-"+suffix,"amountFen":first,"paidOn":"2026-09-20","referenceNo":"MANUAL-A-"+suffix,"note":"人工登记，未验证银行状态"},admin)
final=call("POST",f"/api/admin/settlement-statements/{statement['id']}/payments",{"idempotencyKey":"manual-b-"+suffix,"amountFen":remaining-first,"paidOn":"2026-09-20","referenceNo":"MANUAL-B-"+suffix,"note":"人工登记，未验证银行状态"},admin)
assert final["status"]=="PAID" and final["paidAmountFen"]==final["totalAmountFen"],final
print("PASS: unshipped refund freeze/cancel/simulated refund + split receipt/damage refund/settlement/manual payment")
