package com.synctank.platform.agent;

import com.synctank.platform.diff.DiffReport;
import com.synctank.platform.diff.DiffService;
import com.synctank.platform.radar.ContractImpactReport;
import com.synctank.platform.radar.EffectiveSeverity;
import com.synctank.platform.radar.ImpactRadarService;
import com.synctank.platform.radar.SpecUsageExtractor;
import com.synctank.platform.spec.SpecStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Phase 6 — the bi-directional Contract Agent.
 *
 * ORDER OF OPERATIONS, and why:
 *   1. read the BASELINE spec (what production serves today — never the candidate; same
 *      reasoning as Day 06 §2.2: the deployed shape is the only honest thing to reason about)
 *   2. ask the model for a FieldProposal, giving it the real schema list so it cannot invent one
 *   3. run guardrails G1..G7 in Java — the model's output is INPUT to validation, never trusted
 *   4. edit the source deterministically
 *   5. project the candidate spec
 *   6. run Day 03's classifier and Day 06's radar over baseline vs projection
 *   7. persist an audit row and return. NOTHING has been written to GitHub.
 *
 * approve() is the only method that writes, and it re-derives everything from the base branch
 * rather than replaying stored bytes (Decision 5).
 */
@Service
public class ContractAgentService {

    private static final Logger log = LoggerFactory.getLogger(ContractAgentService.class);

    private final ChatClient chatClient;
    private final AgentProperties props;
    private final SpecStore specStore;
    private final SpecUsageExtractor extractor;
    private final SpecProjector projector;
    private final RecordFieldEditor editor;
    private final DiffService diffService;
    private final ImpactRadarService radar;
    private final GitHubClient github;
    private final AgentRequestRepository requests;

    public ContractAgentService(ChatClient.Builder chatClientBuilder,
                                AgentProperties props,
                                SpecStore specStore,
                                SpecUsageExtractor extractor,
                                SpecProjector projector,
                                RecordFieldEditor editor,
                                DiffService diffService,
                                ImpactRadarService radar,
                                GitHubClient github,
                                AgentRequestRepository requests) {
        this.chatClient = chatClientBuilder.build();
        this.props = props;
        this.specStore = specStore;
        this.extractor = extractor;
        this.projector = projector;
        this.editor = editor;
        this.diffService = diffService;
        this.radar = radar;
        this.github = github;
        this.requests = requests;
    }

    public record DraftCommand(String request, String requester, FieldProposal override) {}

    // ------------------------------------------------------------------
    // DRAFT — no GitHub writes happen anywhere in this method
    // ------------------------------------------------------------------

