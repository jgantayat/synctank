package com.synctank.platform.agent;

public enum AgentRequestStatus {
    /** Draft computed, all guardrails passed, awaiting human approval. */
    DRAFT,
    /** A guardrail refused. /approve returns 409 for this row, permanently. */
    BLOCKED,
    /** A human explicitly rejected the draft. */
    REJECTED,
    /** Branch created, files committed, pull request opened. Terminal. */
    PR_OPENED
}