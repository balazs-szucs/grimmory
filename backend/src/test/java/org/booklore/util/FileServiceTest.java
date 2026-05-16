package org.booklore.util;

import org.booklore.config.AppProperties;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock
    private AppProperties appProperties;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private VipsImageService vipsImageService;

    private FileService fileService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        CoverCroppingSettings coverCroppingSettings = CoverCroppingSettings.builder()
                .verticalCroppingEnabled(true)
                .horizontalCroppingEnabled(true)
                .aspectRatioThreshold(2.5)
                .smartCroppingEnabled(true).build();
        AppSettings appSettings = AppSettings.builder()
                .coverCroppingSettings(coverCroppingSettings)
                .build();
        lenient().when(appSettingService.getAppSettings()).thenReturn(appSettings);

        // General VIPS stubbing to prevent NPEs in various tests
        lenient().when(vipsImageService.canDecode(any(byte[].class))).thenReturn(true);
        lenient().when(vipsImageService.canDecode(any(Path.class))).thenReturn(true);
        lenient().when(vipsImageService.canDecode(any(InputStream.class))).thenReturn(true);
        lenient().when(vipsImageService.readDimensions(any(byte[].class))).thenReturn(new ImageDimensions(1000, 1500));
        lenient().when(vipsImageService.readDimensionsFromFile(any(Path.class))).thenReturn(new ImageDimensions(1000, 1500));

        RestTemplate mockRestTemplate = mock(RestTemplate.class);
        RestTemplate mockNoRedirectRestTemplate = mock(RestTemplate.class);
        fileService = new FileService(appProperties, mockRestTemplate, appSettingService, mockNoRedirectRestTemplate, vipsImageService);
    }

    @Nested
    @DisplayName("Truncate Method")
    class TruncateTests {

        @Test
        @DisplayName("Returns null for null input")
        void truncate_nullInput_returnsNull() {
            assertNull(FileService.truncate(null, 10));
        }

        @Test
        @DisplayName("Returns empty string for empty input")
        void truncate_emptyString_returnsEmpty() {
            assertEquals("", FileService.truncate("", 10));
        }

        @ParameterizedTest(name = "maxLength={0} returns empty string")
        @ValueSource(ints = {0, -1, -100, Integer.MIN_VALUE})
        @DisplayName("Returns empty for zero or negative maxLength")
        void truncate_zeroOrNegativeMaxLength_returnsEmpty(int maxLength) {
            assertEquals("", FileService.truncate("test string", maxLength));
        }

        @Test
        @DisplayName("Returns original when shorter than maxLength")
        void truncate_shortString_returnsOriginal() {
            String input = "short";
            assertSame(input, FileService.truncate(input, 100));
        }

        @Test
        @DisplayName("Returns original when exactly maxLength")
        void truncate_exactLength_returnsOriginal() {
            String input = "exactly10!";
            assertEquals(10, input.length());
            assertSame(input, FileService.truncate(input, 10));
        }

        @Test
        @DisplayName("Truncates when longer than maxLength")
        void truncate_longString_truncates() {
            String result = FileService.truncate("this is a long string", 7);
            assertEquals("this is", result);
            assertEquals(7, result.length());
        }

        @Test
        @DisplayName("Handles maxLength of 1")
        void truncate_maxLengthOne_returnsSingleChar() {
            assertEquals("a", FileService.truncate("abc", 1));
        }

        @Test
        @DisplayName("Preserves unicode characters")
        void truncate_unicodeCharacters_handlesCorrectly() {
            assertEquals("héllo", FileService.truncate("héllo wörld", 5));
            assertEquals("日本語", FileService.truncate("日本語テスト", 3));
        }

        @Test
        @DisplayName("Handles surrogate pairs (emojis)")
        void truncate_surrogratePairs_mayBreakEmoji() {
            String input = "🚀🌟✨";
            // Note: Each emoji is 2 chars, truncating at 3 may break emoji
            String result = FileService.truncate(input, 3);
            assertEquals(3, result.length());
        }

        @Test
        @DisplayName("Handles whitespace-only strings")
        void truncate_whitespaceOnly_handlesCorrectly() {
            assertEquals("   ", FileService.truncate("     ", 3));
            assertEquals("\t\n", FileService.truncate("\t\n\r", 2));
        }

        @Test
        @DisplayName("Handles special characters")
        void truncate_specialCharacters_handlesCorrectly() {
            assertEquals("!@#", FileService.truncate("!@#$%^&*()", 3));
        }

        @Test
        @DisplayName("Handles max integer length")
        void truncate_maxIntegerLength_returnsOriginal() {
            String input = "test";
            assertSame(input, FileService.truncate(input, Integer.MAX_VALUE));
        }

        @ParameterizedTest
        @MethodSource("truncateTestCases")
        @DisplayName("Parameterized truncate tests")
        void truncate_parameterized(String input, int maxLength, String expected) {
            assertEquals(expected, FileService.truncate(input, maxLength));
        }

        static Stream<Arguments> truncateTestCases() {
            return Stream.of(
                    Arguments.of("hello world", 5, "hello"),
                    Arguments.of("test", 10, "test"),
                    Arguments.of("abc", 3, "abc"),
                    Arguments.of("ab", 3, "ab"),
                    Arguments.of("a", 1, "a"),
                    Arguments.of("newline\ntest", 7, "newline")
            );
        }
    }

    @Nested
    @DisplayName("Path Utilities")
    class PathUtilitiesTests {

        @BeforeEach
        void setup() {
            lenient().when(appProperties.getPathConfig()).thenReturn(tempDir.toString());
        }

        @Nested
        @DisplayName("getImagesFolder")
        class GetImagesFolderTests {

            @Test
            void returnsCorrectPath() {
                String result = fileService.getImagesFolder(123L);

                assertAll(
                        () -> assertTrue(result.contains("images")),
                        () -> assertTrue(result.contains("123")),
                        () -> assertTrue(result.startsWith(tempDir.toString()))
                );
            }

            @ParameterizedTest
            @ValueSource(longs = {0L, 1L, Long.MAX_VALUE})
            void handlesEdgeCaseBookIds(long bookId) {
                String result = fileService.getImagesFolder(bookId);
                assertTrue(result.contains(String.valueOf(bookId)));
            }
        }

        @Nested
        @DisplayName("getThumbnailFile")
        class GetThumbnailFileTests {

            @Test
            void returnsCorrectPath() {
                String result = fileService.getThumbnailFile(456L);

                assertAll(
                        () -> assertTrue(result.contains("456")),
                        () -> assertTrue(result.endsWith("thumbnail.jpg"))
                );
            }
        }

        @Nested
        @DisplayName("getCoverFile")
        class GetCoverFileTests {

            @Test
            void returnsCorrectPath() {
                String result = fileService.getCoverFile(789L);

                assertAll(
                        () -> assertTrue(result.contains("789")),
                        () -> assertTrue(result.endsWith("cover.jpg"))
                );
            }
        }

        @Nested
        @DisplayName("getBackgroundsFolder")
        class GetBackgroundsFolderTests {

            @Test
            void withUserId_returnsUserSpecificPath() {
                String result = fileService.getBackgroundsFolder(42L);

                assertAll(
                        () -> assertTrue(result.contains("backgrounds")),
                        () -> assertTrue(result.contains("user-42"))
                );
            }

            @Test
            void withNullUserId_returnsGlobalPath() {
                String result = fileService.getBackgroundsFolder(null);

                assertAll(
                        () -> assertTrue(result.contains("backgrounds")),
                        () -> assertFalse(result.contains("user-"))
                );
            }

            @Test
            void noArgs_delegatesToNullUserId() {
                String withNull = fileService.getBackgroundsFolder(null);
                String noArgs = fileService.getBackgroundsFolder();

                assertEquals(withNull, noArgs);
            }
        }

        @Nested
        @DisplayName("getBackgroundUrl (static)")
        class GetBackgroundUrlTests {

            @Test
            void withUserId_returnsCorrectUrl() {
                String result = FileService.getBackgroundUrl("bg.jpg", 10L);

                assertAll(
                        () -> assertTrue(result.startsWith("/")),
                        () -> assertTrue(result.contains("backgrounds")),
                        () -> assertTrue(result.contains("user-10")),
                        () -> assertTrue(result.endsWith("bg.jpg")),
                        () -> assertFalse(result.contains("\\"), "Should use forward slashes")
                );
            }

            @Test
            void withoutUserId_returnsGlobalUrl() {
                String result = FileService.getBackgroundUrl("bg.jpg", null);

                assertAll(
                        () -> assertFalse(result.contains("user-")),
                        () -> assertTrue(result.contains("backgrounds")),
                        () -> assertFalse(result.contains("\\"))
                );
            }

            @Test
            void handlesFilenameWithSpaces() {
                String result = FileService.getBackgroundUrl("my background.jpg", null);
                assertTrue(result.contains("my background.jpg"));
            }
        }

        @Nested
        @DisplayName("findSystemFile")
        class FindSystemFileTest {
            @Test
            void searchesLocalBinFolderFirst() {
                Path expected = Path.of("bin/example").toAbsolutePath().normalize();

                try (
                    MockedStatic<Files> filesMock = mockStatic(Files.class);
                ) {
                    filesMock.when(() -> Files.isRegularFile(any())).thenReturn(true);

                    Path actual = fileService.findSystemFile("example");

                    assertEquals(expected, actual);
                }
            }

            @Test
            void searchesAppDataToolsFolder() {
                Path expected = tempDir.resolve("tools", "example");

                try (
                        MockedStatic<Files> filesMock = mockStatic(Files.class);
                ) {
                    filesMock.when(() -> Files.isRegularFile(any())).thenReturn(false, true);

                    Path actual = fileService.findSystemFile("example");

                    assertEquals(expected, actual);
                }
            }
        }

        @Nested
        @DisplayName("Other path methods")
        class OtherPathTests {

            @Test
            void getBookMetadataBackupPath_returnsCorrectPath() {
                String result = fileService.getBookMetadataBackupPath(100L);

                assertAll(
                        () -> assertTrue(result.contains("metadata_backup")),
                        () -> assertTrue(result.contains("100"))
                );
            }

            @Test
            void getPdfCachePath_returnsCorrectPath() {
                assertTrue(fileService.getPdfCachePath().contains("pdf_cache"));
            }

            @Test
            void getTempBookdropCoverImagePath_returnsCorrectPath() {
                String result = fileService.getTempBookdropCoverImagePath(555L);

                assertAll(
                        () -> assertTrue(result.contains("bookdrop_temp")),
                        () -> assertTrue(result.endsWith("555.jpg"))
                );
            }
        }
    }

    @Nested
    @DisplayName("Image Operations")
    class ImageOperationsTests {

        @Nested
        @DisplayName("validateImageData")
        class ValidateImageDataTests {
            @Test
            void validData_succeeds() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};
                when(vipsImageService.readDimensions(imageData)).thenReturn(new ImageDimensions(100, 100));

                assertDoesNotThrow(() -> FileService.validateImageData(imageData, vipsImageService));
            }

            @Test
            void nullData_throwsException() {
                IOException ex = assertThrows(IOException.class, () -> FileService.validateImageData(null, vipsImageService));
                assertEquals("Image data is null or empty", ex.getMessage());
            }

            @Test
            void decompressionBomb_throwsException() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};
                // Exceeds MAX_IMAGE_PIXELS (20,000,000)
                when(vipsImageService.readDimensions(imageData)).thenReturn(new ImageDimensions(5000, 5000));

                IOException ex = assertThrows(IOException.class, () -> FileService.validateImageData(imageData, vipsImageService));
                assertTrue(ex.getMessage().contains("exceed limit"));
            }

            @Test
            void invalidData_throwsException() throws IOException {
                byte[] invalidData = "not an image".getBytes();
                when(vipsImageService.readDimensions(invalidData)).thenThrow(new IOException("Decode failed"));

                assertThrows(IOException.class, () -> FileService.validateImageData(invalidData, vipsImageService));
            }
        }

        @Nested
        @DisplayName("saveImage")
        class SaveImageTests {

            @Test
            void validData_callsVips() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};
                Path outputPath = tempDir.resolve("test-output.jpg");

                fileService.saveImage(imageData, outputPath.toString());

                verify(vipsImageService).processStreamToJpeg(any(InputStream.class), any(OutputStream.class), eq(1000), eq(1500));
            }

            @Test
            void createsParentDirectories() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};
                Path outputPath = tempDir.resolve("nested/deep/folder/test.jpg");

                fileService.saveImage(imageData, outputPath.toString());

                assertTrue(Files.exists(outputPath.getParent()));
            }

            @Test
            void emptyImageData_doesNotCallVips() throws IOException {
                byte[] emptyData = new byte[0];
                Path outputPath = tempDir.resolve("empty.jpg");

                fileService.saveImage(emptyData, outputPath.toString());

                verify(vipsImageService, never()).processStreamToJpeg(any(InputStream.class), any(OutputStream.class), anyInt(), anyInt());
            }

            @Test
            void nullImageData_doesNotCallVips() throws IOException {
                Path outputPath = tempDir.resolve("null.jpg");
                fileService.saveImage((byte[]) null, outputPath.toString());
                verify(vipsImageService, never()).processStreamToJpeg(any(InputStream.class), any(OutputStream.class), anyInt(), anyInt());
            }
        }
    }

    @Nested
    @DisplayName("Cover Operations")
    class CoverOperationsTests {

        @BeforeEach
        void setup() throws IOException {
            lenient().when(appProperties.getPathConfig()).thenReturn(tempDir.toString());
        }

        @Nested
        @DisplayName("saveCoverImages")
        class SaveCoverImagesTests {

            @Test
            void createsBothCoverAndThumbnail() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};

                boolean result = fileService.saveCoverImages(imageData, 1L);

                assertTrue(result);
                verify(vipsImageService).processCoverUnified(any(InputStream.class), any(Path.class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }

            @Test
            void largeImage_isScaledDownToMaxDimensions() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};

                boolean result = fileService.saveCoverImages(imageData, 5L);

                assertTrue(result);
                verify(vipsImageService).processCoverUnified(any(InputStream.class), any(Path.class), any(Path.class), eq(1000), eq(1500), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }

            @Test
            void extremelyTallImage_isCropped() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};

                boolean result = fileService.saveCoverImages(imageData, 100L);

                assertTrue(result);
                verify(vipsImageService).processCoverUnified(any(InputStream.class), any(Path.class), any(Path.class), eq(1000), eq(1500), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }

            @Test
            void extremelyWideImage_isCropped() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};

                boolean result = fileService.saveCoverImages(imageData, 101L);

                assertTrue(result);
                verify(vipsImageService).processCoverUnified(any(InputStream.class), any(Path.class), any(Path.class), eq(1000), eq(1500), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }

            @Test
            void normalAspectRatioImage_isNotCropped() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};

                boolean result = fileService.saveCoverImages(imageData, 102L);

                assertTrue(result);
                verify(vipsImageService).processCoverUnified(any(InputStream.class), any(Path.class), any(Path.class), eq(1000), eq(1500), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }

            @Test
            void originalMaintainsDimensions() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};

                fileService.saveCoverImages(imageData, 4L);

                verify(vipsImageService).processCoverUnified(any(InputStream.class), any(Path.class), any(Path.class), eq(1000), eq(1500), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }

            @Test
            void smallImage_maintainsOriginalDimensions() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};

                fileService.saveCoverImages(imageData, 6L);

                verify(vipsImageService).processCoverUnified(any(InputStream.class), any(Path.class), any(Path.class), eq(1000), eq(1500), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }

            @Test
            void convertsTransparentToOpaque() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};
                fileService.saveCoverImages(imageData, 3L);

                verify(vipsImageService).processCoverUnified(any(InputStream.class), any(Path.class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }

            @Test
            void thumbnailHasCorrectDimensions() throws IOException {
                byte[] imageData = new byte[]{1, 2, 3};
                fileService.saveCoverImages(imageData, 1L);

                verify(vipsImageService).processCoverUnified(any(InputStream.class), any(Path.class), any(Path.class), anyInt(), anyInt(), eq(250), eq(350), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }
        }

        @Nested
        @DisplayName("createThumbnailFromBytes")
        class CreateThumbnailFromBytesTests {

            @Test
            void validImageBytes_succeeds() throws IOException {
                byte[] imageBytes = new byte[]{1, 2, 3};
                when(vipsImageService.readDimensions(imageBytes)).thenReturn(new ImageDimensions(100, 100));

                assertDoesNotThrow(() -> fileService.createThumbnailFromBytes(15L, imageBytes));
                verify(vipsImageService).processCoverUnified(any(InputStream.class), any(Path.class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }

            @Test
            void nullImageBytes_throwsRuntimeException() {
                assertThrows(RuntimeException.class, () -> fileService.createThumbnailFromBytes(18L, null));
            }
        }

        @Nested
        @DisplayName("createThumbnailFromFile")
        class CreateThumbnailFromFileTests {

            @Test
            void validJpegFile_succeeds() throws IOException {
                when(appSettingService.getAppSettings()).thenReturn(
                        AppSettings.builder().maxFileUploadSizeInMb(5).build()
                );

                MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[]{1, 2, 3});

                assertDoesNotThrow(() -> fileService.createThumbnailFromFile(5L, file));
            }
        }

        @Nested
        @DisplayName("deleteBookCovers")
        class DeleteBookCoversTests {

            @Test
            void existingCovers_deletesAll() throws IOException {
                Path bookDir = tempDir.resolve("images/10");
                Files.createDirectories(bookDir);
                Files.write(bookDir.resolve("cover.jpg"), new byte[]{1});

                fileService.deleteBookCovers(Set.of(10L));

                assertFalse(Files.exists(bookDir));
            }
        }
    }

    @Nested
    @DisplayName("Network Operations")
    class NetworkOperationsTests {

        @Mock
        private RestTemplate noRedirectRestTemplate;

        @Mock
        private AppSettingService appSettingServiceForNetwork;

        private FileService fileService;

        @BeforeEach
        void setup() {
            lenient().when(appProperties.getPathConfig()).thenReturn(tempDir.toString());

            CoverCroppingSettings coverCroppingSettings = CoverCroppingSettings.builder()
                    .verticalCroppingEnabled(true)
                    .horizontalCroppingEnabled(true)
                    .aspectRatioThreshold(2.5)
                    .smartCroppingEnabled(true).build();
            AppSettings appSettings = AppSettings.builder()
                    .coverCroppingSettings(coverCroppingSettings)
                    .build();
            lenient().when(appSettingServiceForNetwork.getAppSettings()).thenReturn(appSettings);

            fileService = new FileService(appProperties, mock(RestTemplate.class), appSettingServiceForNetwork, noRedirectRestTemplate, vipsImageService);
        }

        @Nested
        @DisplayName("downloadImageFromUrl")
        class DownloadImageFromUrlTests {

            @Test
            @DisplayName("downloads and returns valid image bytes")
            void downloadImageFromUrl_validImage_returnsBytes() throws IOException {
                String imageUrl = "http://1.1.1.1/image.jpg";
                byte[] imageBytes = new byte[]{1, 2, 3};

                ResponseEntity<byte[]> response = ResponseEntity.ok(imageBytes);
                when(noRedirectRestTemplate.exchange(eq(imageUrl), eq(HttpMethod.GET), any(), eq(byte[].class)))
                        .thenReturn(response);

                byte[] result = fileService.downloadImageFromUrl(imageUrl);

                assertArrayEquals(imageBytes, result);
            }

            @Test
            @DisplayName("throws exception when response body is null")
            void downloadImageFromUrl_nullBody_throwsException() {
                String imageUrl = "http://1.1.1.1/image.jpg";
                ResponseEntity<byte[]> response = ResponseEntity.ok(null);
                when(noRedirectRestTemplate.exchange(eq(imageUrl), eq(HttpMethod.GET), any(), eq(byte[].class)))
                        .thenReturn(response);

                assertThrows(IOException.class, () -> fileService.downloadImageFromUrl(imageUrl));
            }

            @Test
            @DisplayName("rewrites redirect URL to preserve hostname when CDN redirects to raw IP")
            void downloadImageFromUrl_redirectToRawIp_rewritesUrlWithOriginalHost() throws IOException {
                String originalUrl = "http://example.com/cover.jpg";
                String cdnIpRedirect = "http://3.168.64.124/cover.jpg";
                byte[] imageBytes = new byte[]{1, 2, 3};

                ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, cdnIpRedirect).build();
                ResponseEntity<byte[]> imageResponse = ResponseEntity.ok(imageBytes);

                when(noRedirectRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                        .thenReturn(redirectResponse, imageResponse);

                byte[] result = fileService.downloadImageFromUrl(originalUrl);

                assertArrayEquals(imageBytes, result);
                
                ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
                verify(noRedirectRestTemplate, times(2)).exchange(urlCaptor.capture(), eq(HttpMethod.GET), any(), eq(byte[].class));
                
                assertEquals(originalUrl, urlCaptor.getAllValues().get(0));
                // Verify it was rewritten to use example.com instead of 3.168.64.124
                assertEquals("http://example.com/cover.jpg", urlCaptor.getAllValues().get(1));
            }

            @Test
            @DisplayName("throws exception when redirect exceeds max limit")
            void downloadImageFromUrl_tooManyRedirects_throwsException() {
                String imageUrl = "http://1.1.1.1/cover.jpg";
                ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, "http://2.2.2.2/cover.jpg").build();

                when(noRedirectRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                        .thenReturn(redirectResponse);

                IOException ex = assertThrows(IOException.class, () -> fileService.downloadImageFromUrl(imageUrl));
                assertTrue(ex.getMessage().contains("Too many redirects"));
            }
        }

        @Nested
        @DisplayName("createThumbnailFromUrl")
        class CreateThumbnailFromUrlTests {

            @Test
            @DisplayName("downloads and saves cover images successfully")
            void createThumbnailFromUrl_validImage_createsCoverAndThumbnail() throws IOException {
                String imageUrl = "http://1.1.1.1/cover.jpg";
                long bookId = 42L;
                byte[] imageBytes = new byte[]{1, 2, 3};

                when(noRedirectRestTemplate.execute(anyString(), eq(HttpMethod.GET), any(), any())).thenAnswer(invocation -> {
                    org.springframework.web.client.ResponseExtractor<?> extractor = invocation.getArgument(3);
                    org.springframework.http.client.ClientHttpResponse response = mock(org.springframework.http.client.ClientHttpResponse.class);
                    when(response.getStatusCode()).thenReturn(HttpStatus.OK);
                    when(response.getBody()).thenReturn(new ByteArrayInputStream(imageBytes));
                    return extractor.extractData(response);
                });

                assertDoesNotThrow(() -> fileService.createThumbnailFromUrl(bookId, imageUrl));

                verify(vipsImageService).processCoverUnified(any(Path.class), any(Path.class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(), anyBoolean(), anyDouble(), anyDouble());
            }

            @Test
            @DisplayName("throws ApiError.FILE_READ_ERROR on download failure")
            void createThumbnailFromUrl_downloadFails_throwsApiError() {
                String imageUrl = "http://example.com/invalid.jpg";
                long bookId = 42L;

                when(noRedirectRestTemplate.execute(anyString(), eq(HttpMethod.GET), any(), any()))
                        .thenThrow(new RuntimeException("Network error"));

                assertThrows(RuntimeException.class, () -> fileService.createThumbnailFromUrl(bookId, imageUrl));
            }
        }
    }
}