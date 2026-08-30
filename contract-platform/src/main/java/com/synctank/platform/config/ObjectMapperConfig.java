package com.synctank.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Day 07 — Spring Boot 4.1 auto-configures Jackson 3's tools.jackson.databind.json.JsonMapper
 * for HTTP message conversion, not classic com.fasterxml.jackson.databind.ObjectMapper. No
 * bean of the classic type is registered by default, even though the classic jar is present
 * transitively (via openapi-diff-core / swagger-parser).
 *
 * GitHubClient and SpecProjector both use the classic Jackson 2 API (ObjectMapper, JsonNode,
 * ObjectNode) rather than Jackson 3, because that's what ships with openapi-diff-core and
 * keeps the agent code consistent with the diff/radar packages it calls into. This bean is
 * what makes that constructor injection resolvable.
 */
@Configuration
public class ObjectMapperConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}