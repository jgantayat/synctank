package com.synctank.orders.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderResponse(
        Long id,
        String customerName,
        @Schema(description = "Replaces the former flat `amount`") Money total,
        OrderStatus status
) {}