package com.synctank.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The only class in the platform that can write to GitHub.
 *
 * Every guardrail protecting the repository is enforced HERE rather than in the caller, on
 * purpose: a future caller (a second agent, a batch job, a well-meaning controller) gets the
 * protection for free and cannot forget it. The rules:
 *
 *   - a write whose branch equals baseBranch is refused outright
 *   - a write whose branch does not start with branchPrefix is refused
 *   - a write whose path does not start with allowedPathPrefix is refused
 *
 * All three throw before any HTTP call is made.
 *
 * JSON HANDLING: RestClient.builder() auto-detects message converters from the classpath,
 * independently of the Spring context — and Boot 4.1 prefers Jackson 3 (tools.jackson.*) by
 * default. This class works entirely in classic Jackson 2 types (com.fasterxml.jackson.databind
 * JsonNode / ObjectNode), because that's what's available transitively via openapi-diff-core.
 * Rather than fight the converter auto-detection (which means depending on the now-deprecated
 * MappingJackson2HttpMessageConverter), every call here exchanges plain String bodies and does
 * JSON (de)serialization itself with the injected ObjectMapper. String is a universal type no
 * converter dispute can touch.
 *
 * Uses RestClient (Spring Framework 6.1+, present in spring-boot-starter-web) rather than a
 * GitHub SDK: five endpoints do not justify a dependency, and an explicit call is easier for
 * a security reviewer to read than a fluent SDK's hidden defaults.
 */
@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public GitHubClient(AgentProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.githubApi())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "synctank-contract-agent");

        if (props.hasToken()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + props.githubToken());
        }
        this.http = builder.build();
    }

    public boolean isWritable() {
        return props.enabled() && props.hasToken();
    }

    // ---------- reads ----------

    public String headSha(String branch) {
        JsonNode node = getJson("/repos/{owner}/{repo}/git/ref/heads/{branch}",
                props.repoOwner(), props.repoName(), branch);
        return require(node, "object", "sha");
    }

    /** Repo-relative paths of every .java file directly inside {@code dir} on {@code ref}. */
    public List<String> listJavaFiles(String dir, String ref) {
        JsonNode listing = getJson("/repos/{owner}/{repo}/contents/{path}?ref={ref}",
                props.repoOwner(), props.repoName(), dir, ref);

        List<String> paths = new ArrayList<>();
        if (listing != null && listing.isArray()) {
            for (JsonNode entry : listing) {
                String name = entry.path("name").asText("");
                if ("file".equals(entry.path("type").asText()) && name.endsWith(".java")) {
                    paths.add(entry.path("path").asText());
                }
            }
        }
        return paths;
    }

    /** Reads one file. Returns null when the path does not exist on that ref. */
    public SourceFile readFile(String path, String ref) {
        try {
            JsonNode node = getJson("/repos/{owner}/{repo}/contents/{path}?ref={ref}",
                    props.repoOwner(), props.repoName(), path, ref);

            if (node == null || !node.hasNonNull("content")) {
                return null;
            }
            // MIME decoder, not the basic one: the contents API wraps base64 at 60 chars and
            // Base64.getDecoder() throws on the embedded newlines.
            byte[] raw = Base64.getMimeDecoder().decode(node.path("content").asText());
            return new SourceFile(path, new String(raw, StandardCharsets.UTF_8),
                    node.path("sha").asText());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    // ---------- writes ----------

    public void createBranch(String newBranch, String fromSha) {
        guardBranch(newBranch);

        ObjectNode body = mapper.createObjectNode();
        body.put("ref", "refs/heads/" + newBranch);
        body.put("sha", fromSha);

        postJson("/repos/{owner}/{repo}/git/refs", body, props.repoOwner(), props.repoName());

        log.info("Contract agent created branch {} from {}", newBranch, fromSha);
    }

    /**
     * @param sha blob sha of the file as it exists on that branch. Required by the contents API
     *            for updates; a stale one yields 409 rather than a lost update — the behaviour
     *            we want.
     */
    public void commitFile(String branch, String path, String content, String sha, String message) {
        guardBranch(branch);
        guardPath(path);

        ObjectNode body = mapper.createObjectNode();
        body.put("message", message);
        body.put("content", Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.put("branch", branch);
        if (sha != null && !sha.isBlank()) {
            body.put("sha", sha);
        }

        putJson("/repos/{owner}/{repo}/contents/{path}", body,
                props.repoOwner(), props.repoName(), path);

        log.info("Contract agent committed {} on {}", path, branch);
    }

    /** @return the html_url of the new pull request. */
    public String openPullRequest(String head, String base, String title, String bodyText) {
        ObjectNode body = mapper.createObjectNode();
        body.put("title", title);
        body.put("head", head);
        body.put("base", base);
        body.put("body", bodyText);
        body.put("maintainer_can_modify", true);

        JsonNode created = postJsonForResult("/repos/{owner}/{repo}/pulls", body,
                props.repoOwner(), props.repoName());

        String url = created == null ? null : created.path("html_url").asText(null);
        log.info("Contract agent opened pull request {}", url);
        return url;
    }

    // ---------- JSON exchange helpers (String in, String out — no converter dispute) ----------

    private JsonNode getJson(String uri, Object... uriVars) {
        String raw = http.get().uri(uri, uriVars).retrieve().body(String.class);
        return parse(raw);
    }

    private void postJson(String uri, ObjectNode body, Object... uriVars) {
        http.post().uri(uri, uriVars)
                .contentType(MediaType.APPLICATION_JSON)
                .body(writeJson(body))
                .retrieve()
                .toBodilessEntity();
    }

    private JsonNode postJsonForResult(String uri, ObjectNode body, Object... uriVars) {
        String raw = http.post().uri(uri, uriVars)
                .contentType(MediaType.APPLICATION_JSON)
                .body(writeJson(body))
                .retrieve()
                .body(String.class);
        return parse(raw);
    }

    private void putJson(String uri, ObjectNode body, Object... uriVars) {
        http.put().uri(uri, uriVars)
                .contentType(MediaType.APPLICATION_JSON)
                .body(writeJson(body))
                .retrieve()
                .toBodilessEntity();
    }

    private String writeJson(ObjectNode body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize request body: " + e.getMessage(), e);
        }
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("GitHub returned unparseable JSON: " + e.getMessage(), e);
        }
    }

    // ---------- guardrails ----------

    private void guardBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("Refusing a write with no branch.");
        }
        if (branch.equals(props.baseBranch())) {
            throw new IllegalStateException(
                    "Refusing to write directly to the base branch '" + props.baseBranch()
                            + "'. The agent operates through pull requests only.");
        }
        if (!branch.startsWith(props.branchPrefix())) {
            throw new IllegalStateException(
                    "Refusing branch '" + branch + "' — agent branches must start with '"
                            + props.branchPrefix() + "'.");
        }
    }

    private void guardPath(String path) {
        if (path == null || !path.startsWith(props.allowedPathPrefix())) {
            throw new IllegalStateException(
                    "Refusing to write '" + path + "' — outside the allowed path prefix '"
                            + props.allowedPathPrefix() + "'.");
        }
    }

    private String require(JsonNode node, String... path) {
        JsonNode cursor = node;
        for (String key : path) {
            if (cursor == null) break;
            cursor = cursor.get(key);
        }
        if (cursor == null || cursor.isNull()) {
            throw new IllegalStateException(
                    "GitHub response missing " + String.join(".", path) + ": " + node);
        }
        return cursor.asText();
    }
}