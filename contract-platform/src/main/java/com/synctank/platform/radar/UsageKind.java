package com.synctank.platform.radar;


public enum UsageKind {
    /** The app calls this operation, e.g. "GET /api/orders/{id}". */
    ENDPOINT,
    /** The app reads or writes this model field, e.g. "OrderResponse.amount". */
    FIELD
}