    @Transactional
    public AgentDraft draft(DraftCommand command) {
        AgentRequest row = requests.save(new AgentRequest(command.request(), command.requester()));
        List<String> guardrails = new ArrayList<>();

        String baseline = specStore.getBaselineSpec(props.specRepoKey());

        FieldProposal proposal = command.override() != null
                ? command.override()
                : propose(command.request(), baseline);

        if (command.override() != null) {
            guardrails.add("Proposal supplied explicitly by the operator — the model was not called. "
                    + "(Demo-insurance path; the guardrails below still ran in full.)");
        }

        row.applyProposal(proposal);

        // --- G1: the model asked a question instead of proposing ---
        if (proposal.clarifyingQuestion() != null && !proposal.clarifyingQuestion().isBlank()) {
            return block(row, guardrails, "The agent needs a clarification before it can draft: "
                    + proposal.clarifyingQuestion(), null);
        }

        // --- G2: type allowlist ---
        if (!editor.isAllowedType(proposal.javaType())) {
            return block(row, guardrails, "Type '" + proposal.javaType() + "' is not on the MVP allowlist "
                    + RecordFieldEditor.ALLOWED_TYPES + ". Types outside it need an import or are "
                    + "primitives that cannot be stubbed at existing call sites.", null);
        }
        guardrails.add("G2 type allowlist — '" + proposal.javaType() + "' accepted.");

        // --- G3: field name shape ---
        if (!editor.isValidFieldName(proposal.fieldName())) {
            return block(row, guardrails, "Field name '" + proposal.fieldName() + "' is not a lower "
                    + "camel-case Java identifier.", null);
        }
        guardrails.add("G3 identifier shape — '" + proposal.fieldName() + "' accepted.");

        // --- G4: the schema must exist in the deployed contract ---
        if (!projector.schemaExists(baseline, proposal.targetSchema())) {
            return block(row, guardrails, "No component schema named '" + proposal.targetSchema()
                    + "' exists in the published baseline. The agent only edits contracts that are "
                    + "already live.", null);
        }
        guardrails.add("G4 schema exists in baseline — '" + proposal.targetSchema() + "'.");

        // --- G5: a top-level record file must back that schema (audit Finding C) ---
        String recordPath = props.sourceDir() + "/" + proposal.targetSchema() + ".java";
        SourceFile recordFile = github.readFile(recordPath, props.baseBranch());
        if (recordFile == null || !editor.declaresRecord(recordFile.content(), proposal.targetSchema())) {
            return block(row, guardrails, "'" + proposal.targetSchema() + "' is not declared as a "
                    + "top-level record at " + recordPath + ". Nested records (CustomerResponse lives "
                    + "inside CustomerController) are out of scope for the MVP.", null);
        }
        guardrails.add("G5 top-level record located — " + recordPath + ".");

        // --- G6: duplicate field ---
        if (editor.hasComponent(recordFile.content(), proposal.targetSchema(), proposal.fieldName())) {
            return block(row, guardrails, "'" + proposal.targetSchema() + "." + proposal.fieldName()
                    + "' already exists. Nothing to do.", null);
        }
        guardrails.add("G6 field is genuinely new.");

        // --- build the edits ---
        List<SourceFile> originals = new ArrayList<>();
        List<String> edited = new ArrayList<>();

        originals.add(recordFile);
        edited.add(editor.addRecordComponent(recordFile.content(), proposal.targetSchema(),
                proposal.javaType(), proposal.fieldName()));

        // Audit Finding B: every canonical-constructor call site must gain an argument in the
        // same commit, or orders-backend does not compile and the agent's own PR goes red.
        String literal = editor.defaultLiteralFor(proposal.javaType());
        for (String path : github.listJavaFiles(props.sourceDir(), props.baseBranch())) {
            if (path.equals(recordPath)) continue;
            SourceFile file = github.readFile(path, props.baseBranch());
            if (file == null || !editor.callsConstructorOf(file.content(), proposal.targetSchema())) {
                continue;
            }
            originals.add(file);
            edited.add(editor.appendConstructorArgument(file.content(), proposal.targetSchema(), literal));
        }
        guardrails.add("Call sites patched in " + (originals.size() - 1) + " additional file(s) "
                + "so the canonical constructor still compiles.");

        // --- project the spec and run the REAL classifier + radar over it ---
        String candidate = projector.addProperty(baseline, proposal.targetSchema(),
                proposal.fieldName(), proposal.javaType());
        DiffReport report = diffService.diff(baseline, candidate);
        ContractImpactReport impact = radar.enrich(report);

        row.setClassifiedSeverity(String.valueOf(report.highestSeverity()));
        row.setEffectiveSeverity(String.valueOf(impact.effectiveSeverity()));

        // --- G7: severity gate. The platform's own engine decides, not the agent. ---
        EffectiveSeverity effective = impact.effectiveSeverity();
        if (effective != EffectiveSeverity.ADDITIVE && effective != EffectiveSeverity.SAFE_WITH_NOTE) {
            return block(row, guardrails, "Refused: the projected change classifies as "
                    + report.highestSeverity() + " (effective " + effective + "). The MVP agent opens "
                    + "additive pull requests only.", impact);
        }
        guardrails.add("G7 severity gate — classified " + report.highestSeverity()
                + ", effective " + effective + ". Additive-only rule satisfied.");

        List<FileEdit> fileEdits = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            fileEdits.add(new FileEdit(originals.get(i).path(),
                    TextDiff.unified(originals.get(i).path(), originals.get(i).content(), edited.get(i))));
        }

        row.setStatus(AgentRequestStatus.DRAFT);
        row.setGuardrailNotes(String.join("\n", guardrails));
        requests.save(row);

        log.info("Contract agent drafted #{}: {}.{} {} — effective {}", row.getId(),
                proposal.targetSchema(), proposal.fieldName(), proposal.javaType(), effective);

