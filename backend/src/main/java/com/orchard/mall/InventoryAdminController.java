package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/inventory")
public class InventoryAdminController {
    private final JdbcTemplate db;
    public InventoryAdminController(JdbcTemplate db) { this.db=db; }
    public record Adjustment(int delta,@NotBlank @Size(min=3,max=200) String reason) {}
    @GetMapping
    public List<Map<String,Object>> list() {
        return db.query("""
            SELECT x.id AS supply_id,p.title,s.code,v.name AS supplier_name,o.label AS origin_label,
                   x.is_default,x.shipping_template_id,i.on_hand_qty,i.reserved_qty,
                   s.net_weight_g,s.billable_weight_g
            FROM sku_supply x JOIN sku s ON s.id=x.sku_id JOIN product p ON p.id=s.product_id
            JOIN supplier v ON v.id=x.supplier_id JOIN origin o ON o.id=x.origin_id
            JOIN supply_inventory i ON i.supply_id=x.id ORDER BY x.id DESC
            """,(rs,n)->{
                var row=new java.util.LinkedHashMap<String,Object>();
                row.put("supplyId",rs.getLong(1));row.put("productTitle",rs.getString(2));
                row.put("skuCode",rs.getString(3));row.put("supplierName",rs.getString(4));
                row.put("originLabel",rs.getString(5));row.put("isDefault",rs.getBoolean(6));
                row.put("shippingTemplateId",rs.getObject(7));row.put("onHandQty",rs.getInt(8));
                row.put("reservedQty",rs.getInt(9));row.put("availableQty",rs.getInt(8)-rs.getInt(9));
                row.put("netWeightG",rs.getInt(10));row.put("billableWeightG",rs.getInt(11));
                return row;
            });
    }
    @PostMapping("/{supplyId}/adjust")
    @Transactional
    public Map<String,Object> adjust(@PathVariable long supplyId,@Valid @RequestBody Adjustment x,Authentication auth) {
        if(x.delta()==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Adjustment cannot be zero");
        var found=db.query("SELECT on_hand_qty,reserved_qty FROM supply_inventory WHERE supply_id=? FOR UPDATE",
            (rs,n)->new int[]{rs.getInt(1),rs.getInt(2)},supplyId);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        long next=(long)found.getFirst()[0]+x.delta();
        if(next<found.getFirst()[1] || next<0 || next>Integer.MAX_VALUE)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Stock cannot fall below reserved quantity");
        db.update("UPDATE supply_inventory SET on_hand_qty=? WHERE supply_id=?",(int)next,supplyId);
        db.update("INSERT INTO inventory_movement (supply_id,delta_on_hand,delta_reserved,reason,actor) VALUES (?,?,?,?,?)",
            supplyId,x.delta(),0,x.reason().trim(),auth.getName());
        return Map.of("supplyId",supplyId,"onHandQty",next,"reservedQty",found.getFirst()[1],"availableQty",next-found.getFirst()[1]);
    }
    @GetMapping("/{supplyId}/movements")
    public List<Map<String,Object>> movements(@PathVariable long supplyId) {
        return db.query("SELECT id,order_id,delta_on_hand,delta_reserved,reason,actor,created_at FROM inventory_movement WHERE supply_id=? ORDER BY id DESC LIMIT 100",
            (rs,n)->{
                var row=new java.util.LinkedHashMap<String,Object>();
                row.put("id",rs.getLong(1));row.put("orderId",rs.getObject(2));row.put("deltaOnHand",rs.getInt(3));
                row.put("deltaReserved",rs.getInt(4));row.put("reason",rs.getString(5));row.put("actor",rs.getString(6));
                row.put("createdAt",rs.getTimestamp(7).toLocalDateTime().toString());return row;
            },supplyId);
    }
}
