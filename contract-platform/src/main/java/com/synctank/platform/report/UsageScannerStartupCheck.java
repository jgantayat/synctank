package com.synctank.platform.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Day 10 (F1) — says, once, at startup, whether the usage scanner can run on this host.
 *
 * Deliberately a log line and not a startup failure: the spec store, diff engine, classifier
 * and Contract Agent drafts are all useful without ripgrep, and the two paths that DO need it
 * already handle its absence correctly — RegistrySeeder refuses to seed (503), and
 * ChangeReportService degrades to "usage scan failed" in the report text. What was missing was
 * any way to notice before the first seed, which in a container means before the first CI run
 * against it. The Dockerfile additionally runs `rg --version` at build time, so for the image
 * this line should always say "available".
 *
 * @Profile("!test") — Day 06 lesson, same as BucketInitializer: no process execution in the
 * context test.
 */
@Profile("!test")
@Component
public class UsageScannerStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UsageScannerStartupCheck.class);

    private final UsageScanner scanner;

    public UsageScannerStartupCheck(UsageScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public void run(ApplicationArguments args) {
        scanner.version().ifPresentOrElse(
                version -> log.info("Usage scanner available: {}", version),
                () -> log.error("Usage scanner UNAVAILABLE: ripgrep (rg) cannot be executed on this "
                        + "host. POST /registry/apps will return 503 rather than seed an empty "
                        + "registry, and change reports will show 'usage scan failed'."));
    }
}