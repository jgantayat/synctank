package com.synctank.platform.radar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface EndpointTrafficRepository extends JpaRepository<EndpointTraffic, Long> {

    Optional<EndpointTraffic> findByAppAndLocation(ClientApp app, String location);

    List<EndpointTraffic> findByApp(ClientApp app);

    @Transactional
    void deleteByApp(ClientApp app);
}