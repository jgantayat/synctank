package com.synctank.platform.spec;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/specs")
public class SpecController {

    private final SpecStore store;

    public SpecController(SpecStore store) {
        this.store = store;
    }

    /** Store one spec version, keyed by repo + commit. */
    @PutMapping(value = "/{repo}/{commit}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void store(@PathVariable String repo,
                      @PathVariable String commit,
                      @RequestBody String specJson) {
        store.putSpec(repo, commit, specJson);
    }

    /** Mark a commit as the published baseline (body = the commit sha). */
    @PutMapping(value = "/{repo}/baseline", consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public void publishBaseline(@PathVariable String repo, @RequestBody String commit) {
        if (commit == null || commit.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body must contain the commit sha");
        }
        store.setBaseline(repo, commit);
    }

    /** Fetch the current baseline spec. */
    @GetMapping(value = "/{repo}/baseline", produces = MediaType.APPLICATION_JSON_VALUE)
    public String baseline(@PathVariable String repo) {
        return store.getBaselineSpec(repo);
    }

    /**
     * Day 08 -- the spec timeline's data source.
     *
     * GET rather than POST, and read-only: this is the only route under /specs that
     * DashboardCorsConfig exposes to a browser. See Decision 5.
     */
    @GetMapping(value = "/{repo}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SpecStore.SpecVersion> history(
            @PathVariable String repo,
            @RequestParam(defaultValue = "25") int limit) {
        return store.listVersions(repo, limit);
    }
}