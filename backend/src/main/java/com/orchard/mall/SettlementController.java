package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class SettlementController {
    private final SettlementService service;private final FulfillmentService fulfillment;public SettlementController(SettlementService service,FulfillmentService fulfillment){this.service=service;this.fulfillment=fulfillment;}
    @GetMapping("/api/admin/supplier-ledger") public List<Map<String,Object>> adminLedger(){return service.ledger(null);}
    @PostMapping("/api/admin/settlement-statements") public Map<String,Object> statement(Authentication a,@Valid @RequestBody SettlementService.StatementRequest r){return service.createStatement(r,a.getName());}
    @GetMapping("/api/admin/settlement-statements") public List<Map<String,Object>> statements(){return service.statements(null);}
    @GetMapping("/api/admin/settlement-statements/{id}") public Map<String,Object> statement(@PathVariable long id){return service.statement(id,null,true);}
    @PostMapping("/api/admin/settlement-statements/{id}/payments") public Map<String,Object> pay(Authentication a,@PathVariable long id,@Valid @RequestBody SettlementService.PaymentRequest r){return service.recordPayment(id,r,a.getName());}
    record Reverse(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String idempotencyKey,@NotBlank String reason){}
    @PostMapping("/api/admin/settlement-statements/{id}/payments/{paymentId}/reverse") public Map<String,Object> reverse(Authentication a,@PathVariable long id,@PathVariable long paymentId,@Valid @RequestBody Reverse r){return service.reverse(id,paymentId,r.idempotencyKey(),r.reason(),a.getName());}
    @PostMapping("/api/admin/supplier-ledger/adjustments") public void adjustment(Authentication a,@Valid @RequestBody SettlementService.AdjustmentRequest r){service.adjustment(r,a.getName());}
    @GetMapping("/api/supplier/ledger") public List<Map<String,Object>> supplierLedger(Authentication a){return service.ledger(fulfillment.supplierId(a.getName()));}
    @GetMapping("/api/supplier/settlement-statements") public List<Map<String,Object>> supplierStatements(Authentication a){return service.statements(fulfillment.supplierId(a.getName()));}
    @GetMapping("/api/supplier/settlement-statements/{id}") public Map<String,Object> supplierStatement(Authentication a,@PathVariable long id){return service.statement(id,fulfillment.supplierId(a.getName()),false);}
}
