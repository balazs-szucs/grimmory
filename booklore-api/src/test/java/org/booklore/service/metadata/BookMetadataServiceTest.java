package org.booklore.service.metadata;

import org.booklore.exception.APIException;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.BookMetadataMapper;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.mapper.MetadataClearFlagsMapper;
import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.BulkMetadataUpdateRequest;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.request.IsbnLookupRequest;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.dto.request.ToggleAllLockRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.Lock;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.book.BookQueryService;
import org.booklore.service.metadata.extractor.CbxMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.service.metadata.parser.BookParser;
import org.booklore.service.metadata.parser.DetailedMetadataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;



import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookMetadataServiceTest {

@Mock
private BookRepository bookRepository;
@Mock
private BookMetadataUpdater bookMetadataUpdater;
@Mock
private BookMetadataMapper bookMetadataMapper;
@Mock
private AuditService auditService;
@Mock
private BookMapper bookMapper;
@Mock
private NotificationService notificationService;
@Mock
private BookMetadataRepository bookMetadataRepository;
@Mock
private BookQueryService bookQueryService;
@Mock
private CbxMetadataExtractor cbxMetadataExtractor;
@Mock
private MetadataExtractorFactory metadataExtractorFactory;
@Mock
private MetadataClearFlagsMapper metadataClearFlagsMapper;
@Mock
private PlatformTransactionManager transactionManager;
@Mock
private AppSettingService appSettingService;

private Map<MetadataProvider, BookParser> parserMap;
private BookMetadataService service;

@BeforeEach
void setUp() {
    parserMap = new HashMap<>();
    service = new BookMetadataService(
            bookRepository, bookMapper, bookMetadataMapper, bookMetadataUpdater,
            notificationService, bookMetadataRepository, bookQueryService,
            parserMap, cbxMetadataExtractor, metadataExtractorFactory,
            metadataClearFlagsMapper, transactionManager, appSettingService
    );
}

@InjectMocks
private BookMetadataService bookMetadataService;

@Test
void updateMetadata_shouldSetCorrectContext() {
    long bookId = 1L;
    MetadataUpdateWrapper wrapper = MetadataUpdateWrapper.builder().build();
    BookEntity bookEntity = new BookEntity();
    bookEntity.setId(bookId);
    bookEntity.setMetadata(new BookMetadataEntity());





ArgumentCaptor<MetadataUpdateContext> captor = ArgumentCaptor.forClass(MetadataUpdateContext.class);
verify(bookMetadataUpdater).setBookMetadata(captor.capture());
MetadataUpdateContext context = captor.getValue();

assertEquals(bookEntity, context.getBookEntity());
assertEquals(wrapper, context.getMetadataUpdateWrapper());
assertTrue(context.isMergeCategories());
assertEquals(MetadataReplaceMode.REPLACE_ALL, context.getReplaceMode());
assertFalse(context.isMergeTags(), "mergeTags should be false to allow deletion of tags");
assertFalse(context.isMergeMoods(), "mergeMoods should be false to allow deletion of moods");
    }
}
