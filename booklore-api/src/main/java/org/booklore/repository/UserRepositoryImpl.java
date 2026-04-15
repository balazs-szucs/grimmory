package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.model.entity.BookLoreUserEntity;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public class UserRepositoryImpl implements UserRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public Optional<BookLoreUserEntity> findByIdWithDetails(Long id) {
        // Query 1: user + settings + permissions (1 ToMany + 1 ToOne — no Cartesian)
        List<BookLoreUserEntity> users = em.createQuery(
                        "SELECT DISTINCT u FROM BookLoreUserEntity u " +
                                "LEFT JOIN FETCH u.settings " +
                                "LEFT JOIN FETCH u.permissions " +
                                "WHERE u.id = :id",
                        BookLoreUserEntity.class)
                .setParameter("id", id)
                .getResultList();
        if (users.isEmpty()) return Optional.empty();

        // Query 2: libraries + libraryPaths on same managed entity (hierarchical — no Cartesian)
        em.createQuery(
                        "SELECT DISTINCT u FROM BookLoreUserEntity u " +
                                "LEFT JOIN FETCH u.libraries l " +
                                "LEFT JOIN FETCH l.libraryPaths " +
                                "WHERE u.id = :id",
                        BookLoreUserEntity.class)
                .setParameter("id", id)
                .getResultList();

        return Optional.of(users.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookLoreUserEntity> findAllWithDetails() {
        // Query 1: all users + settings + permissions
        List<BookLoreUserEntity> users = em.createQuery(
                        "SELECT DISTINCT u FROM BookLoreUserEntity u " +
                                "LEFT JOIN FETCH u.settings " +
                                "LEFT JOIN FETCH u.permissions",
                        BookLoreUserEntity.class)
                .getResultList();
        if (users.isEmpty()) return users;

        // Query 2: all users + libraries + libraryPaths (fills in same persistence context)
        em.createQuery(
                        "SELECT DISTINCT u FROM BookLoreUserEntity u " +
                                "LEFT JOIN FETCH u.libraries l " +
                                "LEFT JOIN FETCH l.libraryPaths",
                        BookLoreUserEntity.class)
                .getResultList();

        return users;
    }
}
