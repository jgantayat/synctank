package com.synctank.platform.config.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Day 09 — pulls the platform's credentials from AWS Secrets Manager into the Environment
 * BEFORE any bean is created.
 *
 * WHY AN INITIALIZER AND NOT A BEAN (this is the load-bearing design decision):
 * GitHubClient bakes the token into its RestClient inside its constructor, and AgentProperties
 * is an immutable record bound once during context refresh. Anything that fetches secrets from
 * inside the application context therefore runs too late — the values must already be in the
 * Environment when binding happens. ApplicationContextInitializers run in prepareContext():
 * after application.yaml has been loaded into the Environment, before any bean definition is
 * bound. That is exactly the window this needs.
 *
 * Registered explicitly in ContractPlatformApplication.main() rather than through
 * META-INF/spring.factories. Explicit wiring cannot be broken by a change in framework
 * component discovery, and a reviewer can see the whole mechanism by opening main().
 *
 * addFirst() means fetched values outrank environment variables, YAML defaults and command-line
 * arguments. That is intended: when provider=aws, Secrets Manager is authoritative.
 *
 * NOTHING IN THIS CLASS EVER LOGS A CREDENTIAL.
 */
public class SecretsInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String PROPERTY_SOURCE_NAME = "synctankSecrets";
    public static final String STATUS_BEAN_NAME = "secretsStatus";

    private static final Logger log = LoggerFactory.getLogger(SecretsInitializer.class);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();

        String provider = environment.getProperty("platform.secrets.provider", "env")
                .trim().toLowerCase(Locale.ROOT);

        SecretsStatus status = switch (provider) {
            case "env" -> localStatus(environment);
            case "aws" -> loadFromAws(environment);
            default -> throw new IllegalStateException(
                    "Unknown platform.secrets.provider '" + provider + "'. Valid values: env, aws.");
        };

        registerStatusBean(context, status);
    }

    // ------------------------------------------------------------------
    // provider = env — does no work at all, which is what makes CI's behaviour
    // unchanged by construction rather than by care.
    // ------------------------------------------------------------------

    private SecretsStatus localStatus(ConfigurableEnvironment environment) {
        List<SecretsStatus.KeyStatus> keys = new ArrayList<>();

        for (SecretMapping.Entry entry : SecretMapping.ENTRIES) {
            String value;
            try {
                value = environment.getProperty(entry.springProperty());
            } catch (RuntimeException e) {
                // An unresolvable ${PLACEHOLDER} with no default. Report it rather than taking
                // the whole boot down from a diagnostics path — the real failure, if it is one,
                // will surface where the property is actually used.
                value = null;
            }
            boolean present = value != null && !value.isBlank();
            keys.add(new SecretsStatus.KeyStatus(entry.springProperty(), entry.purpose(),
                    present, present ? "local" : "absent", SecretsStatus.fingerprint(value)));
        }
        return new SecretsStatus("env", null, keys);
    }

    // ------------------------------------------------------------------
    // provider = aws
    // ------------------------------------------------------------------

    private SecretsStatus loadFromAws(ConfigurableEnvironment environment) {
        String secretId = environment.getProperty("platform.secrets.secret-id", "synctank/platform");
        String region = environment.getProperty("platform.secrets.region", "us-east-1");
        String endpoint = environment.getProperty("platform.secrets.endpoint", "");

        Map<String, String> bundle = AwsSecretBundleLoader.fetch(region, endpoint, secretId);
        Binding binding = bind(bundle);

        if (!binding.missingRequired().isEmpty()) {
            throw new IllegalStateException("Secret '" + secretId + "' is missing required key(s): "
                    + String.join(", ", binding.missingRequired())
                    + ". Refusing to start: continuing would silently run on the plaintext "
                    + "defaults in application.yaml, which is the exact failure this mechanism "
                    + "exists to prevent.");
        }

        environment.getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, binding.properties()));

        log.info("Loaded {} credential(s) from secret '{}' via {} — values are never logged; "
                        + "see GET /health/secrets for presence and fingerprints.",
                binding.properties().size(), secretId,
                endpoint.isBlank() ? "AWS Secrets Manager (" + region + ")" : endpoint);

        return new SecretsStatus("aws", secretId, binding.keys());
    }

    // ------------------------------------------------------------------
    // pure mapping — no AWS, no Spring, unit-testable on its own
    // ------------------------------------------------------------------

    record Binding(Map<String, Object> properties,
                   List<SecretsStatus.KeyStatus> keys,
                   List<String> missingRequired) {}

    static Binding bind(Map<String, String> bundle) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<SecretsStatus.KeyStatus> keys = new ArrayList<>();
        List<String> missingRequired = new ArrayList<>();

        for (SecretMapping.Entry entry : SecretMapping.ENTRIES) {
            String value = bundle.get(entry.jsonKey());
            boolean present = value != null && !value.isBlank();

            if (present) {
                properties.put(entry.springProperty(), value);
            } else if (entry.required()) {
                missingRequired.add(entry.jsonKey());
            }

            keys.add(new SecretsStatus.KeyStatus(entry.springProperty(), entry.purpose(),
                    present, present ? "aws" : "absent", SecretsStatus.fingerprint(value)));
        }
        return new Binding(properties, keys, missingRequired);
    }

    // ------------------------------------------------------------------

    private void registerStatusBean(ConfigurableApplicationContext context, SecretsStatus status) {
        if (context instanceof GenericApplicationContext generic) {
            generic.getBeanFactory().registerSingleton(STATUS_BEAN_NAME, status);
        } else {
            log.warn("Application context {} is not a GenericApplicationContext — /health/secrets "
                            + "will report 'uninitialised'. Credential loading is unaffected.",
                    context.getClass().getSimpleName());
        }
    }
}