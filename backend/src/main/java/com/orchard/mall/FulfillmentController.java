package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class FulfillmentController {
    private final FulfillmentService service;private final JdbcTemplate db;public FulfillmentController(FulfillmentService s,JdbcTemplate db){service=s;this.db=db;}
    record Logistics(@NotBlank String carrierCode,@NotBlank String carrierName,@NotBlank String trackingNo){}
    private long supplier(Authentication a){return service.supplierId(a.getName());}
    @GetMapping("/api/supplier/tasks") public List<Map<String,Object>> supplierTasks(Authentication a){return service.list(supplier(a));}
    @GetMapping("/api/supplier/tasks/{id}") public Map<String,Object> supplierTask(Authentication a,@PathVariable long id){return service.detail(id,supplier(a));}
    @PostMapping("/api/supplier/tasks/{id}/accept") public void accept(Authentication a,@PathVariable long id){service.accept(id,supplier(a),a.getName());}
    @PostMapping("/api/supplier/tasks/{id}/parcels") public Map<String,Object> parcel(Authentication a,@PathVariable long id,@Valid @RequestBody FulfillmentService.ParcelRequest r){return service.createParcel(id,supplier(a),"SUPPLIER",a.getName(),r);}
    @PutMapping("/api/supplier/parcels/{id}") public void edit(Authentication a,@PathVariable long id,@Valid @RequestBody Logistics r){service.updateParcel(id,supplier(a),"SUPPLIER",a.getName(),r.carrierCode(),r.carrierName(),r.trackingNo());}
    @GetMapping("/api/admin/fulfillment-tasks") public List<Map<String,Object>> adminTasks(){return service.list(null);}
    @GetMapping("/api/admin/fulfillment-tasks/{id}") public Map<String,Object> adminTask(@PathVariable long id){return service.detail(id,null);}
    @PostMapping("/api/admin/fulfillment-tasks/{id}/parcels") public Map<String,Object> adminParcel(Authentication a,@PathVariable long id,@Valid @RequestBody FulfillmentService.ParcelRequest r){return service.createParcel(id,null,"ADMIN",a.getName(),r);}
    @GetMapping("/api/customer/orders/{orderId}/parcels") public List<Map<String,Object>> customerParcels(Authentication a,@PathVariable long orderId){long owner=Long.parseLong(a.getName());Integer exists=db.queryForObject("SELECT COUNT(*) FROM sales_order WHERE id=? AND consumer_id=?",Integer.class,orderId,owner);if(exists==0)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);return db.query("SELECT id FROM fulfillment_task WHERE sales_order_id=? ORDER BY id",(rs,n)->rs.getLong(1),orderId).stream().flatMap(id->service.parcels(id).stream()).toList();}
    @PostMapping("/api/customer/parcels/{id}/confirm-received") public Map<String,Object> confirm(Authentication a,@PathVariable long id){return service.confirmReceived(id,Long.parseLong(a.getName()));}
}
