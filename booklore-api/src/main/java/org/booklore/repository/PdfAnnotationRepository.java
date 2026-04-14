package org.booklore.repository;

import org.booklore.model.entity.PdfAnnotationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface PdfAnnotationRepository extends JpaRepository<PdfAnnotationEntity, Long> {

    Optional<PdfAnnotationEntity> findByBookIdAndUserId(Long bookId, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM PdfAnnotationEntity a WHERE a.bookId = :bookId AND a.userId = :userId")
    void deleteByBookIdAndUserId(@Param("bookId") Long bookId, @Param("userId") Long userId);
}
