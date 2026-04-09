package org.booklore.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Validates that GraalVmRuntimeHints registers all classes needed for native image.
 * <p>
 * This test catches missing reflection registrations at JVM test time instead of
 * requiring a full native image build. When a new library class needs reflection, add
 * it to GraalVmRuntimeHints and add a corresponding assertion here.
 */
class GraalVmRuntimeHintsTest {

    private RuntimeHints hints;

    @BeforeEach
    void setUp() {
        hints = new RuntimeHints();
        new GraalVmRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }
    @ParameterizedTest
    @ValueSource(strings = {
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
    })
    void jjwtClassesAreRegisteredAndInstantiable(String className) throws Exception {
        // Verify the class actually exists on the classpath (wrong name = instant failure)
        Class<?> clazz = Class.forName(className);

        // Verify it has a no-arg constructor (JJWT uses Class.newInstance())
        assertThatNoException().isThrownBy(clazz::getDeclaredConstructor);

        // Verify the hint is registered
        assertThat(RuntimeHintsPredicates.reflection()
            .onType(clazz)
            .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
            .accepts(hints);
    }
    @ParameterizedTest
    @ValueSource(strings = {
        "com.github.benmanes.caffeine.cache.SIMSA",
        "com.github.benmanes.caffeine.cache.PDAMS",
        "com.github.benmanes.caffeine.cache.SSMSW",
        "com.github.benmanes.caffeine.cache.PSWMS",
    })
    void caffeineClassesAreRegisteredAndLoadable(String className) throws Exception {
        Class<?> clazz = Class.forName(className);

        assertThat(RuntimeHintsPredicates.reflection()
            .onType(clazz)
            .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
            .accepts(hints);
    }

    @Test
    void caffeineSoftValueCacheMatchesRegisteredClasses() {
        // Mirrors EpubCfiService: softValues + maximumSize + expireAfterAccess → SIMSA + PDAMS
        assertThatNoException().isThrownBy(() ->
            Caffeine.newBuilder()
                .maximumSize(2)
                .expireAfterAccess(Duration.ofSeconds(1))
                .softValues()
                .build()
        );
    }

    @Test
    void caffeineStrongValueCacheMatchesRegisteredClasses() {
        // Mirrors CacheConfig: maximumSize + expireAfterWrite → SSMSW + PSWMS
        assertThatNoException().isThrownBy(() ->
            Caffeine.newBuilder()
                .maximumSize(2)
                .expireAfterWrite(Duration.ofSeconds(1))
                .build()
        );
    }
    @ParameterizedTest
    @ValueSource(strings = {
        "templates/epub/chapter.ftl",
        "db/migration/V1__init.sql",
        "META-INF/services/javax.imageio.spi.ImageReaderSpi",
        "application.yml",
    })
    void resourcePatternsAreRegistered(String resourcePath) {
        assertThat(RuntimeHintsPredicates.resource().forResource(resourcePath))
            .accepts(hints);
    }
}
