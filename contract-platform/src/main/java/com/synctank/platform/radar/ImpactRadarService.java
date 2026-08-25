package com.synctank.platform.radar;

import com.synctank.platform.diff.ChangeRecord;
import com.synctank.platform.diff.DiffReport;
import com.synctank.platform.diff.Severity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

@Service
public class ImpactRadarService {

    private final ClientUsageRepository usageRepo;
    private final EndpointTrafficRepository trafficRepo;
    private final long escalationThreshold;

    public ImpactRadarService(ClientUsageRepository usageRepo,
                              EndpointTrafficRepository trafficRepo,
                              @Value("${platform.radar.escalation-calls-per-day:5000}") long escalationThreshold) {
        this.usageRepo = usageRepo;
        this.trafficRepo = trafficRepo;
        this.escalationThreshold = escalationThreshold;
    }

    /** Wraps Day 03's report with radar findings. Day 03's own verdict is copied through untouched. */
    @Transactional(readOnly = true)
    public ContractImpactReport enrich(DiffReport report) {
        List<ImpactAssessment> impact = report.changes().stream().map(this::assess).toList();

        EffectiveSeverity highest = impact.stream()
                .map(ImpactAssessment::effectiveSeverity)
                .max(Comparator.comparingInt(EffectiveSeverity::rank))
                .orElse(EffectiveSeverity.ADDITIVE);   // no changes at all — matches DiffService's default

        return new ContractImpactReport(
                report.changed(),
                report.highestSeverity(),
                report.changes(),
                report.markdown(),
                highest,
                impact);
    }

    @Transactional(readOnly = true)
    public ImpactAssessment assess(ChangeRecord change) {
        List<ClientUsage> usages = usageRepo.findByLocation(change.location());

        // Group by app id rather than by entity: entity equality is identity-based here,
        // which happens to work within one query's result set but is a trap waiting to spring.
        Map<Long, List<ClientUsage>> byApp = new LinkedHashMap<>();
        for (ClientUsage usage : usages) {
            byApp.computeIfAbsent(usage.getApp().getId(), k -> new ArrayList<>()).add(usage);
        }

        List<ConsumerImpact> consumers = new ArrayList<>();
        long totalCalls = 0;

        for (List<ClientUsage> appUsages : byApp.values()) {
            ClientApp app = appUsages.get(0).getApp();

            List<String> screens = appUsages.stream()
                    .map(ClientUsage::getScreen)
                    .filter(s -> s != null && !s.isBlank())
                    .distinct().sorted().toList();

            List<String> useSites = appUsages.stream()
                    .map(u -> u.getSourceFile() + ":" + u.getSourceLine())
                    .distinct().sorted().toList();

            Set<String> owningEndpoints = new LinkedHashSet<>();
            appUsages.stream()
                    .map(ClientUsage::getOwningEndpoint)
                    .filter(e -> e != null && !e.isBlank())
                    .forEach(owningEndpoints::add);

            long calls = owningEndpoints.stream()
                    .mapToLong(endpoint -> trafficRepo.findByAppAndLocation(app, endpoint)
                            .map(EndpointTraffic::getCallsPerDay)
                            .orElse(0L))
                    .sum();

            totalCalls += calls;
            consumers.add(new ConsumerImpact(app.getAppName(), app.getTeam(), screens, useSites, calls));
        }

        Severity classified = change.severity();
        EffectiveSeverity effective;
        String verdict;

        if (consumers.isEmpty()) {
            if (classified == Severity.BREAKING) {
                effective = EffectiveSeverity.SAFE_WITH_NOTE;
                verdict = "Breaking on paper, but no registered client compiles against '"
                        + change.location() + "'. Downgraded to SAFE_WITH_NOTE — the merge is "
                        + "unblocked, the change is still recorded.";
            } else if (classified == Severity.ADDITIVE) {
                // An added field cannot break a consumer that never compiled against it — the
                // field did not exist in the deployed contract. This branch exists only because
                // the FIELD_ADDED fix now routes additive changes through the radar at all.
                effective = EffectiveSeverity.ADDITIVE;
                verdict = "No consumer can be affected: '" + change.location()
                        + "' did not exist in the deployed contract, so nothing compiles against it yet.";
            } else {
                // DANGEROUS with no known consumer: deliberately NOT downgraded. It is a runtime
                // hazard the type system cannot see, and the registry is known to be incomplete,
                // so its silence is not evidence of safety. See §2.5 of the Day 06 guide.
                effective = map(classified);
                verdict = "No registered consumer found for '" + change.location()
                        + "'. Severity left unchanged — this " + classified
                        + " change can still affect unmapped consumers at runtime.";
            }
        } else if (classified == Severity.DANGEROUS && totalCalls >= escalationThreshold) {
            effective = EffectiveSeverity.BREAKING;
            verdict = "Escalated to BREAKING: a DANGEROUS change on endpoints serving ~"
                    + totalCalls + " calls/day across " + consumers.size()
                    + " registered consumer(s) (threshold " + escalationThreshold + ").";
        } else {
            effective = map(classified);
            verdict = consumers.size() + " registered consumer(s) affected"
                    + (totalCalls > 0 ? ", ~" + totalCalls + " calls/day." : " (no traffic data recorded).");
        }

        return new ImpactAssessment(change.location(), classified, effective, verdict,
                consumers, totalCalls);
    }

    private EffectiveSeverity map(Severity severity) {
        return switch (severity) {
            case ADDITIVE -> EffectiveSeverity.ADDITIVE;
            case DANGEROUS -> EffectiveSeverity.DANGEROUS;
            case BREAKING -> EffectiveSeverity.BREAKING;
        };
    }
}