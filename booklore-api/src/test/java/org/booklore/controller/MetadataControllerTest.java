package org.booklore.controller;

import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.service.metadata.BookMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataControllerTest {

    @Mock
    private BookMetadataService bookMetadataService;

    @InjectMocks
    private MetadataController metadataController;

    @Test
    void updateMetadata_shouldDelegateToService(MetadataReplaceMode replaceMode) {
        long bookId = 1L;
        MetadataUpdateWrapper wrapper = MetadataUpdateWrapper.builder().build();
        BookMetadata bookMetadata = new BookMetadata();

        when(bookMetadataService.updateMetadata(bookId, wrapper, true, replaceMode)).thenReturn(bookMetadata);

        ResponseEntity<BookMetadata> response = metadataController.updateMetadata(wrapper, bookId, true);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(bookMetadata, response.getBody(MetadataReplaceMode.REPLACE_ALL));
        verify(bookMetadataService).updateMetadata(bookId, wrapper, true);
    }

    @Test
    void updateMetadata_shouldPassReplaceModeFromParam() {
        MetadataUpdateContext context = captureContextFromUpdate(MetadataReplaceMode.REPLACE_WHEN_PROVIDED);

        assertEquals(MetadataReplaceMode.REPLACE_WHEN_PROVIDED, context.getReplaceMode());
    }

    @Test
    void updateMetadata_shouldDefaultToReplaceAll() {
        MetadataUpdateContext context = captureContextFromUpdate(MetadataReplaceMode.REPLACE_ALL);

        assertEquals(MetadataReplaceMode.REPLACE_ALL, context.getReplaceMode());
    }
}
