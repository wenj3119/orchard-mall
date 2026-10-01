package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.util.*;

@RestController
public class CatalogController {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    public CatalogController(JdbcTemplate db, ObjectMapper mapper) { this.db = db; this.mapper = mapper; }
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"); }
    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(c -> {
            var ps = c.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private List<Map<String,Object>> rows(String sql, Object... args) {
        return db.query(sql, (rs, index) -> {
            var row = new LinkedHashMap<String,Object>();
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String raw = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
                String key = switch (raw) {
                    case "sortorder" -> "sortOrder"; case "logourl" -> "logoUrl";
                    case "themecolor" -> "themeColor"; case "contactphone" -> "contactPhone";
                    case "contactname" -> "contactName"; case "categoryid" -> "categoryId";
                    case "categoryname" -> "categoryName"; case "imageurl" -> "imageUrl";
                    case "minpricefen" -> "minPriceFen"; case "productid" -> "productId";
                    case "skuid" -> "skuId";
                    case "specjson" -> "specJson"; case "retailpricefen" -> "retailPriceFen";
                    case "netweightg" -> "netWeightG"; case "billableweightg" -> "billableWeightG";
                    case "supplierid" -> "supplierId"; case "suppliername" -> "supplierName";
                    case "sourcetype" -> "sourceType";
                    case "originid" -> "originId"; case "originlabel" -> "originLabel";
                    case "origindescription" -> "originDescription";
                    case "provincecode" -> "provinceCode"; case "citycode" -> "cityCode";
                    case "districtcode" -> "districtCode"; case "usagesupplycount" -> "usageSupplyCount";
                    case "activeusagecount" -> "activeUsageCount"; case "createdat" -> "createdAt";
                    case "updatedat" -> "updatedAt"; case "onhandqty" -> "onHandQty";
                    case "reservedqty" -> "reservedQty"; case "availableqty" -> "availableQty";
                    case "shippingtemplatename" -> "shippingTemplateName"; case "sourcelocked" -> "sourceLocked";
                    case "sourcelockreason" -> "sourceLockReason";
                    case "supplypricefen" -> "supplyPriceFen"; case "isdefault" -> "isDefault";
                    case "shippingtemplateid" -> "shippingTemplateId";
                    default -> raw;
                };
                row.put(key, rs.getObject(i));
            }
            return row;
        }, args);
    }
    private Map<String,Object> one(String sql, Object... args) {
        var found = rows(sql,args);
        if (found.isEmpty()) throw missing();
        return found.getFirst();
    }
    public record Store(@NotBlank String name, String logoUrl, @Pattern(regexp="#[0-9a-fA-F]{6}") String themeColor, String contactPhone, String description) {}
    @GetMapping("/api/public/store")
    public Map<String,Object> publicStore() { return one("SELECT name,logo_url AS logoUrl,theme_color AS themeColor,contact_phone AS contactPhone,description FROM store_settings WHERE id=1"); }
    @GetMapping("/api/admin/store")
    public Map<String,Object> adminStore() { return publicStore(); }
    @PutMapping("/api/admin/store")
    public Map<String,Object> updateStore(@Valid @RequestBody Store s) {
        db.update("UPDATE store_settings SET name=?,logo_url=?,theme_color=?,contact_phone=?,description=? WHERE id=1",
            s.name(),s.logoUrl(),s.themeColor(),s.contactPhone(),s.description());
        return publicStore();
    }
    public record CategoryInput(@NotBlank String name, int sortOrder, boolean enabled) {}
    @GetMapping("/api/public/categories")
    public List<Map<String,Object>> publicCategories() { return rows("SELECT id,name,sort_order AS sortOrder FROM category WHERE enabled=TRUE ORDER BY sort_order,id"); }
    @GetMapping("/api/admin/categories")
    public List<Map<String,Object>> adminCategories() { return rows("SELECT id,name,sort_order AS sortOrder,enabled FROM category ORDER BY sort_order,id"); }
    @PostMapping("/api/admin/categories")
    public Map<String,Object> createCategory(@Valid @RequestBody CategoryInput c) {
        long id=insert("INSERT INTO category (name,sort_order,enabled) VALUES (?,?,?)",c.name(),c.sortOrder(),c.enabled());
        return one("SELECT id,name,sort_order AS sortOrder,enabled FROM category WHERE id=?",id);
    }
    @PutMapping("/api/admin/categories/{id}")
    public void updateCategory(@PathVariable long id,@Valid @RequestBody CategoryInput c) {
        if(db.update("UPDATE category SET name=?,sort_order=?,enabled=? WHERE id=?",c.name(),c.sortOrder(),c.enabled(),id)==0) throw missing();
    }
    public record SupplierInput(@NotBlank String name,@NotBlank String contactName,@NotBlank String contactPhone,
            @Pattern(regexp="SELF|FARMER|FACTORY") String sourceType,boolean enabled) {}
    private String sourceType(SupplierInput s) { return s.sourceType()==null ? "FARMER" : s.sourceType(); }
    @GetMapping("/api/admin/suppliers")
    public List<Map<String,Object>> suppliers() { return rows("SELECT id,name,contact_name AS contactName,contact_phone AS contactPhone,source_type AS sourceType,enabled FROM supplier ORDER BY id"); }
    @PostMapping("/api/admin/suppliers")
    public Map<String,Object> createSupplier(@Valid @RequestBody SupplierInput s) {
        long id=insert("INSERT INTO supplier (name,contact_name,contact_phone,source_type,enabled) VALUES (?,?,?,?,?)",s.name(),s.contactName(),s.contactPhone(),sourceType(s),s.enabled());
        return one("SELECT id,name,contact_name AS contactName,contact_phone AS contactPhone,source_type AS sourceType,enabled FROM supplier WHERE id=?",id);
    }
    @PutMapping("/api/admin/suppliers/{id}")
    public void updateSupplier(@PathVariable long id,@Valid @RequestBody SupplierInput s) {
        if(db.update("UPDATE supplier SET name=?,contact_name=?,contact_phone=?,source_type=?,enabled=? WHERE id=?",s.name(),s.contactName(),s.contactPhone(),sourceType(s),s.enabled(),id)==0) throw missing();
    }
    public record OriginInput(@NotNull Long supplierId,@NotBlank String label,
        @NotBlank @Pattern(regexp="[0-9]{6}") String provinceCode,@NotBlank String province,
        @NotBlank @Pattern(regexp="[0-9]{6}") String cityCode,@NotBlank String city,
        @NotBlank @Pattern(regexp="[0-9]{6}") String districtCode,@NotBlank String district,
        @NotBlank String address,@NotBlank String contactName,@NotBlank String contactPhone,
        boolean isDefault,boolean enabled) {}
    private static final String ORIGIN_SELECT="""
        SELECT o.id,o.supplier_id AS supplierId,s.name AS supplierName,o.label,
               o.province_code AS provinceCode,o.province,o.city_code AS cityCode,o.city,
               o.district_code AS districtCode,o.district,o.address,o.contact_name AS contactName,
               o.contact_phone AS contactPhone,o.is_default AS isDefault,o.enabled,o.created_at AS createdAt,
               o.updated_at AS updatedAt,(SELECT COUNT(*) FROM sku_supply x WHERE x.origin_id=o.id) AS usageSupplyCount,
               (SELECT COUNT(*) FROM sku_supply x WHERE x.origin_id=o.id AND x.is_default=TRUE) AS activeUsageCount
        FROM origin o JOIN supplier s ON s.id=o.supplier_id
        """;
    @GetMapping("/api/admin/origins")
    public List<Map<String,Object>> origins(@RequestParam(required=false) Long supplierId,@RequestParam(required=false) Boolean enabled) {
        if(supplierId!=null && enabled!=null) return rows(ORIGIN_SELECT+" WHERE o.supplier_id=? AND o.enabled=? ORDER BY o.is_default DESC,o.id",supplierId,enabled);
        if(supplierId!=null) return rows(ORIGIN_SELECT+" WHERE o.supplier_id=? ORDER BY o.is_default DESC,o.id",supplierId);
        if(enabled!=null) return rows(ORIGIN_SELECT+" WHERE o.enabled=? ORDER BY o.supplier_id,o.is_default DESC,o.id",enabled);
        return rows(ORIGIN_SELECT+" ORDER BY o.supplier_id,o.is_default DESC,o.id");
    }
    @PostMapping("/api/admin/origins")
    @Transactional
    public Map<String,Object> createOrigin(@Valid @RequestBody OriginInput o) {
        if(o.isDefault()&&!o.enabled()) throw bad("默认发货地必须启用");
        var supplier=one("SELECT id,enabled FROM supplier WHERE id=?",o.supplierId());
        db.queryForList("SELECT id FROM supplier WHERE id=? FOR UPDATE",o.supplierId());
        if(o.isDefault()) db.update("UPDATE origin SET is_default=FALSE WHERE supplier_id=?",o.supplierId());
        long id=insert("""
            INSERT INTO origin (supplier_id,label,province_code,province,city_code,city,district_code,district,
                address,contact_name,contact_phone,is_default,enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,o.supplierId(),o.label().trim(),o.provinceCode(),o.province().trim(),o.cityCode(),o.city().trim(),
            o.districtCode(),o.district().trim(),o.address().trim(),o.contactName().trim(),o.contactPhone().trim(),o.isDefault(),o.enabled());
        return one(ORIGIN_SELECT+" WHERE o.id=?",id);
    }
    @PutMapping("/api/admin/origins/{id}")
    @Transactional
    public Map<String,Object> updateOrigin(@PathVariable long id,@Valid @RequestBody OriginInput o) {
        if(o.isDefault()&&!o.enabled()) throw bad("默认发货地必须启用");
        var current=one("SELECT supplier_id,is_default FROM origin WHERE id=?",id);
        long oldSupplier=((Number)current.get("supplier_id")).longValue();
        db.queryForList("SELECT id FROM supplier WHERE id=? FOR UPDATE",oldSupplier);
        one("SELECT id FROM supplier WHERE id=?",o.supplierId());
        int references=db.queryForObject("SELECT COUNT(*) FROM sku_supply WHERE origin_id=?",Integer.class,id);
        if(oldSupplier!=o.supplierId() && references>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"已有供货关系的发货地不能变更所属供应商；请新建发货地和供货关系");
        if(o.isDefault()) db.update("UPDATE origin SET is_default=FALSE WHERE supplier_id=? AND id<>?",o.supplierId(),id);
        db.update("""
            UPDATE origin SET supplier_id=?,label=?,province_code=?,province=?,city_code=?,city=?,district_code=?,district=?,
                address=?,contact_name=?,contact_phone=?,is_default=?,enabled=? WHERE id=?
            """,o.supplierId(),o.label().trim(),o.provinceCode(),o.province().trim(),o.cityCode(),o.city().trim(),
            o.districtCode(),o.district().trim(),o.address().trim(),o.contactName().trim(),o.contactPhone().trim(),o.isDefault(),o.enabled(),id);
        return one(ORIGIN_SELECT+" WHERE o.id=?",id);
    }
    @PutMapping("/api/admin/origins/{id}/default")
    @Transactional
    public void setDefaultOrigin(@PathVariable long id) {
        var found=one("SELECT supplier_id,enabled FROM origin WHERE id=?",id);
        if(!Boolean.TRUE.equals(found.get("enabled"))) throw bad("默认发货地必须启用");
        long supplier=((Number)found.get("supplier_id")).longValue();
        db.queryForList("SELECT id FROM supplier WHERE id=? FOR UPDATE",supplier);
        db.update("UPDATE origin SET is_default=FALSE WHERE supplier_id=?",supplier);
        db.update("UPDATE origin SET is_default=TRUE WHERE id=?",id);
    }
    @DeleteMapping("/api/admin/origins/{id}")
    @Transactional
    public void deleteOrigin(@PathVariable long id) {
        one("SELECT id FROM origin WHERE id=?",id);
        Integer used=db.queryForObject("SELECT COUNT(*) FROM sku_supply WHERE origin_id=?",Integer.class,id);
        Integer historical=db.queryForObject("SELECT COUNT(*) FROM order_group WHERE origin_id=?",Integer.class,id);
        if((used!=null&&used>0)||(historical!=null&&historical>0))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"发货地已有供货关系或历史订单，不能删除；可改为停用");
        db.update("DELETE FROM origin WHERE id=?",id);
    }
    public record ProductInput(@NotNull Long categoryId,@NotBlank String title,String description,String originDescription) {}
    private static final String PRODUCT_SELECT="SELECT p.id,p.category_id AS categoryId,c.name AS categoryName,p.title,p.description,p.origin_description AS originDescription,p.published,(SELECT CONCAT('/api/media/',pi.media_id) FROM product_image pi WHERE pi.product_id=p.id ORDER BY pi.sort_order,pi.media_id LIMIT 1) AS imageUrl,(SELECT MIN(s.retail_price_fen) FROM sku s WHERE s.product_id=p.id AND s.active=TRUE) AS minPriceFen FROM product p JOIN category c ON c.id=p.category_id";
    @GetMapping("/api/admin/products")
    public List<Map<String,Object>> adminProducts() { return rows(PRODUCT_SELECT+" ORDER BY p.id DESC"); }
    @PostMapping("/api/admin/products")
    public Map<String,Object> createProduct(@Valid @RequestBody ProductInput p) {
        long id=insert("INSERT INTO product (category_id,title,description,origin_description) VALUES (?,?,?,?)",p.categoryId(),p.title(),p.description(),p.originDescription());
        return adminProduct(id);
    }
    @PutMapping("/api/admin/products/{id}")
    public Map<String,Object> updateProduct(@PathVariable long id,@Valid @RequestBody ProductInput p) {
        if(db.update("UPDATE product SET category_id=?,title=?,description=?,origin_description=? WHERE id=?",p.categoryId(),p.title(),p.description(),p.originDescription(),id)==0) throw missing();
        return adminProduct(id);
    }
    @GetMapping("/api/admin/products/{id}")
    public Map<String,Object> adminProduct(@PathVariable long id) {
        var p=new LinkedHashMap<>(one(PRODUCT_SELECT+" WHERE p.id=?",id));
        p.put("images",rows("SELECT m.id,CONCAT('/api/media/',m.id) AS url FROM product_image pi JOIN media_object m ON m.id=pi.media_id WHERE pi.product_id=? ORDER BY pi.sort_order,pi.media_id",id));
        p.put("skus",rows("SELECT id,code,spec_json AS specJson,retail_price_fen AS retailPriceFen,net_weight_g AS netWeightG,billable_weight_g AS billableWeightG,active FROM sku WHERE product_id=? ORDER BY id",id));
        return p;
    }
    @PutMapping("/api/admin/products/{id}/images")
    @Transactional
    public void setImages(@PathVariable long id,@RequestBody List<Long> ids) {
        one("SELECT id FROM product WHERE id=?",id);
        if(ids.size()>12 || new HashSet<>(ids).size()!=ids.size()) throw bad("Invalid image list");
        for(Long mediaId:ids) one("SELECT id FROM media_object WHERE id=?",mediaId);
        db.update("DELETE FROM product_image WHERE product_id=?",id);
        for(int i=0;i<ids.size();i++) db.update("INSERT INTO product_image (product_id,media_id,sort_order) VALUES (?,?,?)",id,ids.get(i),i);
    }
    public record SkuInput(@NotBlank String code,@NotBlank String specJson,@PositiveOrZero long retailPriceFen,
            @PositiveOrZero int netWeightG,@PositiveOrZero int billableWeightG,boolean active) {}
    private void validateSpec(String json) {
        try {
            if (!mapper.readTree(json).isObject()) throw bad("Specification must be a JSON object");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw bad("Specification must be valid JSON");
        }
    }
    @PostMapping("/api/admin/products/{id}/skus")
    public Map<String,Object> createSku(@PathVariable long id,@Valid @RequestBody SkuInput s) {
        one("SELECT id FROM product WHERE id=?",id);
        validateSpec(s.specJson());
        long skuId=insert("INSERT INTO sku (product_id,code,spec_json,retail_price_fen,net_weight_g,billable_weight_g,active) VALUES (?,?,?,?,?,?,?)",
            id,s.code(),s.specJson(),s.retailPriceFen(),s.netWeightG(),s.billableWeightG(),s.active());
        return one("SELECT id,product_id AS productId,code,spec_json AS specJson,retail_price_fen AS retailPriceFen,net_weight_g AS netWeightG,billable_weight_g AS billableWeightG,active FROM sku WHERE id=?",skuId);
    }
    @PutMapping("/api/admin/skus/{id}")
    public void updateSku(@PathVariable long id,@Valid @RequestBody SkuInput s) {
        validateSpec(s.specJson());
        if(db.update("UPDATE sku SET code=?,spec_json=?,retail_price_fen=?,net_weight_g=?,billable_weight_g=?,active=? WHERE id=?",
            s.code(),s.specJson(),s.retailPriceFen(),s.netWeightG(),s.billableWeightG(),s.active(),id)==0) throw missing();
    }
    public record SupplyInput(@NotNull Long supplierId,@NotNull Long originId,@NotNull Long shippingTemplateId,
        @PositiveOrZero long supplyPriceFen,boolean isDefault) {}
    private static final String SUPPLY_SELECT="""
        SELECT x.id,x.sku_id AS skuId,x.supplier_id AS supplierId,s.name AS supplierName,
               x.origin_id AS originId,o.label AS originLabel,x.supply_price_fen AS supplyPriceFen,
               x.shipping_template_id AS shippingTemplateId,t.name AS shippingTemplateName,x.is_default AS isDefault,
               i.on_hand_qty AS onHandQty,i.reserved_qty AS reservedQty,(i.on_hand_qty-i.reserved_qty) AS availableQty,
               (i.on_hand_qty<>0 OR i.reserved_qty<>0 OR EXISTS(SELECT 1 FROM inventory_movement m WHERE m.supply_id=x.id)
                 OR EXISTS(SELECT 1 FROM stock_reservation r WHERE r.supply_id=x.id)
                 OR EXISTS(SELECT 1 FROM order_item oi WHERE oi.supply_id=x.id)) AS sourceLocked,
               CASE WHEN (i.on_hand_qty<>0 OR i.reserved_qty<>0 OR EXISTS(SELECT 1 FROM inventory_movement m WHERE m.supply_id=x.id)
                 OR EXISTS(SELECT 1 FROM stock_reservation r WHERE r.supply_id=x.id)
                 OR EXISTS(SELECT 1 FROM order_item oi WHERE oi.supply_id=x.id))
                 THEN '该来源已有库存、库存流水、预占或订单引用；更换供应商/发货地请新建供货关系' ELSE NULL END AS sourceLockReason
        FROM sku_supply x JOIN supplier s ON s.id=x.supplier_id JOIN origin o ON o.id=x.origin_id
        LEFT JOIN shipping_template t ON t.id=x.shipping_template_id JOIN supply_inventory i ON i.supply_id=x.id
        """;
    private void validateSupplySource(SupplyInput s) {
        var supplier=one("SELECT enabled FROM supplier WHERE id=?",s.supplierId());
        if(!Boolean.TRUE.equals(supplier.get("enabled"))) throw bad("供应商必须存在且已启用");
        var origin=one("SELECT supplier_id,enabled FROM origin WHERE id=?",s.originId());
        if(((Number)origin.get("supplier_id")).longValue()!=s.supplierId()) throw bad("发货地必须属于所选供应商");
        if(!Boolean.TRUE.equals(origin.get("enabled"))) throw bad("发货地必须已启用");
        var template=one("SELECT enabled FROM shipping_template WHERE id=?",s.shippingTemplateId());
        if(!Boolean.TRUE.equals(template.get("enabled"))) throw bad("运费模板必须已启用");
    }
    @GetMapping("/api/admin/skus/{id}/supplies")
    public List<Map<String,Object>> supplies(@PathVariable long id) {
        one("SELECT id FROM sku WHERE id=?",id);
        return rows(SUPPLY_SELECT+" WHERE x.sku_id=? ORDER BY x.id",id);
    }
    @PostMapping("/api/admin/skus/{id}/supplies")
    @Transactional
    public Map<String,Object> createSupply(@PathVariable long id,@Valid @RequestBody SupplyInput s) {
        one("SELECT id FROM sku WHERE id=?",id);
        db.queryForList("SELECT id FROM sku WHERE id=? FOR UPDATE",id);
        validateSupplySource(s);
        if(s.isDefault()) db.update("UPDATE sku_supply SET is_default=FALSE WHERE sku_id=?",id);
        long supplyId=insert("INSERT INTO sku_supply (sku_id,supplier_id,origin_id,shipping_template_id,supply_price_fen,is_default) VALUES (?,?,?,?,?,?)",id,s.supplierId(),s.originId(),s.shippingTemplateId(),s.supplyPriceFen(),s.isDefault());
        db.update("INSERT INTO supply_inventory (supply_id,on_hand_qty,reserved_qty) VALUES (?,0,0)",supplyId);
        return one(SUPPLY_SELECT+" WHERE x.id=?",supplyId);
    }
    @PutMapping("/api/admin/skus/{skuId}/supplies/{supplyId}/default")
    @Transactional
    public void setDefault(@PathVariable long skuId,@PathVariable long supplyId) {
        db.queryForList("SELECT id FROM sku WHERE id=? FOR UPDATE",skuId);
        var source=one("SELECT x.id,s.enabled AS supplier_enabled,o.enabled AS origin_enabled,t.enabled AS template_enabled FROM sku_supply x JOIN supplier s ON s.id=x.supplier_id JOIN origin o ON o.id=x.origin_id JOIN shipping_template t ON t.id=x.shipping_template_id WHERE x.id=? AND x.sku_id=?",supplyId,skuId);
        if(!Boolean.TRUE.equals(source.get("supplier_enabled"))||!Boolean.TRUE.equals(source.get("origin_enabled"))||!Boolean.TRUE.equals(source.get("template_enabled")))
            throw bad("只有供应商、发货地和运费模板均启用的来源才能设为默认");
        db.update("UPDATE sku_supply SET is_default=FALSE WHERE sku_id=?",skuId);
        db.update("UPDATE sku_supply SET is_default=TRUE WHERE id=?",supplyId);
    }
    @PutMapping("/api/admin/skus/{skuId}/supplies/{supplyId}")
    @Transactional
    public void updateSupply(@PathVariable long skuId,@PathVariable long supplyId,@Valid @RequestBody SupplyInput s) {
        db.queryForList("SELECT id FROM sku WHERE id=? FOR UPDATE",skuId);
        var current=one("SELECT supplier_id,origin_id FROM sku_supply WHERE id=? AND sku_id=?",supplyId,skuId);
        validateSupplySource(s);
        boolean moving=((Number)current.get("supplier_id")).longValue()!=s.supplierId()
            || ((Number)current.get("origin_id")).longValue()!=s.originId();
        if(moving) {
            Integer used=db.queryForObject("""
                SELECT (EXISTS(SELECT 1 FROM supply_inventory WHERE supply_id=? AND (on_hand_qty<>0 OR reserved_qty<>0))
                    OR EXISTS(SELECT 1 FROM inventory_movement WHERE supply_id=?)
                    OR EXISTS(SELECT 1 FROM stock_reservation WHERE supply_id=?)
                    OR EXISTS(SELECT 1 FROM order_item WHERE supply_id=?))
                """,Integer.class,supplyId,supplyId,supplyId,supplyId);
            if(used!=null&&used!=0) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "该供货关系已有库存、预占或订单引用，不能搬移来源；请新建供货关系并显式调整默认来源");
        }
        if(s.isDefault()) db.update("UPDATE sku_supply SET is_default=FALSE WHERE sku_id=?",skuId);
        db.update("UPDATE sku_supply SET supplier_id=?,origin_id=?,shipping_template_id=?,supply_price_fen=?,is_default=? WHERE id=?",
            s.supplierId(),s.originId(),s.shippingTemplateId(),s.supplyPriceFen(),s.isDefault(),supplyId);
    }
    @DeleteMapping("/api/admin/skus/{skuId}/supplies/{supplyId}")
    @Transactional
    public void deleteSupply(@PathVariable long skuId,@PathVariable long supplyId) {
        db.queryForList("SELECT id FROM sku WHERE id=? FOR UPDATE",skuId);
        var inventory=one("SELECT on_hand_qty,reserved_qty FROM supply_inventory WHERE supply_id=?",supplyId);
        Integer referenced=db.queryForObject("SELECT (EXISTS(SELECT 1 FROM inventory_movement WHERE supply_id=?) OR EXISTS(SELECT 1 FROM stock_reservation WHERE supply_id=?) OR EXISTS(SELECT 1 FROM order_item WHERE supply_id=?))",Integer.class,supplyId,supplyId,supplyId);
        if(((Number)inventory.get("on_hand_qty")).intValue()!=0 || ((Number)inventory.get("reserved_qty")).intValue()!=0 || (referenced!=null&&referenced!=0))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"已有库存、流水、预占或订单引用的供货关系不能删除");
        db.update("DELETE FROM supply_inventory WHERE supply_id=?",supplyId);
        if(db.update("DELETE FROM sku_supply WHERE id=? AND sku_id=?",supplyId,skuId)==0) throw missing();
    }
    @PutMapping("/api/admin/products/{id}/publication")
    @Transactional
    public void publish(@PathVariable long id,@RequestBody Map<String,Boolean> body) {
        one("SELECT id FROM product WHERE id=?",id);
        boolean published=Boolean.TRUE.equals(body.get("published"));
        if(published) {
            Integer images=db.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=?",Integer.class,id);
            Integer ready=db.queryForObject("SELECT COUNT(*) FROM sku s JOIN sku_supply x ON x.sku_id=s.id AND x.is_default=TRUE JOIN supplier v ON v.id=x.supplier_id AND v.enabled=TRUE JOIN origin o ON o.id=x.origin_id AND o.enabled=TRUE JOIN shipping_template t ON t.id=x.shipping_template_id AND t.enabled=TRUE WHERE s.product_id=? AND s.active=TRUE",Integer.class,id);
            if(images==null || images==0 || ready==null || ready==0) throw bad("Publishing requires an image and an active SKU with enabled default supplier");
        }
        db.update("UPDATE product SET published=? WHERE id=?",published,id);
    }
    @GetMapping("/api/public/products")
    public List<Map<String,Object>> publicProducts(@RequestParam(required=false) Long categoryId) {
        String sql=PRODUCT_SELECT+" WHERE p.published=TRUE AND c.enabled=TRUE AND EXISTS (SELECT 1 FROM sku s JOIN sku_supply x ON x.sku_id=s.id AND x.is_default=TRUE JOIN supplier v ON v.id=x.supplier_id AND v.enabled=TRUE JOIN origin o ON o.id=x.origin_id AND o.enabled=TRUE JOIN shipping_template t ON t.id=x.shipping_template_id AND t.enabled=TRUE WHERE s.product_id=p.id AND s.active=TRUE)";
        if(categoryId==null) return rows(sql+" ORDER BY p.id DESC");
        return rows(sql+" AND p.category_id=? ORDER BY p.id DESC",categoryId);
    }
    @GetMapping("/api/public/products/{id}")
    public Map<String,Object> publicProduct(@PathVariable long id) {
        var found=publicProducts(null).stream().filter(p -> ((Number)p.get("id")).longValue()==id).findFirst().orElseThrow(CatalogController::missing);
        var p=new LinkedHashMap<>(found);
        p.put("images",rows("SELECT CONCAT('/api/media/',media_id) AS url FROM product_image WHERE product_id=? ORDER BY sort_order,media_id",id));
        p.put("skus",rows("SELECT s.id,s.code,s.spec_json AS specJson,s.retail_price_fen AS retailPriceFen,s.net_weight_g AS netWeightG,s.billable_weight_g AS billableWeightG FROM sku s JOIN sku_supply x ON x.sku_id=s.id AND x.is_default=TRUE JOIN supplier v ON v.id=x.supplier_id AND v.enabled=TRUE JOIN origin o ON o.id=x.origin_id AND o.enabled=TRUE JOIN shipping_template t ON t.id=x.shipping_template_id AND t.enabled=TRUE WHERE s.product_id=? AND s.active=TRUE ORDER BY s.id",id));
        return p;
    }
}
