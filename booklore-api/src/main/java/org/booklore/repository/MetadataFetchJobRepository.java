package org.booklore.repository;
import org.springframework.data.repository.query.Param;

import org.booklore.model.entity.MetadataFetchJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface MetadataFetchJobRepository extends JpaRepository<MetadataFetchJobEntity, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM MetadataFetchJobEntity m WHERE m.completedAt < :cutoff")
    int deleteAllByCompletedAtBefore(@Param("cutoff") Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM MetadataFetchJobEntity")
    int deleteAllRecords();

    @Query("SELECT DISTINCT t FROM MetadataFetchJobEntity t LEFT JOIN FETCH t.proposals")
    List<MetadataFetchJobEntity> findAllWithProposals();
}
