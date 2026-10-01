package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@RestController
public class SupplierAuthController {
    private final JdbcTemplate db;private final PasswordEncoder encoder;
    public SupplierAuthController(JdbcTemplate db,PasswordEncoder encoder){this.db=db;this.encoder=encoder;}
    record Login(@NotBlank String username,@NotBlank String password){}
    record Account(@Positive long supplierId,@NotBlank @Size(max=80) String username,@Size(min=12,max=100) String password,Boolean enabled){}
    @PostMapping("/api/supplier/auth/login") public Map<String,String> login(@Valid @RequestBody Login r){
        var u=db.query("SELECT id,password_hash,credentials_version FROM supplier_user WHERE username=? AND enabled=TRUE",(rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getLong(3)},r.username());
        if(u.isEmpty()||!encoder.matches(r.password(),(String)u.getFirst()[1]))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO supplier_session(supplier_user_id,token_hash,credentials_version,expires_at) VALUES (?,?,?,?)",u.getFirst()[0],SecurityConfig.digest(token),u.getFirst()[2],LocalDateTime.now().plusHours(12));return Map.of("token",token);
    }
    @GetMapping("/api/admin/supplier-users") public List<Map<String,Object>> list(){return db.query("SELECT u.id,u.supplier_id,s.name,u.username,u.enabled,u.credentials_version FROM supplier_user u JOIN supplier s ON s.id=u.supplier_id ORDER BY u.id",(rs,n)->Map.of("id",rs.getLong(1),"supplierId",rs.getLong(2),"supplierName",rs.getString(3),"username",rs.getString(4),"enabled",rs.getBoolean(5),"credentialsVersion",rs.getLong(6)));}
    @PostMapping("/api/admin/supplier-users") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> create(@Valid @RequestBody Account r){
        if(r.password()==null||r.password().length()<12)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Initial supplier password must contain at least 12 characters");
        long id;var kh=new org.springframework.jdbc.support.GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement("INSERT INTO supplier_user(supplier_id,username,password_hash,enabled) VALUES (?,?,?,?)",new String[]{"id"});p.setLong(1,r.supplierId());p.setString(2,r.username());p.setString(3,encoder.encode(r.password()));p.setBoolean(4,r.enabled()==null||r.enabled());return p;},kh);id=kh.getKey().longValue();return Map.of("id",id,"username",r.username());
    }
    @PutMapping("/api/admin/supplier-users/{id}") public void update(@PathVariable long id,@Valid @RequestBody Account r){if(r.password()!=null&&!r.password().isBlank())db.update("UPDATE supplier_user SET supplier_id=?,username=?,password_hash=?,enabled=?,credentials_version=credentials_version+1 WHERE id=?",r.supplierId(),r.username(),encoder.encode(r.password()),r.enabled()==null||r.enabled(),id);else db.update("UPDATE supplier_user SET supplier_id=?,username=?,enabled=? WHERE id=?",r.supplierId(),r.username(),r.enabled()==null||r.enabled(),id);db.update("DELETE FROM supplier_session WHERE supplier_user_id=?",id);}
}
