package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/customer/addresses")
public class AddressController {
    private static final Set<String> MAINLAND_PROVINCES=Set.of(
        "11","12","13","14","15","21","22","23","31","32","33","34","35","36","37",
        "41","42","43","44","45","46","50","51","52","53","54","61","62","63","64","65");
    private final JdbcTemplate db;
    public AddressController(JdbcTemplate db) { this.db=db; }
    public record AddressInput(
        @NotBlank @Size(max=60) String recipient,
        @Pattern(regexp="1[3-9][0-9]{9}") String mobile,
        @NotBlank @Pattern(regexp="[0-9]{6}") String provinceCode,@NotBlank @Size(max=40) String provinceName,
        @NotBlank @Pattern(regexp="[0-9]{6}") String cityCode,@NotBlank @Size(max=40) String cityName,
        @NotBlank @Pattern(regexp="[0-9]{6}") String districtCode,@NotBlank @Size(max=40) String districtName,
        @NotBlank @Size(max=240) String detail) {}
    public record Address(long id,String recipient,String mobile,String provinceCode,String provinceName,
        String cityCode,String cityName,String districtCode,String districtName,String detail,boolean isDefault) {}
    private long owner(Authentication auth) { return Long.parseLong(auth.getName()); }
    private void validate(AddressInput a) {
        if (!MAINLAND_PROVINCES.contains(a.provinceCode().substring(0,2))
                || !a.provinceCode().endsWith("0000")
                || !a.cityCode().substring(0,2).equals(a.provinceCode().substring(0,2))
                || !a.cityCode().endsWith("00")
                || !a.districtCode().substring(0,4).equals(a.cityCode().substring(0,4)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid mainland region hierarchy");
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
        validate(a);
        long consumer=owner(auth);
        db.queryForList("SELECT id FROM consumer_account WHERE id=? FOR UPDATE",consumer);
        boolean first=db.queryForObject("SELECT COUNT(*) FROM customer_address WHERE consumer_id=?",Integer.class,consumer)==0;
        var key=new GeneratedKeyHolder();
        db.update(c->{
            var p=c.prepareStatement("INSERT INTO customer_address (consumer_id,recipient,mobile,province_code,province_name,city_code,city_name,district_code,district_name,detail,is_default) VALUES (?,?,?,?,?,?,?,?,?,?,?)",new String[]{"id"});
            p.setLong(1,consumer);p.setString(2,a.recipient().trim());p.setString(3,a.mobile());
            p.setString(4,a.provinceCode());p.setString(5,a.provinceName().trim());p.setString(6,a.cityCode());
            p.setString(7,a.cityName().trim());p.setString(8,a.districtCode());p.setString(9,a.districtName().trim());
            p.setString(10,a.detail().trim());p.setBoolean(11,first);return p;
        },key);
        return rows("SELECT * FROM customer_address WHERE id=? AND consumer_id=?",key.getKey().longValue(),consumer).getFirst();
    }
    @PutMapping("/{id}")
    public Address update(Authentication auth,@PathVariable long id,@Valid @RequestBody AddressInput a) {
        validate(a);
        long consumer=owner(auth);
        if(db.update("UPDATE customer_address SET recipient=?,mobile=?,province_code=?,province_name=?,city_code=?,city_name=?,district_code=?,district_name=?,detail=? WHERE id=? AND consumer_id=?",
            a.recipient().trim(),a.mobile(),a.provinceCode(),a.provinceName().trim(),a.cityCode(),a.cityName().trim(),
            a.districtCode(),a.districtName().trim(),a.detail().trim(),id,consumer)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
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
