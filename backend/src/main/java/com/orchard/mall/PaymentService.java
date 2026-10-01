package com.orchard.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class PaymentService {
    private final JdbcTemplate db;
    private final PaymentAdapters adapters;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final String merchantSubject;
    public PaymentService(JdbcTemplate db,PaymentAdapters adapters,PlatformTransactionManager manager,ObjectMapper json,
            @Value("${app.payment-merchant-subject:orchard-mall-local}") String merchantSubject) {
        this.db=db;this.adapters=adapters;this.tx=new TransactionTemplate(manager);this.json=json;this.merchantSubject=merchantSubject;
    }
    public record AttemptView(long paymentId,String paymentNo,long attemptId,String attemptNo,String channel,
                              String status,long amountFen,String currency,String clientToken) {}
    private record Prepared(AttemptView view,boolean callCreate) {}
    public record ProcessResult(String status,boolean duplicate,String detail) {}
    private record ReceivedEvent(long id,String status,boolean duplicate) {}
    private long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        db.update(c->{var p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);
        return key.getKey().longValue();
    }
    public AttemptView create(long consumer,long orderId,String requestedChannel) {
        String channel=requestedChannel.toUpperCase(Locale.ROOT);
        PaymentAdapter adapter=adapters.require(channel);
        Prepared prepared=tx.execute(s->{
            var orders=db.query("SELECT order_no,payable_amount_fen,status,payment_status FROM sales_order WHERE id=? AND consumer_id=? FOR UPDATE",
                (rs,n)->new Object[]{rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4)},orderId,consumer);
            if(orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var o=orders.getFirst();
            if(!"PENDING_PAYMENT".equals(o[2])) throw new ResponseStatusException(HttpStatus.CONFLICT,"Order is not payable");
            long paymentId;String paymentNo;
            var pays=db.query("SELECT id,payment_no FROM payment_order WHERE sales_order_id=?",(rs,n)->new Object[]{rs.getLong(1),rs.getString(2)},orderId);
            if(pays.isEmpty()) {
                paymentNo="PAY"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
                paymentId=insert("INSERT INTO payment_order(sales_order_id,payment_no,amount_fen,currency,merchant_subject,status) VALUES (?,?,?,'CNY',?,'PENDING')",
                    orderId,paymentNo,o[1],merchantSubject);
            } else {paymentId=(long)pays.getFirst()[0];paymentNo=(String)pays.getFirst()[1];}
            var active=db.query("SELECT id,attempt_no,channel,status FROM payment_attempt WHERE payment_order_id=? AND status IN ('CREATED','PENDING','UNKNOWN') ORDER BY id DESC FOR UPDATE",
                (rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)},paymentId);
            if(!active.isEmpty()) {
                var a=active.getFirst();
                if(!channel.equals(a[2])) throw new ResponseStatusException(HttpStatus.CONFLICT,"Close and verify the active payment attempt before switching channel");
                return new Prepared(new AttemptView(paymentId,paymentNo,(long)a[0],(String)a[1],channel,(String)a[3],(long)o[1],"CNY",null),false);
            }
            String attemptNo="ATT"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
            long attemptId=insert("INSERT INTO payment_attempt(payment_order_id,attempt_no,channel,status) VALUES (?,?,?,'CREATED')",paymentId,attemptNo,channel);
            db.update("UPDATE sales_order SET payment_status='PENDING',row_version=row_version+1 WHERE id=?",orderId);
            return new Prepared(new AttemptView(paymentId,paymentNo,attemptId,attemptNo,channel,"CREATED",(long)o[1],"CNY",null),true);
        });
        if(!prepared.callCreate()) return prepared.view();
        var v=prepared.view();
        PaymentAdapter.CreateResult result;
        try { result=adapter.create(new PaymentAdapter.CreateCommand(v.paymentNo(),v.attemptNo(),v.amountFen(),v.currency(),merchantSubject)); }
        catch(RuntimeException e) {
            tx.executeWithoutResult(s->db.update("UPDATE payment_attempt SET status='UNKNOWN',failure_code='CREATE_UNCERTAIN',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='CREATED'",v.attemptId()));
            throw e;
        }
        tx.executeWithoutResult(s->db.update("UPDATE payment_attempt SET status=?,channel_transaction_no=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='CREATED'",
            result.status(),result.channelTransactionNo(),v.attemptId()));
        return new AttemptView(v.paymentId(),v.paymentNo(),v.attemptId(),v.attemptNo(),v.channel(),result.status(),v.amountFen(),v.currency(),result.clientToken());
    }
    public ProcessResult processNotice(String channel,PaymentAdapter.PaymentNotice notice) {
        ReceivedEvent event=receiveEvent(channel,notice);
        if("PROCESSED".equals(event.status())) return new ProcessResult("DUPLICATE",true,"Already processed");
        if("REJECTED".equals(event.status())) return new ProcessResult("REJECTED",true,"Previously rejected");
        try { return tx.execute(s->processLocked(event.id(),channel,notice)); }
        catch(RuntimeException e) {
            String failure=e.getClass().getSimpleName()+":"+Objects.toString(e.getMessage(),"");
            if(failure.length()>240)failure=failure.substring(0,240);
            String recorded=failure;
            tx.executeWithoutResult(s->db.update("UPDATE payment_event SET processing_status='RECEIVED',attempt_count=attempt_count+1,next_attempt_at=?,failure_reason=? WHERE id=? AND processing_status<>'PROCESSED'",LocalDateTime.now().plusSeconds(5),recorded,event.id()));
            throw e;
        }
    }
    private ReceivedEvent receiveEvent(String channel,PaymentAdapter.PaymentNotice n) {
        String payload;
        try {payload=json.writeValueAsString(n);}catch(Exception e){throw new IllegalArgumentException("Cannot persist payment event",e);}
        String hash=SecurityConfig.digest(payload);
        try { return tx.execute(s->{
            var existing=db.query("SELECT id,payload_hash,processing_status FROM payment_event WHERE channel=? AND event_key=?",
                (rs,x)->new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3)},channel,n.eventKey());
            if(!existing.isEmpty()) {
                var e=existing.getFirst();
                if(!Objects.equals(e[1],hash))throw new ResponseStatusException(HttpStatus.CONFLICT,"Event key was used with a different payload");
                return new ReceivedEvent((long)e[0],(String)e[2],true);
            }
            var attempt=db.query("SELECT a.id,a.payment_order_id FROM payment_attempt a WHERE a.attempt_no=? AND a.channel=?",
                (rs,x)->new long[]{rs.getLong(1),rs.getLong(2)},n.attemptNo(),channel);
            if(attempt.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment attempt not found");
            long id=insert("INSERT INTO payment_event(payment_order_id,payment_attempt_id,channel,event_key,event_type,processing_status,payload_hash,payload_json) VALUES (?,?,?,?,?,'RECEIVED',?,?)",
                attempt.getFirst()[1],attempt.getFirst()[0],channel,n.eventKey(),n.status(),hash,payload);
            return new ReceivedEvent(id,"RECEIVED",false);
        }); } catch(DataIntegrityViolationException race) {
            var existing=db.query("SELECT id,payload_hash,processing_status FROM payment_event WHERE channel=? AND event_key=?",
                (rs,x)->new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3)},channel,n.eventKey());
            if(existing.isEmpty()||!Objects.equals(existing.getFirst()[1],hash))throw race;
            return new ReceivedEvent((long)existing.getFirst()[0],(String)existing.getFirst()[2],true);
        }
    }
    private ProcessResult processLocked(long eventId,String channel,PaymentAdapter.PaymentNotice n) {
        var discovered=db.query("SELECT p.sales_order_id FROM payment_attempt a JOIN payment_order p ON p.id=a.payment_order_id WHERE a.attempt_no=? AND a.channel=?",
            (rs,x)->rs.getLong(1),n.attemptNo(),channel);
        if(discovered.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment attempt not found");
        long discoveredOrderId=discovered.getFirst();
        var order=db.query("SELECT status,payment_status FROM sales_order WHERE id=? FOR UPDATE",(rs,x)->new String[]{rs.getString(1),rs.getString(2)},discoveredOrderId).getFirst();
        var attempts=db.query("SELECT a.id,a.payment_order_id,a.status,p.sales_order_id,p.amount_fen,p.currency,p.merchant_subject,p.status FROM payment_attempt a JOIN payment_order p ON p.id=a.payment_order_id WHERE a.attempt_no=? AND a.channel=? FOR UPDATE",
            (rs,x)->new Object[]{rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getLong(4),rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8)},n.attemptNo(),channel);
        if(attempts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment attempt not found");
        var a=attempts.getFirst();long attemptId=(long)a[0],paymentId=(long)a[1],orderId=(long)a[3];
        var events=db.query("SELECT processing_status,payload_hash FROM payment_event WHERE id=? FOR UPDATE",(rs,x)->new String[]{rs.getString(1),rs.getString(2)},eventId);
        if(events.isEmpty())throw new IllegalStateException("Received payment event disappeared");
        if("PROCESSED".equals(events.getFirst()[0]))return new ProcessResult("DUPLICATE",true,"Already processed");
        db.update("UPDATE payment_event SET processing_status='PROCESSING',attempt_count=attempt_count+1,failure_reason=NULL WHERE id=?",eventId);
        if((long)a[4]!=n.amountFen() || !Objects.equals(a[5],n.currency()) || !Objects.equals(a[6],n.merchantSubject())) {
            db.update("UPDATE payment_event SET processing_status='REJECTED',failure_reason='AMOUNT_CURRENCY_OR_SUBJECT_MISMATCH',processed_at=CURRENT_TIMESTAMP WHERE id=?",eventId);
            return new ProcessResult("REJECTED",false,"Amount, currency or merchant subject mismatch");
        }
        if("UNKNOWN".equals(n.status())) {
            db.update("UPDATE payment_attempt SET status='UNKNOWN',channel_transaction_no=COALESCE(channel_transaction_no,?),updated_at=CURRENT_TIMESTAMP WHERE id=?",n.channelTransactionNo(),attemptId);
            db.update("UPDATE sales_order SET payment_status='UNKNOWN',row_version=row_version+1 WHERE id=? AND status IN ('PENDING_PAYMENT','CLOSE_PENDING')",orderId);
            completeEvent(eventId);return new ProcessResult("UNKNOWN",false,"Awaiting reconciliation");
        }
        if("FAILED".equals(n.status())||"CLOSED".equals(n.status())) {
            db.update("UPDATE payment_attempt SET status=?,channel_transaction_no=COALESCE(channel_transaction_no,?),updated_at=CURRENT_TIMESTAMP WHERE id=?",n.status(),n.channelTransactionNo(),attemptId);
            db.update("UPDATE sales_order SET payment_status='UNPAID',row_version=row_version+1 WHERE id=? AND status='PENDING_PAYMENT'",orderId);
            completeEvent(eventId);return new ProcessResult(n.status(),false,"Terminal unpaid result");
        }
        if(!"SUCCESS".equals(n.status())) { db.update("UPDATE payment_event SET processing_status='REJECTED',failure_reason='UNSUPPORTED_STATUS',processed_at=CURRENT_TIMESTAMP WHERE id=?",eventId);return new ProcessResult("REJECTED",false,"Unsupported status"); }
        boolean sameSuccess="SUCCEEDED".equals(a[2]) && Objects.equals(db.queryForObject("SELECT channel_transaction_no FROM payment_attempt WHERE id=?",String.class,attemptId),n.channelTransactionNo());
        if("PAID".equals(order[1])) {
            if(!sameSuccess) anomaly(paymentId,attemptId,"MULTIPLE_SUCCESS",n,"Another collection already paid the order");
            completeEvent(eventId);return new ProcessResult(sameSuccess?"SUCCESS":"ANOMALY",false,sameSuccess?"Already applied":"Multiple successful collections");
        }
        int held=db.queryForObject("SELECT COUNT(*) FROM stock_reservation WHERE order_id=? AND status='HELD'",Integer.class,orderId);
        if(!List.of("PENDING_PAYMENT","CLOSE_PENDING").contains(order[0]) || held==0) {
            db.update("UPDATE payment_attempt SET status='SUCCEEDED',channel_transaction_no=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",n.channelTransactionNo(),attemptId);
            db.update("UPDATE payment_order SET status='EXCEPTION',updated_at=CURRENT_TIMESTAMP WHERE id=?",paymentId);
            db.update("UPDATE sales_order SET payment_status='EXCEPTION',row_version=row_version+1 WHERE id=?",orderId);
            anomaly(paymentId,attemptId,"SUCCESS_AFTER_RELEASE",n,"Order was closed or inventory reservation was released");
            completeEvent(eventId);return new ProcessResult("ANOMALY",false,"Collected after order closure; refund/manual review required");
        }
        var reservations=db.query("SELECT id,supply_id,quantity FROM stock_reservation WHERE order_id=? AND status='HELD' ORDER BY supply_id FOR UPDATE",
            (rs,x)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3)},orderId);
        for(var r:reservations) {
            if(db.update("UPDATE supply_inventory SET on_hand_qty=on_hand_qty-?,reserved_qty=reserved_qty-? WHERE supply_id=? AND on_hand_qty>=? AND reserved_qty>=?",r[2],r[2],r[1],r[2],r[2])!=1)
                throw new IllegalStateException("Reserved inventory invariant violated");
            db.update("UPDATE stock_reservation SET status='SOLD' WHERE id=? AND status='HELD'",r[0]);
            db.update("INSERT INTO inventory_movement(supply_id,order_id,delta_on_hand,delta_reserved,reason,actor) VALUES (?,?,?,?,?,?)",r[1],orderId,-r[2],-r[2],"支付确认转已售","payment:"+n.attemptNo());
        }
        db.update("UPDATE payment_attempt SET status='SUCCEEDED',channel_transaction_no=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",n.channelTransactionNo(),attemptId);
        db.update("UPDATE payment_order SET status='PAID',updated_at=CURRENT_TIMESTAMP WHERE id=?",paymentId);
        db.update("UPDATE sales_order SET status='PAID',payment_status='PAID',fulfillment_status='PENDING',row_version=row_version+1 WHERE id=?",orderId);
        createTasks(orderId);
        completeEvent(eventId);
        return new ProcessResult("SUCCESS",false,"Payment confirmed");
    }
    private void createTasks(long orderId) {
        var groups=db.query("SELECT id,supplier_id FROM order_group WHERE order_id=? ORDER BY id",(rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},orderId);
        for(var g:groups) {
            var existing=db.query("SELECT id FROM fulfillment_task WHERE order_group_id=?",(rs,n)->rs.getLong(1),g[0]);
            long taskId;
            if(existing.isEmpty()) taskId=insert("INSERT INTO fulfillment_task(task_no,sales_order_id,order_group_id,supplier_id,status) VALUES (?,?,?,?,'PENDING_ACCEPTANCE')",
                "FUL"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT),orderId,g[0],g[1]); else taskId=existing.getFirst();
            db.update("INSERT INTO fulfillment_task_item(task_id,order_item_id,product_title,sku_code,spec_json,required_qty) SELECT ?,id,product_title,sku_code,spec_json,quantity FROM order_item WHERE group_id=? AND NOT EXISTS(SELECT 1 FROM fulfillment_task_item x WHERE x.task_id=? AND x.order_item_id=order_item.id)",taskId,g[0],taskId);
            db.update("INSERT INTO supplier_ledger_entry(supplier_id,sales_order_id,order_item_id,entry_type,source_key,quantity,amount_fen,status,description) SELECT supplier_id,order_id,id,'GOODS',CONCAT('goods:',id),quantity,supply_price_fen*quantity,'FROZEN','按下单供货价快照计提货款' FROM order_item i WHERE group_id=? AND NOT EXISTS(SELECT 1 FROM supplier_ledger_entry l WHERE l.source_key=CONCAT('goods:',i.id))",g[0]);
            db.update("INSERT INTO supplier_ledger_entry(supplier_id,sales_order_id,entry_type,source_key,amount_fen,status,description) SELECT supplier_id,order_id,'SHIPPING',CONCAT('shipping:',id),shipping_fee_fen,'FROZEN','下单履约分组约定运费' FROM order_group og WHERE id=? AND NOT EXISTS(SELECT 1 FROM supplier_ledger_entry l WHERE l.source_key=CONCAT('shipping:',og.id))",g[0]);
            try { db.update("INSERT INTO notification_outbox(event_type,aggregate_type,aggregate_id,idempotency_key,payload_json) VALUES ('FULFILLMENT_TASK_CREATED','FULFILLMENT_TASK',?,?,?)",taskId,"fulfillment-created:"+taskId,json.writeValueAsString(Map.of("taskId",taskId,"supplierId",g[1]))); }
            catch(Exception e){throw new IllegalStateException(e);}
        }
    }
    private void anomaly(long paymentId,long attemptId,String type,PaymentAdapter.PaymentNotice n,String detail) {
        db.update("INSERT INTO payment_anomaly(payment_order_id,payment_attempt_id,anomaly_type,channel_transaction_no,amount_fen,detail) VALUES (?,?,?,?,?,?)",paymentId,attemptId,type,n.channelTransactionNo(),n.amountFen(),detail);
    }
    private void completeEvent(long id){db.update("UPDATE payment_event SET processing_status='PROCESSED',processed_at=CURRENT_TIMESTAMP WHERE id=?",id);}

    @Scheduled(fixedDelayString="${app.payment-event-recovery-ms:10000}")
    public void recoverReceivedEvents() {
        var rows=db.query("SELECT id,channel,payload_json FROM payment_event WHERE processing_status='RECEIVED' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 50",
            (rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3)});
        for(var row:rows) try {
            var notice=json.readValue((String)row[2],PaymentAdapter.PaymentNotice.class);
            tx.execute(s->processLocked((long)row[0],(String)row[1],notice));
        } catch(Exception e) {
            String failure=e.getClass().getSimpleName()+":"+Objects.toString(e.getMessage(),"");if(failure.length()>240)failure=failure.substring(0,240);
            db.update("UPDATE payment_event SET processing_status='RECEIVED',next_attempt_at=?,failure_reason=? WHERE id=? AND processing_status<>'PROCESSED'",LocalDateTime.now().plusSeconds(30),failure,row[0]);
        }
    }

    public void coordinateClose(Long consumer,long orderId,String finalStatus) {
        record Active(long id,String attemptNo,String channel,String transactionNo){}
        var active=tx.execute(s->{
            String owner=consumer==null?"":" AND consumer_id=?";
            Object[] args=consumer==null?new Object[]{orderId}:new Object[]{orderId,consumer};
            var rows=db.query("SELECT status FROM sales_order WHERE id=?"+owner+" FOR UPDATE",(rs,n)->rs.getString(1),args);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if(!List.of("PENDING_PAYMENT","CLOSE_PENDING").contains(rows.getFirst())) return List.<Active>of();
            var attempts=db.query("SELECT a.id,a.attempt_no,a.channel,a.channel_transaction_no FROM payment_attempt a JOIN payment_order p ON p.id=a.payment_order_id WHERE p.sales_order_id=? AND a.status IN ('CREATED','PENDING','UNKNOWN') ORDER BY a.id FOR UPDATE",
                (rs,n)->new Active(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),orderId);
            if(attempts.isEmpty()) { closeAndRelease(orderId,finalStatus);return List.<Active>of(); }
            db.update("UPDATE sales_order SET status='CLOSE_PENDING',payment_status='CLOSE_PENDING',row_version=row_version+1 WHERE id=? AND status='PENDING_PAYMENT'",orderId);
            return attempts;
        });
        if(active==null||active.isEmpty()) return;
        boolean allClosed=true;
        for(var a:active) {
            PaymentAdapter.QueryResult result=adapters.require(a.channel()).close(a.attemptNo(),a.transactionNo());
            if(!"CLOSED".equals(result.status())&&!"FAILED".equals(result.status())) {allClosed=false;tx.executeWithoutResult(s->db.update("UPDATE payment_attempt SET status='UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE id=?",a.id()));}
            else tx.executeWithoutResult(s->db.update("UPDATE payment_attempt SET status='CLOSED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status<>'SUCCEEDED'",a.id()));
        }
        if(allClosed) tx.executeWithoutResult(s->{
            int remaining=db.queryForObject("SELECT COUNT(*) FROM payment_attempt a JOIN payment_order p ON p.id=a.payment_order_id WHERE p.sales_order_id=? AND a.status IN ('CREATED','PENDING','UNKNOWN','SUCCEEDED')",Integer.class,orderId);
            if(remaining==0) closeAndRelease(orderId,finalStatus);
        });
    }
    private void closeAndRelease(long orderId,String finalStatus) {
        if(db.update("UPDATE sales_order SET status=?,payment_status='UNPAID',closed_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=? AND status IN ('PENDING_PAYMENT','CLOSE_PENDING')",finalStatus,orderId)!=1)return;
        var held=db.query("SELECT id,supply_id,quantity FROM stock_reservation WHERE order_id=? AND status='HELD' ORDER BY supply_id FOR UPDATE",(rs,n)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3)},orderId);
        for(var r:held) if(db.update("UPDATE stock_reservation SET status='RELEASED' WHERE id=? AND status='HELD'",r[0])==1) {
            if(db.update("UPDATE supply_inventory SET reserved_qty=reserved_qty-? WHERE supply_id=? AND reserved_qty>=?",r[2],r[1],r[2])!=1)throw new IllegalStateException("Reservation exceeds inventory");
            db.update("INSERT INTO inventory_movement(supply_id,order_id,delta_on_hand,delta_reserved,reason,actor) VALUES (?,?,0,?,?,?)",r[1],orderId,-r[2],"CANCELLED".equals(finalStatus)?"订单取消":"订单超时","system");
        }
    }
}
