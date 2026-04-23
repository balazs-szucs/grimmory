package org.booklore.util;

import org.booklore.config.AppProperties;
import org.booklore.model.settings.AppSettings;
import org.booklore.model.settings.CoverCroppingSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                when(mockVips.readDimensions(imageData)).thenReturn(new ImageDimensions(10000, 10000));

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
            verify(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), eq(1000), eq(1500));
            verify(mockVips).flattenThumbnailAndSave(any(Path.class), any(Path.class), eq(250), eq(350));
        }

        @Test
        void largeImage_isScaledDownToMaxDimensions() throws IOException {
            int largeWidth = 2000;
            int largeHeight = 3000;
            BufferedImage largeImage = createTestImage(largeWidth, largeHeight);
            
            boolean result = fileService.saveCoverImages(largeImage, 5L);

            assertTrue(result);
            verify(mockVips).flattenResizeAndSave(any(byte[].class), any(Path.class), eq(1000), eq(1500));
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
            verify(mockVips).flattenCropResizeAndSave(any(byte[].class), any(Path.class),
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
            verify(mockVips).flattenCropResizeAndSave(any(byte[].class), any(Path.class),
                    anyInt(), anyInt(), anyInt(), anyInt(), eq(1000), eq(1500));
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
