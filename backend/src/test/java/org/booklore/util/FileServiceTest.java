package org.booklore.util;

import org.booklore.config.AppProperties;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.model.entity.BookMetadataEntity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
                .smartCroppingEnabled(true)
                .build();
        AppSettings appSettings = AppSettings.builder()
                .coverCroppingSettings(coverCroppingSettings)
                .build();
        lenient().when(appSettingService.getAppSettings()).thenReturn(appSettings);
        lenient().when(appProperties.getPathConfig()).thenReturn(tempDir.toString());

        RestTemplate mockRestTemplate = mock(RestTemplate.class);
        RestTemplate mockNoRedirectRestTemplate = mock(RestTemplate.class);
        mockVips = mock(VipsImageService.class);

        // Stub processStreamToJpeg: copy input to output
        lenient().doAnswer(inv -> {
            InputStream in = inv.getArgument(0);
            OutputStream out = inv.getArgument(1);
            in.transferTo(out);
            return null;
        }).when(mockVips).processStreamToJpeg(any(InputStream.class), any(OutputStream.class), anyInt(), anyInt());

        // Stub bufferedImageToJpeg: convert BufferedImage to JPEG bytes using ImageIO
        lenient().when(mockVips.bufferedImageToJpeg(any(BufferedImage.class), anyInt())).thenAnswer(inv -> {
            BufferedImage img = inv.getArgument(0);
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
            try (var bis = new java.io.ByteArrayInputStream(data)) {
                BufferedImage img = ImageIO.read(bis);
                if (img == null) throw new IOException("Cannot decode image");
                return new ImageDimensions(img.getWidth(), img.getHeight());
            }
        });

        // Stub readDimensionsFromFile: read file and decode to get dimensions
        lenient().when(mockVips.readDimensionsFromFile(any(Path.class))).thenAnswer(inv -> {
            Path path = inv.getArgument(0);
            try (var bis = new java.io.BufferedInputStream(Files.newInputStream(path))) {
                BufferedImage img = ImageIO.read(bis);
                if (img == null) throw new IOException("Cannot decode image from file");
                return new ImageDimensions(img.getWidth(), img.getHeight());
            }
        });

        // Stub findContentBounds: simple white-at-top detection
        lenient().when(mockVips.findContentBounds(any(byte[].class))).thenAnswer(inv -> {
            byte[] data = inv.getArgument(0);
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (img == null) return new TrimBounds(0, 0, 1, 1);
            int top = 0;
            if (img.getHeight() > 200 && (img.getRGB(0, 0) & 0xFFFFFF) == 0xFFFFFF) {
                top = 200;
            }
            int left = 0;
            if (img.getWidth() > 200 && (img.getRGB(0, 0) & 0xFFFFFF) == 0xFFFFFF) {
                left = 200;
            }
            return new TrimBounds(left, top, img.getWidth() - left, img.getHeight() - top);
        });

        lenient().when(mockVips.findContentBounds(any(Path.class))).thenAnswer(inv -> {
            Path path = inv.getArgument(0);
            BufferedImage img = ImageIO.read(path.toFile());
            if (img == null) return new TrimBounds(0, 0, 1, 1);
            int top = 0;
            if (img.getHeight() > 200 && (img.getRGB(0, 0) & 0xFFFFFF) == 0xFFFFFF) {
                top = 200;
            }
            int left = 0;
            if (img.getWidth() > 200 && (img.getRGB(0, 0) & 0xFFFFFF) == 0xFFFFFF) {
                left = 200;
            }
            return new TrimBounds(left, top, img.getWidth() - left, img.getHeight() - top);
        });

        // Stub flattenResizeAndSave: resize and write a JPEG
        lenient().doAnswer(inv -> {
            byte[] data = inv.getArgument(0);
            Path target = inv.getArgument(1);
            int maxWidth = inv.getArgument(2);
            int maxHeight = inv.getArgument(3);
            
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
            double ratio = Math.min((double) maxWidth / img.getWidth(), (double) maxHeight / img.getHeight());
            int newWidth = (int) (img.getWidth() * Math.min(1.0, ratio));
            int newHeight = (int) (img.getHeight() * Math.min(1.0, ratio));
            
            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.drawImage(img, 0, 0, newWidth, newHeight, null);
            g.dispose();
            
            Files.createDirectories(target.getParent());
            ImageIO.write(resized, "JPEG", target.toFile());
            return null;
        }).when(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt());

        lenient().doAnswer(inv -> {
            Path source = inv.getArgument(0);
            Path target = inv.getArgument(1);
            int maxWidth = inv.getArgument(2);
            int maxHeight = inv.getArgument(3);

            BufferedImage img = ImageIO.read(source.toFile());
            double ratio = Math.min((double) maxWidth / img.getWidth(), (double) maxHeight / img.getHeight());
            int newWidth = (int) (img.getWidth() * Math.min(1.0, ratio));
            int newHeight = (int) (img.getHeight() * Math.min(1.0, ratio));

            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.drawImage(img, 0, 0, newWidth, newHeight, null);
            g.dispose();

            Files.createDirectories(target.getParent());
            ImageIO.write(resized, "JPEG", target.toFile());
            return null;
        }).when(mockVips).flattenResizeAndSave(any(Path.class), any(Path.class), anyInt(), anyInt());

        // Stub flattenThumbnailAndSave (file-to-file): resize and save
        lenient().doAnswer(inv -> {
            Path source = inv.getArgument(0);
            Path target = inv.getArgument(1);
            int width = inv.getArgument(2);
            int height = inv.getArgument(3);
            
            BufferedImage img = ImageIO.read(source.toFile());
            BufferedImage thumb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            g.drawImage(img, 0, 0, width, height, null);
            g.dispose();
            
            Files.createDirectories(target.getParent());
            ImageIO.write(thumb, "JPEG", target.toFile());
            return null;
        }).when(mockVips).flattenThumbnailAndSave(any(Path.class), any(Path.class), anyInt(), anyInt());

        // Stub flattenCropResizeAndSave (byte[] version): crop, resize and save
        lenient().doAnswer(inv -> {
            byte[] data = inv.getArgument(0);
            Path target = inv.getArgument(1);
            int left = inv.getArgument(2);
            int top = inv.getArgument(3);
            int width = inv.getArgument(4);
            int height = inv.getArgument(5);
            int maxWidth = inv.getArgument(6);
            int maxHeight = inv.getArgument(7);
            
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
            BufferedImage cropped = img.getSubimage(left, top, width, height);
            
            double ratio = Math.min((double) maxWidth / width, (double) maxHeight / height);
            int newWidth = (int) (width * Math.min(1.0, ratio));
            int newHeight = (int) (height * Math.min(1.0, ratio));
            
            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.drawImage(cropped, 0, 0, newWidth, newHeight, null);
            g.dispose();
            
            Files.createDirectories(target.getParent());
            ImageIO.write(resized, "JPEG", target.toFile());
            return null;
        }).when(mockVips).flattenCropResizeAndSave(any(byte[].class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        // Stub Path version of flattenCropResizeAndSave
        lenient().doAnswer(inv -> {
            Path source = inv.getArgument(0);
            Path target = inv.getArgument(1);
            int left = inv.getArgument(2);
            int top = inv.getArgument(3);
            int width = inv.getArgument(4);
            int height = inv.getArgument(5);
            int maxWidth = inv.getArgument(6);
            int maxHeight = inv.getArgument(7);
            
            BufferedImage img = ImageIO.read(source.toFile());
            BufferedImage cropped = img.getSubimage(left, top, width, height);
            
            double ratio = Math.min((double) maxWidth / width, (double) maxHeight / height);
            int newWidth = (int) (width * Math.min(1.0, ratio));
            int newHeight = (int) (height * Math.min(1.0, ratio));
            
            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.drawImage(cropped, 0, 0, newWidth, newHeight, null);
            g.dispose();
            
            Files.createDirectories(target.getParent());
            ImageIO.write(resized, "JPEG", target.toFile());
            return null;
        }).when(mockVips).flattenCropResizeAndSave(any(Path.class), any(Path.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        fileService = new FileService(appProperties, mockRestTemplate, appSettingService, mockNoRedirectRestTemplate, mockVips);
    }

    @Nested
    @DisplayName("Image Operations")
    class ImageOperationsTests {

        @Nested
        @DisplayName("validateImageData")
        class ValidateImageDataTests {
            @Test
            void validData_doesNotThrow() throws IOException {
                BufferedImage image = createTestImage(100, 100);
                byte[] imageData = imageToBytes(image);
                assertDoesNotThrow(() -> FileService.validateImageData(imageData, mockVips));
            }

            @Test
            void nullData_throwsException() {
                IOException ex = assertThrows(IOException.class, () -> FileService.validateImageData(null, mockVips));
                assertEquals("Image data is null or empty", ex.getMessage());
            }

            @Test
            void decompressionBomb_throwsException() throws IOException {
                byte[] imageData = new byte[10];
                when(mockVips.readDimensions(any(byte[].class))).thenReturn(new ImageDimensions(10000, 10000));

                IOException ex = assertThrows(IOException.class, () -> FileService.validateImageData(imageData, mockVips));
                assertTrue(ex.getMessage().contains("possible decompression bomb"));
            }
        }

        @Nested
        @DisplayName("saveImage")
        class SaveImageTests {
            @Test
            void validData_savesImage() throws IOException {
                BufferedImage image = createTestImage(100, 100);
                byte[] imageData = imageToBytes(image);
                Path outputPath = tempDir.resolve("test.jpg");

                assertDoesNotThrow(() -> fileService.saveImage(imageData, outputPath.toString()));
                assertTrue(Files.exists(outputPath));
                verify(mockVips).flattenResizeAndSave(eq(imageData), eq(outputPath), anyInt(), anyInt());
            }

            @Test
            void emptyData_doesNotSave() throws IOException {
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

        @Test
        void saveCoverImages_createsCoverAndThumbnail() throws IOException {
            BufferedImage image = createTestImage(800, 1200);
            boolean result = fileService.saveCoverImages(image, 1L);

            assertTrue(result);
            verify(mockVips).flattenResizeAndSave(any(Path.class), any(Path.class), eq(1000), eq(1500));
            verify(mockVips).flattenThumbnailAndSave(any(Path.class), any(Path.class), eq(250), eq(350));
        }

        @Test
        void largeImage_isScaledDownToMaxDimensions() throws IOException {
            int largeWidth = 2000;
            int largeHeight = 3000;
            BufferedImage largeImage = createTestImage(largeWidth, largeHeight);

            boolean result = fileService.saveCoverImages(largeImage, 5L);

            assertTrue(result);
            verify(mockVips).flattenResizeAndSave(any(Path.class), any(Path.class), eq(1000), eq(1500));
        }
    }

    @Nested
    @DisplayName("Cover Cropping")
    class CoverCroppingTests {
        @Test
        @DisplayName("extremely tall image is cropped when vertical cropping enabled")
        void extremelyTallImage_isCropped() throws IOException {
            int width = 940;
            int height = 11280;
            BufferedImage tallImage = createTestImage(width, height);

            boolean result = fileService.saveCoverImages(tallImage, 100L);

            assertTrue(result);
                verify(mockVips).flattenCropResizeAndSave(any(Path.class), any(Path.class),
                    anyInt(), anyInt(), anyInt(), anyInt(), eq(1000), eq(1500));
        }

        @Test
        @DisplayName("extremely wide image is cropped when horizontal cropping enabled")
        void extremelyWideImage_isCropped() throws IOException {
            int width = 3000;
            int height = 400;
            BufferedImage wideImage = createTestImage(width, height);

            boolean result = fileService.saveCoverImages(wideImage, 101L);

            assertTrue(result);

            BufferedImage savedCover = ImageIO.read(
                    new File(fileService.getCoverFile(101L)));

            assertNotNull(savedCover);

            // The image should be cropped to a more reasonable aspect ratio
            double savedRatio = (double) savedCover.getWidth() / savedCover.getHeight();
            assertTrue(savedRatio < 3.0,
                    "Cropped image should have reasonable aspect ratio, was: " + savedRatio);
        }

        @Test
        @DisplayName("normal aspect ratio image is not cropped")
        void normalAspectRatioImage_isNotCropped() throws IOException {
            // Create a normal book cover sized image (ratio ~1.5:1)
            int width = 600;
            int height = 900;  // ratio = 1.5:1

            BufferedImage normalImage = createTestImage(width, height);
            boolean result = fileService.saveCoverImages(normalImage, 102L);

            assertTrue(result);

            BufferedImage savedCover = ImageIO.read(
                    new File(fileService.getCoverFile(102L)));

            assertNotNull(savedCover);

            // The image should maintain its original aspect ratio
            double originalRatio = (double) height / width;
            double savedRatio = (double) savedCover.getHeight() / savedCover.getWidth();
            assertEquals(originalRatio, savedRatio, 0.01,
                    "Normal aspect ratio image should not be cropped");
        }

        @Test
        @DisplayName("cropping is disabled when settings are off")
        void croppingDisabled_imageNotCropped() throws IOException {
            // Reconfigure with cropping disabled
            CoverCroppingSettings disabledSettings = CoverCroppingSettings.builder()
                    .verticalCroppingEnabled(false)
                    .horizontalCroppingEnabled(false)
                    .aspectRatioThreshold(2.5)
                    .smartCroppingEnabled(true).build();
            AppSettings appSettings = AppSettings.builder()
                    .coverCroppingSettings(disabledSettings)
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(appSettings);

            // Create an extremely tall image
            int width = 400;
            int height = 4000;  // ratio = 10:1

            BufferedImage tallImage = createTestImage(width, height);
            boolean result = fileService.saveCoverImages(tallImage, 103L);

            assertTrue(result);

            BufferedImage savedCover = ImageIO.read(
                    new File(fileService.getCoverFile(103L)));

            assertNotNull(savedCover);

            // Since the image exceeds max dimensions, it will be scaled, but aspect ratio preserved
            double originalRatio = (double) height / width;
            double savedRatio = (double) savedCover.getHeight() / savedCover.getWidth();
            assertEquals(originalRatio, savedRatio, 0.01,
                    "Image should not be cropped when cropping is disabled");
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
            BufferedImage tallImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = tallImage.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, 200);
            g.setColor(Color.BLUE);
            g.fillRect(0, 200, width, height - 200);
            g.dispose();

            boolean result = fileService.saveCoverImages(tallImage, 104L);
            assertTrue(result);

            BufferedImage savedCover = ImageIO.read(new File(fileService.getCoverFile(104L)));
            assertNotNull(savedCover);
            double savedRatio = (double) savedCover.getHeight() / savedCover.getWidth();
            assertTrue(savedRatio < 3.0, "Cropped image should have reasonable aspect ratio");
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
            BufferedImage wideImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = wideImage.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 200, height);
            g.setColor(Color.BLUE);
            g.fillRect(200, 0, width - 200, height);
            g.dispose();

            boolean result = fileService.saveCoverImages(wideImage, 105L);
            assertTrue(result);

            BufferedImage savedCover = ImageIO.read(new File(fileService.getCoverFile(105L)));
            assertNotNull(savedCover);
            double savedRatio = (double) savedCover.getWidth() / savedCover.getHeight();
            assertTrue(savedRatio < 3.0, "Cropped image should have reasonable aspect ratio");
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
            BufferedImage uniformImage = createTestImage(width, height, Color.BLUE);

            boolean result = fileService.saveCoverImages(uniformImage, 106L);
            assertTrue(result);

            BufferedImage savedCover = ImageIO.read(new File(fileService.getCoverFile(106L)));
            assertNotNull(savedCover);
            double savedRatio = (double) savedCover.getHeight() / savedCover.getWidth();
            assertTrue(savedRatio < 3.0, "Cropped image should have reasonable aspect ratio");
        }

        @Test
        @DisplayName("null cover cropping settings returns image unchanged")
        void nullCoverCroppingSettings_returnsImageUnchanged() throws IOException {
            AppSettings appSettings = AppSettings.builder()
                    .coverCroppingSettings(null)
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(appSettings);

            int width = 500;
            int height = 3000;  // Very tall image
            BufferedImage tallImage = createTestImage(width, height);

            boolean result = fileService.saveCoverImages(tallImage, 107L);
            assertTrue(result);

            BufferedImage savedCover = ImageIO.read(new File(fileService.getCoverFile(107L)));
            assertNotNull(savedCover);
            assertTrue(savedCover.getWidth() <= 1000);
            assertTrue(savedCover.getHeight() <= 1500);
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
            when(appSettingService.getAppSettings()).thenReturn(
                    AppSettings.builder()
                            .maxFileUploadSizeInMb(5)
                            .build()
            );

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
            when(appSettingService.getAppSettings()).thenReturn(
                    AppSettings.builder()
                            .maxFileUploadSizeInMb(5)
                            .build()
            );

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
            when(appSettingService.getAppSettings()).thenReturn(
                    AppSettings.builder()
                            .maxFileUploadSizeInMb(5)
                            .build()
            );

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
            when(appSettingService.getAppSettings()).thenReturn(
                    AppSettings.builder()
                            .maxFileUploadSizeInMb(5)
                            .build()
            );

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
            when(appSettingService.getAppSettings()).thenReturn(
                    AppSettings.builder()
                            .maxFileUploadSizeInMb(5)
                            .build()
            );

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
            when(appSettingService.getAppSettings()).thenReturn(
                    AppSettings.builder()
                            .maxFileUploadSizeInMb(5)
                            .build()
            );

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

    @Nested
    @DisplayName("Network Operations")
    class NetworkOperationsTests {
        @Mock
        private RestTemplate restTemplate;

        @Test
        @DisplayName("downloads and returns valid image")
        void downloadImageFromUrl_validImage_returnsBytes() throws IOException {
            String imageUrl = "http://1.1.1.1/image.jpg";
            BufferedImage testImage = createTestImage(100, 100);
            byte[] imageBytes = imageToBytes(testImage);

            ResponseEntity<byte[]> responseEntity = ResponseEntity.ok(imageBytes);
            when(restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(byte[].class)
            )).thenReturn(responseEntity);

            FileService testFileService = new FileService(appProperties, restTemplate, appSettingService, restTemplate, mockVips);
            byte[] result = testFileService.downloadImageFromUrl(imageUrl);

            assertNotNull(result);
            assertArrayEquals(imageBytes, result);
        }
    }

    // Helper methods
    private BufferedImage createTestImage(int width, int height) {
        return createTestImage(width, height, Color.RED);
    }

    private BufferedImage createTestImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private byte[] imageToBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }
}
