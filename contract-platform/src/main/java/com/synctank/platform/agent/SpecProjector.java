package com.synctank.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Produces the candidate spec the backend WOULD emit, by adding one property to one component
 * schema of the baseline.
 *
 * Why not run springdoc for real (Decision 2): orders-backend's springdoc plugin boots the
 * whole application on port 8080 to scrape /v3/api-docs — ~40 seconds and a port conflict
 * inside what is supposed to be a live dashboard interaction.
 *
 * The mappings below are read off orders-backend/contract/openapi.baseline.json, not guessed:
 *   OrderResponse.id           Long   -> {"type":"integer","format":"int64"}
 *   OrderResponse.customerName String -> {"type":"string"}
 *   OrderResponse.amount       double -> {"type":"number","format":"double"}
 *
 * No entry is added to `required`: springdoc marks a record component required only when it
 * carries a validation annotation (verified — CreateOrderRequest.customerName has @NotBlank
 * and IS required; amount has @Positive and is NOT). The agent adds no annotations, so the
 * field is optional, which is the whole reason the change is additive.
 *
 * SAY IT ON THE SLIDE: the preview is a projection. CI regenerates the authoritative spec on
 * the agent's own pull request within ninety seconds — the loop closing, not a caveat.
 */
@Component
public class SpecProjector {

    private final ObjectMapper mapper;

    public SpecProjector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String addProperty(String baselineSpecJson, String schemaName, String fieldName, String javaType) {
        try {
            JsonNode root = mapper.readTree(baselineSpecJson);
            JsonNode schemasNode = root.path("components").path("schemas");
            if (!schemasNode.isObject()) {
                throw new IllegalStateException("Baseline spec has no components.schemas object.");
            }
            JsonNode schemaNode = schemasNode.get(schemaName);
            if (schemaNode == null || !schemaNode.isObject()) {
                throw new IllegalStateException(
                        "Baseline spec has no component schema named '" + schemaName + "'.");
            }

            ObjectNode schema = (ObjectNode) schemaNode;
            ObjectNode properties = schema.has("properties") && schema.get("properties").isObject()
                    ? (ObjectNode) schema.get("properties")
                    : schema.putObject("properties");

            if (properties.has(fieldName)) {
                throw new IllegalStateException(
                        "'" + schemaName + "." + fieldName + "' already exists in the baseline spec.");
            }
            properties.set(fieldName, propertyFor(javaType));

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Baseline spec is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** True when the schema exists in the baseline — used by guardrail G4. */
    public boolean schemaExists(String baselineSpecJson, String schemaName) {
        try {
            return mapper.readTree(baselineSpecJson)
                    .path("components").path("schemas").has(schemaName);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    private ObjectNode propertyFor(String javaType) {
        ObjectNode node = mapper.createObjectNode();
        switch (javaType) {
            case "String" -> node.put("type", "string");
            case "Long" -> { node.put("type", "integer"); node.put("format", "int64"); }
            case "Integer" -> { node.put("type", "integer"); node.put("format", "int32"); }
            case "Double" -> { node.put("type", "number"); node.put("format", "double"); }
            case "Boolean" -> node.put("type", "boolean");
            default -> throw new IllegalArgumentException(
                    "No spec projection defined for Java type '" + javaType
                            + "'. RecordFieldEditor.ALLOWED_TYPES and this switch must stay in step.");
        }
        return node;
    }
}