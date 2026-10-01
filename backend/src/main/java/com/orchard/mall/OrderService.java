package com.orchard.mall;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class OrderService {
    private final JdbcTemplate db;
    private final QuoteService quotes;
    private final PaymentService payments;
    private final int expiryMinutes;
    public OrderService(JdbcTemplate db,QuoteService quotes,PaymentService payments,@Value("${app.order-expiry-minutes:30}") int expiryMinutes) {
        if(expiryMinutes<5 || expiryMinutes>1440) throw new IllegalArgumentException("Order expiry must be 5–1440 minutes");
        this.db=db;this.quotes=quotes;this.payments=payments;this.expiryMinutes=expiryMinutes;
    }
    public static class QuoteChanged extends RuntimeException {
        public final QuoteService.Quote quote;
        public QuoteChanged(QuoteService.Quote quote) { super("Quote changed");this.quote=quote; }
    }
    public record Place(long addressId,List<Long> cartItemIds,String quoteHash,String idempotencyKey) {}
    public record OrderSummary(long id,String orderNo,String status,String paymentStatus,String fulfillmentStatus,String completionStatus,String afterSalesStatus,
        long itemAmountFen,long shippingAmountFen,long payableAmountFen,String createdAt,String expiresAt) {}
    public record OrderDetail(OrderSummary order,QuoteService.AddressSnapshot address,
        List<Map<String,Object>> groups,List<Map<String,Object>> items) {}
    private long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        db.update(c->{var p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);
        return key.getKey().longValue();
    }
    private String requestHash(long consumer,Place request) {
        var ids=new ArrayList<>(request.cartItemIds());Collections.sort(ids);
        return SecurityConfig.digest(consumer+"|"+request.addressId()+"|"+ids+"|"+request.quoteHash());
    }
    @Transactional
    public OrderDetail create(long consumer,Place request) {
        if(request.cartItemIds()==null || request.quoteHash()==null || request.quoteHash().isBlank()
                || request.idempotencyKey()==null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{8,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid checkout request");
        db.queryForList("SELECT id FROM consumer_account WHERE id=? FOR UPDATE",consumer);
        String hash=requestHash(consumer,request);
        var existing=db.query("SELECT id,request_hash FROM sales_order WHERE consumer_id=? AND idempotency_key=?",
            (rs,n)->Map.entry(rs.getLong(1),rs.getString(2)),consumer,request.idempotencyKey());
        if(!existing.isEmpty()) {
            if(!existing.getFirst().getValue().equals(hash))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key used for different request");
            return detail(consumer,existing.getFirst().getKey(),false);
        }
        var quote=quotes.quote(consumer,request.addressId(),request.cartItemIds(),true);
        if(!quote.quoteHash().equals(request.quoteHash())) throw new QuoteChanged(quote);
        if(!quote.purchasable()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Checkout contains unavailable items");
        var address=quote.address();
        var expires=LocalDateTime.now().plusMinutes(expiryMinutes);
        String orderNo="OM"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        long orderId=insert("""
            INSERT INTO sales_order (order_no,consumer_id,idempotency_key,request_hash,quote_hash,status,
                item_amount_fen,shipping_amount_fen,payable_amount_fen,recipient,mobile,province_code,province_name,
                city_code,city_name,district_code,district_name,address_detail,expires_at)
            VALUES (?,?,?,?,?,'PENDING_PAYMENT',?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,orderNo,consumer,request.idempotencyKey(),hash,quote.quoteHash(),quote.itemAmountFen(),
            quote.shippingAmountFen(),quote.payableAmountFen(),address.recipient(),address.mobile(),
            address.provinceCode(),address.provinceName(),address.cityCode(),address.cityName(),
            address.districtCode(),address.districtName(),address.detail(),expires);
        var quantities=new TreeMap<Long,Integer>();
        for(var line:quote.items()) quantities.merge(line.supplyId(),line.quantity(),Math::addExact);
        for(var entry:quantities.entrySet()) {
            int updated=db.update("""
                UPDATE supply_inventory SET reserved_qty=reserved_qty+?
                WHERE supply_id=? AND on_hand_qty-reserved_qty>=?
                """,entry.getValue(),entry.getKey(),entry.getValue());
            if(updated!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Stock changed; retry checkout");
            db.update("INSERT INTO stock_reservation (order_id,supply_id,quantity,status) VALUES (?,?,?,'HELD')",
                orderId,entry.getKey(),entry.getValue());
            db.update("INSERT INTO inventory_movement (supply_id,order_id,delta_on_hand,delta_reserved,reason,actor) VALUES (?,?,0,?,'订单预占',?)",
                entry.getKey(),orderId,entry.getValue(),"consumer:"+consumer);
        }
        var groupIds=new HashMap<String,Long>();
        for(var group:quote.groups()) {
            long id=insert("""
                INSERT INTO order_group (order_id,supplier_id,supplier_name,origin_id,origin_label,origin_province,
                    origin_city,origin_address,origin_province_code,origin_city_code,origin_district_code,origin_district,
                    origin_contact_name,origin_contact_phone,template_id,template_name,template_version,rule_region_code,
                    billable_weight_g,item_amount_fen,shipping_fee_fen)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,orderId,group.supplierId(),group.supplierName(),group.originId(),group.originLabel(),
                group.originProvince(),group.originCity(),group.originAddress(),group.originProvinceCode(),group.originCityCode(),
                group.originDistrictCode(),group.originDistrict(),group.originContactName(),group.originContactPhone(),group.templateId(),group.templateName(),
                group.templateVersion(),group.ruleRegionCode(),group.billableWeightG(),group.itemAmountFen(),group.shippingFeeFen());
            groupIds.put(group.groupKey(),id);
        }
        for(var line:quote.items()) {
            db.update("""
                INSERT INTO order_item (order_id,group_id,product_id,product_title,sku_id,sku_code,spec_json,
                    quantity,unit_price_fen,line_amount_fen,net_weight_g,billable_weight_g,supply_id,supply_price_fen,
                    supplier_id,origin_id,template_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,orderId,groupIds.get(line.groupKey()),line.productId(),line.productTitle(),line.skuId(),
                line.skuCode(),line.specJson(),line.quantity(),line.unitPriceFen(),line.lineAmountFen(),
                line.netWeightG(),line.billableWeightG(),line.supplyId(),line.supplyPriceFen(),line.supplierId(),line.originId(),line.templateId());
            db.update("DELETE FROM shopping_cart WHERE id=? AND consumer_id=? AND row_version=?",
                line.cartItemId(),consumer,line.cartVersion());
        }
        return detail(consumer,orderId,false);
    }
    public List<OrderSummary> list(long consumer) {
        return db.query("SELECT id,order_no,status,payment_status,fulfillment_status,completion_status,after_sales_status,item_amount_fen,shipping_amount_fen,payable_amount_fen,created_at,expires_at FROM sales_order WHERE consumer_id=? ORDER BY id DESC",
            (rs,n)->summary(rs),consumer);
    }
    public List<OrderSummary> adminList() {
        return db.query("SELECT id,order_no,status,payment_status,fulfillment_status,completion_status,after_sales_status,item_amount_fen,shipping_amount_fen,payable_amount_fen,created_at,expires_at FROM sales_order ORDER BY id DESC LIMIT 200",
            (rs,n)->summary(rs));
    }
    private OrderSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderSummary(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getLong(8),rs.getLong(9),
            rs.getLong(10),rs.getTimestamp(11).toLocalDateTime().toString(),rs.getTimestamp(12).toLocalDateTime().toString());
    }
    public OrderDetail detail(long consumer,long id,boolean admin) {
        String sql="SELECT id,order_no,status,payment_status,fulfillment_status,completion_status,after_sales_status,item_amount_fen,shipping_amount_fen,payable_amount_fen,created_at,expires_at,recipient,mobile,province_code,province_name,city_code,city_name,district_code,district_name,address_detail FROM sales_order WHERE id=?";
        var found=db.query(sql+(admin?"":" AND consumer_id=?"),(rs,n)->{
            var order=summary(rs);
            var address=new QuoteService.AddressSnapshot(rs.getString(13),rs.getString(14),rs.getString(15),
                rs.getString(16),rs.getString(17),rs.getString(18),rs.getString(19),rs.getString(20),rs.getString(21));
            return Map.entry(order,address);
        },admin?new Object[]{id}:new Object[]{id,consumer});
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Map<String,Object>> groups=db.query("""
            SELECT id,supplier_id,supplier_name,origin_id,origin_label,origin_province,origin_city,
                   origin_address,origin_province_code,origin_city_code,origin_district_code,origin_district,
                   origin_contact_name,origin_contact_phone,template_id,template_name,template_version,rule_region_code,
                   billable_weight_g,item_amount_fen,shipping_fee_fen
            FROM order_group WHERE order_id=? ORDER BY id
            """,(rs,n)->{
                var m=new LinkedHashMap<String,Object>();
                m.put("id",rs.getLong(1));m.put("supplierId",rs.getLong(2));m.put("supplierName",rs.getString(3));
                m.put("originId",rs.getLong(4));m.put("originLabel",rs.getString(5));
                m.put("originProvince",rs.getString(6));m.put("originCity",rs.getString(7));
                m.put("originAddress",rs.getString(8));m.put("originProvinceCode",rs.getString(9));m.put("originCityCode",rs.getString(10));
                m.put("originDistrictCode",rs.getString(11));m.put("originDistrict",rs.getString(12));
                m.put("originContactName",rs.getString(13));m.put("originContactPhone",rs.getString(14));
                m.put("templateId",rs.getLong(15));m.put("templateName",rs.getString(16));
                m.put("templateVersion",rs.getLong(17));m.put("ruleRegionCode",rs.getString(18));m.put("billableWeightG",rs.getLong(19));
                m.put("itemAmountFen",rs.getLong(20));m.put("shippingFeeFen",rs.getLong(21));return m;
            },id);
        List<Map<String,Object>> items=db.query("""
            SELECT id,group_id,product_id,product_title,sku_id,sku_code,spec_json,quantity,unit_price_fen,
                   line_amount_fen,net_weight_g,billable_weight_g,supply_id,supplier_id,origin_id,template_id
            FROM order_item WHERE order_id=? ORDER BY id
            """,(rs,n)->{
                var m=new LinkedHashMap<String,Object>();
                m.put("id",rs.getLong(1));m.put("groupId",rs.getLong(2));m.put("productId",rs.getLong(3));m.put("productTitle",rs.getString(4));
                m.put("skuId",rs.getLong(5));m.put("skuCode",rs.getString(6));m.put("specJson",rs.getString(7));
                m.put("quantity",rs.getInt(8));m.put("unitPriceFen",rs.getLong(9));m.put("lineAmountFen",rs.getLong(10));
                m.put("netWeightG",rs.getInt(11));m.put("billableWeightG",rs.getInt(12));
                m.put("supplyId",rs.getLong(13));m.put("supplierId",rs.getLong(14));m.put("originId",rs.getLong(15));
                m.put("templateId",rs.getLong(16));return m;
            },id);
        return new OrderDetail(found.getFirst().getKey(),found.getFirst().getValue(),groups,items);
    }
    public OrderDetail cancel(long consumer,long id) {
        detail(consumer,id,false);
        payments.coordinateClose(consumer,id,"CANCELLED");
        return detail(consumer,id,false);
    }
    public void closeExpired(long id) {
        Integer due=db.queryForObject("SELECT COUNT(*) FROM sales_order WHERE id=? AND status IN ('PENDING_PAYMENT','CLOSE_PENDING') AND expires_at<=CURRENT_TIMESTAMP",Integer.class,id);
        if(due!=null&&due>0)payments.coordinateClose(null,id,"CLOSED");
    }
}
