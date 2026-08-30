package com.synctank.platform.agent;

/**
 * @param path repo-relative, e.g. "orders-backend/src/main/java/com/synctank/orders/api/OrderResponse.java"
 * @param content decoded UTF-8 source
 * @param sha the git BLOB sha on the branch it was read from. The contents API requires it on
 *            update as an optimistic-lock token.
 */
public record SourceFile(String path, String content, String sha) {}