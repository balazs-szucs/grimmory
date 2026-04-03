package org.booklore.service.reader;

import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.util.FileUtils;
import org.grimmory.comic4j.archive.ComicArchiveReader;
import org.grimmory.comic4j.image.ImageEntry;
import org.grimmory.comic4j.image.ImageFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CbxReaderServiceTest {

    @Mock
    BookRepository bookRepository;

    @InjectMocks
    CbxReaderService cbxReaderService;

    BookEntity bookEntity;
    Path cbzPath;

    @BeforeEach
    void setup() throws Exception {
        bookEntity = new BookEntity();
        bookEntity.setId(1L);
        cbzPath = Path.of("/tmp/test.cbz");
    }

    @Test
    void testGetAvailablePages_ThrowsOnMissingBook() {
        when(bookRepository.findByIdWithBookFiles(2L)).thenReturn(Optional.empty());
        assertThrows(ApiError.BOOK_NOT_FOUND.createException().getClass(), () -> cbxReaderService.getAvailablePages(2L));
    }

    @Test
    void testGetAvailablePages_Success() throws Exception {
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));

        try (
            MockedStatic<FileUtils> fileUtilsStatic = mockStatic(FileUtils.class);
            MockedStatic<Files> filesStatic = mockStatic(Files.class);
            MockedStatic<ComicArchiveReader> readerStatic = mockStatic(ComicArchiveReader.class);
        ) {
            fileUtilsStatic.when(() -> FileUtils.getBookFullPath(bookEntity)).thenReturn(cbzPath);
            filesStatic.when(() -> Files.getLastModifiedTime(cbzPath)).thenReturn(FileTime.from(Instant.now()));
            readerStatic.when(() -> ComicArchiveReader.listImages(cbzPath))
                    .thenReturn(List.of(new ImageEntry("1.jpg", "1", 1000, 0, ImageFormat.JPEG)));

            List<Integer> pages = cbxReaderService.getAvailablePages(1L);
            assertEquals(List.of(1), pages);
        }
    }

    @Test
    void testStreamPageImage_Success() throws Exception {
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));

        try (
            MockedStatic<FileUtils> fileUtilsStatic = mockStatic(FileUtils.class);
            MockedStatic<Files> filesStatic = mockStatic(Files.class);
            MockedStatic<ComicArchiveReader> readerStatic = mockStatic(ComicArchiveReader.class);
        ) {
            fileUtilsStatic.when(() -> FileUtils.getBookFullPath(bookEntity)).thenReturn(cbzPath);
            filesStatic.when(() -> Files.getLastModifiedTime(cbzPath)).thenReturn(FileTime.from(Instant.now()));
            readerStatic.when(() -> ComicArchiveReader.listImages(cbzPath))
                    .thenReturn(List.of(new ImageEntry("1.jpg", "1", 1000, 0, ImageFormat.JPEG)));
            readerStatic.when(() -> ComicArchiveReader.extractImage(cbzPath, "1.jpg"))
                    .thenReturn(new byte[]{1, 2, 3});

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cbxReaderService.streamPageImage(1L, 1, out);
            assertArrayEquals(new byte[]{1, 2, 3}, out.toByteArray());
        }
    }

    @Test
    void testStreamPageImage_PageOutOfRange_Throws() throws Exception {
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));

        try (
            MockedStatic<FileUtils> fileUtilsStatic = mockStatic(FileUtils.class);
            MockedStatic<Files> filesStatic = mockStatic(Files.class);
            MockedStatic<ComicArchiveReader> readerStatic = mockStatic(ComicArchiveReader.class);
        ) {
            fileUtilsStatic.when(() -> FileUtils.getBookFullPath(bookEntity)).thenReturn(cbzPath);
            filesStatic.when(() -> Files.getLastModifiedTime(cbzPath)).thenReturn(FileTime.from(Instant.now()));
            readerStatic.when(() -> ComicArchiveReader.listImages(cbzPath))
                    .thenReturn(List.of(new ImageEntry("1.jpg", "1", 1000, 0, ImageFormat.JPEG)));

            assertThrows(
                    FileNotFoundException.class,
                    () -> cbxReaderService.streamPageImage(1L, 2, new ByteArrayOutputStream())
            );
        }
    }
}
