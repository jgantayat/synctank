package com.synctank.platform.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRequestRepository extends JpaRepository<AgentRequest, Long> {
    List<AgentRequest> findAllByOrderByCreatedAtDesc();
}