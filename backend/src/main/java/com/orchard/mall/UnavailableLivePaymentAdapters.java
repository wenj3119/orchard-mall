package com.orchard.mall;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

abstract class UnavailableAdapter implements PaymentAdapter {
    private final String channel;
    UnavailableAdapter(String channel){this.channel=channel;}
    @Override public String channel(){return channel;}
    private ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,channel+" payment credentials/adapter are not configured");}
    @Override public CreateResult create(CreateCommand c){throw unavailable();}
    @Override public QueryResult query(String a,String t){throw unavailable();}
    @Override public QueryResult close(String a,String t){throw unavailable();}
    @Override public PaymentNotice verifyAndParse(String p,String s){throw unavailable();}
}
@Component class WechatPaymentAdapter extends UnavailableAdapter { WechatPaymentAdapter(){super("WECHAT");} }
@Component class AlipayPaymentAdapter extends UnavailableAdapter { AlipayPaymentAdapter(){super("ALIPAY");} }
