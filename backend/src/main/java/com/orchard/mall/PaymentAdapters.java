package com.orchard.mall;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Component
public class PaymentAdapters {
    private final List<PaymentAdapter> adapters;
    public PaymentAdapters(List<PaymentAdapter> adapters) { this.adapters=adapters; }
    public PaymentAdapter require(String channel) {
        return adapters.stream().filter(a->a.channel().equalsIgnoreCase(channel)).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                channel+" payment is not configured"));
    }
}
