package com.synctank.orders.api;

public record OrderResponse(
        Long id,
        String customerName,
        double amount,
        String status
) {}