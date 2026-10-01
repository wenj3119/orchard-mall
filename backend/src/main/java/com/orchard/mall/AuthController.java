package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    public AuthController(JdbcTemplate db, PasswordEncoder encoder) { this.db = db; this.encoder = encoder; }
    public record Login(@NotBlank String username, @NotBlank String password) {}
    @PostMapping("/login")
    public Map<String,String> login(@Valid @RequestBody Login input) {
        var users = db.query("SELECT id,password_hash FROM admin_user WHERE username=? AND enabled=TRUE",
            (rs,n) -> Map.of("id", rs.getString(1), "hash", rs.getString(2)), input.username());
        if (users.isEmpty() || !encoder.matches(input.password(), users.getFirst().get("hash")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        db.update("INSERT INTO admin_session (user_id,token_hash,expires_at) VALUES (?,?,?)",
            Long.parseLong(users.getFirst().get("id")), SecurityConfig.digest(token), LocalDateTime.now().plusHours(12));
        return Map.of("token", token);
    }
    @PostMapping("/logout")
    public void logout(@RequestHeader("Authorization") String header) {
        if (header.startsWith("Bearer ")) db.update("DELETE FROM admin_session WHERE token_hash=?", SecurityConfig.digest(header.substring(7)));
    }
    @Bean
    CommandLineRunner createInitialAdmin(JdbcTemplate db, PasswordEncoder encoder,
            @Value("${app.admin-init-password:}") String password) {
        return args -> {
            if (password.isBlank()) return;
            Integer count = db.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class);
            if (count != null && count == 0) {
                if (password.length() < 12) throw new IllegalStateException("ADMIN_INIT_PASSWORD must contain at least 12 characters");
                db.update("INSERT INTO admin_user (username,password_hash) VALUES (?,?)", "admin", encoder.encode(password));
            }
        };
    }
}
