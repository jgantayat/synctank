package com.synctank.orders.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable Long id) {
        return new OrderResponse(id, "Asha Rao", 249.50, "SHIPPED");
    }

    @GetMapping
    public List<OrderResponse> listOrders() {
        return List.of(
                new OrderResponse(1L, "Asha Rao", 249.50, "SHIPPED"),
                new OrderResponse(2L, "Vikram Iyer", 89.00, "NEW")
        );
    }

    @PostMapping
    public OrderResponse createOrder(@Valid @RequestBody CreateOrderRequest req) {
        return new OrderResponse(42L, req.customerName(), req.amount(), "NEW");
    }
}