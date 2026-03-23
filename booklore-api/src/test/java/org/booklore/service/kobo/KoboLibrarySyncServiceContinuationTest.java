package org.booklore.service.kobo;

import tools.jackson.databind.ObjectMapper;
import org.booklore.model.dto.kobo.KoboHeaders;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ShelfType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.KoboLibrarySnapshotRepository;
import org.booklore.repository.KoboUserSettingsRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Kobo sync continuation tokens.
 * 
 * Tests verify that large libraries are synced in batches using the x-kobo-sync: continue header
 * pattern (ported from Komga's KoboControllerTest).
 * 
 * Research basis:
 * - GitHub Issue #1276: Large library timeout issues
 * - Komga pattern: sync-item-limit=1 for testing continuation
 * - Calibre-Web pattern: x-kobo-sync header handling
 * 
 * Note: Uses MockMvc to properly exercise the full request pipeline including
 * header parsing (X-Kobo-Synctoken) which is how the real device communicates.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // Force continuation by limiting sync batch size
    "grimmory.kobo.sync-item-limit=2"
})
@Transactional
class KoboLibrarySyncServiceContinuationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private ShelfRepository shelfRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private KoboLibrarySnapshotRepository snapshotRepository;

    @Autowired
    private KoboUserSettingsRepository koboUserSettingsRepository;

    @Autowired
    private EntityManager entityManager;

    private String authToken;
    private ShelfEntity koboShelf;
    private BookLoreUserEntity testUser;
    private LibraryEntity library;
    private LibraryPathEntity libraryPath;

    @BeforeEach
    void setUp() throws Exception {
        // Create library first (no path field — uses LibraryPathEntity)
        library = LibraryEntity.builder()
                .name("Kobo Test Library")
                .build();
        entityManager.persist(library);
        entityManager.flush();

        // Create library path
        libraryPath = LibraryPathEntity.builder()
                .library(library)
                .path("/test/books")
                .build();
        entityManager.persist(libraryPath);
        entityManager.flush();

        // Create test user
        testUser = BookLoreUserEntity.builder()
                .email("kobo-test@example.com")
                .username("kobotest")
                .passwordHash("$2a$10$dummyhash")
                .name("Kobo Test User")
                .createdAt(java.time.LocalDateTime.now())
                .isDefaultPassword(false)
                .libraries(new java.util.HashSet<>())
                .build();
        entityManager.persist(testUser);
        entityManager.flush();

        // Associate user with library (owning side is user)
        testUser.getLibraries().add(library);
        entityManager.merge(testUser);
        entityManager.flush();
        
        // Create or get Kobo shelf
        koboShelf = shelfRepository.findByUserIdAndName(testUser.getId(), ShelfType.KOBO.getName())
                .orElseGet(() -> {
                    ShelfEntity shelf = ShelfEntity.builder()
                            .name(ShelfType.KOBO.getName())
                            .user(testUser)
                            .build();
                    entityManager.persist(shelf);
                    entityManager.flush();
                    return shelf;
                });
        
        // Generate auth token and register it in kobo_user_settings (required for security filter)
        authToken = java.util.UUID.randomUUID().toString();
        KoboUserSettingsEntity settings = KoboUserSettingsEntity.builder()
                .userId(testUser.getId())
                .token(authToken)
                .syncEnabled(true)
                .syncItemLimit(2)  // Match test property
                .build();
        koboUserSettingsRepository.save(settings);
    }

    @Test
    void givenLargeLibrary_whenSyncing_thenUsesContinuationTokens() throws Exception {
        // Given: User with 5 books on Kobo shelf (more than sync-item-limit of 2)
        createTestBooksOnKoboShelf(5);
        
        // When: First sync (should return 2 books + continue header)
        var mvcResult1 = mockMvc.perform(get("/api/kobo/{token}/v1/library/sync", authToken)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(header().string(KoboHeaders.X_KOBO_SYNC, "continue"))
            .andExpect(header().exists(KoboHeaders.X_KOBO_SYNCTOKEN))
            .andReturn();
        
        String syncToken1 = mvcResult1.getResponse().getHeader(KoboHeaders.X_KOBO_SYNCTOKEN);
        assertThat(syncToken1).isNotNull().isNotBlank();
        
        // Verify response contains books (should be 2 based on limit)
        var entitlements1 = objectMapper.readTree(mvcResult1.getResponse().getContentAsString());
        assertThat(entitlements1.size()).isEqualTo(2);
        
        // When: Second sync with token (should return next batch)
        var mvcResult2 = mockMvc.perform(get("/api/kobo/{token}/v1/library/sync", authToken)
                .header(KoboHeaders.X_KOBO_SYNCTOKEN, syncToken1)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(header().string(KoboHeaders.X_KOBO_SYNC, "continue"))
            .andReturn();
        
        String syncToken2 = mvcResult2.getResponse().getHeader(KoboHeaders.X_KOBO_SYNCTOKEN);
        var entitlements2 = objectMapper.readTree(mvcResult2.getResponse().getContentAsString());
        assertThat(entitlements2.size()).isEqualTo(2);
        
        // When: Third sync (should complete sync)
        var mvcResult3 = mockMvc.perform(get("/api/kobo/{token}/v1/library/sync", authToken)
                .header(KoboHeaders.X_KOBO_SYNCTOKEN, syncToken2)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            // No continue header = sync complete
            .andExpect(result -> {
                String syncHeader = result.getResponse().getHeader(KoboHeaders.X_KOBO_SYNC);
                assertThat(syncHeader).isNotEqualTo("continue");
            })
            .andReturn();
        
        var entitlements3 = objectMapper.readTree(mvcResult3.getResponse().getContentAsString());
        assertThat(entitlements3.size()).isEqualTo(1);  // Remaining book
    }

    @Test
    void givenSmallLibrary_whenSyncing_thenNoContinuationNeeded() throws Exception {
        // Given: User with 1 book on Kobo shelf (less than sync-item-limit)
        createTestBooksOnKoboShelf(1);
        
        // When: Sync
        var mvcResult = mockMvc.perform(get("/api/kobo/{token}/v1/library/sync", authToken)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            // No continue header for small libraries
            .andExpect(result -> {
                String syncHeader = result.getResponse().getHeader(KoboHeaders.X_KOBO_SYNC);
                assertThat(syncHeader).isNotEqualTo("continue");
            })
            .andReturn();
        
        var entitlements = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(entitlements.size()).isEqualTo(1);
    }

    @Test
    void givenEmptyLibrary_whenSyncing_thenReturnsEmptyResponse() throws Exception {
        // Given: User with no books on Kobo shelf
        
        // When: Sync
        var mvcResult = mockMvc.perform(get("/api/kobo/{token}/v1/library/sync", authToken)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        
        var entitlements = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(entitlements.isEmpty()).isTrue();
    }

    // Helper methods

    private void createTestBooksOnKoboShelf(int count) {
        for (int i = 0; i < count; i++) {
            BookEntity book = createTestBook("Test Book " + i);
            koboShelf.getBookEntities().add(book);
        }
        shelfRepository.save(koboShelf);
    }

    private BookEntity createTestBook(String title) {
        String safeTitle = title.replaceAll("\\s+", "_");

        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(libraryPath)
                .addedOn(Instant.now())
                .deleted(false)
                .bookFiles(new ArrayList<>())
                .build();
        entityManager.persist(book);
        entityManager.flush();

        // Create metadata (title/author live here)
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book)
                .bookId(book.getId())
                .title(title)
                .build();
        entityManager.persist(metadata);
        entityManager.flush();

        // Create primary book file with currentHash (required for snapshot)
        BookFileEntity bookFile = BookFileEntity.builder()
                .book(book)
                .bookType(BookFileType.EPUB)
                .isBookFormat(true)
                .fileName(safeTitle + ".epub")
                .fileSubPath("")
                .fileSizeKb(1L)
                .currentHash("test-hash-" + safeTitle)
                .build();
        entityManager.persist(bookFile);
        entityManager.flush();

        book.getBookFiles().add(bookFile);
        entityManager.merge(book);
        entityManager.flush();

        return book;
    }
}
