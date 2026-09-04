package com.orderflow.event.repository;

import com.orderflow.event.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetch unpublished events ordered by creation time.
     * The LIMIT is controlled by the caller via Pageable or native query
     * to avoid loading unbounded result sets.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published = FALSE
            ORDER BY created_at ASC
            LIMIT :batchSize
            """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedBatch(@Param("batchSize") int batchSize);
}
