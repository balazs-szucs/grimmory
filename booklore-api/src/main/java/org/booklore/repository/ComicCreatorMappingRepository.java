package org.booklore.repository;

import org.booklore.model.entity.ComicCreatorMappingEntity;
import org.booklore.model.enums.ComicCreatorRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ComicCreatorMappingRepository extends JpaRepository<ComicCreatorMappingEntity, Long> {

    List<ComicCreatorMappingEntity> findByComicMetadataBookId(Long bookId);

    List<ComicCreatorMappingEntity> findByComicMetadataBookIdAndRole(Long bookId, ComicCreatorRole role);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM ComicCreatorMappingEntity m WHERE m.comicMetadata.bookId = :bookId")
    void deleteByComicMetadataBookId(@Param("bookId") Long bookId);
}
