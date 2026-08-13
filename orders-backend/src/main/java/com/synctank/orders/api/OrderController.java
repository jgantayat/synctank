package com.synctank.orders.api;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderResponse getOrder(@PathVariable Long id) {
        return new OrderResponse(id, "Asha Rao",  new Money(BigDecimal.valueOf(149.99), "USD"), "SHIPPED");
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<OrderResponse> listOrders() {
        return List.of(
                new OrderResponse(1L, "Asha Rao", new Money(BigDecimal.valueOf(249.50), "USD"), "SHIPPED"),
                new OrderResponse(2L, "Vikram Iyer", new Money(BigDecimal.valueOf(89.00),"USD"), "NEW") );
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderResponse createOrder(@Valid @RequestBody CreateOrderRequest req) {
        return new OrderResponse(42L, req.customerName(), new Money(BigDecimal.valueOf(req.amount()), "USD"), "NEW");
    }
}