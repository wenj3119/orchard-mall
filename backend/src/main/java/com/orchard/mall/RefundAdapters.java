package com.orchard.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Component
public class RefundAdapters {
    private final List<RefundAdapter> adapters;public RefundAdapters(List<RefundAdapter> adapters){this.adapters=adapters;}
    public RefundAdapter require(String channel){return adapters.stream().filter(a->a.channel().equalsIgnoreCase(channel)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,channel+" refund is not configured"));}
}

@Component
class DevRefundAdapter implements RefundAdapter {
    private final boolean enabled;private final ObjectMapper json;private final JdbcTemplate db;
    DevRefundAdapter(Environment env,ObjectMapper json,JdbcTemplate db,@Value("${app.dev-refund-enabled:false}") boolean enabled){this.enabled=enabled;this.json=json;this.db=db;var p=Set.of(env.getActiveProfiles());if(enabled&&(p.stream().anyMatch(x->x.toLowerCase(Locale.ROOT).contains("prod"))||p.stream().noneMatch(x->x.equals("dev")||x.equals("test"))))throw new IllegalStateException("Development refund simulator is forbidden outside dev/test");}
    private void available(){if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Development refund simulator is disabled");}
    public String channel(){return "DEV_SIMULATOR";}
    public CreateResult create(CreateCommand c){available();return new CreateResult("PENDING","DEV-RF-"+c.channelRequestNo());}
    public QueryResult query(String request,String channelNo){available();String status=db.queryForObject("SELECT status FROM refund_order WHERE channel_request_no=?",String.class,request);return new QueryResult("UNKNOWN".equals(status)?"UNKNOWN":status,channelNo);}
    public RefundNotice verifyAndParse(String payload,String signature){available();if(!"dev-internal".equals(signature))throw new IllegalArgumentException("Invalid simulator signature");try{return json.readValue(payload,RefundNotice.class);}catch(Exception e){throw new IllegalArgumentException("Invalid refund notice",e);}}
}

abstract class UnavailableRefundAdapter implements RefundAdapter {
    private final String channel;UnavailableRefundAdapter(String channel){this.channel=channel;}public String channel(){return channel;}
    private ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,channel+" refund credentials/adapter are not configured");}
    public CreateResult create(CreateCommand c){throw unavailable();}public QueryResult query(String r,String c){throw unavailable();}public RefundNotice verifyAndParse(String p,String s){throw unavailable();}
}
@Component class WechatRefundAdapter extends UnavailableRefundAdapter{WechatRefundAdapter(){super("WECHAT");}}
@Component class AlipayRefundAdapter extends UnavailableRefundAdapter{AlipayRefundAdapter(){super("ALIPAY");}}
