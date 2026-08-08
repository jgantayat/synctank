package com.synctank.orders.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderResponse(
        Long id,
        String customerName,
        @Schema(minimum = "0") double amount,
        @Schema(allowableValues = {"PENDING", "SHIPPED", "DELIVERED", "CANCELLED"}) String status
) {}