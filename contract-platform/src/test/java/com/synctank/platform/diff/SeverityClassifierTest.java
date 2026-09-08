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
        // The fixture has always added OrderResponse.note; until this fix nothing classified it.
        assertThat(categoriesOf(report)).contains("ENDPOINT_ADDED", "FIELD_ADDED");
    }

    @Test
    void addedOptionalFieldIsAdditiveAndNamedByLocation() throws IOException {
        DiffReport report = diffFixtures("additive-before.json", "additive-after.json");

        ChangeRecord added = report.changes().stream()
                .filter(c -> "FIELD_ADDED".equals(c.category()))
                .findFirst().orElseThrow();

        assertThat(added.location()).isEqualTo("OrderResponse.note");
        assertThat(added.severity()).isEqualTo(Severity.ADDITIVE);
    }

    @Test
    void addedRequiredFieldIsDangerousNotAdditive() throws IOException {
        DiffReport report = diffFixtures("required-add-before.json", "required-add-after.json");

        assertThat(report.highestSeverity()).isEqualTo(Severity.DANGEROUS);
        assertThat(categoriesOf(report)).contains("REQUIRED_FIELD_ADDED");
    }

    @Test
    void brandNewSchemaDoesNotProduceOneRowPerProperty() throws IOException {
        // breaking-after.json introduces Money wholesale. Its properties must NOT each become
        // a FIELD_ADDED row — the endpoint-level change already says what a reviewer needs.
        DiffReport report = diffFixtures("breaking-before.json", "breaking-after.json");

        assertThat(report.changes()).noneMatch(c -> c.location().startsWith("Money."));
        assertThat(report.highestSeverity()).isEqualTo(Severity.BREAKING);
    }

    @Test
    void endpointFieldRemovalAndTypeChangesAreClassifiedAsBreaking() throws IOException {
        DiffReport report = diffFixtures("breaking-before.json", "breaking-after.json");

        assertThat(report.highestSeverity()).isEqualTo(Severity.BREAKING);
        assertThat(categoriesOf(report)).contains("ENDPOINT_REMOVED", "FIELD_REMOVED", "FIELD_TYPE_CHANGED");
    }

    @Test
    void nullabilityEnumAndValidationChangesAreClassifiedAsDangerous() throws IOException {
        DiffReport report = diffFixtures("dangerous-before.json", "dangerous-after.json");

        assertThat(report.highestSeverity()).isEqualTo(Severity.DANGEROUS);
        assertThat(categoriesOf(report)).contains("NULLABILITY_FLIP", "ENUM_NARROWED", "VALIDATION_TIGHTENED");
    }

    @Test
    void nullabilityFlipIsDetectedInOpenApi31TypeUnions() throws IOException {
        // The 3.0 fixtures cover `nullable: true`. This is the dialect springdoc ACTUALLY
        // emits: {"type": ["string", "null"]}. Before Day 08 this produced no DANGEROUS
        // record at all -- the rule was dead against every real spec the pipeline made.
        DiffReport report = diffFixtures("dangerous31-before.json", "dangerous31-after.json");

        assertThat(categoriesOf(report)).contains("NULLABILITY_FLIP");
        assertThat(report.highestSeverity()).isEqualTo(Severity.DANGEROUS);
    }

    @Test
    void nullableUnionIsNotMistakenForABreakingTypeChange() throws IOException {
        // The second half of the bug: taking the first element of {"string","null"} could
        // yield "null", making the type signature change from "string" to "null" and
        // classifying a merely-dangerous change as BREAKING -- which FAILS the CI gate.
        DiffReport report = diffFixtures("dangerous31-before.json", "dangerous31-after.json");

        assertThat(categoriesOf(report)).doesNotContain("FIELD_TYPE_CHANGED");
        assertThat(report.changes())
                .noneMatch(c -> c.severity() == Severity.BREAKING);
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