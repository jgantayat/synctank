package com.synctank.platform.web;

import com.synctank.platform.config.S3Props;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Day 11 — GET /health/ready. The deep check that /health deliberately is not.
 *
 * Audit finding F4: HealthController answers {"status":"UP"} from a Map literal. It has
 * never touched Postgres or S3, which was fine on localhost where both were three metres
 * away, and is not fine on ECS where "the task is running" and "the task can do its job"
 * are genuinely different states. A task that has lost its database passes the liveness
 * check, stays in the load balancer, and serves 500s to a dashboard while the ECS console
 * shows a healthy service. This endpoint is how you tell those two states apart in the
 * fifteen seconds you have before a demo.
 *
 * The ALB health check still points at /health, NOT here — see the guide's decision D4.
 * With a single task, deregistering the only target over a transient database blip turns
 * a blip into a hard outage. This is a diagnostic and an alarm source, not a gate.
 *
 * Cached for ten seconds. The checks are cheap but not free (a connection validation and
 * a HeadBucket), and this endpoint is reachable by anything that can reach the platform;
 * an uncached deep probe is a small amplification lever.
 *
 * Reports the SHAPE of a failure, never configuration: exception type and message only,
 * no JDBC URL, no endpoint, no bucket credentials.
 */
@RestController
public class ReadinessController {

    private static final Duration CACHE_TTL = Duration.ofSeconds(10);

    public record Check(String name, boolean ok, String detail) {}

    public record Readiness(boolean ready, Instant checkedAt, List<Check> checks) {}

    private final DataSource dataSource;
    private final S3Client s3;
    private final String bucket;

    private volatile Readiness cached;

    public ReadinessController(DataSource dataSource, S3Client s3, S3Props props) {
        this.dataSource = dataSource;
        this.s3 = s3;
        this.bucket = props.bucket();
    }

    @GetMapping(value = "/health/ready", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Readiness> ready() {
        Readiness current = cached;
        if (current == null || current.checkedAt().isBefore(Instant.now().minus(CACHE_TTL))) {
            current = probe();
            cached = current;
        }
        // 503, not 200-with-a-false-flag: `curl -f` must fail, so a shell script and a
        // CloudWatch canary both get the answer without parsing JSON.
        return ResponseEntity
                .status(current.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(current);
    }

    private Readiness probe() {
        Check database = check("database", () -> {
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(2)) {
                    throw new IllegalStateException("connection failed validation");
                }
            }
            return "connection valid";
        });

        Check specStore = check("spec-store", () -> {
            s3.headBucket(b -> b.bucket(bucket));
            return "bucket '" + bucket + "' reachable";
        });

        return new Readiness(database.ok() && specStore.ok(), Instant.now(),
                List.of(database, specStore));
    }

    private Check check(String name, ThrowingSupplier probe) {
        try {
            return new Check(name, true, probe.get());
        } catch (Exception e) {
            return new Check(name, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }
}