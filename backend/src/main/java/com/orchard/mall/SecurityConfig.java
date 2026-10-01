package com.orchard.mall;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JdbcTemplate db,ObjectMapper mapper,
            @org.springframework.beans.factory.annotation.Value("${app.cors-origin}") String origin) throws Exception {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization","Content-Type"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        http.csrf(c -> c.disable()).cors(c -> c.configurationSource(source))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request,response,exception) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    mapper.writeValue(response.getWriter(),Map.of(
                        "code","UNAUTHORIZED","message","请先登录"));
                })
                .accessDeniedHandler((request,response,exception) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    mapper.writeValue(response.getWriter(),Map.of(
                        "code","FORBIDDEN","message","没有权限执行此操作"));
                }))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/auth/login", "/api/supplier/auth/login", "/api/dev/consumer-login", "/api/public/**", "/api/media/**", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers("/api/auth/logout").authenticated()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/customer/**").hasRole("CUSTOMER")
                .requestMatchers("/api/supplier/**").hasRole("SUPPLIER")
                .anyRequest().denyAll())
            .addFilterBefore(new TokenFilter(db), BasicAuthenticationFilter.class);
        return http.build();
    }
    static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static class TokenFilter extends OncePerRequestFilter {
        private final JdbcTemplate db;
        TokenFilter(JdbcTemplate db) { this.db = db; }
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String header = req.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String hash = digest(header.substring(7));
                var names = db.query("SELECT u.username FROM admin_session s JOIN admin_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>? AND u.enabled=TRUE",
                    (rs, n) -> rs.getString(1), hash, LocalDateTime.now());
                if (!names.isEmpty()) SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(names.getFirst(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                else {
                    var consumers = db.query("SELECT s.consumer_id FROM consumer_session s WHERE s.token_hash=? AND s.expires_at>?",
                        (rs,n) -> rs.getLong(1), hash, LocalDateTime.now());
                    if (!consumers.isEmpty()) SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(consumers.getFirst().toString(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
                    else {
                        var suppliers=db.query("SELECT u.id,u.supplier_id FROM supplier_session s JOIN supplier_user u ON u.id=s.supplier_user_id JOIN supplier p ON p.id=u.supplier_id WHERE s.token_hash=? AND s.expires_at>? AND s.credentials_version=u.credentials_version AND u.enabled=TRUE AND p.enabled=TRUE",
                            (rs,n)->"supplier:"+rs.getLong(2)+":"+rs.getLong(1),hash,LocalDateTime.now());
                        if(!suppliers.isEmpty()) SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(suppliers.getFirst(),null,List.of(new SimpleGrantedAuthority("ROLE_SUPPLIER"))));
                    }
                }
            }
            chain.doFilter(req, res);
        }
    }
}
