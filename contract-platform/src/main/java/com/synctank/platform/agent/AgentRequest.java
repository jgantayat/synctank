package com.synctank.platform.agent;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One natural-language contract request and everything that happened to it.
 *
 * Deliberately a database row rather than a log line: on a PR review someone will ask "who
 * asked for this and what did the agent actually propose?", and the answer has to outlive
 * the container.
 *
 * Note what is NOT stored: the produced file bytes. Approve re-reads from the base branch and
 * re-applies (Decision 5), so persisted bytes would be a stale second source of truth.
 */
@Entity
@Table(name = "agent_request")
public class AgentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_text", nullable = false, length = 2000)
    private String requestText;

    @Column(name = "requester", length = 200)
    private String requester;

    @Column(name = "target_schema", length = 200)
    private String targetSchema;

    @Column(name = "field_name", length = 200)
    private String fieldName;

    @Column(name = "java_type", length = 100)
    private String javaType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AgentRequestStatus status;

    /** Day 03's verdict on the projected spec. */
    @Column(name = "classified_severity", length = 20)
    private String classifiedSeverity;

    /** Day 06's verdict. This is what the severity gate reads. */
    @Column(name = "effective_severity", length = 20)
    private String effectiveSeverity;

    @Column(name = "guardrail_notes", length = 4000)
    private String guardrailNotes;

    @Column(name = "pr_url", length = 500)
    private String prUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected AgentRequest() { /* JPA */ }

    public AgentRequest(String requestText, String requester) {
        this.requestText = requestText;
        this.requester = requester;
        this.status = AgentRequestStatus.DRAFT;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRequestText() { return requestText; }
    public String getRequester() { return requester; }
    public String getTargetSchema() { return targetSchema; }
    public String getFieldName() { return fieldName; }
    public String getJavaType() { return javaType; }
    public AgentRequestStatus getStatus() { return status; }
    public String getClassifiedSeverity() { return classifiedSeverity; }
    public String getEffectiveSeverity() { return effectiveSeverity; }
    public String getGuardrailNotes() { return guardrailNotes; }
    public String getPrUrl() { return prUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }

    public void applyProposal(FieldProposal proposal) {
        this.targetSchema = proposal.targetSchema();
        this.fieldName = proposal.fieldName();
        this.javaType = proposal.javaType();
    }

    public void setStatus(AgentRequestStatus status) { this.status = status; }
    public void setClassifiedSeverity(String s) { this.classifiedSeverity = s; }
    public void setEffectiveSeverity(String s) { this.effectiveSeverity = s; }

    public void setGuardrailNotes(String notes) {
        // Truncate rather than throw: an audit row that fails to save because a guardrail
        // message was verbose is strictly worse than a truncated one.
        this.guardrailNotes = notes == null || notes.length() <= 4000
                ? notes : notes.substring(0, 3997) + "...";
    }

    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    public void markDecided() { this.decidedAt = Instant.now(); }
}