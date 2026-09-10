package com.synctank.platform.web;

import com.synctank.platform.config.secrets.SecretsStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day 09 — GET /health/secrets. Answers "did the secrets load, and from where?" without
 * printing a credential. Presence, source and an 8-hex SHA-256 prefix only (see SecretsStatus).
 *
 * ObjectProvider rather than a plain constructor parameter, for a specific reason worth
 * recording: SecretsInitializer is registered in main(), and @SpringBootTest does NOT call
 * main() — SpringBootContextLoader builds its own SpringApplication from the class. So in the
 * context test no secretsStatus singleton exists, and a hard dependency here would fail
 * ContractPlatformApplicationTests with an error that looks nothing like its cause.
 * getIfAvailable() degrades to "uninitialised" instead.
 *
 * NOT added to DashboardCorsConfig on purpose: no browser page on an allowed origin should be
 * able to read this.
 */
@RestController
public class SecretsStatusController {

    private final ObjectProvider<SecretsStatus> status;

    public SecretsStatusController(ObjectProvider<SecretsStatus> status) {
        this.status = status;
    }

    @GetMapping(value = "/health/secrets", produces = MediaType.APPLICATION_JSON_VALUE)
    public SecretsStatus secrets() {
        return status.getIfAvailable(SecretsStatus::notInitialised);
    }
}