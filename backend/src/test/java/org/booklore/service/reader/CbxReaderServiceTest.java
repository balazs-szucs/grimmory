package org.booklore.service.reader;

import org.booklore.exception.ApiError;
import org.booklore.exception.APIException;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.ArchiveService;
import org.booklore.service.FileStreamingService;
import org.booklore.service.reader.ChapterCacheService;
import org.booklore.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CbxReaderServiceTest {

    @Mock
    BookRepository bookRepository;

    @Mock
    ArchiveService archiveService;

    @Mock
    ChapterCacheService chapterCacheService;

    @Mock
    FileStreamingService fileStreamingService;

    @InjectMocks
    CbxReaderService cbxReaderService;

    @Captor
    ArgumentCaptor<Long> longCaptor;

    @TempDir
    Path tempDir;

    BookEntity bookEntity;
    Path cbzPath;

    @BeforeEach
    void setup() throws Exception {
        bookEntity = new BookEntity();
        bookEntity.setId(1L);
        cbzPath = tempDir.resolve("test.cbz");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(cbzPath))) {
            ZipEntry entry = new ZipEntry("1.jpg");
            zos.putNextEntry(entry);
            zos.write(new byte[]{1, 2, 3});
            zos.closeEntry();
        }
    }

    @Test
    void testGetAvailablePages_ThrowsOnMissingBook() {
        when(bookRepository.findByIdForStreaming(2L)).thenReturn(Optional.empty());
        assertThrows(ApiError.BOOK_NOT_FOUND.createException().getClass(), () -> cbxReaderService.getAvailablePages(2L));
    }

    @Test
    void testGetAvailablePages_Success() throws Exception {
        when(bookRepository.findByIdForStreaming(1L)).thenReturn(Optional.of(bookEntity));
        when(archiveService.streamEntryNames(cbzPath)).then((i) -> Stream.of("1.jpg"));

        try (MockedStatic<FileUtils> fileUtilsStatic = mockStatic(FileUtils.class)) {
            fileUtilsStatic.when(() -> FileUtils.getBookFullPath(bookEntity)).thenReturn(cbzPath);

            cbxReaderService.initCache(1L, null);

            List<Integer> pages = cbxReaderService.getAvailablePages(1L);
            assertEquals(List.of(1), pages);
        }
    }

    @Test
    void testStreamPageImage_Success() throws Exception {
        when(bookRepository.findByIdForStreaming(1L)).thenReturn(Optional.of(bookEntity));
        when(archiveService.streamEntryNames(cbzPath)).then((i) -> Stream.of("1.jpg"));
        
        // Return a dummy path for the cached page
        Path dummyPath = tempDir.resolve("dummy.jpg");
        when(chapterCacheService.getCachedPage(anyString(), anyInt())).thenReturn(dummyPath);
        
        try (MockedStatic<FileUtils> fileUtilsStatic = mockStatic(FileUtils.class)) {
            fileUtilsStatic.when(() -> FileUtils.getBookFullPath(bookEntity)).thenReturn(cbzPath);

            cbxReaderService.initCache(1L, null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cbxReaderService.streamPageImage(1L, 1, out);
            assertArrayEquals(new byte[]{1, 2, 3}, out.toByteArray());
        }
    }

    @Test
    void testStreamPageImage_PageOutOfRange_Throws() throws Exception {
        when(bookRepository.findByIdForStreaming(1L)).thenReturn(Optional.of(bookEntity));
        when(archiveService.streamEntryNames(cbzPath)).then((i) -> Stream.of("1.jpg"));

        try (MockedStatic<FileUtils> fileUtilsStatic = mockStatic(FileUtils.class)) {
            fileUtilsStatic.when(() -> FileUtils.getBookFullPath(bookEntity)).thenReturn(cbzPath);

            cbxReaderService.initCache(1L, null);

            assertThrows(
                    FileNotFoundException.class,
                    () -> cbxReaderService.streamPageImage(1L, 2, new ByteArrayOutputStream())
            );
        }
    }

    @Test
    void testStreamPageImage_EntryNotFound_Throws() throws Exception {
        when(bookRepository.findByIdForStreaming(1L)).thenReturn(Optional.of(bookEntity));
        when(archiveService.streamEntryNames(cbzPath)).then((i) -> Stream.of("1.jpg"));

        try (MockedStatic<FileUtils> fileUtilsStatic = mockStatic(FileUtils.class)) {
            fileUtilsStatic.when(() -> FileUtils.getBookFullPath(bookEntity)).thenReturn(cbzPath);

            assertThrows(
                    FileNotFoundException.class,
                    () -> cbxReaderService.streamPageImage(1L, 2, new ByteArrayOutputStream())
            );
        }
    }

    @Test
    void testStreamPageImage_InvalidBookType_Throws() {
        when(bookRepository.findByIdForStreaming(1L)).thenReturn(Optional.of(bookEntity));
        APIException ex = assertThrows(APIException.class, () ->
                cbxReaderService.streamPageImage(1L, "../traversal", 1, new ByteArrayOutputStream())
        );
        assertTrue(ex.getMessage().contains("Invalid book type"), "Expected INVALID_INPUT, got: " + ex.getMessage());
    }

    @Test
    void testInitCache_InvalidBookType_Throws() {
        when(bookRepository.findByIdForStreaming(1L)).thenReturn(Optional.of(bookEntity));
        APIException ex = assertThrows(APIException.class, () ->
                cbxReaderService.initCache(1L, "../traversal")
        );
        assertTrue(ex.getMessage().contains("Invalid book type"), "Expected INVALID_INPUT, got: " + ex.getMessage());
    }
}
