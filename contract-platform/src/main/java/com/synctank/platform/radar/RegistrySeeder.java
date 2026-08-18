package com.synctank.platform.radar;

import com.synctank.platform.report.UsageScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Populates the client registry for one consuming app.
 *
 * Input is the BASELINE spec, not the candidate spec — see §2.2 of the Day 06 guide.
 * The registry describes what the deployed frontend compiles against today; seeding it
 * from a PR's candidate spec would make a field-removal look like it has zero consumers
 * and silently invert the whole feature.
 */
@Service
public class RegistrySeeder {

    private static final Logger log = LoggerFactory.getLogger(RegistrySeeder.class);

    private final ClientAppRepository appRepo;
    private final ClientUsageRepository usageRepo;
    private final SpecUsageExtractor extractor;
    private final UsageScanner scanner;

    public RegistrySeeder(ClientAppRepository appRepo,
                          ClientUsageRepository usageRepo,
                          SpecUsageExtractor extractor,
                          UsageScanner scanner) {
        this.appRepo = appRepo;
        this.usageRepo = usageRepo;
        this.extractor = extractor;
        this.scanner = scanner;
    }

    public record SeedRequest(String appName, String team, String repo, String clientVersion,
                              String frontendSrcPath, String baselineSpec) {}

    public record SeedResult(String appName, String team, int endpointUsages, int fieldUsages,
                             List<String> screens, List<String> notes) {}

    @Transactional
    public SeedResult seed(SeedRequest request) {
        List<String> notes = new ArrayList<>();

        ClientApp app = appRepo.findByAppName(request.appName())
                .orElseGet(() -> new ClientApp(request.appName(), request.team(),
                        request.repo(), request.clientVersion()));
        app.setTeam(request.team());
        app.setRepo(request.repo());
        app.setClientVersion(request.clientVersion());
        app = appRepo.save(app);

        // Destructive re-seed: the previous contract shape's rows must not survive.
        usageRepo.deleteByApp(app);

        Path srcRoot = Path.of(request.frontendSrcPath());
        if (!Files.isDirectory(srcRoot)) {
            // Day 05's hardest-won lesson: relative paths resolve against the JVM working
            // directory, not the project root. Fail loudly here instead of quietly seeding nothing.
            throw new IllegalArgumentException(
                    "frontendSrcPath is not a directory: " + srcRoot.toAbsolutePath()
                            + " — pass an ABSOLUTE path, e.g. $(cd orders-frontend/src && pwd)");
        }

        List<Path> consumerDirs = scanner.findClientConsumerDirs(srcRoot);
        if (consumerDirs.isEmpty()) {
            notes.add("No file under " + srcRoot + " imports from the generated client — "
                    + "registry seeded empty. Has `npm run generate:api` ever run in this tree?");
            return new SeedResult(app.getAppName(), app.getTeam(), 0, 0, List.of(), notes);
        }
        notes.add("Client consumer directories: " + consumerDirs.size());

        SpecUsageExtractor.SpecUsage spec = extractor.extract(request.baselineSpec());

        // schema name -> the endpoints that carry it, so a FIELD row can inherit calls/day
        Map<String, Set<String>> schemaToEndpoints = new LinkedHashMap<>();
        for (SpecUsageExtractor.EndpointInfo endpoint : spec.endpoints()) {
            for (String schema : endpoint.schemas()) {
                schemaToEndpoints.computeIfAbsent(schema, k -> new LinkedHashSet<>())
                        .add(endpoint.location());
            }
        }

        List<ClientUsage> rows = new ArrayList<>();
        int endpointUsages = 0;
        int fieldUsages = 0;

        // --- ENDPOINT usage: grep the generated service method name (= operationId) ---
        for (SpecUsageExtractor.EndpointInfo endpoint : spec.endpoints()) {
            if (endpoint.operationId() == null || endpoint.operationId().isBlank()) {
                notes.add("Skipped " + endpoint.location() + " — spec has no operationId to grep for.");
                continue;
            }
            for (String hit : scanner.findUsages(consumerDirs, endpoint.operationId())) {
                Hit parsed = Hit.parse(hit);
                if (parsed == null) continue;
                rows.add(new ClientUsage(app, UsageKind.ENDPOINT, endpoint.location(),
                        endpoint.location(), screenOf(srcRoot, parsed.file()),
                        parsed.file(), parsed.line()));
                endpointUsages++;
            }
        }

        // --- FIELD usage: grep each property name of each component schema ---
        for (Map.Entry<String, List<String>> entry : spec.schemaFields().entrySet()) {
            String schemaName = entry.getKey();
            Set<String> owners = schemaToEndpoints.getOrDefault(schemaName, Set.of());
            for (String field : entry.getValue()) {
                String location = schemaName + "." + field;   // Day 03's exact format
                for (String hit : scanner.findUsages(consumerDirs, field)) {
                    Hit parsed = Hit.parse(hit);
                    if (parsed == null) continue;
                    String screen = screenOf(srcRoot, parsed.file());
                    if (owners.isEmpty()) {
                        rows.add(new ClientUsage(app, UsageKind.FIELD, location, null,
                                screen, parsed.file(), parsed.line()));
                        fieldUsages++;
                    } else {
                        for (String owner : owners) {
                            rows.add(new ClientUsage(app, UsageKind.FIELD, location, owner,
                                    screen, parsed.file(), parsed.line()));
                            fieldUsages++;
                        }
                    }
                }
            }
        }

        usageRepo.saveAll(rows);

        List<String> screens = rows.stream().map(ClientUsage::getScreen)
                .filter(s -> s != null && !s.isBlank()).distinct().sorted().toList();

        log.info("Seeded registry for {}: {} endpoint usages, {} field usages, screens {}",
                app.getAppName(), endpointUsages, fieldUsages, screens);

        return new SeedResult(app.getAppName(), app.getTeam(), endpointUsages, fieldUsages,
                screens, notes);
    }

    /** "path:line:matched text" — POSIX paths contain no colons, so a 3-way split is safe. */
    private record Hit(String file, Integer line) {
        static Hit parse(String raw) {
            String[] parts = raw.split(":", 3);
            if (parts.length < 2) return null;
            try {
                return new Hit(parts[0], Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException e) {
                return null;   // e.g. the "(usage scan failed: ...)" sentinel line
            }
        }
    }

    /**
     * src/app/order-detail/order-detail.ts  ->  "order-detail"
     * src/app/app.ts                        ->  "app"
     *
     * This is the "map generated clients to screens" step from Phase 5's plan: an Angular
     * feature folder is the closest honest proxy for a screen without parsing the router.
     */
    static String screenOf(Path frontendSrcRoot, String absoluteFile) {
        try {
            Path relative = frontendSrcRoot.toAbsolutePath()
                    .relativize(Path.of(absoluteFile).toAbsolutePath());
            List<String> segments = new ArrayList<>();
            relative.forEach(p -> segments.add(p.toString()));
            if (segments.isEmpty()) return "unknown";
            int start = "app".equals(segments.get(0)) ? 1 : 0;
            if (segments.size() - start > 1) {
                return segments.get(start);                       // a feature folder
            }
            String fileName = segments.get(segments.size() - 1);  // a file directly under app/
            int dot = fileName.indexOf('.');
            return dot == -1 ? fileName : fileName.substring(0, dot);
        } catch (IllegalArgumentException e) {
            return "unknown";   // different filesystem roots — cannot relativize
        }
    }
}