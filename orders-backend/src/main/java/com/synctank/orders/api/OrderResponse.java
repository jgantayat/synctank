package com.synctank.orders.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderResponse(
        Long id,
        String customerName,
        Money total,
        @Schema(allowableValues = {"PENDING", "SHIPPED", "DELIVERED", "CANCELLED"}) String status
) {}