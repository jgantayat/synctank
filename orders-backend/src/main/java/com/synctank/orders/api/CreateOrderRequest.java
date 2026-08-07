package com.synctank.orders.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
        @NotBlank String customerName,
        @Positive double amount,
        String note
) {}