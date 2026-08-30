package com.synctank.platform.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every guardrail expressible as configuration lives here rather than as a constant in the
 * service, so a reviewer auditing "what can this thing touch?" has one file to read.
 *
 * Picked up by @ConfigurationPropertiesScan on ContractPlatformApplication — no
 * @EnableConfigurationProperties needed, same as S3Props.
 */
@ConfigurationProperties(prefix = "platform.agent")
public record AgentProperties(

        /** Kill switch. When false every /agent endpoint returns 503. */
        boolean enabled,

        String githubApi,
        String repoOwner,
        String repoName,

        /** Never written to. GitHubClient refuses any commit whose branch equals this. */
        String baseBranch,

        /** Every agent branch starts with this. Makes cleanup a one-line grep. */
        String branchPrefix,

        /** Directory listed to find constructor call sites, repo-relative. */
        String sourceDir,

        /** No file outside this prefix can be written. Checked in GitHubClient, not the caller. */
        String allowedPathPrefix,

        /** Repo key SpecStore holds the baseline under. CI publishes "orders-backend". */
        String specRepoKey,

        /** Fine-grained PAT: Contents RW + Pull requests RW, one repo. Blank = draft-only mode. */
        String githubToken,

        /** Browser origins allowed to call /agent/**. The Angular dev server, normally. */
        String dashboardOrigins
) {
    public boolean hasToken() {
        return githubToken != null && !githubToken.isBlank();
    }

    public String repoSlug() {
        return repoOwner + "/" + repoName;
    }
}