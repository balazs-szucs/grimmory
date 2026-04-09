package org.booklore.config;

import org.booklore.config.security.SecurityUtil;
import org.booklore.config.security.annotation.CheckBookAccess;
import org.booklore.config.security.annotation.CheckLibraryAccess;
import org.booklore.config.security.userdetails.KoreaderUserDetails;
import org.booklore.config.security.userdetails.OpdsUserDetails;
import org.booklore.convertor.AudioFileChapterListConverter;
import org.booklore.convertor.BookRecommendationIdsListConverter;
import org.booklore.convertor.FormatPriorityConverter;
import org.booklore.convertor.JpaJsonConverter;
import org.booklore.convertor.MapToStringConverter;
import org.booklore.convertor.SortConverter;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookRecommendationLite;
import org.booklore.model.dto.Sort;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.dto.request.MetadataRefreshRequest;
import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.model.dto.settings.KoboSettings;
import org.booklore.model.dto.settings.MetadataMatchWeights;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.dto.settings.MetadataProviderSpecificFields;
import org.booklore.model.dto.settings.MetadataPublicReviewsSettings;
import org.booklore.model.dto.settings.OidcAutoProvisionDetails;
import org.booklore.model.dto.settings.OidcProviderDetails;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataAuthorKey;
import org.booklore.model.entity.BookMetadataCategoryKey;
import org.booklore.model.entity.BookShelfKey;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.SortDirection;
import org.booklore.model.websocket.BookAddNotification;
import org.booklore.model.websocket.BooksRemoveNotification;
import org.booklore.model.websocket.LibraryHealthPayload;
import org.booklore.model.websocket.LogNotification;
import org.booklore.model.websocket.Severity;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.NotebookEntryRepository;
import org.booklore.repository.projection.BookCoverUpdateProjection;
import org.booklore.repository.projection.BookEmbeddingProjection;
import org.booklore.service.metadata.writer.ComicInfo;
import org.booklore.task.options.LibraryRescanOptions;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

