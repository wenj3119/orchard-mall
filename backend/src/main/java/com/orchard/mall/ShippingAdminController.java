package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class ShippingAdminController {
    private final JdbcTemplate db;
    public ShippingAdminController(JdbcTemplate db) { this.db=db; }
    public record TemplateInput(@NotBlank String name,@PositiveOrZero Long freeThresholdFen,boolean enabled) {}
    public record RuleInput(@NotBlank String regionCode,boolean blocked,
        @Positive int firstWeightG,@PositiveOrZero long firstFeeFen,
        @Positive int stepWeightG,@PositiveOrZero long stepFeeFen) {}
    public record AssignTemplate(@NotNull Long templateId) {}
    private long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        db.update(c->{ var p=c.prepareStatement(sql,new String[]{"id"}); for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]); return p; },key);
        return key.getKey().longValue();
    }
    private List<Map<String,Object>> rules(long templateId) {
        return db.query("SELECT id,region_code,blocked,first_weight_g,first_fee_fen,step_weight_g,step_fee_fen FROM shipping_rule WHERE template_id=? ORDER BY region_code",
            (rs,n)->Map.of("id",rs.getLong(1),"regionCode",rs.getString(2),"blocked",rs.getBoolean(3),
                "firstWeightG",rs.getInt(4),"firstFeeFen",rs.getLong(5),"stepWeightG",rs.getInt(6),"stepFeeFen",rs.getLong(7)),templateId);
    }
    private Map<String,Object> template(long id) {
        var found=db.query("SELECT id,name,free_threshold_fen,enabled,version FROM shipping_template WHERE id=?",
            (rs,n)->{
                var m=new LinkedHashMap<String,Object>();
                m.put("id",rs.getLong(1));m.put("name",rs.getString(2));
                m.put("freeThresholdFen",rs.getObject(3));m.put("enabled",rs.getBoolean(4));m.put("version",rs.getLong(5));
                m.put("rules",rules(rs.getLong(1)));return m;
            },id);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return found.getFirst();
    }
    @GetMapping("/shipping-templates")
    public List<Map<String,Object>> list() {
        return db.query("SELECT id FROM shipping_template ORDER BY id DESC",(rs,n)->template(rs.getLong(1)));
    }
    @GetMapping("/shipping-templates/{id}")
    public Map<String,Object> get(@PathVariable long id) { return template(id); }
    @PostMapping("/shipping-templates")
    public Map<String,Object> create(@Valid @RequestBody TemplateInput x) {
        long id=insert("INSERT INTO shipping_template (name,free_threshold_fen,enabled) VALUES (?,?,?)",
            x.name().trim(),x.freeThresholdFen(),x.enabled());
        return template(id);
    }
    @PutMapping("/shipping-templates/{id}")
    public Map<String,Object> update(@PathVariable long id,@Valid @RequestBody TemplateInput x) {
        if(db.update("UPDATE shipping_template SET name=?,free_threshold_fen=?,enabled=?,version=version+1 WHERE id=?",
            x.name().trim(),x.freeThresholdFen(),x.enabled(),id)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return template(id);
    }
    private void validateRule(RuleInput x) {
        String code=x.regionCode();
        if(!code.equals("000000") && (!code.matches("[0-9]{6}") || Set.of("710000","810000","820000").contains(code)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only mainland six-digit region codes or 000000");
    }
    @PostMapping("/shipping-templates/{id}/rules")
    @Transactional
    public Map<String,Object> createRule(@PathVariable long id,@Valid @RequestBody RuleInput x) {
        template(id);validateRule(x);
        insert("INSERT INTO shipping_rule (template_id,region_code,blocked,first_weight_g,first_fee_fen,step_weight_g,step_fee_fen) VALUES (?,?,?,?,?,?,?)",
            id,x.regionCode(),x.blocked(),x.firstWeightG(),x.firstFeeFen(),x.stepWeightG(),x.stepFeeFen());
        db.update("UPDATE shipping_template SET version=version+1 WHERE id=?",id);
        return template(id);
    }
    @PutMapping("/shipping-templates/{id}/rules/{ruleId}")
    @Transactional
    public Map<String,Object> updateRule(@PathVariable long id,@PathVariable long ruleId,@Valid @RequestBody RuleInput x) {
        validateRule(x);
        if(db.update("UPDATE shipping_rule SET region_code=?,blocked=?,first_weight_g=?,first_fee_fen=?,step_weight_g=?,step_fee_fen=? WHERE id=? AND template_id=?",
            x.regionCode(),x.blocked(),x.firstWeightG(),x.firstFeeFen(),x.stepWeightG(),x.stepFeeFen(),ruleId,id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        db.update("UPDATE shipping_template SET version=version+1 WHERE id=?",id);
        return template(id);
    }
    @DeleteMapping("/shipping-templates/{id}/rules/{ruleId}")
    @Transactional
    public void deleteRule(@PathVariable long id,@PathVariable long ruleId) {
        if(db.update("DELETE FROM shipping_rule WHERE id=? AND template_id=?",ruleId,id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        db.update("UPDATE shipping_template SET version=version+1 WHERE id=?",id);
    }
    @PutMapping("/supplies/{supplyId}/shipping-template")
    public void assign(@PathVariable long supplyId,@Valid @RequestBody AssignTemplate x) {
        var enabled=db.query("SELECT enabled FROM shipping_template WHERE id=?",(rs,n)->rs.getBoolean(1),x.templateId());
        if(enabled.isEmpty() || !enabled.getFirst()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Template must be enabled");
        Integer source=db.queryForObject("SELECT COUNT(*) FROM sku_supply s JOIN supplier v ON v.id=s.supplier_id AND v.enabled=TRUE JOIN origin o ON o.id=s.origin_id AND o.enabled=TRUE WHERE s.id=?",Integer.class,supplyId);
        if(source==null||source==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Supplier and shipping origin must be enabled");
        if(db.update("UPDATE sku_supply SET shipping_template_id=? WHERE id=?",x.templateId(),supplyId)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
