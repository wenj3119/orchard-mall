package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.SecureRandom;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

@RestController
public class ConsumerAuthController {
    private final JdbcTemplate db;
    private final boolean enabled;
    public ConsumerAuthController(JdbcTemplate db, Environment environment,
            @Value("${app.dev-consumer-login-enabled:false}") boolean enabled) {
        this.db=db;
        this.enabled=enabled;
        if (enabled && (!environment.acceptsProfiles(Profiles.of("dev","test"))
                || environment.acceptsProfiles(Profiles.of("prod"))))
            throw new IllegalStateException("Development consumer login is forbidden outside dev/test or with prod profile");
    }
    public record DevLogin(@Pattern(regexp="WECHAT|ALIPAY") String platform,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,80}") String externalUserId) {}
    @PostMapping("/api/dev/consumer-login")
    public Map<String,Object> devLogin(@Valid @RequestBody DevLogin input) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var ids=db.query("SELECT consumer_id FROM consumer_identity WHERE platform=? AND app_id='DEV' AND platform_user_id=?",
            (rs,n)->rs.getLong(1),input.platform(),input.externalUserId());
        long consumerId;
        if (ids.isEmpty()) {
            var key=new GeneratedKeyHolder();
            db.update(c->c.prepareStatement("INSERT INTO consumer_account (created_at) VALUES (CURRENT_TIMESTAMP)",new String[]{"id"}),key);
            consumerId=key.getKey().longValue();
            db.update("INSERT INTO consumer_identity (consumer_id,platform,app_id,platform_user_id) VALUES (?,?,?,?)",
                consumerId,input.platform(),"DEV",input.externalUserId());
        } else consumerId=ids.getFirst();
        byte[] random=new byte[32];
        new SecureRandom().nextBytes(random);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        db.update("INSERT INTO consumer_session (consumer_id,token_hash,expires_at) VALUES (?,?,?)",
            consumerId,SecurityConfig.digest(token),LocalDateTime.now().plusDays(7));
        return Map.of("token",token,"consumerId",consumerId,"platform",input.platform());
    }
    @GetMapping("/api/customer/me")
    public Map<String,Object> me(Authentication auth) {
        long consumerId=Long.parseLong(auth.getName());
        var identities=db.query("SELECT platform,app_id AS appId FROM consumer_identity WHERE consumer_id=?",
            (rs,n)->Map.of("platform",rs.getString(1),"appId",rs.getString(2)),consumerId);
        return Map.of("consumerId",consumerId,"identities",identities);
    }
}
