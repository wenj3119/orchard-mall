package com.orchard.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class QuoteService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    public QuoteService(JdbcTemplate db,ObjectMapper mapper) { this.db=db;this.mapper=mapper; }
    public record AddressSnapshot(String recipient,String mobile,String provinceCode,String provinceName,
        String cityCode,String cityName,String districtCode,String districtName,String detail) {}
    public record QuoteLine(long cartItemId,long cartVersion,long productId,String productTitle,long skuId,
        String skuCode,String specJson,int quantity,long unitPriceFen,long lineAmountFen,int netWeightG,
        int billableWeightG,Long supplyId,long supplyPriceFen,Long supplierId,String supplierName,Long originId,String originLabel,
        String originProvince,String originCity,String originAddress,Long templateId,String templateName,Long templateVersion,
        String groupKey,String reason) {}
    public record QuoteGroup(String groupKey,long supplierId,String supplierName,long originId,String originLabel,
        String originProvinceCode,String originProvince,String originCityCode,String originCity,String originDistrictCode,
        String originDistrict,String originAddress,String originContactName,String originContactPhone,
        long templateId,String templateName,long templateVersion,
        String ruleRegionCode,long billableWeightG,long itemAmountFen,Long shippingFeeFen,String reason,
        List<Long> cartItemIds) {}
    public record Quote(AddressSnapshot address,List<QuoteLine> items,List<QuoteGroup> groups,
        long itemAmountFen,long shippingAmountFen,long payableAmountFen,boolean purchasable,
        List<String> reasons,String quoteHash) {}
    private record Raw(long cartItemId,long cartVersion,int quantity,boolean selected,long skuId,String skuCode,
        String specJson,long price,int netWeightG,int billableWeightG,boolean skuActive,long productId,
        String productTitle,boolean published,boolean categoryEnabled,Long supplyId,long supplyPriceFen,Long supplierId,
        String supplierName,boolean supplierEnabled,Long originId,String originLabel,String originProvinceCode,String originProvince,
        String originCityCode,String originCity,String originDistrictCode,String originDistrict,String originAddress,
        String originContactName,String originContactPhone,boolean originEnabled,Long templateId,String templateName,Long freeThresholdFen,Long templateVersion,
        boolean templateEnabled,int availableQty) {}
    private record Rule(String code,boolean blocked,int firstWeightG,long firstFeeFen,int stepWeightG,long stepFeeFen) {}
    private static class GroupWork {
        final String key;
        final Raw first;
        long amount;
        long weight;
        final List<Long> cartIds=new ArrayList<>();
        GroupWork(String key,Raw first) { this.key=key;this.first=first; }
    }
    public AddressSnapshot address(long consumer,long id) {
        var found=db.query("""
            SELECT recipient,mobile,province_code,province_name,city_code,city_name,district_code,district_name,detail
            FROM customer_address WHERE id=? AND consumer_id=?
            """,(rs,n)->new AddressSnapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)),id,consumer);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Address not found");
        return found.getFirst();
    }
    private List<Raw> cartRows(long consumer,List<Long> cartIds,boolean lock) {
        String placeholders=String.join(",",Collections.nCopies(cartIds.size(),"?"));
        String sql="""
            SELECT c.id,c.row_version,c.quantity,c.selected,s.id,s.code,s.spec_json,s.retail_price_fen,
                   s.net_weight_g,s.billable_weight_g,s.active,p.id,p.title,p.published,k.enabled,
                   x.id,COALESCE(x.supply_price_fen,0),x.supplier_id,v.name,v.enabled,x.origin_id,o.label,
                   o.province_code,o.province,o.city_code,o.city,o.district_code,o.district,o.address,
                   o.contact_name,o.contact_phone,o.enabled,
                   x.shipping_template_id,t.name,t.free_threshold_fen,t.version,t.enabled,
                   COALESCE(i.on_hand_qty-i.reserved_qty,0)
            FROM shopping_cart c JOIN sku s ON s.id=c.sku_id JOIN product p ON p.id=s.product_id
            JOIN category k ON k.id=p.category_id
            LEFT JOIN sku_supply x ON x.sku_id=s.id AND x.is_default=TRUE
            LEFT JOIN supplier v ON v.id=x.supplier_id
            LEFT JOIN origin o ON o.id=x.origin_id
            LEFT JOIN shipping_template t ON t.id=x.shipping_template_id
            LEFT JOIN supply_inventory i ON i.supply_id=x.id
            WHERE c.consumer_id=? AND c.id IN (%s) ORDER BY c.id
            """.formatted(placeholders)+(lock ? " FOR UPDATE" : "");
        Object[] args=new Object[cartIds.size()+1];
        args[0]=consumer;
        for(int i=0;i<cartIds.size();i++) args[i+1]=cartIds.get(i);
        return db.query(sql,(rs,n)->new Raw(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getBoolean(4),
            rs.getLong(5),rs.getString(6),rs.getString(7),rs.getLong(8),rs.getInt(9),rs.getInt(10),
            rs.getBoolean(11),rs.getLong(12),rs.getString(13),rs.getBoolean(14),rs.getBoolean(15),
            (Long)rs.getObject(16),rs.getLong(17),(Long)rs.getObject(18),rs.getString(19),rs.getBoolean(20),
            (Long)rs.getObject(21),rs.getString(22),rs.getString(23),rs.getString(24),rs.getString(25),
            rs.getString(26),rs.getString(27),rs.getString(28),rs.getString(29),rs.getString(30),rs.getString(31),
            rs.getBoolean(32),(Long)rs.getObject(33),rs.getString(34),(Long)rs.getObject(35),(Long)rs.getObject(36),
            rs.getBoolean(37),rs.getInt(38)),args);
    }
    private List<Rule> rules(long templateId) {
        return db.query("SELECT region_code,blocked,first_weight_g,first_fee_fen,step_weight_g,step_fee_fen FROM shipping_rule WHERE template_id=?",
            (rs,n)->new Rule(rs.getString(1),rs.getBoolean(2),rs.getInt(3),rs.getLong(4),rs.getInt(5),rs.getLong(6)),templateId);
    }
    private Rule matchingRule(long templateId,AddressSnapshot a,Map<Long,List<Rule>> cache) {
        var byCode=new HashMap<String,Rule>();
        for(var r:cache.computeIfAbsent(templateId,this::rules)) byCode.put(r.code(),r);
        for(String code:List.of(a.districtCode(),a.cityCode(),a.provinceCode(),"000000")) {
            if(byCode.containsKey(code)) return byCode.get(code);
        }
        return null;
    }
    public Quote quote(long consumer,long addressId,List<Long> ids,boolean lock) {
        if(ids==null || ids.isEmpty() || ids.size()>50 || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size()!=ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select 1 to 50 distinct cart items");
        var sorted=new ArrayList<>(ids);Collections.sort(sorted);
        var address=address(consumer,addressId);
        var raw=cartRows(consumer,sorted,lock);
        if(raw.size()!=sorted.size()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cart changed; refresh checkout");
        var lines=new ArrayList<QuoteLine>();
        var work=new LinkedHashMap<String,GroupWork>();
        var reasons=new ArrayList<String>();
        long itemTotal=0;
        for(var r:raw) {
            String reason=null;
            if(!r.selected()) reason="购物车商品未勾选";
            else if(!r.published() || !r.categoryEnabled()) reason="商品已下架";
            else if(!r.skuActive()) reason="规格已停用";
            else if(r.supplyId()==null) reason="暂无默认供货来源";
            else if(!r.supplierEnabled()) reason="供货商已停用";
            else if(!r.originEnabled()) reason="发货地已停用";
            else if(r.templateId()==null || !r.templateEnabled()) reason="运费模板未配置";
            else if(r.billableWeightG()<=0) reason="计费重量未配置";
            else if(r.availableQty()<r.quantity()) reason="库存不足";
            long amount=Math.multiplyExact(r.price(),r.quantity());
            itemTotal=Math.addExact(itemTotal,amount);
            String key=r.supplierId()==null || r.originId()==null || r.templateId()==null ? null
                : r.supplierId()+":"+r.originId()+":"+r.templateId();
            lines.add(new QuoteLine(r.cartItemId(),r.cartVersion(),r.productId(),r.productTitle(),r.skuId(),
                r.skuCode(),r.specJson(),r.quantity(),r.price(),amount,r.netWeightG(),r.billableWeightG(),
                r.supplyId(),r.supplyPriceFen(),r.supplierId(),r.supplierName(),r.originId(),r.originLabel(),
                r.originProvince(),r.originCity(),r.originAddress(),r.templateId(),r.templateName(),r.templateVersion(),key,reason));
            if(reason!=null) reasons.add(r.productTitle()+"："+reason);
            else {
                var g=work.computeIfAbsent(key,k->new GroupWork(k,r));
                g.amount=Math.addExact(g.amount,amount);
                g.weight=Math.addExact(g.weight,Math.multiplyExact((long)r.billableWeightG(),r.quantity()));
                g.cartIds.add(r.cartItemId());
            }
        }
        var groups=new ArrayList<QuoteGroup>();
        var cache=new HashMap<Long,List<Rule>>();
        long shippingTotal=0;
        for(var g:work.values()) {
            var r=g.first;
            var rule=matchingRule(r.templateId(),address,cache);
            String reason=null;
            Long fee=null;
            String code=rule==null ? "" : rule.code();
            if(rule==null) reason="配送地区未配置";
            else if(rule.blocked()) reason="该地区不配送";
            else if(r.freeThresholdFen()!=null && g.amount>=r.freeThresholdFen()) fee=0L;
            else {
                long extra=Math.max(0,g.weight-rule.firstWeightG());
                long steps=(extra+rule.stepWeightG()-1)/rule.stepWeightG();
                fee=Math.addExact(rule.firstFeeFen(),Math.multiplyExact(steps,rule.stepFeeFen()));
            }
            if(reason!=null) reasons.add(r.supplierName()+"："+reason);
            if(fee!=null) shippingTotal=Math.addExact(shippingTotal,fee);
            groups.add(new QuoteGroup(g.key,r.supplierId(),r.supplierName(),r.originId(),r.originLabel(),
                r.originProvinceCode(),r.originProvince(),r.originCityCode(),r.originCity(),r.originDistrictCode(),
                r.originDistrict(),r.originAddress(),r.originContactName(),r.originContactPhone(),
                r.templateId(),r.templateName(),r.templateVersion(),
                code,g.weight,g.amount,fee,reason,List.copyOf(g.cartIds)));
        }
        long payable=Math.addExact(itemTotal,shippingTotal);
        boolean purchasable=reasons.isEmpty() && groups.stream().allMatch(g->g.shippingFeeFen()!=null);
        try {
            String canonical=mapper.writeValueAsString(List.of(address,lines,groups,itemTotal,shippingTotal,payable,purchasable));
            return new Quote(address,List.copyOf(lines),List.copyOf(groups),itemTotal,shippingTotal,payable,
                purchasable,List.copyOf(reasons),SecurityConfig.digest(canonical));
        } catch(Exception e) { throw new IllegalStateException("Cannot hash quote",e); }
    }
}
