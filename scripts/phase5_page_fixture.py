"""Create isolated Phase 5 page-acceptance data through development business APIs."""
import base64
import json
import os
import pathlib
import runpy
import uuid

root = pathlib.Path(__file__).resolve().parents[1]
phase3 = runpy.run_path(str(pathlib.Path(__file__).with_name('smoke_phase3.py')))
call = phase3['call']
admin = phase3['admin']
buyer = phase3['buyer']
sku = phase3['sku']
address = phase3['address']
supplier = phase3['supplier']
source_order = phase3['order']
suffix = uuid.uuid4().hex[:10]


def paid_order(quantity, label):
    cart = call('POST', '/api/customer/cart', {'skuId': sku['id'], 'quantity': quantity}, buyer)[0]
    quote = call('POST', '/api/customer/checkout/quote', {'addressId': address, 'cartItemIds': [cart['id']]}, buyer)
    result = call('POST', '/api/customer/orders', {
        'addressId': address, 'cartItemIds': [cart['id']], 'quoteHash': quote['quoteHash'],
        'idempotencyKey': f'phase5-{label}-{suffix}'
    }, buyer, expected=201)
    order = result['order']['id']
    attempt = call('POST', f'/api/customer/orders/{order}/payments', {'channel': 'DEV_SIMULATOR'}, buyer)
    call('POST', f"/api/customer/payment-attempts/{attempt['attemptNo']}/simulate", {
        'result': 'SUCCESS', 'eventKey': f'phase5-pay-{label}-{suffix}'
    }, buyer)
    return call('GET', f'/api/customer/orders/{order}', token=buyer)


def apply_case(order_detail, action, reason, quantity=1):
    item_id = order_detail['items'][0]['id']
    return call('POST', f"/api/customer/orders/{order_detail['order']['id']}/after-sales", {
        'action': action, 'reasonCode': reason, 'reasonDetail': 'Phase 5 local test data',
        'items': [{'orderItemId': item_id, 'quantity': quantity}]
    }, buyer, expected=201)


def evidence(case_id):
    png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lXcAAAAASUVORK5CYII=')
    boundary = '----phase5-' + suffix
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="test-only.png"\r\nContent-Type: image/png\r\n\r\n'.encode()
            + png + f'\r\n--{boundary}--\r\n'.encode())
    return call('POST', f'/api/customer/after-sales/{case_id}/evidence', body, buyer,
                f'multipart/form-data; boundary={boundary}')


for parcel in call('GET', f'/api/customer/orders/{source_order}/parcels', token=buyer):
    call('POST', f"/api/customer/parcels/{parcel['id']}/confirm-received", token=buyer)
source_detail = call('GET', f'/api/customer/orders/{source_order}', token=buyer)
assert source_detail['order']['completionStatus'] == 'COMPLETED'

available = [row for row in call('GET', '/api/admin/supplier-ledger', token=admin)
             if row['supplierId'] == supplier['id'] and row['status'] == 'AVAILABLE' and row['amountFen'] > 0]
statement = call('POST', '/api/admin/settlement-statements', {
    'supplierId': supplier['id'], 'ledgerEntryIds': [row['id'] for row in available]
}, admin)
call('POST', f"/api/admin/settlement-statements/{statement['id']}/payments", {
    'idempotencyKey': f'phase5-fixture-pay-{suffix}', 'amountFen': 100,
    'paidOn': '2026-09-21', 'referenceNo': f'TEST-{suffix}', 'note': '仅本地人工记录'
}, admin)

damage = apply_case(source_detail, 'REFUND', 'BAD_FRUIT')
damage_evidence = evidence(damage['id'])
replacement = apply_case(source_detail, 'REPLACEMENT', 'BAD_FRUIT')
replacement_evidence = evidence(replacement['id'])
unshipped_order = paid_order(1, 'unshipped')
unshipped = apply_case(unshipped_order, 'REFUND', 'UNSHIPPED_CANCEL')
mobile_order = paid_order(3, 'mobile')

other_buyer = call('POST', '/api/dev/consumer-login', {
    'platform': 'WECHAT', 'externalUserId': f'phase5-unrelated-{suffix}'
})['token']
other_supplier = call('POST', '/api/admin/suppliers', {
    'name': '五期无关供应商' + suffix, 'sourceType': 'FARMER', 'contactName': '测试',
    'contactPhone': '13800000002', 'enabled': True
}, admin)
other_supplier_password = 'OnlyTest-' + uuid.uuid4().hex
other_supplier_user = call('POST', '/api/admin/supplier-users', {
    'supplierId': other_supplier['id'], 'username': 'unrelated-' + suffix,
    'password': other_supplier_password, 'enabled': True
}, admin, expected=201)
other_supplier_token = call('POST', '/api/supplier/auth/login', {
    'username': other_supplier_user['username'], 'password': other_supplier_password
})['token']

manifest = {
    'suffix': suffix, 'skuId': sku['id'], 'supplierId': supplier['id'],
    'supplierUsername': 'supplier-' + phase3['suffix'],
    'sourceOrderId': source_order, 'sourceOrderNo': source_detail['order']['orderNo'],
    'statementId': statement['id'], 'statementNo': statement['statementNo'],
    'damageCaseId': damage['id'], 'damageCaseNo': damage['caseNo'], 'damageEvidenceId': damage_evidence['id'],
    'replacementCaseId': replacement['id'], 'replacementCaseNo': replacement['caseNo'],
    'replacementEvidenceId': replacement_evidence['id'],
    'unshippedOrderId': unshipped_order['order']['id'], 'unshippedOrderNo': unshipped_order['order']['orderNo'],
    'unshippedCaseId': unshipped['id'], 'unshippedCaseNo': unshipped['caseNo'],
    'mobileOrderId': mobile_order['order']['id'], 'mobileOrderNo': mobile_order['order']['orderNo'],
    'otherSupplierUserId': other_supplier_user['id']
}
artifacts = root / '.artifacts' / 'phase5'
artifacts.mkdir(parents=True, exist_ok=True)
(artifacts / 'page-fixture.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
private = {'adminToken': admin, 'buyerToken': buyer, 'otherBuyerToken': other_buyer,
           'otherSupplierToken': other_supplier_token,
           'supplierPassword': phase3['supplier_password']}
private_path = artifacts / 'page-fixture-private.json'
private_path.write_text(json.dumps(private))
os.chmod(private_path, 0o600)
print(json.dumps(manifest, ensure_ascii=False, indent=2))
