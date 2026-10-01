package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/api/customer/cart")
public class CartController {
    private final JdbcTemplate db;
    public CartController(JdbcTemplate db) { this.db=db; }
    public record Add(@NotNull Long skuId,@Min(1) @Max(99) int quantity) {}
    public record Change(@Min(1) @Max(99) int quantity,boolean selected) {}
    public record CartItem(long id,long skuId,long productId,String productTitle,String skuCode,String specJson,
        long unitPriceFen,int quantity,boolean selected,long rowVersion,String imageUrl,
        int netWeightG,int billableWeightG,int availableQty,String unavailableReason) {}
    private long owner(Authentication auth) { return Long.parseLong(auth.getName()); }
    private List<CartItem> rows(long consumer) {
        return db.query("""
            SELECT c.id,c.sku_id,p.id,p.title,s.code,s.spec_json,s.retail_price_fen,
                   c.quantity,c.selected,c.row_version,
                   (SELECT CONCAT('/api/media/',pi.media_id) FROM product_image pi WHERE pi.product_id=p.id ORDER BY pi.sort_order,pi.media_id LIMIT 1) AS image_url,
                   s.net_weight_g,s.billable_weight_g,
                   COALESCE(i.on_hand_qty-i.reserved_qty,0) AS available_qty,
                   p.published,k.enabled,s.active,x.id,v.enabled,o.enabled,x.shipping_template_id,t.enabled
            FROM shopping_cart c JOIN sku s ON s.id=c.sku_id JOIN product p ON p.id=s.product_id
            JOIN category k ON k.id=p.category_id
            LEFT JOIN sku_supply x ON x.sku_id=s.id AND x.is_default=TRUE
            LEFT JOIN supplier v ON v.id=x.supplier_id
            LEFT JOIN origin o ON o.id=x.origin_id
            LEFT JOIN shipping_template t ON t.id=x.shipping_template_id
            LEFT JOIN supply_inventory i ON i.supply_id=x.id
            WHERE c.consumer_id=? ORDER BY c.id DESC
            """,(rs,n)->{
                String reason=null;
                if(!rs.getBoolean(15) || !rs.getBoolean(16)) reason="商品已下架";
                else if(!rs.getBoolean(17)) reason="规格已停用";
                else if(rs.getObject(18)==null) reason="暂无供货来源";
                else if(!rs.getBoolean(19)) reason="供货商已停用";
                else if(!rs.getBoolean(20)) reason="发货地已停用";
                else if(rs.getObject(21)==null || !rs.getBoolean(22)) reason="运费模板未配置";
                else if(rs.getInt(13)<=0) reason="计费重量未配置";
                else if(rs.getInt(14)<rs.getInt(8)) reason="库存不足";
                return new CartItem(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),
                    rs.getString(5),rs.getString(6),rs.getLong(7),rs.getInt(8),rs.getBoolean(9),
                    rs.getLong(10),rs.getString(11),rs.getInt(12),rs.getInt(13),rs.getInt(14),reason);
            },consumer);
    }
    @GetMapping
    public List<CartItem> list(Authentication auth) { return rows(owner(auth)); }
    private void checkSku(long skuId) {
        Integer count=db.queryForObject("""
            SELECT COUNT(*) FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id
            WHERE s.id=? AND s.active=TRUE AND p.published=TRUE AND c.enabled=TRUE
            """,Integer.class,skuId);
        if(count==null || count==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"SKU is unavailable");
    }
    @PostMapping
    @Transactional
    public List<CartItem> add(Authentication auth,@Valid @RequestBody Add x) {
        long consumer=owner(auth);
        db.queryForList("SELECT id FROM consumer_account WHERE id=? FOR UPDATE",consumer);
        checkSku(x.skuId());
        var existing=db.query("SELECT id,quantity FROM shopping_cart WHERE consumer_id=? AND sku_id=?",
            (rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},consumer,x.skuId());
        if(existing.isEmpty()) db.update("INSERT INTO shopping_cart (consumer_id,sku_id,quantity,selected) VALUES (?,?,?,TRUE)",
            consumer,x.skuId(),x.quantity());
        else {
            long total=existing.getFirst()[1]+x.quantity();
            if(total>99) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cart quantity cannot exceed 99");
            db.update("UPDATE shopping_cart SET quantity=?,selected=TRUE,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                total,existing.getFirst()[0]);
        }
        return rows(consumer);
    }
    @PutMapping("/{id}")
    @Transactional
    public List<CartItem> change(Authentication auth,@PathVariable long id,@Valid @RequestBody Change x) {
        long consumer=owner(auth);
        db.queryForList("SELECT id FROM consumer_account WHERE id=? FOR UPDATE",consumer);
        var found=db.query("SELECT sku_id FROM shopping_cart WHERE id=? AND consumer_id=?",
            (rs,n)->rs.getLong(1),id,consumer);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(x.selected()) checkSku(found.getFirst());
        db.update("UPDATE shopping_cart SET quantity=?,selected=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND consumer_id=?",
            x.quantity(),x.selected(),id,consumer);
        return rows(consumer);
    }
    @DeleteMapping("/{id}")
    @Transactional
    public List<CartItem> delete(Authentication auth,@PathVariable long id) {
        long consumer=owner(auth);
        db.queryForList("SELECT id FROM consumer_account WHERE id=? FOR UPDATE",consumer);
        if(db.update("DELETE FROM shopping_cart WHERE id=? AND consumer_id=?",id,consumer)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows(consumer);
    }
}
