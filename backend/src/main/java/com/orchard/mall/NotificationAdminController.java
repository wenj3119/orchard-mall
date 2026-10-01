package com.orchard.mall;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class NotificationAdminController {
    private final JdbcTemplate db;private final NotificationOutbox outbox;public NotificationAdminController(JdbcTemplate db,NotificationOutbox outbox){this.db=db;this.outbox=outbox;}
    @GetMapping("/api/admin/notification-events") public Map<String,Object> list(){var rows=db.query("SELECT id,event_type,aggregate_type,aggregate_id,status,attempt_count,max_attempts,next_attempt_at,last_error,created_at FROM notification_outbox ORDER BY id DESC LIMIT 200",(rs,n)->{var m=new LinkedHashMap<String,Object>();m.put("id",rs.getLong(1));m.put("eventType",rs.getString(2));m.put("aggregateType",rs.getString(3));m.put("aggregateId",rs.getLong(4));m.put("status",rs.getString(5));m.put("attemptCount",rs.getInt(6));m.put("maxAttempts",rs.getInt(7));m.put("nextAttemptAt",rs.getTimestamp(8).toLocalDateTime().toString());m.put("lastError",rs.getString(9));m.put("createdAt",rs.getTimestamp(10).toLocalDateTime().toString());return m;});return Map.of("capability",outbox.capability(),"events",rows);}
    @PostMapping("/api/admin/notification-events/{id}/retry") @ResponseStatus(HttpStatus.NO_CONTENT) public void retry(@PathVariable long id){int n=db.update("UPDATE notification_outbox SET status='PENDING',attempt_count=0,next_attempt_at=CURRENT_TIMESTAMP,lease_until=NULL,worker_id=NULL,last_error=NULL WHERE id=? AND status IN ('DEAD','PENDING')",id);if(n==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending or exhausted events can be retried");}
}
