package com.synctank.platform.agent;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Two-step by design (Decision 3): POST /agent/requests computes and persists but writes
 * nothing to GitHub; POST /agent/requests/{id}/approve is the only path that can.
 *
 * The human gate is therefore structural rather than cultural — no code path opens a pull
 * request without a second HTTP call carrying an id that exists only because a draft was made.
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private final ContractAgentService agent;
    private final AgentProperties props;

    public AgentController(ContractAgentService agent, AgentProperties props) {
        this.agent = agent;
        this.props = props;
    }

    public record DraftRequest(String request, String requester, FieldProposal override) {}
    public record DecisionRequest(String actor, String reason) {}

    public record AuditEntry(Long id, String requestText, String requester, String targetSchema,
                             String fieldName, String javaType, String status,
                             String classifiedSeverity, String effectiveSeverity,
                             String prUrl, Instant createdAt, Instant decidedAt) {}

    @PostMapping(value = "/requests",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentDraft draft(@RequestBody DraftRequest body) {
        requireEnabled();
        if (body.request() == null || body.request().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request is required");
        }
        try {
            return agent.draft(new ContractAgentService.DraftCommand(
                    body.request(),
                    body.requester() == null || body.requester().isBlank() ? "unknown" : body.requester(),
                    body.override()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            // A blocked guardrail returns 200 with status=BLOCKED — a normal outcome the UI
            // renders. Reaching HERE means something structural failed: no baseline in MinIO,
            // GitHub unreachable, malformed spec. Those deserve a real error code.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
        }
    }

    @PostMapping(value = "/requests/{id}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentDraft approve(@PathVariable Long id,
                              @RequestBody(required = false) DecisionRequest body) {
        requireEnabled();
        String approver = (body == null || body.actor() == null || body.actor().isBlank())
                ? "unknown" : body.actor();
        try {
            return agent.approve(id, approver);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @PostMapping(value = "/requests/{id}/reject",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentDraft reject(@PathVariable Long id, @RequestBody DecisionRequest body) {
        requireEnabled();
        try {
            return agent.reject(id, body == null || body.reason() == null ? "no reason given" : body.reason());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @GetMapping(value = "/requests", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AuditEntry> auditLog() {
        return agent.auditLog().stream()
                .map(r -> new AuditEntry(r.getId(), r.getRequestText(), r.getRequester(),
                        r.getTargetSchema(), r.getFieldName(), r.getJavaType(),
                        String.valueOf(r.getStatus()), r.getClassifiedSeverity(),
                        r.getEffectiveSeverity(), r.getPrUrl(), r.getCreatedAt(), r.getDecidedAt()))
                .toList();
    }

    /** What the dashboard reads on load to decide whether to show the Approve button. */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status() {
        return Map.of(
                "enabled", props.enabled(),
                "repository", props.repoSlug(),
                "baseBranch", props.baseBranch(),
                "canOpenPullRequests", props.hasToken(),
                "allowedTypes", RecordFieldEditor.ALLOWED_TYPES);
    }

    private void requireEnabled() {
        if (!props.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Contract Agent is disabled (platform.agent.enabled=false).");
        }
    }
}