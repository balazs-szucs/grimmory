package org.booklore.service.library;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookRestorationService {

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final NotificationService notificationService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void restoreDeletedBooks(List<LibraryFile> libraryFiles) {
        if (libraryFiles.isEmpty()) return;

        LibraryEntity libraryEntity = libraryFiles.getFirst().getLibraryEntity();
        Long libraryId = libraryEntity.getId();
        
        // Map of full path to library file for fast lookup
        java.util.Map<java.nio.file.Path, LibraryFile> pathMap = libraryFiles.stream()
                .collect(java.util.stream.Collectors.toMap(LibraryFile::getFullPath, f -> f, (a, b) -> a));

        List<BookEntity> deletedBooks = bookRepository.findDeletedByLibraryIdWithFiles(libraryId);
        if (deletedBooks.isEmpty()) return;

        java.util.List<BookEntity> toRestore = new java.util.ArrayList<>();
        
        for (BookEntity book : deletedBooks) {
            if (!book.hasFiles()) continue;
            
            BookFileEntity primaryFile = book.getPrimaryBookFile();
            if (primaryFile == null) continue;

            // Try exact path match first (fastest)
            if (pathMap.containsKey(book.getFullFilePath())) {
                LibraryFile lf = pathMap.get(book.getFullFilePath());
                restoreBook(book, lf);
                toRestore.add(book);
                continue;
            }

            // Fallback: match by hash (canonical identity)
            // This handles cases where books were moved between folders
            String currentHash = primaryFile.getCurrentHash();
            if (currentHash != null) {
                Optional<LibraryFile> hashMatch = libraryFiles.stream()
                        .filter(lf -> {
                            // Only compute hash for potentially matching files (size check)
                            // Or if LibraryFile already has hash (it currently doesn't during scan)
                            // For now, we'll use a more conservative approach to avoid massive I/O
                            // but in a real 'smart scan' we'd have hashes for everything new.
                            return false; // Placeholder for future hash-based matching
                        })
                        .findFirst();
                
                // Note: Identity continuity is tricky without hashes for all new files.
                // If we want to support moves during rescan, we must hash "new" files.
            }
        }

        if (toRestore.isEmpty()) return;

        bookRepository.saveAll(toRestore);
        log.info("Restored {} books in library: {}", toRestore.size(), libraryEntity.getName());
    }

    private void restoreBook(BookEntity book, LibraryFile lf) {
        book.setDeleted(false);
        book.setDeletedAt(null);
        book.setAddedOn(Instant.now());
        
        // Update file metadata if it changed
        BookFileEntity primaryFile = book.getPrimaryBookFile();
        if (primaryFile != null) {
            primaryFile.setLastModified(lf.getLastModified());
            primaryFile.setLastScannedAt(Instant.now());
        }
        
        notificationService.sendMessage(Topic.BOOK_ADD, bookMapper.toBookWithDescription(book, false));
    }
}
