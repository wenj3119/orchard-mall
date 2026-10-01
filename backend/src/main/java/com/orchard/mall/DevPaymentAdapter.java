package com.orchard.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

@Component
public class DevPaymentAdapter implements PaymentAdapter {
    private final boolean enabled;
    private final ObjectMapper json;
    private final JdbcTemplate db;
    public DevPaymentAdapter(Environment env,ObjectMapper json,JdbcTemplate db,
            @Value("${app.dev-payment-enabled:false}") boolean enabled) {
        this.enabled=enabled;this.json=json;this.db=db;
        var profiles=Set.of(env.getActiveProfiles());
        if(enabled && (profiles.stream().anyMatch(p->p.toLowerCase(Locale.ROOT).contains("prod"))
                || profiles.stream().noneMatch(p->p.equals("dev")||p.equals("test"))))
            throw new IllegalStateException("Development payment simulator is forbidden outside dev/test");
    }
    @Override public String channel(){ return "DEV_SIMULATOR"; }
    private void available(){ if(!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Development payment simulator is disabled"); }
    @Override public CreateResult create(CreateCommand c){ available();return new CreateResult("PENDING","DEV-"+c.attemptNo(),"DEV_ONLY"); }
    @Override public QueryResult query(String a,String t){ available();return new QueryResult("UNKNOWN",t); }
    @Override public QueryResult close(String a,String t){ available();String status=db.queryForObject("SELECT status FROM payment_attempt WHERE attempt_no=?",String.class,a);return new QueryResult("UNKNOWN".equals(status)?"UNKNOWN":"CLOSED",t); }
    @Override public PaymentNotice verifyAndParse(String payload,String signature) {
        available();
        if(!"dev-internal".equals(signature)) throw new IllegalArgumentException("Invalid simulator signature");
        try { return json.readValue(payload,PaymentNotice.class); }
        catch(Exception e){ throw new IllegalArgumentException("Invalid simulator notice",e); }
    }
}
