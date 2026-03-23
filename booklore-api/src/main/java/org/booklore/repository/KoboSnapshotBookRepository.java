package org.booklore.repository;


import org.booklore.model.entity.KoboSnapshotBookEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KoboSnapshotBookRepository extends JpaRepository<KoboSnapshotBookEntity, Long> {

    Page<KoboSnapshotBookEntity> findBySnapshot_IdAndSyncedFalse(String snapshotId, Pageable pageable);

    @Modifying
    @Query("UPDATE KoboSnapshotBookEntity b SET b.synced = true WHERE b.snapshot.id = :snapshotId AND b.bookId IN :bookIds")
    void markBooksSynced(@Param("snapshotId") String snapshotId, @Param("bookIds") List<Long> bookIds);

    @Query("""
                SELECT curr
                FROM KoboSnapshotBookEntity curr
                WHERE curr.snapshot.id = :currSnapshotId
                  AND curr.bookId IN (
                      SELECT prev.bookId
                      FROM KoboSnapshotBookEntity prev
                      WHERE prev.snapshot.id = :prevSnapshotId
                  )
            """)
    List<KoboSnapshotBookEntity> findExistingBooksBetweenSnapshots(
            @Param("prevSnapshotId") String prevSnapshotId,
            @Param("currSnapshotId") String currSnapshotId
    );

    @Query("""
            SELECT curr
            FROM KoboSnapshotBookEntity curr
            WHERE curr.snapshot.id = :currSnapshotId
              AND (:unsyncedOnly = false OR curr.synced = false)
              AND curr.bookId NOT IN (
                    SELECT prev.bookId
                    FROM KoboSnapshotBookEntity prev
                    WHERE prev.snapshot.id = :prevSnapshotId
              )
            """)
    Page<KoboSnapshotBookEntity> findNewlyAddedBooks(
            @Param("prevSnapshotId") String prevSnapshotId,
            @Param("currSnapshotId") String currSnapshotId,
            @Param("unsyncedOnly") boolean unsyncedOnly,
            Pageable pageable
    );

    @Query("""
                SELECT prev
                FROM KoboSnapshotBookEntity prev
                WHERE prev.snapshot.id = :prevSnapshotId
                  AND prev.bookId NOT IN (
                      SELECT curr.bookId
                      FROM KoboSnapshotBookEntity curr
                      WHERE curr.snapshot.id = :currSnapshotId
                  )
                  AND prev.bookId NOT IN (
                      SELECT p.bookIdSynced
                      FROM KoboDeletedBookProgressEntity p
                      WHERE p.snapshotId = :currSnapshotId
                  )
            """)
    Page<KoboSnapshotBookEntity> findRemovedBooks(
            @Param("prevSnapshotId") String prevSnapshotId,
            @Param("currSnapshotId") String currSnapshotId,
            Pageable pageable
    );

    @Query("""
                SELECT curr
                FROM KoboSnapshotBookEntity curr
                JOIN KoboSnapshotBookEntity prev
                    ON curr.bookId = prev.bookId
                WHERE curr.snapshot.id = :currSnapshotId
                  AND prev.snapshot.id = :prevSnapshotId
                  AND curr.fileHash = prev.fileHash
                  AND (curr.metadataUpdatedAt = prev.metadataUpdatedAt OR (curr.metadataUpdatedAt IS NULL AND prev.metadataUpdatedAt IS NULL))
                  AND (curr.coverHash = prev.coverHash OR (curr.coverHash IS NULL AND prev.coverHash IS NULL))
                  AND (curr.readProgressLastModified = prev.readProgressLastModified OR (curr.readProgressLastModified IS NULL AND prev.readProgressLastModified IS NULL))
            """)
    List<KoboSnapshotBookEntity> findUnchangedBooksBetweenSnapshots(
            @Param("prevSnapshotId") String prevSnapshotId,
            @Param("currSnapshotId") String currSnapshotId
    );

    @Query("""
                SELECT curr
                FROM KoboSnapshotBookEntity curr
                JOIN KoboSnapshotBookEntity prev
                    ON curr.bookId = prev.bookId
                WHERE curr.snapshot.id = :currSnapshotId
                  AND prev.snapshot.id = :prevSnapshotId
                  AND curr.synced = false
                  AND (
                      curr.fileHash <> prev.fileHash
                      OR (curr.metadataUpdatedAt <> prev.metadataUpdatedAt AND curr.metadataUpdatedAt IS NOT NULL AND prev.metadataUpdatedAt IS NOT NULL)
                      OR (curr.metadataUpdatedAt IS NOT NULL AND prev.metadataUpdatedAt IS NULL)
                      OR (curr.coverHash <> prev.coverHash AND curr.coverHash IS NOT NULL AND prev.coverHash IS NOT NULL)
                      OR (curr.coverHash IS NOT NULL AND prev.coverHash IS NULL)
                      OR (curr.bookFileSize <> prev.bookFileSize AND curr.bookFileSize IS NOT NULL AND prev.bookFileSize IS NOT NULL)
                      OR (curr.bookFileSize IS NOT NULL AND prev.bookFileSize IS NULL)
                  )
            """)
    Page<KoboSnapshotBookEntity> findChangedBooks(
            @Param("prevSnapshotId") String prevSnapshotId,
            @Param("currSnapshotId") String currSnapshotId,
            Pageable pageable
    );

    /**
     * Find books that exist in both snapshots, are unchanged in file/metadata/cover,
     * but whose read progress has changed. Mirrors Komga's findBooksReadProgressChanged.
     */
    @Query("""
                SELECT curr
                FROM KoboSnapshotBookEntity curr
                JOIN KoboSnapshotBookEntity prev
                    ON curr.bookId = prev.bookId
                WHERE curr.snapshot.id = :currSnapshotId
                  AND prev.snapshot.id = :prevSnapshotId
                  AND curr.synced = false
                  AND curr.fileHash = prev.fileHash
                  AND (curr.metadataUpdatedAt = prev.metadataUpdatedAt OR (curr.metadataUpdatedAt IS NULL AND prev.metadataUpdatedAt IS NULL))
                  AND (curr.coverHash = prev.coverHash OR (curr.coverHash IS NULL AND prev.coverHash IS NULL))
                  AND (
                      (curr.readProgressLastModified <> prev.readProgressLastModified
                          AND curr.readProgressLastModified IS NOT NULL AND prev.readProgressLastModified IS NOT NULL)
                      OR (curr.readProgressLastModified IS NOT NULL AND prev.readProgressLastModified IS NULL)
                      OR (curr.readProgressLastModified IS NULL AND prev.readProgressLastModified IS NOT NULL)
                  )
            """)
    Page<KoboSnapshotBookEntity> findBooksReadProgressChanged(
            @Param("prevSnapshotId") String prevSnapshotId,
            @Param("currSnapshotId") String currSnapshotId,
            Pageable pageable
    );

}
