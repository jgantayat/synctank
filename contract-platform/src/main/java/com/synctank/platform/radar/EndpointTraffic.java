package com.synctank.platform.radar;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Runtime evidence: how many calls per day one app makes to one endpoint.
 *
 * Phase 5's plan offers two sources — a Spring HandlerInterceptor in orders-backend, or
 * API-gateway log ingestion. Neither is built today; this table is populated over HTTP.
 * For the challenge demo that is legitimate AND MUST BE SAID OUT LOUD on the slide:
 * "telemetry seeded for demo; the interceptor is a fifty-line class we chose not to
 * fake data through." Claiming live telemetry you don't have is the one thing that
 * will lose you the room if a judge probes it.
 */
@Entity
@Table(name = "endpoint_traffic",
        uniqueConstraints = @UniqueConstraint(name = "uk_traffic_app_location",
                columnNames = {"app_id", "location"}))
public class EndpointTraffic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "app_id", nullable = false)
    private ClientApp app;

    /** Endpoint form only, e.g. "GET /api/orders/{id}". */
    @Column(name = "location", nullable = false, length = 512)
    private String location;

    @Column(name = "calls_per_day", nullable = false)
    private long callsPerDay;

    @Column(name = "last_called_at")
    private Instant lastCalledAt;

    protected EndpointTraffic() { /* JPA */ }

    public EndpointTraffic(ClientApp app, String location, long callsPerDay) {
        this.app = app;
        this.location = location;
        this.callsPerDay = callsPerDay;
        this.lastCalledAt = Instant.now();
    }

    public Long getId() { return id; }
    public ClientApp getApp() { return app; }
    public String getLocation() { return location; }
    public long getCallsPerDay() { return callsPerDay; }
    public Instant getLastCalledAt() { return lastCalledAt; }

    public void setCallsPerDay(long callsPerDay) {
        this.callsPerDay = callsPerDay;
        this.lastCalledAt = Instant.now();
    }
}