public class GraalVmRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerReflectionHints(hints);
        registerResourceHints(hints);
    }

    private void registerReflectionHints(RuntimeHints hints) {
        MemberCategory[] allAccess = {
            MemberCategory.ACCESS_DECLARED_FIELDS,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS
        };

        // JPA attribute converters instantiated reflectively by Hibernate
        Class<?>[] converters = {
            SortConverter.class,
            MapToStringConverter.class,
            JpaJsonConverter.class,
            FormatPriorityConverter.class,
            BookRecommendationIdsListConverter.class,
            AudioFileChapterListConverter.class,
        };
        for (Class<?> c : converters) {
            hints.reflection().registerType(c, allAccess);
        }

        // TypeReference target types erased at compile time, invisible to AOT
        Class<?>[] typeRefTargets = {
            BookRecommendationLite.class,
            BookFileEntity.AudioFileChapter.class,
            Sort.class,
            SortDirection.class,
            BookFileType.class,
            MetadataRefreshOptions.class,
        };
        for (Class<?> c : typeRefTargets) {
            hints.reflection().registerType(c, allAccess);
        }

        // Settings DTOs  deserialized from DB strings via a generic Jackson helper
        Class<?>[] settingsDtos = {
            OidcProviderDetails.class,
            OidcAutoProvisionDetails.class,
            MetadataProviderSettings.class,
            MetadataMatchWeights.class,
            MetadataPersistenceSettings.class,
            MetadataPublicReviewsSettings.class,
            KoboSettings.class,
            CoverCroppingSettings.class,
            MetadataProviderSpecificFields.class,
        };
        for (Class<?> c : settingsDtos) {
            hints.reflection().registerType(c, allAccess);
        }

        // Jackson polymorphic subtypes
        Class<?>[] jacksonSubtypes = {
            LibraryRescanOptions.class,
            MetadataRefreshRequest.class,
        };
        for (Class<?> c : jacksonSubtypes) {
            hints.reflection().registerType(c, allAccess);
        }

        // Custom Jackson BeanPropertyWriter instantiated reflectively by Jackson
        hints.reflection().registerType(KomgaCleanBeanPropertyWriter.class, allAccess);

        // Spring Data projection interfaces proxied at runtime
        Class<?>[] projections = {
            BookEmbeddingProjection.class,
            BookCoverUpdateProjection.class,
            NotebookEntryRepository.EntryProjection.class,
            NotebookEntryRepository.BookProjection.class,
            NotebookEntryRepository.BookWithCountProjection.class,
            AuthorRepository.AuthorBookProjection.class,
        };
        for (Class<?> c : projections) {
            hints.reflection().registerType(c, allAccess);
            hints.proxies().registerJdkProxy(c);
        }

        // Embeddable composite keys
        Class<?>[] embeddables = {
            BookShelfKey.class,
            BookMetadataCategoryKey.class,
            BookMetadataAuthorKey.class,
        };
        for (Class<?> c : embeddables) {
            hints.reflection().registerType(c, allAccess);
        }

        // Security principal types used in instanceof checks at runtime
        Class<?>[] principals = {
            BookLoreUser.class,
            OpdsUserDetails.class,
            KoreaderUserDetails.class,
        };
        for (Class<?> c : principals) {
            hints.reflection().registerType(c, allAccess);
        }

        // AOP security annotations accessed reflectively by aspect advisors
        hints.reflection().registerType(CheckLibraryAccess.class, allAccess);
        hints.reflection().registerType(CheckBookAccess.class, allAccess);

        // SecurityUtil referenced by SpEL in @PreAuthorize expressions
        hints.reflection().registerType(SecurityUtil.class, allAccess);

        // WebSocket STOMP message payload DTOs serialized by Jackson via message broker
        Class<?>[] websocketDtos = {
            BookAddNotification.class,
            BooksRemoveNotification.class,
            LibraryHealthPayload.class,
            LogNotification.class,
            TaskProgressPayload.class,
            Topic.class,
            Severity.class,
        };
        for (Class<?> c : websocketDtos) {
            hints.reflection().registerType(c, allAccess);
        }

        // JAXB model classes accessed reflectively by jakarta.xml.bind at runtime
        Class<?>[] jaxbTypes = {
            ComicInfo.class,
            ComicInfo.Pages.class,
        };
        for (Class<?> c : jaxbTypes) {
            hints.reflection().registerType(c, allAccess);
        }

        // JJWT 0.13.0 uses Classes.newInstance(String) throughout its static initializers
        // across Jwts, Jwts.SIG/ENC/KEY/ZIP, Jwks, Jwks.OP/CRV/HASH, and Keys.
        // Every such call site is listed here (extracted by scanning all jjwt jars).
        String[] jjwtClasses = {
            // Jwts.<clinit>
            "io.jsonwebtoken.impl.DefaultJwtBuilder$Supplier",
            "io.jsonwebtoken.impl.DefaultJwtParserBuilder$Supplier",
            "io.jsonwebtoken.impl.DefaultJwtHeaderBuilder$Supplier",
            "io.jsonwebtoken.impl.DefaultClaimsBuilder$Supplier",
            // Jwts.SIG/ENC/KEY/ZIP.<clinit>
            "io.jsonwebtoken.impl.security.StandardSecureDigestAlgorithms",
            "io.jsonwebtoken.impl.security.StandardEncryptionAlgorithms",
            "io.jsonwebtoken.impl.security.StandardKeyAlgorithms",
            "io.jsonwebtoken.impl.io.StandardCompressionAlgorithms",
            // Keys.<clinit>
            "io.jsonwebtoken.impl.security.KeysBridge",
            // Jwks.<clinit>
            "io.jsonwebtoken.impl.security.DefaultDynamicJwkBuilder$Supplier",
            "io.jsonwebtoken.impl.security.DefaultJwkParserBuilder$Supplier",
            "io.jsonwebtoken.impl.security.DefaultJwkSetBuilder$Supplier",
            "io.jsonwebtoken.impl.security.DefaultJwkSetParserBuilder$Supplier",
            // Jwks.OP.<clinit>
            "io.jsonwebtoken.impl.security.StandardKeyOperations",
            "io.jsonwebtoken.impl.security.DefaultKeyOperationBuilder$Supplier",
            "io.jsonwebtoken.impl.security.DefaultKeyOperationPolicyBuilder$Supplier",
            // Jwks.CRV.<clinit>
            "io.jsonwebtoken.impl.security.StandardCurves",
            // Jwks.HASH.<clinit>
            "io.jsonwebtoken.impl.security.StandardHashAlgorithms",
        };
        for (String className : jjwtClasses) {
            hints.reflection().registerType(TypeReference.of(className), allAccess);
        }

        // Caffeine cache implementation classes loaded via Class.forName in LocalCacheFactory
        // and node implementation classes loaded via Class.forName in NodeFactory.
        // Each cache config requires both a cache class and a node class.
        String[] caffeineClasses = {
            "com.github.benmanes.caffeine.cache.SIMSA",   // cache: softValues + maxSize + expireAfterAccess
            "com.github.benmanes.caffeine.cache.PDAMS",   // node:  softValues + maxSize + expireAfterAccess
            "com.github.benmanes.caffeine.cache.SSMSW",   // cache: strongValues + maxSize + expireAfterWrite
            "com.github.benmanes.caffeine.cache.PSWMS",   // node:  strongValues + maxSize + expireAfterWrite
        };
        for (String className : caffeineClasses) {
            hints.reflection().registerType(TypeReference.of(className), allAccess);
        }
    }

    private void registerResourceHints(RuntimeHints hints) {
        // Freemarker templates for EPUB generation
        hints.resources().registerPattern("templates/epub/**");

        // Static assets used at runtime
        hints.resources().registerPattern("static/images/**");
        hints.resources().registerPattern("static/scalar.html");

        // Flyway SQL migrations
        hints.resources().registerPattern("db/migration/**");

        // Application configuration
        hints.resources().registerPattern("application*.properties");
        hints.resources().registerPattern("application*.yml");
        hints.resources().registerPattern("application*.yaml");

        // SPI service descriptors (TwelveMonkeys ImageIO, JAXB, JJWT, etc.)
        hints.resources().registerPattern("META-INF/services/**");
    }

}
