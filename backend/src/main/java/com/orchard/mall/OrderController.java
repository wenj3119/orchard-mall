package com.orchard.mall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class OrderController {
    private final OrderService orders;
    private final QuoteService quotes;
    public OrderController(OrderService orders,QuoteService quotes) { this.orders=orders;this.quotes=quotes; }
    public record QuoteRequest(@Positive long addressId,@NotEmpty @Size(max=50) List<@Positive Long> cartItemIds) {}
    public record PlaceRequest(@Positive long addressId,@NotEmpty @Size(max=50) List<@Positive Long> cartItemIds,
        @NotBlank @Size(max=64) String quoteHash,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String idempotencyKey) {}
    private long owner(Authentication auth) { return Long.parseLong(auth.getName()); }
    @PostMapping("/api/customer/checkout/quote")
    public QuoteService.Quote quote(Authentication auth,@Valid @RequestBody QuoteRequest request) {
        return quotes.quote(owner(auth),request.addressId(),request.cartItemIds(),false);
    }
    @PostMapping("/api/customer/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderService.OrderDetail create(Authentication auth,@Valid @RequestBody PlaceRequest request) {
        return orders.create(owner(auth),new OrderService.Place(request.addressId(),request.cartItemIds(),
            request.quoteHash(),request.idempotencyKey()));
    }
    @GetMapping("/api/customer/orders")
    public List<OrderService.OrderSummary> list(Authentication auth) { return orders.list(owner(auth)); }
    @GetMapping("/api/customer/orders/{id}")
    public OrderService.OrderDetail detail(Authentication auth,@PathVariable long id) { return orders.detail(owner(auth),id,false); }
    @PostMapping("/api/customer/orders/{id}/cancel")
    public OrderService.OrderDetail cancel(Authentication auth,@PathVariable long id) { return orders.cancel(owner(auth),id); }
    @GetMapping("/api/admin/orders")
    public List<OrderService.OrderSummary> adminList() { return orders.adminList(); }
    @GetMapping("/api/admin/orders/{id}")
    public OrderService.OrderDetail adminDetail(@PathVariable long id) { return orders.detail(0,id,true); }
}
