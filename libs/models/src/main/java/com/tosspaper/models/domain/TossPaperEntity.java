package com.tosspaper.models.domain;

/**
 * Marker interface for domain entities with String ID.
 * Entities implementing this interface must have a String id field
 * and provide a getId() method (typically via Lombok @Data).
 */
public interface TossPaperEntity {

    /**
     * Get the unique identifier for this entity.
     * @return the entity ID (UUID/ULID format)
     */
    String getId();
}
