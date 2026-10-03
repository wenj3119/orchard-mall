package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

@RestController
public class WechatLoginController {
    private final JdbcTemplate db;
    private final WechatCodeExchange exchange;
    private final TransactionTemplate transactions;

    public WechatLoginController(JdbcTemplate db, WechatCodeExchange exchange, PlatformTransactionManager manager) {
        this.db = db;
        this.exchange = exchange;
        this.transactions = new TransactionTemplate(manager);
    }

    public record Login(@NotBlank @Size(max = 256) String code) {}

    @PostMapping("/api/wechat/auth/login")
    public Map<String, Object> login(@Valid @RequestBody Login input) {
        String appId = exchange.appId();
        String openId = exchange.openId(input.code());
        long consumerId = findOrCreate(appId, openId);
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        db.update("INSERT INTO consumer_session (consumer_id,token_hash,expires_at) VALUES (?,?,?)",
            consumerId, SecurityConfig.digest(token), LocalDateTime.now().plusDays(7));
        return Map.of("token", token, "consumerId", consumerId, "platform", "WECHAT");
    }

    private long findOrCreate(String appId, String openId) {
        try {
            return transactions.execute(status -> {
                Long existing = find(appId, openId);
                if (existing != null) return existing;
                var key = new GeneratedKeyHolder();
                db.update(c -> c.prepareStatement("INSERT INTO consumer_account (created_at) VALUES (CURRENT_TIMESTAMP)", new String[]{"id"}), key);
                long consumerId = key.getKey().longValue();
                db.update("INSERT INTO consumer_identity (consumer_id,platform,app_id,platform_user_id) VALUES (?,?,?,?)",
                    consumerId, "WECHAT", appId, openId);
                return consumerId;
            });
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent login can win the unique (platform, app_id, platform_user_id) insert.
            // The losing transaction rolls back its new consumer before this lookup.
            Long existing = find(appId, openId);
            if (existing != null) return existing;
            throw duplicate;
        }
    }

    private Long find(String appId, String openId) {
        var ids = db.query("SELECT consumer_id FROM consumer_identity WHERE platform=? AND app_id=? AND platform_user_id=?",
            (rs, n) -> rs.getLong(1), "WECHAT", appId, openId);
        return ids.isEmpty() ? null : ids.getFirst();
    }
}
