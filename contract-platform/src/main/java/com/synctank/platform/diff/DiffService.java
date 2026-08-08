package com.synctank.platform.diff;

import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.output.MarkdownRender;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class DiffService {

    private final SeverityClassifier classifier;

    public DiffService(SeverityClassifier classifier) {
        this.classifier = classifier;
    }

    public DiffReport diff(String baselineSpecJson, String candidateSpecJson) {
        ChangedOpenApi changed = OpenApiCompare.fromContents(baselineSpecJson, candidateSpecJson);

        List<ChangeRecord> records = new ArrayList<>();
        records.addAll(classifier.classifyStructuralChanges(changed));
        records.addAll(classifier.classifyDangerousChanges(changed.getOldSpecOpenApi(), changed.getNewSpecOpenApi()));

        Severity highest = records.stream()
                .map(ChangeRecord::severity)
                .max(Comparator.comparingInt(this::rank))
                .orElse(Severity.ADDITIVE);

        String markdown = new MarkdownRender().render(changed);

        return new DiffReport(changed.isDifferent(), highest, records, markdown);
    }

    /** BREAKING outranks DANGEROUS outranks ADDITIVE, for picking the single "headline" severity. */
    private int rank(Severity s) {
        return switch (s) {
            case ADDITIVE -> 0;
            case DANGEROUS -> 1;
            case BREAKING -> 2;
        };
    }
}