package com.orchard.mall;

public interface RefundAdapter {
    String channel();
    CreateResult create(CreateCommand command);
    QueryResult query(String channelRequestNo,String channelRefundNo);
    RefundNotice verifyAndParse(String payload,String signature);
    record CreateCommand(String refundNo,String channelRequestNo,String channelTransactionNo,long amountFen,String currency,String reason){}
    record CreateResult(String status,String channelRefundNo){}
    record QueryResult(String status,String channelRefundNo){}
    record RefundNotice(String eventKey,String channelRequestNo,String status,String channelRefundNo,long amountFen,String currency){}
}
