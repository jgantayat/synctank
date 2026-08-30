package com.synctank.platform.agent;

import com.synctank.platform.radar.ContractImpactReport;

import java.util.List;

/**
 * What the dashboard renders: the code, the contract consequence, and the blast radius in one
 * payload.
 *
 * `impact` is the SAME ContractImpactReport type /diff returns, produced by the SAME
 * DiffService and ImpactRadarService. The agent has no private severity logic — if it did,
 * "the agent says it's safe" would mean something different from "the platform says it's
 * safe", and the first question from the room would be which one to believe.
 */
public record AgentDraft(
        Long requestId,
        AgentRequestStatus status,
        String requestText,
        FieldProposal proposal,
        List<FileEdit> fileEdits,
        String specDiffMarkdown,
        ContractImpactReport impact,
        List<String> guardrails,
        String blockedReason,
        String prUrl
) {}
