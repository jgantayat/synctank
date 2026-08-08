package com.synctank.orders.api;

import com.synctank.orders.status.OrderStatus;

public record OrderResponse(
        Long id,
        String customerName,
        double amount,
        OrderStatus status
) {}