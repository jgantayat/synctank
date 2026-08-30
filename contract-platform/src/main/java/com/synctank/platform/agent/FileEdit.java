package com.synctank.platform.agent;

/** One file the agent proposes to change, with a display-only unified diff. */
public record FileEdit(String path, String unifiedDiff) {}