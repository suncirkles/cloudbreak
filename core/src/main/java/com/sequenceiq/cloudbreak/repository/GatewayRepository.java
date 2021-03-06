package com.sequenceiq.cloudbreak.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sequenceiq.cloudbreak.domain.stack.cluster.gateway.Gateway;

@EntityType(entityClass = Gateway.class)
public interface GatewayRepository extends JpaRepository<Gateway, Long> {
}
