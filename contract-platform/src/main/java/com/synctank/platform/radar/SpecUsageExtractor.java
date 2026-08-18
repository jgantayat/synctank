package com.synctank.platform.radar;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads an OpenAPI document and reports two things the registry needs:
 *   1. every operation, its generated method name (operationId), and the schemas it carries;
 *   2. every component schema and its property names.
 *
 * Deliberately reads the SPEC, not openapi-generator's .openapi-generator/FILES manifest.
 * See §2.3 of the Day 06 guide — src/app/generated/ is gitignored, so FILES is not
 * reliably present, and it describes what was emitted rather than what is used.
 */
@Component
public class SpecUsageExtractor {

    /**
     * @param location    Day 03 format: "GET /api/orders/{id}"
     * @param operationId springdoc emits the Java method name, e.g. "getOrder" — which is
     *                    also the method openapi-generator puts on the TypeScript service,
     *                    so it is directly greppable in the Angular source.
     * @param schemas     component schema names this operation's request or response carries
     */
    public record EndpointInfo(String location, String operationId, Set<String> schemas) {}

    public record SpecUsage(List<EndpointInfo> endpoints, Map<String, List<String>> schemaFields) {}

    public SpecUsage extract(String specJson) {
        ParseOptions options = new ParseOptions();
        // resolve=false keeps $ref values intact. If refs were inlined we could no longer
        // tell which component schema an operation carries, which is the whole point here.
        options.setResolve(false);

        OpenAPI api = new OpenAPIV3Parser().readContents(specJson, null, options).getOpenAPI();
        if (api == null) {
            return new SpecUsage(List.of(), Map.of());
        }

        List<EndpointInfo> endpoints = new ArrayList<>();
        if (api.getPaths() != null) {
            for (Map.Entry<String, PathItem> pathEntry : api.getPaths().entrySet()) {
                String path = pathEntry.getKey();
                Map<PathItem.HttpMethod, Operation> ops = pathEntry.getValue().readOperationsMap();
                for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : ops.entrySet()) {
                    Operation op = opEntry.getValue();
                    // Matches SeverityClassifier: added.getMethod() + " " + added.getPathUrl()
                    String location = opEntry.getKey().name() + " " + path;

                    Set<String> schemas = new LinkedHashSet<>();
                    collectFromRequestBody(op.getRequestBody(), schemas);
                    collectFromResponses(op.getResponses(), schemas);

                    endpoints.add(new EndpointInfo(location, op.getOperationId(), schemas));
                }
            }
        }

        Map<String, List<String>> schemaFields = new LinkedHashMap<>();
        Map<String, Schema> components = componentsOf(api);
        for (Map.Entry<String, Schema> entry : components.entrySet()) {
            Schema<?> schema = entry.getValue();
            if (schema.getProperties() == null) {
                continue;
            }
            schemaFields.put(entry.getKey(), new ArrayList<>(schema.getProperties().keySet()));
        }

        return new SpecUsage(endpoints, schemaFields);
    }

    private void collectFromRequestBody(RequestBody body, Set<String> out) {
        if (body != null) {
            collectFromContent(body.getContent(), out);
        }
    }

    private void collectFromResponses(ApiResponses responses, Set<String> out) {
        if (responses == null) return;
        for (ApiResponse response : responses.values()) {
            if (response != null) {
                collectFromContent(response.getContent(), out);
            }
        }
    }

    private void collectFromContent(Content content, Set<String> out) {
        if (content == null) return;
        content.values().forEach(mediaType -> collectRefs(mediaType.getSchema(), out));
    }

    /**
     * Walks an inline schema down to the component names it references.
     * Terminates on $ref without recursing into the referenced component, so a
     * self-referencing schema cannot loop.
     */
    @SuppressWarnings("unchecked")
    private void collectRefs(Schema<?> schema, Set<String> out) {
        if (schema == null) return;
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            out.add(ref.substring(ref.lastIndexOf('/') + 1));
            return;
        }
        if (schema.getItems() != null) {
            collectRefs(schema.getItems(), out);   // List<OrderResponse>
        }
        if (schema.getProperties() != null) {
            ((Map<String, Schema>) schema.getProperties()).values()
                    .forEach(child -> collectRefs(child, out));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Schema> componentsOf(OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
            return Collections.emptyMap();
        }
        return api.getComponents().getSchemas();
    }
}