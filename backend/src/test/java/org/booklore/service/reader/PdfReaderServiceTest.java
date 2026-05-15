package org.booklore.service.reader;

import org.booklore.exception.APIException;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.FileStreamingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PdfReaderServiceTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private ChapterCacheService chapterCacheService;
    @Mock
    private FileStreamingService fileStreamingService;

    @InjectMocks
    private PdfReaderService pdfReaderService;

    private BookEntity bookEntity;
    private Path pdfPath;

    @BeforeEach
    void setup() {
        bookEntity = new BookEntity();
        bookEntity.setId(1L);
        pdfPath = Path.of("/tmp/test.pdf");
    }

    @Test
    void testStreamPageImage_InvalidBookType_Throws() {
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        APIException ex = assertThrows(APIException.class, () ->
                pdfReaderService.streamPageImage(1L, "../traversal", 1, request, response)
        );
        assertTrue(ex.getMessage().contains("Invalid book type"), "Expected INVALID_INPUT, got: " + ex.getMessage());
    }

    @Test
    void testInitCache_InvalidBookType_Throws() {
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));
        APIException ex = assertThrows(APIException.class, () ->
                pdfReaderService.initCache(1L, "../traversal")
        );
        assertTrue(ex.getMessage().contains("Invalid book type"), "Expected INVALID_INPUT, got: " + ex.getMessage());
    }
}
