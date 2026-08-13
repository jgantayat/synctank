package com.synctank.platform.diff;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SeverityClassifierTest {

    private final DiffService diffService = new DiffService(new SeverityClassifier());

    @Test
    void additiveChangesAreClassifiedAsAdditive() throws IOException {
        DiffReport report = diffFixtures("additive-before.json", "additive-after.json");

        assertThat(report.changed()).isTrue();
        assertThat(report.highestSeverity()).isEqualTo(Severity.ADDITIVE);
        assertThat(categoriesOf(report)).contains("ENDPOINT_ADDED");
    }

    @Test
    void endpointFieldRemovalAndTypeChangesAreClassifiedAsBreaking() throws IOException {
        DiffReport report = diffFixtures("breaking-before.json", "breaking-after.json");

        assertThat(report.highestSeverity()).isEqualTo(Severity.BREAKING);
        assertThat(categoriesOf(report))
                .contains("ENDPOINT_REMOVED", "FIELD_REMOVED", "FIELD_TYPE_CHANGED");
    }

    @Test
    void nullabilityEnumAndValidationChangesAreClassifiedAsDangerous() throws IOException {
        DiffReport report = diffFixtures("dangerous-before.json", "dangerous-after.json");

        assertThat(report.highestSeverity()).isEqualTo(Severity.DANGEROUS);
        assertThat(categoriesOf(report))
                .contains("NULLABILITY_FLIP", "ENUM_NARROWED", "VALIDATION_TIGHTENED");
    }

    private DiffReport diffFixtures(String beforeFile, String afterFile) throws IOException {
        return diffService.diff(readFixture(beforeFile), readFixture(afterFile));
    }

    private String readFixture(String name) throws IOException {
        ClassPathResource resource = new ClassPathResource("diff-fixtures/" + name);
        return Files.readString(resource.getFile().toPath(), StandardCharsets.UTF_8);
    }

    private Set<String> categoriesOf(DiffReport report) {
        return report.changes().stream().map(ChangeRecord::category).collect(Collectors.toSet());
    }
}