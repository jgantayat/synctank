package com.synctank.orders.api;

import java.math.BigDecimal;

public record Money(BigDecimal value, String currency) {}
