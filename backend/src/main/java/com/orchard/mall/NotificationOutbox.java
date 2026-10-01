package com.orchard.mall;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

interface NotificationAdapter { void deliver(String idempotencyKey,String eventType,String payload);String capability(); }

@Component class DevelopmentNotificationAdapter implements NotificationAdapter {
    private final Set<String> delivered=ConcurrentHashMap.newKeySet();
    public void deliver(String key,String type,String payload){delivered.add(key);org.slf4j.LoggerFactory.getLogger(getClass()).info("Development in-app notification delivered: type={}, key={}",type,key);}
    public String capability(){return "IN_APP_DEVELOPMENT_ONLY_NO_SMS_OR_PLATFORM_SUBSCRIPTION";}
}

@Component
public class NotificationOutbox {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final NotificationAdapter adapter;private final String worker=UUID.randomUUID().toString();private final int configuredMax;
    public NotificationOutbox(JdbcTemplate db,PlatformTransactionManager manager,NotificationAdapter adapter,@Value("${app.notification-max-attempts:8}") int max){this.db=db;this.tx=new TransactionTemplate(manager);this.adapter=adapter;this.configuredMax=max;}
    record Claimed(long id,String eventType,String key,String payload,int attempt,int max){}
    @Scheduled(fixedDelayString="${app.notification-scan-ms:5000}") public void scan(){for(int i=0;i<20;i++){var c=claim();if(c==null)return;try{adapter.deliver(c.key(),c.eventType(),c.payload());db.update("UPDATE notification_outbox SET status='SENT',completed_at=CURRENT_TIMESTAMP,lease_until=NULL,worker_id=NULL,last_error=NULL WHERE id=? AND worker_id=?",c.id(),worker);}catch(Exception e){int delay=Math.min(3600,1<<Math.min(c.attempt(),10));String error=e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),"");if(error.length()>240)error=error.substring(0,240);db.update("UPDATE notification_outbox SET status=?,next_attempt_at=?,lease_until=NULL,worker_id=NULL,last_error=? WHERE id=? AND worker_id=?",c.attempt()>=c.max()?"DEAD":"PENDING",LocalDateTime.now().plusSeconds(delay),error,c.id(),worker);}}}
    private Claimed claim(){return tx.execute(s->{
        var ids=db.query("SELECT id FROM notification_outbox WHERE ((status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP) OR (status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP)) ORDER BY id LIMIT 10",(rs,n)->rs.getLong(1));
        for(long id:ids){int n=db.update("UPDATE notification_outbox SET status='PROCESSING',attempt_count=attempt_count+1,lease_until=?,worker_id=? WHERE id=? AND ((status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP) OR (status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP))",LocalDateTime.now().plusMinutes(2),worker,id);if(n==1)return db.query("SELECT id,event_type,idempotency_key,payload_json,attempt_count,LEAST(max_attempts,?) FROM notification_outbox WHERE id=?",(rs,x)->new Claimed(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getInt(6)),configuredMax,id).getFirst();}
        return null;
    });}
    public String capability(){return adapter.capability();}
}