        return new AgentDraft(row.getId(), AgentRequestStatus.DRAFT, command.request(), proposal,
                fileEdits, report.markdown(), impact, guardrails, null, null);
    }

    // ------------------------------------------------------------------
    // APPROVE — the only method in the platform that writes to GitHub
    // ------------------------------------------------------------------

    @Transactional
    public AgentDraft approve(Long requestId, String approver) {
        AgentRequest row = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No agent request #" + requestId));

        if (row.getStatus() != AgentRequestStatus.DRAFT) {
            throw new IllegalStateException("Agent request #" + requestId + " is " + row.getStatus()
                    + " — only a DRAFT can be approved.");
        }
        if (!github.isWritable()) {
            throw new IllegalStateException("No GitHub token configured (platform.agent.github-token "
                    + "is blank) — the platform is in draft-only mode.");
        }

        FieldProposal proposal = new FieldProposal(row.getTargetSchema(), row.getFieldName(),
                row.getJavaType(), row.getRequestText(), "approved by " + approver, "HIGH", "");

        // Recompute against the CURRENT base branch rather than replaying stored bytes:
        // someone may have merged to main between draft and approve (Decision 5).
        String baseSha = github.headSha(props.baseBranch());
        String branch = props.branchPrefix() + "add-"
                + hyphenate(proposal.targetSchema()) + "-"
                + hyphenate(proposal.fieldName()) + "-" + requestId;

        github.createBranch(branch, baseSha);

        String recordPath = props.sourceDir() + "/" + proposal.targetSchema() + ".java";
        SourceFile recordFile = github.readFile(recordPath, props.baseBranch());
        if (recordFile == null) {
            throw new IllegalStateException("Record file vanished from " + props.baseBranch()
                    + ": " + recordPath);
        }

        github.commitFile(branch, recordPath,
                editor.addRecordComponent(recordFile.content(), proposal.targetSchema(),
                        proposal.javaType(), proposal.fieldName()),
                recordFile.sha(),
                "feat(contract): add %s.%s (%s) — requested via Contract Agent #%d"
                        .formatted(proposal.targetSchema(), proposal.fieldName(),
                                proposal.javaType(), requestId));

        String literal = editor.defaultLiteralFor(proposal.javaType());
        int patched = 0;
        for (String path : github.listJavaFiles(props.sourceDir(), props.baseBranch())) {
            if (path.equals(recordPath)) continue;
            SourceFile file = github.readFile(path, props.baseBranch());
            if (file == null || !editor.callsConstructorOf(file.content(), proposal.targetSchema())) {
                continue;
            }
            // The blob sha read from the base branch is still current on the new branch for any
            // file the previous commit did not touch — the branch was created from that tip.
            github.commitFile(branch, path,
                    editor.appendConstructorArgument(file.content(), proposal.targetSchema(), literal),
                    file.sha(),
                    "chore(contract): stub %s at %s call sites for Contract Agent #%d"
                            .formatted(proposal.fieldName(), proposal.targetSchema(), requestId));
            patched++;
        }

        String prUrl = github.openPullRequest(branch, props.baseBranch(),
                "feat(contract): add %s.%s".formatted(proposal.targetSchema(), proposal.fieldName()),
                pullRequestBody(row, patched));

        row.setStatus(AgentRequestStatus.PR_OPENED);
        row.setPrUrl(prUrl);
        row.markDecided();
        requests.save(row);

        return new AgentDraft(row.getId(), AgentRequestStatus.PR_OPENED, row.getRequestText(),
                proposal, List.of(), null, null,
                List.of("Branch " + branch + " created from " + props.baseBranch(),
                        "Record edited and " + patched + " call-site file(s) patched",
                        "Pull request opened — backend review is the second human gate"),
                null, prUrl);
    }

    @Transactional
    public AgentDraft reject(Long requestId, String reason) {
        AgentRequest row = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No agent request #" + requestId));
        row.setStatus(AgentRequestStatus.REJECTED);
        row.setGuardrailNotes("Rejected by a human: " + reason);
        row.markDecided();
        requests.save(row);
        return new AgentDraft(row.getId(), AgentRequestStatus.REJECTED, row.getRequestText(),
                null, List.of(), null, null, List.of(), reason, null);
    }

    @Transactional(readOnly = true)
    public List<AgentRequest> auditLog() {
        return requests.findAllByOrderByCreatedAtDesc();
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private FieldProposal propose(String request, String baselineSpec) {
        SpecUsageExtractor.SpecUsage usage = extractor.extract(baselineSpec);

        // Hand the model the real contract. A model that can see the schemas cannot invent one,
        // and a model that can see the existing field names is far less likely to propose a
        // duplicate — turning a guardrail rejection into a correct proposal.
        String contractBlock = usage.schemaFields().entrySet().stream()
                .map(e -> "- %s: %s".formatted(e.getKey(), String.join(", ", e.getValue())))
                .collect(Collectors.joining("\n"));

        String endpointBlock = usage.endpoints().stream()
                .map(e -> "- %s (operationId %s) carries %s".formatted(
                        e.location(), e.operationId(), e.schemas()))
                .collect(Collectors.joining("\n"));

        String system = """
                You translate a developer's plain-English request for an API contract change into a
                single structured field proposal. You do not write code and you do not decide whether
                the change is safe — a deterministic engine does both after you.

                Rules you must follow:
                - targetSchema MUST be one of the component schema names listed below, exactly as written.
                - fieldName MUST be lower camel case and MUST NOT already exist on that schema.
                - javaType MUST be exactly one of: String, Long, Integer, Boolean, Double.
                  Use String for emails, phone numbers, names, codes, dates and identifiers-as-text.
                  Use Long for numeric identifiers. Use Double for money or quantities.
                  Use Boolean for flags. Use Integer for small counts.
                - If the request names a field that already exists, or names no schema you can identify
                  with confidence, or asks for anything other than adding ONE field, leave every other
                  value empty and put a single question in clarifyingQuestion.
                - Never invent a schema name that is not in the list. Never propose more than one field.
                - description is one line for a pull-request body. rationale is one sentence naming the
                  words in the request that led you to this schema and this field.
                - confidence is HIGH, MEDIUM or LOW.
                - When you are confident, clarifyingQuestion MUST be an empty string.
                """;

        String user = """
                Developer's request:
                %s

                Component schemas currently in the deployed contract, with their existing fields:
                %s

                Operations in the deployed contract:
                %s
                """.formatted(request, contractBlock, endpointBlock);

        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(FieldProposal.class);
    }

    private AgentDraft block(AgentRequest row, List<String> guardrails, String reason,
                             ContractImpactReport impact) {
        row.setStatus(AgentRequestStatus.BLOCKED);
        row.setGuardrailNotes(String.join("\n", guardrails) + "\nBLOCKED: " + reason);
        row.markDecided();
        requests.save(row);
        log.info("Contract agent blocked #{}: {}", row.getId(), reason);
        return new AgentDraft(row.getId(), AgentRequestStatus.BLOCKED, row.getRequestText(),
                null, List.of(), null, impact, guardrails, reason, null);
    }

    private String pullRequestBody(AgentRequest row, int patchedFiles) {
        return """
                ### Opened by the SyncTank Contract Agent

                **Original request** (agent request #%d, from %s):
                > %s

                **Change:** adds `%s.%s` of type `%s`.

                **Preview verdict at draft time:** classified `%s`, effective `%s` after the
                Consumer Impact Radar.

                **Call sites:** %d file(s) had the canonical constructor argument stubbed with `null`
                so `orders-backend` still compiles. **A backend reviewer must replace those stubs with
                the real value before merging.**

                ---
                The agent drafted this change; it did not decide it was safe. The contract pipeline on
                this pull request regenerates the OpenAPI spec from source, re-runs the severity
                classifier and the Impact Radar against the published baseline, and comments below.
                Read that comment, not this one, for the authoritative verdict.

                Guardrails that ran before this PR was opened:
                %s
                                """.formatted(row.getId(), row.getRequester(), row.getRequestText(),
                row.getTargetSchema(), row.getFieldName(), row.getJavaType(),
                row.getClassifiedSeverity(), row.getEffectiveSeverity(),
                patchedFiles, row.getGuardrailNotes());
    }

    private String hyphenate(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}