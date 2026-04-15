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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
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

    private VipsImageService mockVips;
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

        RestTemplate mockRestTemplate = mock(RestTemplate.class);
        RestTemplate mockNoRedirectRestTemplate = mock(RestTemplate.class);
        mockVips = mock(VipsImageService.class);

        // Stub bufferedImageToJpeg: convert BufferedImage to JPEG bytes using ImageIO
        lenient().when(mockVips.bufferedImageToJpeg(any(BufferedImage.class), anyInt())).thenAnswer(inv -> {
            BufferedImage img = inv.getArgument(0);
            // Convert to RGB if the image has alpha (JPEG doesn't support alpha)
            if (img.getColorModel().hasAlpha()) {
                BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                g.drawImage(img, 0, 0, null);
                g.dispose();
                img = rgb;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "JPEG", baos);
            return baos.toByteArray();
        });

        // Stub readDimensions: decode bytes to get dimensions
        lenient().when(mockVips.readDimensions(any(byte[].class))).thenAnswer(inv -> {
            byte[] data = inv.getArgument(0);
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (img == null) throw new IOException("Cannot decode image");
            return new ImageDimensions(img.getWidth(), img.getHeight());
        });

        // Stub readDimensionsFromFile: read file and decode to get dimensions
        lenient().when(mockVips.readDimensionsFromFile(any(Path.class))).thenAnswer(inv -> {
            Path path = inv.getArgument(0);
            byte[] data = Files.readAllBytes(path);
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (img == null) throw new IOException("Cannot decode image from file");
            return new ImageDimensions(img.getWidth(), img.getHeight());
        });

        // Stub findContentBounds: return the full image as content bounds (no trim)
        lenient().when(mockVips.findContentBounds(any(byte[].class))).thenAnswer(inv -> {
            byte[] data = inv.getArgument(0);
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (img == null) return new TrimBounds(0, 0, 1, 1);
            return new TrimBounds(0, 0, img.getWidth(), img.getHeight());
        });

        // Stub flattenResizeAndSave: write a JPEG to the target path
        lenient().doAnswer(inv -> {
            byte[] data = inv.getArgument(0);
            Path target = inv.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            return null;
        }).when(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt());

        // Stub flattenThumbnailAndSave (file-to-file): copy source to target
        lenient().doAnswer(inv -> {
            Path source = inv.getArgument(0);
            Path target = inv.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(mockVips).flattenThumbnailAndSave(any(Path.class), any(Path.class), anyInt(), anyInt());

        // Stub flattenCropResizeAndSave: write bytes to the target path
        lenient().doAnswer(inv -> {
            byte[] data = inv.getArgument(0);
            Path target = inv.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            return null;
        }).when(mockVips).flattenCropResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        fileService = new FileService(appProperties, mockRestTemplate, appSettingService, mockNoRedirectRestTemplate, mockVips);
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
        @DisplayName("saveImage")
        class SaveImageTests {

            @Test
            void nullImageData_doesNotThrow() {
                Path outputPath = tempDir.resolve("null.jpg");
                assertDoesNotThrow(() -> fileService.saveImage(null, outputPath.toString()));
                assertFalse(Files.exists(outputPath));
            }

            @Test
            void emptyImageData_doesNotThrow() {
                byte[] emptyData = new byte[0];
                Path outputPath = tempDir.resolve("empty.jpg");
                assertDoesNotThrow(() -> fileService.saveImage(emptyData, outputPath.toString()));
                assertFalse(Files.exists(outputPath));
            }
        }
    }

    @Nested
    @DisplayName("Cover Operations")
    class CoverOperationsTests {

        @BeforeEach
        void setup() {
            lenient().when(appProperties.getPathConfig()).thenReturn(tempDir.toString());
        }

        @Nested
        @DisplayName("saveCoverImages")
        class SaveCoverImagesTests {

            @Test
            void createsBothCoverAndThumbnail() throws IOException {
                BufferedImage image = createTestImage(500, 700);

                boolean result = fileService.saveCoverImages(image, 1L);

                assertAll(
                        () -> assertTrue(result),
                        () -> assertTrue(Files.exists(Path.of(fileService.getCoverFile(1L)))),
                        () -> assertTrue(Files.exists(Path.of(fileService.getThumbnailFile(1L))))
                );
            }

            @Test
            void thumbnailHasCorrectDimensions() throws IOException {
                BufferedImage image = createTestImage(1000, 1400);

                fileService.saveCoverImages(image, 2L);

                // Verify thumbnail was created via vips with correct dimensions
                verify(mockVips).flattenThumbnailAndSave(any(Path.class), any(Path.class), eq(250), eq(350));
            }

            @Test
            void convertsTransparentToOpaqueWithWhiteBackground() throws IOException {
                BufferedImage imageWithAlpha = new BufferedImage(
                        100, 100, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = imageWithAlpha.createGraphics();
                g.setColor(new Color(255, 0, 0, 128)); // Semi-transparent red
                g.fillRect(0, 0, 100, 100);
                g.dispose();

                boolean result = fileService.saveCoverImages(imageWithAlpha, 3L);

                assertTrue(result);
                // Alpha flattening is now handled by vips, verify the call went through
                verify(mockVips).bufferedImageToJpeg(eq(imageWithAlpha), eq(95));
                verify(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt());
            }

            @Test
            void createsDirectoryIfNotExists() throws IOException {
                BufferedImage image = createTestImage(100, 100);
                long bookId = 999L;

                fileService.saveCoverImages(image, bookId);

                assertTrue(Files.isDirectory(
                        Path.of(fileService.getImagesFolder(bookId))));
            }

            @Test
            void originalMaintainsDimensions() throws IOException {
                BufferedImage image = createTestImage(800, 1200);

                fileService.saveCoverImages(image, 4L);

                // Verify cover was saved via flattenResizeAndSave with max bounds
                verify(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), eq(1000), eq(1500));
            }

            @Test
            void largeImage_isScaledDownToMaxDimensions() throws IOException {
                int largeWidth = 2000;
                int largeHeight = 3000;

                BufferedImage largeImage = createTestImage(largeWidth, largeHeight);
                boolean result = fileService.saveCoverImages(largeImage, 5L);

                assertTrue(result);
                // Resizing is delegated to vips with max bounds
                verify(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), eq(1000), eq(1500));
            }

            @Test
            void smallImage_maintainsOriginalDimensions() throws IOException {
                int smallWidth = 400;
                int smallHeight = 600;

                BufferedImage smallImage = createTestImage(smallWidth, smallHeight);
                boolean result = fileService.saveCoverImages(smallImage, 6L);

                assertTrue(result);
                // Still goes through flattenResizeAndSave which handles proportional sizing
                verify(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), eq(1000), eq(1500));
            }
        }

        @Nested
        @DisplayName("Cover Cropping for Extreme Aspect Ratios")
        class CoverCroppingTests {

            @Test
            @DisplayName("extremely tall image is cropped when vertical cropping enabled")
            void extremelyTallImage_isCropped() throws IOException {
                int width = 940;
                int height = 11280;  // ratio = 12:1

                BufferedImage tallImage = createTestImage(width, height);
                boolean result = fileService.saveCoverImages(tallImage, 100L);

                assertTrue(result);
                // Should use flattenCropResizeAndSave for extreme aspect ratio
                verify(mockVips).flattenCropResizeAndSave(any(byte[].class), any(Path.class),
                        anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            }

            @Test
            @DisplayName("extremely wide image is cropped when horizontal cropping enabled")
            void extremelyWideImage_isCropped() throws IOException {
                int width = 3000;
                int height = 400;  // width/height ratio = 7.5:1

                BufferedImage wideImage = createTestImage(width, height);
                boolean result = fileService.saveCoverImages(wideImage, 101L);

                assertTrue(result);
                // Should use flattenCropResizeAndSave for extreme aspect ratio
                verify(mockVips).flattenCropResizeAndSave(any(byte[].class), any(Path.class),
                        anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            }

            @Test
            @DisplayName("normal aspect ratio image is not cropped")
            void normalAspectRatioImage_isNotCropped() throws IOException {
                int width = 600;
                int height = 900;  // ratio = 1.5:1

                BufferedImage normalImage = createTestImage(width, height);
                boolean result = fileService.saveCoverImages(normalImage, 102L);

                assertTrue(result);
                // Should use flattenResizeAndSave (no crop) for normal aspect ratio
                verify(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), eq(1000), eq(1500));
                verify(mockVips, never()).flattenCropResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            }

            @Test
            @DisplayName("cropping is disabled when settings are off")
            void croppingDisabled_imageNotCropped() throws IOException {
                CoverCroppingSettings disabledSettings = CoverCroppingSettings.builder()
                        .verticalCroppingEnabled(false)
                        .horizontalCroppingEnabled(false)
                        .aspectRatioThreshold(2.5)
                        .smartCroppingEnabled(true).build();
                AppSettings appSettings = AppSettings.builder()
                        .coverCroppingSettings(disabledSettings)
                        .build();
                when(appSettingService.getAppSettings()).thenReturn(appSettings);

                int width = 400;
                int height = 4000;  // ratio = 10:1

                BufferedImage tallImage = createTestImage(width, height);
                boolean result = fileService.saveCoverImages(tallImage, 103L);

                assertTrue(result);
                // With cropping disabled, should use flattenResizeAndSave (no crop)
                verify(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), eq(1000), eq(1500));
                verify(mockVips, never()).flattenCropResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            }

            @Test
            @DisplayName("smart cropping enabled for tall image finds content start")
            void smartCroppingEnabled_tallImage_cropsFromContent() throws IOException {
                CoverCroppingSettings smartCropSettings = CoverCroppingSettings.builder()
                        .verticalCroppingEnabled(true)
                        .horizontalCroppingEnabled(true)
                        .aspectRatioThreshold(2.5)
                        .smartCroppingEnabled(true)
                        .build();
                AppSettings appSettings = AppSettings.builder()
                        .coverCroppingSettings(smartCropSettings)
                        .build();
                when(appSettingService.getAppSettings()).thenReturn(appSettings);

                int width = 500;
                int height = 3000;  // ratio = 6:1

                // Mock findContentBounds to simulate content starting at y=200
                when(mockVips.findContentBounds(any(byte[].class))).thenReturn(new TrimBounds(0, 200, width, height - 200));

                BufferedImage tallImage = createTestImage(width, height);
                boolean result = fileService.saveCoverImages(tallImage, 104L);
                assertTrue(result);

                // Should crop with offset from content bounds
                verify(mockVips).flattenCropResizeAndSave(any(byte[].class), any(Path.class),
                        anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            }

            @Test
            @DisplayName("smart cropping enabled for wide image finds content start")
            void smartCroppingEnabled_wideImage_cropsFromContent() throws IOException {
                CoverCroppingSettings smartCropSettings = CoverCroppingSettings.builder()
                        .verticalCroppingEnabled(true)
                        .horizontalCroppingEnabled(true)
                        .aspectRatioThreshold(2.5)
                        .smartCroppingEnabled(true)
                        .build();
                AppSettings appSettings = AppSettings.builder()
                        .coverCroppingSettings(smartCropSettings)
                        .build();
                when(appSettingService.getAppSettings()).thenReturn(appSettings);

                int width = 3000;
                int height = 400;  // ratio = 7.5:1

                // Mock findContentBounds to simulate content starting at x=200
                when(mockVips.findContentBounds(any(byte[].class))).thenReturn(new TrimBounds(200, 0, width - 200, height));

                BufferedImage wideImage = createTestImage(width, height);
                boolean result = fileService.saveCoverImages(wideImage, 105L);
                assertTrue(result);

                verify(mockVips).flattenCropResizeAndSave(any(byte[].class), any(Path.class),
                        anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            }

            @Test
            @DisplayName("smart cropping with uniform color image uses top/left")
            void smartCroppingEnabled_uniformColorImage_usesTopLeft() throws IOException {
                CoverCroppingSettings smartCropSettings = CoverCroppingSettings.builder()
                        .verticalCroppingEnabled(true)
                        .horizontalCroppingEnabled(true)
                        .aspectRatioThreshold(2.5)
                        .smartCroppingEnabled(true)
                        .build();
                AppSettings appSettings = AppSettings.builder()
                        .coverCroppingSettings(smartCropSettings)
                        .build();
                when(appSettingService.getAppSettings()).thenReturn(appSettings);

                int width = 500;
                int height = 3000;

                // For uniform images, findTrim returns zero-area bounds
                when(mockVips.findContentBounds(any(byte[].class))).thenReturn(new TrimBounds(0, 0, 0, 0));

                BufferedImage uniformImage = createTestImage(width, height, Color.BLUE);
                boolean result = fileService.saveCoverImages(uniformImage, 106L);
                assertTrue(result);

                verify(mockVips).flattenCropResizeAndSave(any(byte[].class), any(Path.class),
                        anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            }

            @Test
            @DisplayName("null cover cropping settings returns image unchanged")
            void nullCoverCroppingSettings_returnsImageUnchanged() throws IOException {
                AppSettings appSettings = AppSettings.builder()
                        .coverCroppingSettings(null)
                        .build();
                when(appSettingService.getAppSettings()).thenReturn(appSettings);

                int width = 500;
                int height = 3000;
                BufferedImage tallImage = createTestImage(width, height);

                boolean result = fileService.saveCoverImages(tallImage, 107L);
                assertTrue(result);

                // No cropping settings means no crop
                verify(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), eq(1000), eq(1500));
                verify(mockVips, never()).flattenCropResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            }
        }

        @Nested
        @DisplayName("createThumbnailFromBytes")
        class CreateThumbnailFromBytesTests {

            @Test
            void validImageBytes_succeeds() throws IOException {
                BufferedImage image = createTestImage(300, 400);
                byte[] imageBytes = imageToBytes(image);

                assertDoesNotThrow(() ->
                        fileService.createThumbnailFromBytes(15L, imageBytes));
                assertTrue(Files.exists(Path.of(fileService.getCoverFile(15L))));
            }

            @Test
            void invalidImageBytes_throwsException() {
                byte[] invalidData = "not an image".getBytes();

                assertThrows(RuntimeException.class, () ->
                        fileService.createThumbnailFromBytes(16L, invalidData));
            }

            @Test
            void emptyImageBytes_throwsRuntimeException() {
                byte[] emptyData = new byte[0];

                RuntimeException exception = assertThrows(RuntimeException.class, () ->
                        fileService.createThumbnailFromBytes(17L, emptyData));
                assertEquals("Error reading files from path: Image data is null or empty", exception.getMessage());
            }

            @Test
            void nullImageBytes_throwsRuntimeException() {
                RuntimeException exception = assertThrows(RuntimeException.class, () ->
                        fileService.createThumbnailFromBytes(18L, null));
                assertEquals("Error reading files from path: Image data is null or empty", exception.getMessage());
            }
        }

        @Nested
        @DisplayName("createThumbnailFromFile")
        class CreateThumbnailFromFileTests {

            @Test
            void validJpegFile_succeeds() throws IOException {
                BufferedImage image = createTestImage(300, 400);
                byte[] imageBytes = imageToBytes(image);
                MockMultipartFile file = new MockMultipartFile(
                        "file", "test.jpg", "image/jpeg", imageBytes);

                assertDoesNotThrow(() ->
                        fileService.createThumbnailFromFile(5L, file));
                assertTrue(Files.exists(Path.of(fileService.getCoverFile(5L))));
            }

            @Test
            void validPngFile_succeeds() throws IOException {
                BufferedImage image = createTestImage(300, 400);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "PNG", baos);
                MockMultipartFile file = new MockMultipartFile(
                        "file", "test.png", "image/png", baos.toByteArray());

                assertDoesNotThrow(() ->
                        fileService.createThumbnailFromFile(6L, file));
            }

            @Test
            void emptyFile_throwsRuntimeException() {
                MockMultipartFile emptyFile = new MockMultipartFile(
                        "file", "empty.jpg", "image/jpeg", new byte[0]);

                // validateCoverFile throws IllegalArgumentException, but it's caught and wrapped in RuntimeException via ApiError
                RuntimeException exception = assertThrows(RuntimeException.class, () ->
                        fileService.createThumbnailFromFile(7L, emptyFile));
                assertTrue(exception.getMessage().contains("empty") ||
                                exception.getCause() instanceof IllegalArgumentException,
                        "Exception message should indicate file is empty or wrap IllegalArgumentException");
            }

            @Test
            void invalidMimeType_throwsRuntimeException() {
                MockMultipartFile gifFile = new MockMultipartFile(
                        "file", "test.gif", "image/gif", "fake data".getBytes());

                RuntimeException exception = assertThrows(RuntimeException.class, () ->
                        fileService.createThumbnailFromFile(8L, gifFile));
                assertTrue(exception.getMessage().contains("Only JPEG and PNG files are allowed") ||
                                exception.getCause() instanceof IllegalArgumentException,
                        "Exception message should indicate only JPEG and PNG are allowed or wrap IllegalArgumentException");
            }

            @Test
            void fileTooLarge_throwsRuntimeException() {
                byte[] largeData = new byte[6 * 1024 * 1024]; // 6MB
                MockMultipartFile largeFile = new MockMultipartFile(
                        "file", "large.jpg", "image/jpeg", largeData);

                // validateCoverFile throws IllegalArgumentException, but it's caught and wrapped in RuntimeException via ApiError
                RuntimeException exception = assertThrows(RuntimeException.class, () ->
                        fileService.createThumbnailFromFile(9L, largeFile));
                assertTrue(exception.getMessage().contains("5 MB") ||
                                exception.getCause() instanceof IllegalArgumentException,
                        "Exception message should indicate file size limit or wrap IllegalArgumentException");
            }

            @Test
            void fileExactlyAtSizeLimit_succeeds() throws IOException {
                BufferedImage image = createTestImage(100, 100);
                byte[] imageBytes = imageToBytes(image);
                // Ensure it's under 5MB
                assertTrue(imageBytes.length < 5 * 1024 * 1024);

                MockMultipartFile file = new MockMultipartFile(
                        "file", "valid.jpg", "image/jpeg", imageBytes);

                assertDoesNotThrow(() ->
                        fileService.createThumbnailFromFile(10L, file));
            }

            @Test
            void caseInsensitiveMimeType_succeeds() throws IOException {
                BufferedImage image = createTestImage(100, 100);
                byte[] imageBytes = imageToBytes(image);
                MockMultipartFile file = new MockMultipartFile(
                        "file", "test.jpg", "IMAGE/JPEG", imageBytes);

                assertDoesNotThrow(() ->
                        fileService.createThumbnailFromFile(11L, file));
            }

            @Test
            void corruptImageData_throwsException() {
                // Valid MIME type but corrupt image data
                byte[] corruptData = ("not an image but has jpeg mime type").getBytes();
                MockMultipartFile corruptFile = new MockMultipartFile(
                        "file", "corrupt.jpg", "image/jpeg", corruptData);

                assertThrows(RuntimeException.class, () ->
                        fileService.createThumbnailFromFile(12L, corruptFile));
            }

            @Test
            void unsupportedMimeType_gif_throwsRuntimeException() {
                byte[] gifData = "GIF89a...".getBytes(); // Fake GIF header
                MockMultipartFile gifFile = new MockMultipartFile(
                        "file", "test.gif", "image/gif", gifData);

                // validateCoverFile throws IllegalArgumentException, but it's caught and wrapped in RuntimeException via ApiError
                assertThrows(RuntimeException.class, () ->
                                fileService.createThumbnailFromFile(13L, gifFile),
                        "Should throw RuntimeException (wrapping IllegalArgumentException) for unsupported MIME type");
            }

            @Test
            void mimeTypeWithExtraParameters_succeeds() throws IOException {
                BufferedImage image = createTestImage(100, 100);
                byte[] imageBytes = imageToBytes(image);
                MockMultipartFile file = new MockMultipartFile(
                        "file", "test.jpg", "image/jpeg;charset=UTF-8", imageBytes);

                assertDoesNotThrow(() ->
                        fileService.createThumbnailFromFile(14L, file));
            }
        }

        @Nested
        @DisplayName("setBookCoverPath")
        class SetBookCoverPathTests {

            @Test
            void setsTimestampToCurrentTime() {
                BookMetadataEntity entity = new BookMetadataEntity();
                Instant before = Instant.now();

                FileService.setBookCoverPath(entity);

                Instant after = Instant.now();

                assertNotNull(entity.getCoverUpdatedOn());
                assertFalse(entity.getCoverUpdatedOn().isBefore(before));
                assertFalse(entity.getCoverUpdatedOn().isAfter(after));
            }

            @Test
            void overwritesExistingTimestamp() {
                BookMetadataEntity entity = new BookMetadataEntity();
                Instant oldTime = Instant.parse("2020-01-01T00:00:00Z");
                entity.setCoverUpdatedOn(oldTime);

                FileService.setBookCoverPath(entity);

                assertNotEquals(oldTime, entity.getCoverUpdatedOn());
            }
        }

        @Nested
        @DisplayName("deleteBookCovers")
        class DeleteBookCoversTests {

            @Test
            void existingCovers_deletesAll() throws IOException {
                BufferedImage image = createTestImage(100, 100);
                fileService.saveCoverImages(image, 10L);
                fileService.saveCoverImages(image, 11L);

                fileService.deleteBookCovers(Set.of(10L, 11L));

                assertAll(
                        () -> assertFalse(Files.exists(
                                Path.of(fileService.getImagesFolder(10L)))),
                        () -> assertFalse(Files.exists(
                                Path.of(fileService.getImagesFolder(11L))))
                );
            }

            @Test
            void nonExistentCovers_doesNotThrow() {
                assertDoesNotThrow(() ->
                        fileService.deleteBookCovers(Set.of(999L, 1000L)));
            }

            @Test
            void emptySet_doesNothing() {
                assertDoesNotThrow(() ->
                        fileService.deleteBookCovers(Set.of()));
            }

            @Test
            void mixedExistingAndNonExisting_deletesExisting() throws Exception {
                BufferedImage image = createTestImage(100, 100);
                fileService.saveCoverImages(image, 20L);

                fileService.deleteBookCovers(Set.of(20L, 21L));

                assertFalse(Files.exists(Path.of(fileService.getImagesFolder(20L))));
            }

            @Test
            void singleBookId_works() throws IOException {
                BufferedImage image = createTestImage(100, 100);
                fileService.saveCoverImages(image, 30L);

                fileService.deleteBookCovers(Set.of(30L));

                assertFalse(Files.exists(Path.of(fileService.getImagesFolder(30L))));
            }
        }
    }

    @Nested
    @DisplayName("Network Operations")
    class NetworkOperationsTests {

        @Mock
        private RestTemplate restTemplate;

        @Mock
        private AppSettingService appSettingServiceForNetwork;

        private FileService fileService;

        @BeforeEach
        void setup() throws IOException {
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

            VipsImageService networkMockVips = mock(VipsImageService.class);

            // Stub vips for tests that call saveCoverImages
            lenient().when(networkMockVips.bufferedImageToJpeg(any(BufferedImage.class), anyInt())).thenAnswer(inv -> {
                BufferedImage img = inv.getArgument(0);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "JPEG", baos);
                return baos.toByteArray();
            });
            lenient().when(networkMockVips.readDimensions(any(byte[].class))).thenAnswer(inv -> {
                byte[] data = inv.getArgument(0);
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                if (img == null) throw new IOException("Cannot decode image");
                return new ImageDimensions(img.getWidth(), img.getHeight());
            });
            lenient().when(networkMockVips.readDimensionsFromFile(any(Path.class))).thenAnswer(inv -> {
                Path path = inv.getArgument(0);
                byte[] data = Files.readAllBytes(path);
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                if (img == null) throw new IOException("Cannot decode image from file");
                return new ImageDimensions(img.getWidth(), img.getHeight());
            });
            lenient().when(networkMockVips.findContentBounds(any(byte[].class))).thenAnswer(inv -> {
                byte[] data = inv.getArgument(0);
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                if (img == null) return new TrimBounds(0, 0, 1, 1);
                return new TrimBounds(0, 0, img.getWidth(), img.getHeight());
            });
            lenient().doAnswer(inv -> {
                byte[] data = inv.getArgument(0);
                Path target = inv.getArgument(1);
                Files.createDirectories(target.getParent());
                Files.write(target, data);
                return null;
            }).when(networkMockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt());
            lenient().doAnswer(inv -> {
                Path source = inv.getArgument(0);
                Path target = inv.getArgument(1);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return null;
            }).when(networkMockVips).flattenThumbnailAndSave(any(Path.class), any(Path.class), anyInt(), anyInt());
            lenient().doAnswer(inv -> {
                byte[] data = inv.getArgument(0);
                Path target = inv.getArgument(1);
                Files.createDirectories(target.getParent());
                Files.write(target, data);
                return null;
            }).when(networkMockVips).flattenCropResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

            fileService = new FileService(appProperties, restTemplate, appSettingServiceForNetwork, restTemplate, networkMockVips);
        }

        @Nested
        @DisplayName("downloadImageFromUrl")
        class DownloadImageFromUrlTests {

            @Test
            @DisplayName("downloads and returns valid image")
            @Timeout(5)
            void downloadImageFromUrl_validImage_returnsBytes() throws IOException {
                String imageUrl = "http://1.1.1.1/image.jpg";
                BufferedImage testImage = createTestImage(100, 100);
                byte[] imageBytes = imageToBytes(testImage);

                RestTemplate mockRestTemplate = mock(RestTemplate.class);
                AppSettingService mockAppSettingService = mock(AppSettingService.class);
                FileService testFileService = new FileService(appProperties, mockRestTemplate, mockAppSettingService, mockRestTemplate, mock(VipsImageService.class));

                ResponseEntity<byte[]> responseEntity = ResponseEntity.ok(imageBytes);
                when(mockRestTemplate.exchange(
                        anyString(),
                        eq(HttpMethod.GET),
                        any(HttpEntity.class),
                        eq(byte[].class)
                )).thenReturn(responseEntity);

                byte[] result = testFileService.downloadImageFromUrl(imageUrl);

                assertNotNull(result);
                assertTrue(result.length > 0);
            }

            @Test
            @DisplayName("throws exception when response body is null")
            @Timeout(5)
            void downloadImageFromUrl_nullBody_throwsException() {
                String imageUrl = "http://1.1.1.1/image.jpg";
                ResponseEntity<byte[]> responseEntity = ResponseEntity.ok(null);
                when(restTemplate.exchange(
                        anyString(),
                        eq(HttpMethod.GET),
                        any(HttpEntity.class),
                        eq(byte[].class)
                )).thenReturn(responseEntity);

                assertThrows(IOException.class, () ->
                        fileService.downloadImageFromUrl(imageUrl));
            }

            @Test
            @DisplayName("throws exception on HTTP error status")
            @Timeout(5)
            void downloadImageFromUrl_httpError_throwsException() {
                String imageUrl = "http://1.1.1.1/image.jpg";
                ResponseEntity<byte[]> responseEntity = ResponseEntity.notFound().build();
                when(restTemplate.exchange(
                        anyString(),
                        eq(HttpMethod.GET),
                        any(HttpEntity.class),
                        eq(byte[].class)
                )).thenReturn(responseEntity);

                assertThrows(IOException.class, () ->
                        fileService.downloadImageFromUrl(imageUrl));
            }

            @Test
            @DisplayName("rewrites redirect URL to preserve hostname when CDN redirects to raw IP")
            @Timeout(5)
            void downloadImageFromUrl_redirectToRawIp_rewritesUrlWithOriginalHost() throws IOException {
                String originalUrl = "http://example.com/cover.jpg";
                String cdnIpRedirect = "http://3.168.64.124/cover.jpg";
                BufferedImage testImage = createTestImage(100, 100);
                byte[] imageBytes = imageToBytes(testImage);

                RestTemplate mockRestTemplate = mock(RestTemplate.class);
                FileService testFileService = new FileService(appProperties, mockRestTemplate, appSettingServiceForNetwork, mockRestTemplate, mock(VipsImageService.class));

                ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(302)
                        .header("Location", cdnIpRedirect).build();
                ResponseEntity<byte[]> imageResponse = ResponseEntity.ok(imageBytes);

                var urlCaptor = ArgumentCaptor.forClass(String.class);
                when(mockRestTemplate.exchange(
                        urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
                )).thenReturn(redirectResponse, imageResponse);

                byte[] result = testFileService.downloadImageFromUrl(originalUrl);

                assertNotNull(result);
                assertEquals(originalUrl, urlCaptor.getAllValues().get(0));
                assertEquals("http://example.com/cover.jpg", urlCaptor.getAllValues().get(1));
            }

            @Test
            @DisplayName("preserves redirect path when rewriting raw IP URL back to hostname")
            @Timeout(5)
            void downloadImageFromUrl_redirectToRawIpDifferentPath_preservesPath() throws IOException {
                String originalUrl = "http://example.com/images/cover.jpg";
                String cdnIpRedirect = "http://3.168.64.124/cdn/optimized/cover.jpg?token=abc";
                BufferedImage testImage = createTestImage(100, 100);
                byte[] imageBytes = imageToBytes(testImage);

                RestTemplate mockRestTemplate = mock(RestTemplate.class);
                FileService testFileService = new FileService(appProperties, mockRestTemplate, appSettingServiceForNetwork, mockRestTemplate, mock(VipsImageService.class));

                ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(302)
                        .header("Location", cdnIpRedirect).build();
                ResponseEntity<byte[]> imageResponse = ResponseEntity.ok(imageBytes);

                var urlCaptor = ArgumentCaptor.forClass(String.class);
                when(mockRestTemplate.exchange(
                        urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
                )).thenReturn(redirectResponse, imageResponse);

                testFileService.downloadImageFromUrl(originalUrl);

                assertEquals("http://example.com/cdn/optimized/cover.jpg?token=abc", urlCaptor.getAllValues().get(1));
            }

            @Test
            @DisplayName("does not rewrite URL when redirect target is a hostname")
            @Timeout(5)
            void downloadImageFromUrl_redirectToHostname_keepsRedirectUrl() throws IOException {
                String originalUrl = "http://example.com/cover.jpg";
                String hostnameRedirect = "http://www.example.com/cover.jpg";
                BufferedImage testImage = createTestImage(100, 100);
                byte[] imageBytes = imageToBytes(testImage);

                RestTemplate mockRestTemplate = mock(RestTemplate.class);
                FileService testFileService = new FileService(appProperties, mockRestTemplate, appSettingServiceForNetwork, mockRestTemplate, mock(VipsImageService.class));

                ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(301)
                        .header("Location", hostnameRedirect).build();
                ResponseEntity<byte[]> imageResponse = ResponseEntity.ok(imageBytes);

                var urlCaptor = ArgumentCaptor.forClass(String.class);
                when(mockRestTemplate.exchange(
                        urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
                )).thenReturn(redirectResponse, imageResponse);

                testFileService.downloadImageFromUrl(originalUrl);

                assertEquals(hostnameRedirect, urlCaptor.getAllValues().get(1));
            }

            @Test
            @DisplayName("chain: hostname -> hostname -> raw IP uses last hostname for rewrite")
            @Timeout(5)
            void downloadImageFromUrl_multipleRedirectsToRawIp_usesLastHostname() throws IOException {
                String originalUrl = "http://example.com/cover.jpg";
                String hostnameRedirect = "http://www.example.com/cover.jpg";
                String ipRedirect = "http://52.84.12.99/cover.jpg";
                BufferedImage testImage = createTestImage(100, 100);
                byte[] imageBytes = imageToBytes(testImage);

                RestTemplate mockRestTemplate = mock(RestTemplate.class);
                FileService testFileService = new FileService(appProperties, mockRestTemplate, appSettingServiceForNetwork, mockRestTemplate, mock(VipsImageService.class));

                ResponseEntity<byte[]> redirect1 = ResponseEntity.status(301)
                        .header("Location", hostnameRedirect).build();
                ResponseEntity<byte[]> redirect2 = ResponseEntity.status(302)
                        .header("Location", ipRedirect).build();
                ResponseEntity<byte[]> imageResponse = ResponseEntity.ok(imageBytes);

                var urlCaptor = ArgumentCaptor.forClass(String.class);
                when(mockRestTemplate.exchange(
                        urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
                )).thenReturn(redirect1, redirect2, imageResponse);

                testFileService.downloadImageFromUrl(originalUrl);

                assertEquals(originalUrl, urlCaptor.getAllValues().get(0));
                assertEquals(hostnameRedirect, urlCaptor.getAllValues().get(1));
                assertEquals("http://www.example.com/cover.jpg", urlCaptor.getAllValues().get(2));
            }

            @Test
            @DisplayName("throws exception when redirect exceeds max limit")
            @Timeout(5)
            void downloadImageFromUrl_tooManyRedirects_throwsException() {
                String imageUrl = "http://1.1.1.1/cover.jpg";

                RestTemplate mockRestTemplate = mock(RestTemplate.class);
                FileService testFileService = new FileService(appProperties, mockRestTemplate, appSettingServiceForNetwork, mockRestTemplate, mock(VipsImageService.class));

                ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(302)
                        .header("Location", "http://2.2.2.2/cover.jpg")
                        .build();

                when(mockRestTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
                )).thenReturn(redirectResponse);

                IOException ex = assertThrows(IOException.class, () ->
                        testFileService.downloadImageFromUrl(imageUrl));
                assertTrue(ex.getMessage().contains("Too many redirects"));
            }

            @Test
            @DisplayName("throws exception when redirect has no Location header")
            @Timeout(5)
            void downloadImageFromUrl_redirectWithoutLocation_throwsException() {
                String imageUrl = "http://1.1.1.1/image.jpg";

                ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(302).build();
                when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
                )).thenReturn(redirectResponse);

                IOException ex = assertThrows(IOException.class, () ->
                        fileService.downloadImageFromUrl(imageUrl));
                assertTrue(ex.getMessage().contains("Location"));
            }
        }

        @Nested
        @DisplayName("createThumbnailFromUrl")
        class CreateThumbnailFromUrlTests {

            @Test
            @DisplayName("downloads and delegates to saveCoverImages successfully")
            @Timeout(5)
            void createThumbnailFromUrl_validImage_delegatesToSaveCoverImages() throws IOException {
                String imageUrl = "http://1.1.1.1/cover.jpg";
                long bookId = 42L;
                BufferedImage testImage = createTestImage(800, 1200);
                byte[] imageBytes = imageToBytes(testImage);

                VipsImageService spyVips = mock(VipsImageService.class);
                lenient().when(spyVips.readDimensions(any(byte[].class))).thenAnswer(inv -> {
                    byte[] data = inv.getArgument(0);
                    BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                    return new ImageDimensions(img.getWidth(), img.getHeight());
                });
                lenient().when(spyVips.readDimensionsFromFile(any(Path.class))).thenAnswer(inv -> {
                    Path path = inv.getArgument(0);
                    byte[] data = Files.readAllBytes(path);
                    BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                    return new ImageDimensions(img.getWidth(), img.getHeight());
                });
                lenient().when(spyVips.findContentBounds(any(byte[].class))).thenAnswer(inv -> {
                    byte[] data = inv.getArgument(0);
                    BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                    return new TrimBounds(0, 0, img.getWidth(), img.getHeight());
                });
                lenient().doAnswer(inv -> {
                    Path target = inv.getArgument(1);
                    Files.createDirectories(target.getParent());
                    Files.write(target, (byte[]) inv.getArgument(0));
                    return null;
                }).when(spyVips).flattenResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt());
                lenient().doAnswer(inv -> {
                    Path source = inv.getArgument(0);
                    Path target = inv.getArgument(1);
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return null;
                }).when(spyVips).flattenThumbnailAndSave(any(Path.class), any(Path.class), anyInt(), anyInt());

                FileService testFileService = new FileService(appProperties, restTemplate, appSettingServiceForNetwork, restTemplate, spyVips);

                ResponseEntity<byte[]> responseEntity = ResponseEntity.ok(imageBytes);
                when(restTemplate.exchange(
                        anyString(),
                        eq(HttpMethod.GET),
                        any(HttpEntity.class),
                        eq(byte[].class)
                )).thenReturn(responseEntity);

                assertDoesNotThrow(() ->
                        testFileService.createThumbnailFromUrl(bookId, imageUrl));

                verify(spyVips).flattenResizeAndSave(eq(imageBytes), any(Path.class), anyInt(), anyInt());
                verify(spyVips).flattenThumbnailAndSave(any(Path.class), any(Path.class), anyInt(), anyInt());
            }

            @Test
            @DisplayName("throws ApiError.FILE_READ_ERROR on download failure")
            @Timeout(5)
            void createThumbnailFromUrl_downloadFails_throwsApiError() {
                String imageUrl = "http://example.com/invalid.jpg";
                long bookId = 42L;

                when(restTemplate.exchange(
                        anyString(),
                        eq(HttpMethod.GET),
                        any(HttpEntity.class),
                        eq(byte[].class)
                )).thenThrow(new RuntimeException("Network error"));

                // FileService wraps exceptions in RuntimeException via ApiError
                RuntimeException exception = assertThrows(RuntimeException.class, () ->
                        fileService.createThumbnailFromUrl(bookId, imageUrl));
                assertTrue(exception.getMessage().contains("Network error") ||
                                exception.getMessage().contains("Failed"),
                        "Exception message should indicate download failure");
            }

        }
    }

    private BufferedImage createTestImage(int width, int height) {
        return createTestImage(width, height, Color.BLUE);
    }

    private BufferedImage createTestImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.drawString("Test", 10, height / 2);
        g.dispose();
        return image;
    }

    private byte[] imageToBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", baos);
        return baos.toByteArray();
    }
}