package com.orchard.mall;

public interface PaymentAdapter {
    String channel();
    CreateResult create(CreateCommand command);
    QueryResult query(String attemptNo, String channelTransactionNo);
    QueryResult close(String attemptNo, String channelTransactionNo);
    PaymentNotice verifyAndParse(String payload, String signature);

    record CreateCommand(String paymentNo,String attemptNo,long amountFen,String currency,String merchantSubject) {}
    record CreateResult(String status,String channelTransactionNo,String clientToken) {}
    record QueryResult(String status,String channelTransactionNo) {}
    record PaymentNotice(String eventKey,String attemptNo,String status,String channelTransactionNo,
                         long amountFen,String currency,String merchantSubject) {}
}
