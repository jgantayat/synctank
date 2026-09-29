package com.synctank.orders.api;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderResponse getOrder(@PathVariable Long id) {
        return new OrderResponse(id, "Asha Rao", 249.50, "SHIPPED");
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<OrderResponse> listOrders() {
        return List.of(
                new OrderResponse(1L, "Asha Rao", 249.50, "SHIPPED"),
                new OrderResponse(2L, "Vikram Iyer", 89.00, "PENDING")
        );
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderResponse createOrder(@Valid @RequestBody CreateOrderRequest req) {
        return new OrderResponse(42L, req.customerName(), req.amount(), "PENDING");
    }

    /** Day 12 regression test J1 — an additive endpoint. Never merged. */
    @GetMapping(value = "/count", produces = MediaType.APPLICATION_JSON_VALUE)
    public java.util.Map<String, Integer> countOrders() {
        return java.util.Map.of("count", 2);
    }
}