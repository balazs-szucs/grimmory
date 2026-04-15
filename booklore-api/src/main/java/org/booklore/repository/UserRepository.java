package org.booklore.repository;

import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.enums.ProvisioningMethod;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<BookLoreUserEntity, Long>, UserRepositoryCustom {

    Optional<BookLoreUserEntity> findByUsername(String username);

    Optional<BookLoreUserEntity> findByEmail(String email);

    /**
     * Fetch a user with settings and permissions (without libraries) for settings-related operations.
     */
    @Query("SELECT DISTINCT u FROM BookLoreUserEntity u LEFT JOIN FETCH u.settings LEFT JOIN FETCH u.permissions WHERE u.id = :id")
    Optional<BookLoreUserEntity> findByIdWithSettings(@Param("id") Long id);

    /**
     * Fetch a user with libraries and permissions for authorization/library-access operations.
     */
    @Query("SELECT DISTINCT u FROM BookLoreUserEntity u LEFT JOIN FETCH u.libraries l LEFT JOIN FETCH l.libraryPaths LEFT JOIN FETCH u.permissions WHERE u.id = :id")
    Optional<BookLoreUserEntity> findByIdWithLibraries(@Param("id") Long id);

    /**
     * Fetch a user with just permissions for security checks or simple profile updates.
     */
    @Query("SELECT DISTINCT u FROM BookLoreUserEntity u LEFT JOIN FETCH u.permissions WHERE u.id = :id")
    Optional<BookLoreUserEntity> findByIdWithPermissions(@Param("id") Long id);

    long countByProvisioningMethod(ProvisioningMethod provisioningMethod);

    Optional<BookLoreUserEntity> findByOidcIssuerAndOidcSubject(String oidcIssuer, String oidcSubject);
}

