package com.orchard.mall;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
public class AuditController {
    private final JdbcTemplate db;
    public AuditController(JdbcTemplate db) { this.db=db; }
    @GetMapping("/api/admin/audit-logs")
    public List<Map<String,Object>> recent() {
        return db.query("SELECT id,username,method,path,status_code,created_at FROM audit_log ORDER BY id DESC LIMIT 100",
            (rs,n)->Map.of("id",rs.getLong(1),"username",rs.getString(2),"method",rs.getString(3),
                "path",rs.getString(4),"statusCode",rs.getInt(5),"createdAt",rs.getTimestamp(6).toLocalDateTime().toString()));
    }
}
