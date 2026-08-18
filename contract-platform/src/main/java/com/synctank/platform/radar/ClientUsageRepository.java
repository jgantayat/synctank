package com.synctank.platform.radar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ClientUsageRepository extends JpaRepository<ClientUsage, Long> {

    /** The radar's only hot path: exact-match on Day 03's location() string. */
    List<ClientUsage> findByLocation(String location);

    List<ClientUsage> findByApp(ClientApp app);

    /**
     * Re-seeding is destructive by design: CI re-derives an app's entire usage set from
     * the baseline spec on every run, so stale rows from a previous contract shape must
     * not survive. Derived delete queries need their own transaction.
     */
    @Transactional
    void deleteByApp(ClientApp app);
}