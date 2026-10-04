package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customer/addresses")
public class AddressController {
    private final JdbcTemplate db;
    private final RegionCatalog regions;
    public AddressController(JdbcTemplate db, RegionCatalog regions) { this.db=db; this.regions=regions; }
    public record AddressInput(
        @NotBlank @Size(max=60) String recipient,
        @Pattern(regexp="1[3-9][0-9]{9}") String mobile,
        @NotBlank @Pattern(regexp="[0-9]{6}") String provinceCode,
        @NotBlank @Pattern(regexp="[0-9]{6}") String cityCode,
        @NotBlank @Pattern(regexp="[0-9]{6}") String districtCode,
        @NotBlank @Size(max=240) String detail,Boolean isDefault) {}
    public record Address(long id,String recipient,String mobile,String provinceCode,String provinceName,
        String cityCode,String cityName,String districtCode,String districtName,String detail,boolean isDefault) {}
    private long owner(Authentication auth) { return Long.parseLong(auth.getName()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String,String>> invalidField(MethodArgumentNotValidException error) {
        var field=error.getBindingResult().getFieldErrors().getFirst().getField();
        var label=switch(field) {
            case "recipient" -> "收货人";
            case "mobile" -> "手机号";
            case "provinceCode", "cityCode", "districtCode" -> "所在地区";
            case "detail" -> "详细地址";
            default -> "地址";
        };
        return ResponseEntity.badRequest().body(Map.of("detail",label+"：填写内容无效，请检查后重试", "field",field));
    }
    private List<Address> rows(String sql,Object... args) {
        return db.query(sql,(rs,n)->new Address(rs.getLong("id"),rs.getString("recipient"),rs.getString("mobile"),
            rs.getString("province_code"),rs.getString("province_name"),rs.getString("city_code"),
            rs.getString("city_name"),rs.getString("district_code"),rs.getString("district_name"),
            rs.getString("detail"),rs.getBoolean("is_default")),args);
    }
    @GetMapping
    public List<Address> list(Authentication auth) {
        return rows("SELECT * FROM customer_address WHERE consumer_id=? ORDER BY is_default DESC,id DESC",owner(auth));
    }
    @PostMapping
    @Transactional
    public Address create(Authentication auth,@Valid @RequestBody AddressInput a) {
        var names=regions.resolve(a.provinceCode(),a.cityCode(),a.districtCode());
        long consumer=owner(auth);
        db.queryForList("SELECT id FROM consumer_account WHERE id=? FOR UPDATE",consumer);
        boolean first=db.queryForObject("SELECT COUNT(*) FROM customer_address WHERE consumer_id=?",Integer.class,consumer)==0;
        var key=new GeneratedKeyHolder();
        db.update(c->{
            var p=c.prepareStatement("INSERT INTO customer_address (consumer_id,recipient,mobile,province_code,province_name,city_code,city_name,district_code,district_name,detail,is_default) VALUES (?,?,?,?,?,?,?,?,?,?,?)",new String[]{"id"});
            p.setLong(1,consumer);p.setString(2,a.recipient().trim());p.setString(3,a.mobile());
            p.setString(4,a.provinceCode());p.setString(5,names.province());p.setString(6,a.cityCode());
            p.setString(7,names.city());p.setString(8,a.districtCode());p.setString(9,names.district());
            p.setString(10,a.detail().trim());p.setBoolean(11,first);return p;
        },key);
        if(Boolean.TRUE.equals(a.isDefault()) && !first) {
            db.update("UPDATE customer_address SET is_default=FALSE WHERE consumer_id=?",consumer);
            db.update("UPDATE customer_address SET is_default=TRUE WHERE id=? AND consumer_id=?",key.getKey().longValue(),consumer);
        }
        return rows("SELECT * FROM customer_address WHERE id=? AND consumer_id=?",key.getKey().longValue(),consumer).getFirst();
    }
    @PutMapping("/{id}")
    @Transactional
    public Address update(Authentication auth,@PathVariable long id,@Valid @RequestBody AddressInput a) {
        var names=regions.resolve(a.provinceCode(),a.cityCode(),a.districtCode());
        long consumer=owner(auth);
        if(Boolean.TRUE.equals(a.isDefault())) db.queryForList("SELECT id FROM consumer_account WHERE id=? FOR UPDATE",consumer);
        if(db.update("UPDATE customer_address SET recipient=?,mobile=?,province_code=?,province_name=?,city_code=?,city_name=?,district_code=?,district_name=?,detail=? WHERE id=? AND consumer_id=?",
            a.recipient().trim(),a.mobile(),a.provinceCode(),names.province(),a.cityCode(),names.city(),
            a.districtCode(),names.district(),a.detail().trim(),id,consumer)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(Boolean.TRUE.equals(a.isDefault())) {
            db.update("UPDATE customer_address SET is_default=FALSE WHERE consumer_id=?",consumer);
            db.update("UPDATE customer_address SET is_default=TRUE WHERE id=? AND consumer_id=?",id,consumer);
        }
        return rows("SELECT * FROM customer_address WHERE id=? AND consumer_id=?",id,consumer).getFirst();
    }
    @PutMapping("/{id}/default")
    @Transactional
    public void setDefault(Authentication auth,@PathVariable long id) {
        long consumer=owner(auth);
        db.queryForList("SELECT id FROM consumer_account WHERE id=? FOR UPDATE",consumer);
        if(rows("SELECT * FROM customer_address WHERE id=? AND consumer_id=?",id,consumer).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        db.update("UPDATE customer_address SET is_default=FALSE WHERE consumer_id=?",consumer);
        db.update("UPDATE customer_address SET is_default=TRUE WHERE id=? AND consumer_id=?",id,consumer);
    }
    @DeleteMapping("/{id}")
    @Transactional
    public void delete(Authentication auth,@PathVariable long id) {
        long consumer=owner(auth);
        db.queryForList("SELECT id FROM consumer_account WHERE id=? FOR UPDATE",consumer);
        var found=rows("SELECT * FROM customer_address WHERE id=? AND consumer_id=?",id,consumer);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        db.update("DELETE FROM customer_address WHERE id=? AND consumer_id=?",id,consumer);
        if(found.getFirst().isDefault()) {
            var next=rows("SELECT * FROM customer_address WHERE consumer_id=? ORDER BY id DESC LIMIT 1",consumer);
            if(!next.isEmpty()) db.update("UPDATE customer_address SET is_default=TRUE WHERE id=?",next.getFirst().id());
        }
    }
}
