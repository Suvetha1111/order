package com.orderflow.event.repository;

import com.orderflow.event.entity.ProcessedEvent;
import com.orderflow.event.entity.ProcessedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);
}
