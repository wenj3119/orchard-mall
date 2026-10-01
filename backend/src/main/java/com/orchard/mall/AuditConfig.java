package com.orchard.mall;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuditConfig implements WebMvcConfigurer {
    private final JdbcTemplate db;
    public AuditConfig(JdbcTemplate db) { this.db=db; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                if("GET".equals(request.getMethod()) || response.getStatus()>=400) return;
                var auth=SecurityContextHolder.getContext().getAuthentication();
                if(auth==null || !auth.isAuthenticated() || !request.getRequestURI().startsWith("/api/admin/")) return;
                db.update("INSERT INTO audit_log (username,method,path,status_code) VALUES (?,?,?,?)",
                    auth.getName(),request.getMethod(),request.getRequestURI(),response.getStatus());
            }
        }).addPathPatterns("/api/admin/**");
    }
}
