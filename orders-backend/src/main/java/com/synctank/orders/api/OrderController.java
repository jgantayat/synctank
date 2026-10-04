package com.synctank.orders.api;
import java.math.BigDecimal;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderResponse getOrder(@PathVariable Long id) {
        return new OrderResponse(id, "Asha Rao",
                new Money(new BigDecimal("249.50"), "INR"), OrderStatus.SHIPPED);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<OrderResponse> listOrders() {
        return List.of(
                new OrderResponse(1L, "Asha Rao",
                        new Money(new BigDecimal("249.50"), "INR"), OrderStatus.SHIPPED),
                new OrderResponse(2L, "Vikram Iyer",
                        new Money(new BigDecimal("89.00"), "INR"), OrderStatus.PENDING)
        );
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderResponse createOrder(@Valid @RequestBody CreateOrderRequest req) {
        return new OrderResponse(42L, req.customerName(),
                new Money(BigDecimal.valueOf(req.amount()), "INR"), OrderStatus.PENDING);
    }
}