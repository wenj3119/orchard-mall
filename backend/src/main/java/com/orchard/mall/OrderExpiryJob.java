package com.orchard.mall;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpiryJob {
    private final JdbcTemplate db;
    private final OrderService orders;
    public OrderExpiryJob(JdbcTemplate db,OrderService orders) { this.db=db;this.orders=orders; }
    @Scheduled(fixedDelayString="${app.order-expiry-scan-ms:30000}")
    public void scan() {
        var ids=db.query("SELECT id FROM sales_order WHERE status IN ('PENDING_PAYMENT','CLOSE_PENDING') AND expires_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 100",
            (rs,n)->rs.getLong(1));
        for(long id:ids) {
            try { orders.closeExpired(id); }
            catch(Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Order expiry retry needed for id {}",id,e); }
        }
    }
}
