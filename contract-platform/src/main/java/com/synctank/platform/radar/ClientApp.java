package com.synctank.platform.radar;

import jakarta.persistence.*;

import java.time.Instant;

/** One consuming application, e.g. the customer portal Angular app. */
@Entity
@Table(name = "client_app",
        uniqueConstraints = @UniqueConstraint(name = "uk_client_app_name", columnNames = "app_name"))
public class ClientApp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_name", nullable = false)
    private String appName;

    /** Owning team — the "and Team Payments' reconciliation job" half of the pitch line. */
    @Column(name = "team")
    private String team;

    @Column(name = "repo")
    private String repo;

    /** Which generated client build this app compiles against — usually a commit sha. */
    @Column(name = "client_version")
    private String clientVersion;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    protected ClientApp() { /* JPA */ }

    public ClientApp(String appName, String team, String repo, String clientVersion) {
        this.appName = appName;
        this.team = team;
        this.repo = repo;
        this.clientVersion = clientVersion;
        this.registeredAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getAppName() { return appName; }
    public String getTeam() { return team; }
    public String getRepo() { return repo; }
    public String getClientVersion() { return clientVersion; }
    public Instant getRegisteredAt() { return registeredAt; }

    public void setTeam(String team) { this.team = team; }
    public void setRepo(String repo) { this.repo = repo; }
    public void setClientVersion(String clientVersion) { this.clientVersion = clientVersion; }
}