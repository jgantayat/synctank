package com.synctank.platform;

import org.junit.jupiter.api.Test;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;

import static org.assertj.core.api.Assertions.assertThat;

class ScratchDiffTest {
    @Test
    void librarySmokeTest() {
        String oldSpec = "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"paths\":{}}";
        String newSpec = "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"paths\":{\"/x\":{\"get\":{\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";

        ChangedOpenApi diff = OpenApiCompare.fromContents(oldSpec, newSpec);

        assertThat(diff.isDifferent()).isTrue();
        assertThat(diff.getNewEndpoints()).hasSize(1);
    }
}