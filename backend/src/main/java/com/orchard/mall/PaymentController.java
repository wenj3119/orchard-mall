package com.orchard.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@RestController
public class PaymentController {
    private final PaymentService payments;private final PaymentAdapters adapters;private final JdbcTemplate db;private final ObjectMapper json;
    public PaymentController(PaymentService payments,PaymentAdapters adapters,JdbcTemplate db,ObjectMapper json){this.payments=payments;this.adapters=adapters;this.db=db;this.json=json;}
    record CreatePayment(@NotBlank String channel){}
    record Simulate(@NotBlank @Pattern(regexp="SUCCESS|FAILED|UNKNOWN") String result,String eventKey,
                    @Min(0) @Max(30000) Long delayMs,Long amountFen,String currency,String merchantSubject){}
    private long customer(Authentication a){return Long.parseLong(a.getName());}
    @PostMapping("/api/customer/orders/{id}/payments")
    public PaymentService.AttemptView create(Authentication a,@PathVariable long id,@Valid @RequestBody CreatePayment r){return payments.create(customer(a),id,r.channel());}
    @PostMapping("/api/customer/payment-attempts/{attemptNo}/simulate")
    public Map<String,Object> simulate(Authentication a,@PathVariable String attemptNo,@Valid @RequestBody Simulate r) throws Exception {
        var rows=db.query("SELECT a.channel,a.channel_transaction_no,p.amount_fen,p.currency,p.merchant_subject FROM payment_attempt a JOIN payment_order p ON p.id=a.payment_order_id JOIN sales_order o ON o.id=p.sales_order_id WHERE a.attempt_no=? AND o.consumer_id=?",
            (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5)},attemptNo,customer(a));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var x=rows.getFirst();if(!"DEV_SIMULATOR".equals(x[0]))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only simulator attempts can be simulated");
        String event=r.eventKey()==null||r.eventKey().isBlank()?"DEV-EVT-"+UUID.randomUUID():r.eventKey();
        var notice=new PaymentAdapter.PaymentNotice(event,attemptNo,r.result(),(String)x[1],r.amountFen()==null?(long)x[2]:r.amountFen(),r.currency()==null?(String)x[3]:r.currency(),r.merchantSubject()==null?(String)x[4]:r.merchantSubject());
        String payload=json.writeValueAsString(notice);
        PaymentAdapter adapter=adapters.require("DEV_SIMULATOR");
        long delay=r.delayMs()==null?0:r.delayMs();
        if(delay>0){CompletableFuture.delayedExecutor(delay,java.util.concurrent.TimeUnit.MILLISECONDS).execute(()->payments.processNotice("DEV_SIMULATOR",adapter.verifyAndParse(payload,"dev-internal")));return Map.of("status","SCHEDULED","eventKey",event,"delayMs",delay);}
        var result=payments.processNotice("DEV_SIMULATOR",adapter.verifyAndParse(payload,"dev-internal"));
        if("REJECTED".equals(result.status()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,result.detail());
        return Map.of("status",result.status(),"duplicate",result.duplicate(),"eventKey",event,"detail",result.detail());
    }
    @GetMapping("/api/admin/payments") public List<Map<String,Object>> adminPayments(){return db.query("SELECT p.id,p.payment_no,o.order_no,p.amount_fen,p.currency,p.status,p.created_at FROM payment_order p JOIN sales_order o ON o.id=p.sales_order_id ORDER BY p.id DESC LIMIT 200",(rs,n)->Map.of("id",rs.getLong(1),"paymentNo",rs.getString(2),"orderNo",rs.getString(3),"amountFen",rs.getLong(4),"currency",rs.getString(5),"status",rs.getString(6),"createdAt",rs.getTimestamp(7).toLocalDateTime().toString()));}
    @GetMapping("/api/admin/payment-anomalies") public List<Map<String,Object>> anomalies(){return db.query("SELECT a.id,p.payment_no,a.anomaly_type,a.channel_transaction_no,a.amount_fen,a.detail,a.status,a.created_at FROM payment_anomaly a JOIN payment_order p ON p.id=a.payment_order_id ORDER BY a.id DESC",(rs,n)->{var m=new LinkedHashMap<String,Object>();m.put("id",rs.getLong(1));m.put("paymentNo",rs.getString(2));m.put("type",rs.getString(3));m.put("channelTransactionNo",rs.getString(4));m.put("amountFen",rs.getLong(5));m.put("detail",rs.getString(6));m.put("status",rs.getString(7));m.put("createdAt",rs.getTimestamp(8).toLocalDateTime().toString());return m;});}
    @GetMapping("/api/admin/payment-events") public List<Map<String,Object>> events(){return db.query("SELECT id,channel,event_key,event_type,processing_status,failure_reason,received_at FROM payment_event ORDER BY id DESC LIMIT 200",(rs,n)->{var m=new LinkedHashMap<String,Object>();m.put("id",rs.getLong(1));m.put("channel",rs.getString(2));m.put("eventKey",rs.getString(3));m.put("eventType",rs.getString(4));m.put("status",rs.getString(5));m.put("failureReason",rs.getString(6));m.put("receivedAt",rs.getTimestamp(7).toLocalDateTime().toString());return m;});}
}
