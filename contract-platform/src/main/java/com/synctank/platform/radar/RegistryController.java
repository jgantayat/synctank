package com.synctank.platform.radar;

import com.synctank.platform.diff.ChangeRecord;
import com.synctank.platform.diff.Severity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/registry")
public class RegistryController {

    private final RegistrySeeder seeder;
    private final ImpactRadarService radar;
    private final ClientAppRepository appRepo;
    private final ClientUsageRepository usageRepo;
    private final EndpointTrafficRepository trafficRepo;

    public RegistryController(RegistrySeeder seeder, ImpactRadarService radar,
                              ClientAppRepository appRepo, ClientUsageRepository usageRepo,
                              EndpointTrafficRepository trafficRepo) {
        this.seeder = seeder;
        this.radar = radar;
        this.appRepo = appRepo;
        this.usageRepo = usageRepo;
        this.trafficRepo = trafficRepo;
    }

    /** Register (or re-register) a consuming app and derive its usage from the BASELINE spec. */
    @PostMapping(value = "/apps",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RegistrySeeder.SeedResult register(@RequestBody RegistrySeeder.SeedRequest request) {
        if (request.appName() == null || request.appName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appName is required");
        }
        if (request.baselineSpec() == null || request.baselineSpec().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baselineSpec is required");
        }
        return seeder.seed(request);
    }

    public record AppSummary(String appName, String team, String repo, String clientVersion,
                             long usageRows, List<String> screens, long totalCallsPerDay) {}

    @GetMapping(value = "/apps", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public List<AppSummary> listApps() {
        return appRepo.findAll().stream().map(app -> {
            List<ClientUsage> usages = usageRepo.findByApp(app);
            List<String> screens = usages.stream().map(ClientUsage::getScreen)
                    .filter(s -> s != null && !s.isBlank()).distinct().sorted().toList();
            long calls = trafficRepo.findByApp(app).stream()
                    .mapToLong(EndpointTraffic::getCallsPerDay).sum();
            return new AppSummary(app.getAppName(), app.getTeam(), app.getRepo(),
                    app.getClientVersion(), usages.size(), screens, calls);
        }).toList();
    }

    /**
     * Seed runtime call volumes. Body is endpoint location -> calls/day, e.g.
     *   {"GET /api/orders/{id}": 12000}
     * Phase 5's interceptor is not built; this is the seedable substitute. Label it as
     * seeded on the slide — see the comment on EndpointTraffic.
     */
    @PutMapping(value = "/apps/{appName}/traffic",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public Map<String, Object> seedTraffic(@PathVariable String appName,
                                           @RequestBody Map<String, Long> callsByEndpoint) {
        ClientApp app = appRepo.findByAppName(appName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No registered app named '" + appName + "' — POST /registry/apps first"));

        callsByEndpoint.forEach((location, calls) ->
                trafficRepo.findByAppAndLocation(app, location)
                        .map(existing -> { existing.setCallsPerDay(calls); return existing; })
                        .map(trafficRepo::save)
                        .orElseGet(() -> trafficRepo.save(new EndpointTraffic(app, location, calls))));

        return Map.of("appName", appName, "endpoints", callsByEndpoint.size());
    }

    /**
     * Remove an app entirely. This is the demo lever: deleting the only registered consumer
     * turns the SAME breaking change from BREAKING into SAFE_WITH_NOTE on the next diff.
     */
    @DeleteMapping(value = "/apps/{appName}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public Map<String, Object> deregister(@PathVariable String appName) {
        ClientApp app = appRepo.findByAppName(appName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No registered app named '" + appName + "'"));
        usageRepo.deleteByApp(app);
        trafficRepo.deleteByApp(app);
        appRepo.delete(app);
        return Map.of("deregistered", appName);
    }

    /**
     * Ad-hoc radar lookup for any Day-03-format location. Useful on stage: you can show the
     * blast radius of a field without running a whole diff.
     * Example: /registry/impact?location=OrderResponse.amount&severity=BREAKING
     */
    @GetMapping(value = "/impact", produces = MediaType.APPLICATION_JSON_VALUE)
    public ImpactAssessment impact(@RequestParam String location,
                                   @RequestParam(defaultValue = "BREAKING") Severity severity) {
        return radar.assess(new ChangeRecord(severity, "AD_HOC_QUERY", location,
                "Ad-hoc radar lookup — not from a real diff."));
    }
}