package com.synctank.platform.radar;

import jakarta.persistence.*;

/**
 * One proven use site: this app, at this file and line, references this contract location.
 *
 * `location` is stored in EXACTLY the format Day 03's SeverityClassifier emits —
 * "OrderResponse.amount" for fields, "GET /api/orders/{id}" for endpoints — so the
 * radar join is a plain string equality with ChangeRecord.location(). No mapping layer,
 * nothing to keep in sync. If Day 03's format ever changes, this is the file that breaks.
 */
@Entity
@Table(name = "client_usage",
        indexes = @Index(name = "idx_client_usage_location", columnList = "location"))
public class ClientUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // EAGER on purpose: open-in-view is false (see application.yaml), and the radar reads
    // app.getAppName()/getTeam() outside any transaction while building its response.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "app_id", nullable = false)
    private ClientApp app;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private UsageKind kind;

    @Column(name = "location", nullable = false, length = 512)
    private String location;

    /**
     * The endpoint whose payload carries this location — for FIELD rows, the operation whose
     * request or response references the owning schema. This is what lets a field-level change
     * inherit an endpoint's calls/day. For ENDPOINT rows it is the location itself.
     */
    @Column(name = "owning_endpoint", length = 512)
    private String owningEndpoint;

    /** Angular route/module folder, e.g. "order-detail". The "which screen?" half of the report. */
    @Column(name = "screen")
    private String screen;

    @Column(name = "source_file", length = 1024)
    private String sourceFile;

    @Column(name = "source_line")
    private Integer sourceLine;

    protected ClientUsage() { /* JPA */ }

    public ClientUsage(ClientApp app, UsageKind kind, String location, String owningEndpoint,
                       String screen, String sourceFile, Integer sourceLine) {
        this.app = app;
        this.kind = kind;
        this.location = location;
        this.owningEndpoint = owningEndpoint;
        this.screen = screen;
        this.sourceFile = sourceFile;
        this.sourceLine = sourceLine;
    }

    public Long getId() { return id; }
    public ClientApp getApp() { return app; }
    public UsageKind getKind() { return kind; }
    public String getLocation() { return location; }
    public String getOwningEndpoint() { return owningEndpoint; }
    public String getScreen() { return screen; }
    public String getSourceFile() { return sourceFile; }
    public Integer getSourceLine() { return sourceLine; }
}