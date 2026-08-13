package com.synctank.platform.diff;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.model.Endpoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class SeverityClassifier {

    public List<ChangeRecord> classifyStructuralChanges(ChangedOpenApi diff) {
        List<ChangeRecord> records = new ArrayList<>();

        for (Endpoint added : diff.getNewEndpoints()) {
            String loc = added.getMethod() + " " + added.getPathUrl();
            records.add(new ChangeRecord(
                    Severity.ADDITIVE,
                    "ENDPOINT_ADDED",
                    loc,
                    "New endpoint " + loc + " added — safe, no existing consumer is affected."
            ));
        }

        for (Endpoint removed : diff.getMissingEndpoints()) {
            String loc = removed.getMethod() + " " + removed.getPathUrl();
            records.add(new ChangeRecord(
                    Severity.BREAKING,
                    "ENDPOINT_REMOVED",
                    loc,
                    "Endpoint " + loc + " was removed — any consumer still calling it will get a 404."
            ));
        }

        return records;
    }

    /**
     * Field-level breaking changes: a property disappearing entirely, or a property
     * that keeps its name but changes shape underneath it. Both are structurally
     * invisible to openapi-diff's own endpoint-level add/remove comparison, but both
     * are exactly the kind of change that makes generated TypeScript fail to compile
     * at every use site — the canonical BREAKING case from your own worked example
     * (double amount -> Money total).
     */
    public List<ChangeRecord> classifyFieldChanges(OpenAPI oldApi, OpenAPI newApi) {
        List<ChangeRecord> records = new ArrayList<>();

        Map<String, Schema> oldSchemas = schemasOf(oldApi);
        Map<String, Schema> newSchemas = schemasOf(newApi);

        for (Map.Entry<String, Schema> entry : newSchemas.entrySet()) {
            String schemaName = entry.getKey();
            Schema<?> newSchema = entry.getValue();
            Schema<?> oldSchema = oldSchemas.get(schemaName);
            if (oldSchema == null || oldSchema.getProperties() == null || newSchema.getProperties() == null) {
                continue; // brand-new schema entirely — nothing to compare yet
            }

            @SuppressWarnings("unchecked")
            Map<String, Schema> oldProps = oldSchema.getProperties();
            @SuppressWarnings("unchecked")
            Map<String, Schema> newProps = newSchema.getProperties();

            // FIELD_REMOVED — a property that existed before is simply gone now.
            for (String propName : oldProps.keySet()) {
                if (!newProps.containsKey(propName)) {
                    String location = schemaName + "." + propName;
                    records.add(new ChangeRecord(
                            Severity.BREAKING,
                            "FIELD_REMOVED",
                            location,
                            "'" + location + "' was removed — generated TypeScript no longer has this "
                                    + "property; every use site fails to compile."
                    ));
                }
            }

            // FIELD_TYPE_CHANGED — same property name survives, but its shape changed.
            for (Map.Entry<String, Schema> propEntry : newProps.entrySet()) {
                String propName = propEntry.getKey();
                Schema<?> newProp = propEntry.getValue();
                Schema<?> oldProp = oldProps.get(propName);
                if (oldProp == null) continue; // brand-new field — additive, not this rule's concern

                if (typeChanged(oldProp, newProp)) {
                    String location = schemaName + "." + propName;
                    records.add(new ChangeRecord(
                            Severity.BREAKING,
                            "FIELD_TYPE_CHANGED",
                            location,
                            "'" + location + "' changed from " + describe(oldProp) + " to " + describe(newProp)
                                    + " — generated TypeScript changes shape; every use site fails to compile."
                    ));
                }
            }
        }

        return records;
    }

    public List<ChangeRecord> classifyDangerousChanges(OpenAPI oldApi, OpenAPI newApi) {
        List<ChangeRecord> records = new ArrayList<>();

        Map<String, Schema> oldSchemas = schemasOf(oldApi);
        Map<String, Schema> newSchemas = schemasOf(newApi);

        for (Map.Entry<String, Schema> entry : newSchemas.entrySet()) {
            String schemaName = entry.getKey();
            Schema<?> newSchema = entry.getValue();
            Schema<?> oldSchema = oldSchemas.get(schemaName);
            if (oldSchema == null || oldSchema.getProperties() == null || newSchema.getProperties() == null) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Schema> oldProps = oldSchema.getProperties();
            @SuppressWarnings("unchecked")
            Map<String, Schema> newProps = newSchema.getProperties();

            for (Map.Entry<String, Schema> propEntry : newProps.entrySet()) {
                String propName = propEntry.getKey();
                Schema<?> newProp = propEntry.getValue();
                Schema<?> oldProp = oldProps.get(propName);
                if (oldProp == null) continue;

                String location = schemaName + "." + propName;
                records.addAll(nullabilityFlip(location, oldProp, newProp));
                records.addAll(enumNarrowed(location, oldProp, newProp));
                records.addAll(validationTightened(location, oldProp, newProp));
            }
        }

        return records;
    }

    private List<ChangeRecord> nullabilityFlip(String location, Schema<?> oldProp, Schema<?> newProp) {
        boolean oldNullable = Boolean.TRUE.equals(oldProp.getNullable());
        boolean newNullable = Boolean.TRUE.equals(newProp.getNullable());
        if (!oldNullable && newNullable) {
            return List.of(new ChangeRecord(
                    Severity.DANGEROUS,
                    "NULLABILITY_FLIP",
                    location,
                    "'" + location + "' was non-nullable and is now nullable — frontend code "
                            + "assuming a value exists may break at runtime, even though TypeScript won't flag it."
            ));
        }
        return List.of();
    }

    private List<ChangeRecord> enumNarrowed(String location, Schema<?> oldProp, Schema<?> newProp) {
        List<?> oldEnum = oldProp.getEnum();
        List<?> newEnum = newProp.getEnum();
        if (oldEnum == null || newEnum == null) return List.of();

        List<Object> removedValues = new ArrayList<>(oldEnum);
        removedValues.removeAll(newEnum);
        if (removedValues.isEmpty()) return List.of();

        return List.of(new ChangeRecord(
                Severity.DANGEROUS,
                "ENUM_NARROWED",
                location,
                "'" + location + "' no longer allows: " + removedValues
                        + " — any frontend branch checking for these values is now dead code."
        ));
    }

    private List<ChangeRecord> validationTightened(String location, Schema<?> oldProp, Schema<?> newProp) {
        List<ChangeRecord> records = new ArrayList<>();
        addIfTightened(records, location, "minLength", oldProp.getMinLength(), newProp.getMinLength(), true);
        addIfTightened(records, location, "maxLength", oldProp.getMaxLength(), newProp.getMaxLength(), false);
        addIfTightened(records, location, "minimum", oldProp.getMinimum(), newProp.getMinimum(), true);
        addIfTightened(records, location, "maximum", oldProp.getMaximum(), newProp.getMaximum(), false);
        return records;
    }

    private void addIfTightened(List<ChangeRecord> records, String location, String constraint,
                                Number oldBound, Number newBound, boolean isLowerBound) {
        if (oldBound == null || newBound == null) return;
        double oldVal = oldBound.doubleValue();
        double newVal = newBound.doubleValue();
        boolean tightened = isLowerBound ? newVal > oldVal : newVal < oldVal;
        if (!tightened) return;

        records.add(new ChangeRecord(
                Severity.DANGEROUS,
                "VALIDATION_TIGHTENED",
                location,
                "'" + location + "' " + constraint + " tightened from " + oldBound + " to " + newBound
                        + " — requests valid under the old contract may now be rejected."
        ));
    }

    private boolean typeChanged(Schema<?> oldProp, Schema<?> newProp) {
        return !typeSignature(oldProp).equals(typeSignature(newProp));
    }

    private String typeSignature(Schema<?> schema) {
        if (schema.get$ref() != null) {
            return "ref:" + schema.get$ref();
        }
        if ("array".equals(schema.getType()) && schema.getItems() != null) {
            return "array<" + typeSignature(schema.getItems()) + ">";
        }
        String type = schema.getType() != null ? schema.getType() : "unknown";
        String format = schema.getFormat();
        return format != null ? type + ":" + format : type;
    }

    private String describe(Schema<?> schema) {
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            return ref.substring(ref.lastIndexOf('/') + 1);
        }
        return schema.getType() != null ? schema.getType() : "unknown";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Schema> schemasOf(OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
            return Collections.emptyMap();
        }
        return api.getComponents().getSchemas();
    }
}