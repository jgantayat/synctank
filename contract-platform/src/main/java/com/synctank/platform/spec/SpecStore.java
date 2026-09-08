package com.synctank.platform.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synctank.platform.config.S3Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Service
public class SpecStore {

    private static final Logger log = LoggerFactory.getLogger(SpecStore.class);

    /** The keys under a path item that count as an operation when tallying endpoints. */
    private static final Set<String> OPERATIONS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private final S3Client s3;
    private final ObjectMapper mapper;
    private final String bucket;

    // Day 08: ObjectMapper is injected rather than constructed. Boot 4.1 auto-configures
    // Jackson 3 and registers no classic ObjectMapper bean of its own -- Day 07's
    // ObjectMapperConfig supplies this one. Constructing a private mapper here would work
    // but would quietly fork configuration away from the rest of the platform.
    public SpecStore(S3Client s3, ObjectMapper mapper, S3Props props) {
        this.s3 = s3;
        this.mapper = mapper;
        this.bucket = props.bucket();
    }

    /**
     * One stored spec version. `baseline` marks the commit that /specs/{repo}/baseline
     * currently points at, so the dashboard can annotate it without a second round trip.
     */
    public record SpecVersion(String commit, Instant storedAt, long sizeBytes,
                              int endpointCount, int schemaCount, boolean baseline) {}

    public void putSpec(String repo, String commit, String specJson) {
        s3.putObject(b -> b.bucket(bucket).key(specKey(repo, commit)),
                RequestBody.fromString(specJson));
    }

    public void setBaseline(String repo, String commit) {
        s3.putObject(b -> b.bucket(bucket).key(baselineKey(repo)),
                RequestBody.fromString(commit.trim()));
    }

    public String getBaselineSpec(String repo) {
        String commit = s3.getObjectAsBytes(b -> b.bucket(bucket).key(baselineKey(repo)))
                .asUtf8String().trim();
        return s3.getObjectAsBytes(b -> b.bucket(bucket).key(specKey(repo, commit)))
                .asUtf8String();
    }

    /**
     * The commit the baseline pointer names, or null when no baseline has been published.
     *
     * A fresh bucket legitimately has no BASELINE object -- that is the state of every CI
     * runner before the first merge to main. Returning null rather than propagating
     * NoSuchKeyException keeps listVersions() working on a brand-new environment.
     */
    public String baselineCommit(String repo) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(baselineKey(repo)))
                    .asUtf8String().trim();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /**
     * Every stored spec version for a repo, newest `limit` versions, returned oldest-first.
     *
     * Oldest-first is for the chart's x-axis; the cap is applied to the NEWEST versions
     * before the reversal, so a long-lived bucket shows recent history rather than ancient
     * history. See Decision 3.
     */
    public List<SpecVersion> listVersions(String repo, int limit) {
        String prefix = specPrefix(repo);
        String baseline = baselineCommit(repo);

        List<S3Object> objects = new ArrayList<>();
        s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix))
                .contents()
                .forEach(objects::add);

        // The BASELINE pointer lives under the same prefix and is NOT a spec -- it holds a
        // bare commit sha. Filtering on the .json suffix excludes it without a special case.
        List<S3Object> newestFirst = objects.stream()
                .filter(o -> o.key().endsWith(".json"))
                .sorted(Comparator.comparing(S3Object::lastModified).reversed())
                .limit(Math.max(1, limit))
                .toList();

        List<SpecVersion> versions = new ArrayList<>();
        for (S3Object object : newestFirst) {
            String file = object.key().substring(prefix.length());
            String commit = file.substring(0, file.length() - ".json".length());

            int endpoints = 0;
            int schemas = 0;
            try {
                String json = s3.getObjectAsBytes(b -> b.bucket(bucket).key(object.key()))
                        .asUtf8String();
                JsonNode spec = mapper.readTree(json);
                endpoints = countEndpoints(spec);
                schemas = countSchemas(spec);
            } catch (Exception e) {
                // Decision 4: one unreadable object must not take the whole timeline down.
                log.warn("Could not parse stored spec {} -- reporting zero counts", object.key(), e);
            }

            versions.add(new SpecVersion(commit, object.lastModified(), object.size(),
                    endpoints, schemas, commit.equals(baseline)));
        }

        return versions.reversed();   // Java 21 SequencedCollection
    }

    private int countEndpoints(JsonNode spec) {
        JsonNode paths = spec.get("paths");
        if (paths == null) {
            return 0;
        }
        int total = 0;
        for (JsonNode pathItem : paths) {
            Iterator<String> keys = pathItem.fieldNames();
            while (keys.hasNext()) {
                if (OPERATIONS.contains(keys.next().toLowerCase())) {
                    total++;
                }
            }
        }
        return total;
    }

    private int countSchemas(JsonNode spec) {
        JsonNode components = spec.get("components");
        if (components == null) {
            return 0;
        }
        JsonNode schemas = components.get("schemas");
        return schemas == null ? 0 : schemas.size();
    }

    private String specPrefix(String repo) {
        return "specs/%s/".formatted(repo);
    }

    private String specKey(String repo, String commit) {
        return "specs/%s/%s.json".formatted(repo, commit);
    }

    private String baselineKey(String repo) {
        return "specs/%s/BASELINE".formatted(repo);
    }
}