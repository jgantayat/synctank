package com.synctank.platform.config.secrets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;

import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Day 09 — reads one JSON secret from AWS Secrets Manager (or LocalStack) and returns it as a
 * flat map. Nothing here knows what the keys mean; SecretMapping owns that.
 *
 * DefaultCredentialsProvider, never static keys (Decision D3). It resolves the SDK's standard
 * chain: environment variables and ~/.aws/credentials on a developer machine, the ECS task role
 * on Day 11's deployment. The same code path works in both, and no bootstrap credential is ever
 * written into a config file — which is the whole point of the day.
 *
 * A fresh classic ObjectMapper is constructed here rather than injected. There is no Spring
 * context yet when this runs; that is not an oversight, it is the entire reason the mechanism
 * is an ApplicationContextInitializer (see F6 in the guide's audit).
 *
 * ERROR MESSAGES NEVER ECHO A VALUE. They name the secret id and the endpoint, which are
 * configuration, and the exception type, which is diagnosis.
 */
final class AwsSecretBundleLoader {

    private AwsSecretBundleLoader() {}

    static Map<String, String> fetch(String region, String endpoint, String secretId) {
        String json;
        try {
            json = readSecretString(region, endpoint, secretId);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Could not read secret '" + secretId + "' from "
                            + describeTarget(region, endpoint) + " — "
                            + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + ". Refusing to start on the plaintext defaults in application.yaml.", e);
        }

        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Secret '" + secretId + "' has no secretString. "
                    + "A binary secret is not supported — store a JSON object.");
        }
        return parse(json, secretId);
    }

    private static String readSecretString(String region, String endpoint, String secretId) {
        SecretsManagerClientBuilder builder = SecretsManagerClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (endpoint != null && !endpoint.isBlank()) {
            builder = builder.endpointOverride(URI.create(endpoint));
        }

        try (SecretsManagerClient client = builder.build()) {
            return client.getSecretValue(request -> request.secretId(secretId)).secretString();
        }
    }

    private static Map<String, String> parse(String json, String secretId) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (JsonProcessingException e) {
            // Deliberately does not include the payload in the message.
            throw new IllegalStateException("Secret '" + secretId + "' is not valid JSON: "
                    + e.getOriginalMessage());
        }
        if (!root.isObject()) {
            throw new IllegalStateException("Secret '" + secretId
                    + "' must be a JSON object of credential name to value.");
        }

        Map<String, String> bundle = new LinkedHashMap<>();
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode value = root.get(name);
            bundle.put(name, value == null || value.isNull() ? "" : value.asText());
        }
        return bundle;
    }

    private static String describeTarget(String region, String endpoint) {
        return (endpoint == null || endpoint.isBlank())
                ? "AWS Secrets Manager in " + region
                : endpoint + " (endpoint override)";
    }
}