package org.booklore.repository;

import org.booklore.model.entity.ShelfEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShelfRepository extends JpaRepository<ShelfEntity, Long> {

    boolean existsByUserIdAndName(Long id, String name);

    @EntityGraph(attributePaths = "bookEntities")
    List<ShelfEntity> findByUserId(Long id);

    @EntityGraph(attributePaths = "bookEntities")
    Optional<ShelfEntity> findByUserIdAndName(Long id, String name);

    @EntityGraph(attributePaths = "bookEntities")
    @org.springframework.data.jpa.repository.Query("SELECT s FROM ShelfEntity s WHERE s.user.id = :userId OR s.isPublic = true")
    List<ShelfEntity> findByUserIdOrPublicShelfTrue(@org.springframework.data.repository.query.Param("userId") Long userId);

    List<ShelfEntity> findByUserIdInAndName(List<Long> userIds, String name);
}
