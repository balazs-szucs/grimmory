package org.booklore.util;

import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.service.appsettings.AppSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
@Service
public class FileService {

    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final AppSettingService appSettingService;
    private final RestTemplate noRedirectRestTemplate;
    private final VipsImageService vipsImageService;

    private static final int MAX_REDIRECTS = 5;


    private static final double TARGET_COVER_ASPECT_RATIO = 1.5;
    private static final double SMART_CROP_MARGIN_PERCENT = 0.02;

    // @formatter:off
    private static final String IMAGES_DIR                    = "images";
    private static final String AUTHOR_IMAGES_DIR             = "author-images";
    private static final String BACKGROUNDS_DIR               = "backgrounds";
    private static final String ICONS_DIR                     = "icons";
    private static final String SVG_DIR                       = "svg";
    private static final String THUMBNAIL_FILENAME            = "thumbnail.jpg";
    private static final String COVER_FILENAME                = "cover.jpg";
    private static final String AUTHOR_PHOTO_FILENAME         = "photo.jpg";
    private static final String AUTHOR_THUMBNAIL_FILENAME     = "thumbnail.jpg";
    private static final String AUDIOBOOK_THUMBNAIL_FILENAME  = "audiobook-thumbnail.jpg";
    private static final String AUDIOBOOK_COVER_FILENAME      = "audiobook-cover.jpg";
    private static final String JPEG_MIME_TYPE                = "image/jpeg";
    private static final String PNG_MIME_TYPE                 = "image/png";
    private static final long   MAX_FILE_SIZE_BYTES           = 5L * 1024 * 1024;
    // 20 MP covers legitimate book covers and author photos with a comfortable safety margin.
    private static final long   MAX_IMAGE_PIXELS              = 20_000_000L;
    private static final int    THUMBNAIL_WIDTH               = 250;
    private static final int    THUMBNAIL_HEIGHT              = 350;
    private static final int    SQUARE_THUMBNAIL_SIZE         = 250;
    private static final int    MAX_ORIGINAL_WIDTH            = 1000;
    private static final int    MAX_ORIGINAL_HEIGHT           = 1500;
    private static final int    MAX_SQUARE_SIZE               = 1000;
    private static final String IMAGE_FORMAT                  = "JPEG";
    // @formatter:on

    // ========================================
    // PATH UTILITIES
    // ========================================

    public String getImagesFolder(long bookId) {
        return Paths.get(appProperties.getPathConfig(), IMAGES_DIR, String.valueOf(bookId)).toString();
    }

    public String getThumbnailFile(long bookId) {
        return Paths.get(appProperties.getPathConfig(), IMAGES_DIR, String.valueOf(bookId), THUMBNAIL_FILENAME).toString();
    }

    public String getCoverFile(long bookId) {
        return Paths.get(appProperties.getPathConfig(), IMAGES_DIR, String.valueOf(bookId), COVER_FILENAME).toString();
    }

    public String getAudiobookThumbnailFile(long bookId) {
        return Paths.get(appProperties.getPathConfig(), IMAGES_DIR, String.valueOf(bookId), AUDIOBOOK_THUMBNAIL_FILENAME).toString();
    }

    public String getAudiobookCoverFile(long bookId) {
        return Paths.get(appProperties.getPathConfig(), IMAGES_DIR, String.valueOf(bookId), AUDIOBOOK_COVER_FILENAME).toString();
    }

    public String getAuthorImagesFolder(long authorId) {
        return Paths.get(appProperties.getPathConfig(), AUTHOR_IMAGES_DIR, String.valueOf(authorId)).toString();
    }

    public String getAuthorPhotoFile(long authorId) {
        return Paths.get(appProperties.getPathConfig(), AUTHOR_IMAGES_DIR, String.valueOf(authorId), AUTHOR_PHOTO_FILENAME).toString();
    }

    public String getAuthorThumbnailFile(long authorId) {
        return Paths.get(appProperties.getPathConfig(), AUTHOR_IMAGES_DIR, String.valueOf(authorId), AUTHOR_THUMBNAIL_FILENAME).toString();
    }

    public String getBackgroundsFolder(Long userId) {
        if (userId != null) {
            return Paths.get(appProperties.getPathConfig(), BACKGROUNDS_DIR, "user-" + userId).toString();
        }
        return Paths.get(appProperties.getPathConfig(), BACKGROUNDS_DIR).toString();
    }

    public String getBackgroundsFolder() {
        return getBackgroundsFolder(null);
    }

    public static String getBackgroundUrl(String filename, Long userId) {
        if (userId != null) {
            return Paths.get("/", BACKGROUNDS_DIR, "user-" + userId, filename).toString().replace("\\", "/");
        }
        return Paths.get("/", BACKGROUNDS_DIR, filename).toString().replace("\\", "/");
    }

    public String getBookMetadataBackupPath(long bookId) {
        return Paths.get(appProperties.getPathConfig(), "metadata_backup", String.valueOf(bookId)).toString();
    }

    public String getPdfCachePath() {
        return Paths.get(appProperties.getPathConfig(), "pdf_cache").toString();
    }

    public String getTempBookdropCoverImagePath(long bookdropFileId) {
        return Paths.get(appProperties.getPathConfig(), "bookdrop_temp", bookdropFileId + ".jpg").toString();
    }

    private String getSystemSearchPath() {
        // Search first in the application folder's "local" `bin`.
        StringBuilder localPaths = new StringBuilder("bin");

        // Then, check the legacy "tools" path from previous app versions.
        localPaths.append(File.pathSeparator).append(Path.of(appProperties.getPathConfig(), "tools"));

        // If not found in those, then search the system $PATH.
        String systemSearchPath = System.getenv("PATH");
        if (systemSearchPath != null) {
            localPaths.append(File.pathSeparator).append(systemSearchPath);
        }

        return localPaths.toString();
    }

    public Path findSystemFile(String filename) {
        String[] searchPaths = getSystemSearchPath().split(":");

        for (String path : searchPaths) {
            Path possiblePath = Paths
                    .get(path)
                    .resolve(filename)
                    .toAbsolutePath()
                    .normalize();

            if (Files.isRegularFile(possiblePath)) {
                return possiblePath;
            }
        }

        return null;
    }


    // ========================================
    // VALIDATION
    // ========================================

    private static void validateCoverFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String contentType = file.getContentType();
        if (contentType == null) {
            throw new IllegalArgumentException("Content type is required");
        }
        String lowerType = contentType.toLowerCase();
        if (!lowerType.startsWith(JPEG_MIME_TYPE) && !lowerType.startsWith(PNG_MIME_TYPE)) {
            throw new IllegalArgumentException("Only JPEG and PNG files are allowed");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File size must not exceed 5 MB");
        }
    }

    // ========================================
    // IMAGE OPERATIONS
    // ========================================

    /**
     * Validates that image bytes can be decoded by libvips and checks dimensions against the
     * decompression-bomb limit.
     */
    public static void validateImageData(byte[] imageData, VipsImageService vips) throws IOException {
        if (imageData == null || imageData.length == 0) {
            throw new IOException("Image data is null or empty");
        }
        ImageDimensions dims = vips.readDimensions(imageData);
        long pixelCount = (long) dims.width() * dims.height();
        if (pixelCount > MAX_IMAGE_PIXELS) {
            throw new IOException(String.format(
                    "Rejected image: dimensions %dx%d (%d pixels) exceed limit %d — possible decompression bomb",
                    dims.width(), dims.height(), pixelCount, MAX_IMAGE_PIXELS));
        }
    }

    public void saveImage(byte[] imageData, String filePath) throws IOException {
        if (imageData == null || imageData.length == 0) {
            log.warn("Skipping saveImage for {}: image data is null or empty", filePath);
            return;
        }
        File outputFile = new File(filePath);
        File parentDir = outputFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + parentDir);
        }
        vipsImageService.flattenResizeAndSave(imageData, outputFile.toPath(),
                MAX_ORIGINAL_WIDTH, MAX_ORIGINAL_HEIGHT);
        log.info("Image saved successfully to: {}", filePath);
    }

    public byte[] downloadImageFromUrl(String imageUrl) throws IOException {
        try {
            return downloadImageFromUrlInternal(imageUrl);
        } catch (Exception e) {
            log.warn("Failed to download image from {}: {}", imageUrl, e.getMessage());
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to download image from " + imageUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Downloads raw image bytes from a URL with SSRF protection (scheme validation
     */
    public byte[] downloadImageBytesFromUrl(String imageUrl) throws IOException {
        try {
            return downloadImageBytesFromUrlInternal(imageUrl);
        } catch (Exception e) {
            log.warn("Failed to download image bytes from {}: {}", imageUrl, e.getMessage());
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to download image bytes from " + imageUrl + ": " + e.getMessage(), e);
        }
    }

    private byte[] downloadImageBytesFromUrlInternal(String imageUrl) throws IOException {
        URI uri = URI.create(imageUrl);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Only HTTP and HTTPS protocols are allowed");
        }

        String host = uri.getHost();
        if (host == null) {
            throw new IOException("Invalid URL: no host found in " + imageUrl);
        }

        InetAddress[] inetAddresses = InetAddress.getAllByName(host);
        if (inetAddresses.length == 0) {
            throw new IOException("Could not resolve host: " + host);
        }
        for (InetAddress inetAddress : inetAddresses) {
            if (isInternalAddress(inetAddress)) {
                throw new SecurityException("URL points to a local or private internal network address: " + host + " (" + inetAddress.getHostAddress() + ")");
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "BookLore/1.0 (Book and Comic Metadata Fetcher; +https://github.com/booklore-app/booklore)");
        headers.set(HttpHeaders.ACCEPT, "image/*");

        HttpEntity<String> entity = new HttpEntity<>(headers);

        log.debug("Downloading image bytes from: {}", imageUrl);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                imageUrl,
                HttpMethod.GET,
                entity,
                byte[].class
        );

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return response.getBody();
        }

        throw new IOException("Failed to download image bytes. HTTP Status: " + response.getStatusCode());
    }

    private byte[] downloadImageFromUrlInternal(String imageUrl) throws IOException {
        String currentUrl = imageUrl;
        int redirectCount = 0;

        while (redirectCount <= MAX_REDIRECTS) {
            URI uri = URI.create(currentUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Only HTTP and HTTPS protocols are allowed");
            }

            String host = uri.getHost();
            if (host == null) {
                throw new IOException("Invalid URL: no host found in " + currentUrl);
            }

            // Validate resolved IPs to block SSRF against internal networks
            InetAddress[] inetAddresses = InetAddress.getAllByName(host);
            if (inetAddresses.length == 0) {
                throw new IOException("Could not resolve host: " + host);
            }
            for (InetAddress inetAddress : inetAddresses) {
                if (isInternalAddress(inetAddress)) {
                    throw new SecurityException("URL points to a local or private internal network address: " + host + " (" + inetAddress.getHostAddress() + ")");
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "BookLore/1.0 (Book and Comic Metadata Fetcher; +https://github.com/booklore-app/booklore)");
            headers.set(HttpHeaders.ACCEPT, "image/*");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.debug("Downloading image from: {}", currentUrl);

            ResponseEntity<byte[]> response = noRedirectRestTemplate.exchange(
                    currentUrl,
                    HttpMethod.GET,
                    entity,
                    byte[].class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else if (response.getStatusCode().is3xxRedirection()) {
                String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
                if (location == null) {
                    throw new IOException("Redirection response without Location header");
                }
                URI redirectUri = uri.resolve(location);

                // When a CDN redirects to a raw IP (e.g. CloudFront -> 3.168.64.124),
                // the Host header would become the bare IP, which the CDN rejects with
                // 400. Rewrite the URL to keep the previous hostname so the JDK
                // HttpClient sets the correct Host header automatically.
                if (isRawIpAddress(redirectUri.getHost())) {
                    try {
                        redirectUri = new URI(
                                redirectUri.getScheme(),
                                redirectUri.getUserInfo(),
                                host,
                                redirectUri.getPort(),
                                redirectUri.getPath(),
                                redirectUri.getQuery(),
                                redirectUri.getFragment()
                        );
                    } catch (URISyntaxException e) {
                        throw new IOException("Invalid redirect URI: " + e.getMessage(), e);
                    }
                }

                currentUrl = redirectUri.toString();
                redirectCount++;
            } else {
                throw new IOException("Failed to download image. HTTP Status: " + response.getStatusCode());
            }
        }

        throw new IOException("Too many redirects (max " + MAX_REDIRECTS + ")");
    }

    private boolean isRawIpAddress(String host) {
        if (host == null) {
            return false;
        }
        // IPv6 in URI brackets
        if (host.startsWith("[")) {
            return true;
        }
        // IPv4: all segments are digits
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            for (String part : parts) {
                if (!part.chars().allMatch(Character::isDigit)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() ||
            address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }

        byte[] addr = address.getAddress();
        // Check for IPv6 Unique Local Address (fc00::/7)
        if (addr.length == 16) {
            if ((addr[0] & 0xFE) == (byte) 0xFC) {
                return true;
            }
        }

        // Handle IPv4-mapped IPv6 addresses (::ffff:127.0.0.1)
        if (isIpv4MappedAddress(addr)) {
            try {
                byte[] ipv4Bytes = new byte[4];
                System.arraycopy(addr, 12, ipv4Bytes, 0, 4);
                InetAddress ipv4Addr = InetAddress.getByAddress(ipv4Bytes);
                return isInternalAddress(ipv4Addr);
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        }

        return false;
    }

    private boolean isIpv4MappedAddress(byte[] addr) {
        if (addr.length != 16) return false;
        for (int i = 0; i < 10; i++) {
            if (addr[i] != 0) return false;
        }
        return (addr[10] == (byte) 0xFF) && (addr[11] == (byte) 0xFF);
    }

    // ========================================
    // COVER OPERATIONS
    // ========================================

    public void createThumbnailFromFile(long bookId, MultipartFile file) {
        try {
            validateCoverFile(file);
            boolean success = saveCoverImages(file.getInputStream(), bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException("Failed to save cover images");
            }
            log.info("Cover images created and saved for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating the thumbnail: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public void createThumbnailFromBytes(long bookId, byte[] imageBytes) {
        try {
            validateImageData(imageBytes, vipsImageService);
            boolean success = saveCoverImages(imageBytes, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException("Failed to save cover images");
            }
            log.info("Cover images created and saved from bytes for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating thumbnail from bytes: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public void createThumbnailFromUrl(long bookId, String imageUrl) {
        try {
            byte[] imageBytes = downloadImageFromUrl(imageUrl);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("Skipping thumbnail creation for book {}: download failed", bookId);
                return;
            }
            boolean success = saveCoverImages(imageBytes, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException("Failed to save cover images");
            }
            log.info("Cover images created and saved from URL for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating thumbnail from URL: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    // ========================================
    // AUTHOR PHOTO OPERATIONS
    // ========================================

    public void createAuthorThumbnailFromUrl(long authorId, String imageUrl) {
        try {
            byte[] imageBytes = downloadImageFromUrl(imageUrl);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("Skipping author thumbnail creation for author {}: download failed", authorId);
                return;
            }
            boolean success = saveAuthorImages(imageBytes, authorId);
            if (!success) {
                log.warn("Failed to save author images for author ID: {}", authorId);
            }
            log.info("Author images created and saved from URL for author ID: {}", authorId);
        } catch (Exception e) {
            log.warn("Failed to create author thumbnail from URL for author {}: {}", authorId, e.getMessage());
        }
    }

    public boolean saveAuthorImages(byte[] imageData, long authorId) throws IOException {
        String folderPath = getAuthorImagesFolder(authorId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        Path photoFile = Path.of(folderPath, AUTHOR_PHOTO_FILENAME);
        Path thumbnailFile = Path.of(folderPath, AUTHOR_THUMBNAIL_FILENAME);

        // Flatten + resize + save photo
        vipsImageService.flattenResizeAndSave(imageData, photoFile, MAX_ORIGINAL_WIDTH, MAX_ORIGINAL_HEIGHT);

        // Compute aspect-ratio crop for thumbnail from saved photo
        ImageDimensions photoDims = vipsImageService.readDimensionsFromFile(photoFile);
        double targetRatio = (double) THUMBNAIL_WIDTH / THUMBNAIL_HEIGHT;
        double sourceRatio = (double) photoDims.width() / photoDims.height();
        int cropWidth, cropHeight, cropX, cropY;
        if (sourceRatio > targetRatio) {
            cropHeight = photoDims.height();
            cropWidth = (int) (cropHeight * targetRatio);
            cropX = (photoDims.width() - cropWidth) / 2;
            cropY = 0;
        } else {
            cropWidth = photoDims.width();
            cropHeight = (int) (cropWidth / targetRatio);
            cropX = 0;
            cropY = (photoDims.height() - cropHeight) / 2;
        }

        // Crop + resize → thumbnail
        vipsImageService.cropResizeAndSave(photoFile, thumbnailFile,
                cropX, cropY, cropWidth, cropHeight, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);

        return true;
    }

    public boolean saveAuthorImages(InputStream imageStream, long authorId) throws IOException {
        String folderPath = getAuthorImagesFolder(authorId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        Path photoFile = Path.of(folderPath, AUTHOR_PHOTO_FILENAME);
        Path thumbnailFile = Path.of(folderPath, AUTHOR_THUMBNAIL_FILENAME);

        try (InputStream in = imageStream; var photoOut = Files.newOutputStream(photoFile)) {
            vipsImageService.processStreamToJpeg(in, photoOut, MAX_ORIGINAL_WIDTH, MAX_ORIGINAL_HEIGHT);
        }

        ImageDimensions photoDims = vipsImageService.readDimensionsFromFile(photoFile);
        double targetRatio = (double) THUMBNAIL_WIDTH / THUMBNAIL_HEIGHT;
        double sourceRatio = (double) photoDims.width() / photoDims.height();
        int cropWidth, cropHeight, cropX, cropY;
        if (sourceRatio > targetRatio) {
            cropHeight = photoDims.height();
            cropWidth = (int) (cropHeight * targetRatio);
            cropX = (photoDims.width() - cropWidth) / 2;
            cropY = 0;
        } else {
            cropWidth = photoDims.width();
            cropHeight = (int) (cropWidth / targetRatio);
            cropX = 0;
            cropY = (photoDims.height() - cropHeight) / 2;
        }

        vipsImageService.cropResizeAndSave(photoFile, thumbnailFile,
                cropX, cropY, cropWidth, cropHeight, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);

        return true;
    }

    /**
     * Bridge for callers that still have a BufferedImage (e.g. from pdfium4j).
     */
    public boolean saveAuthorImages(BufferedImage sourceImage, long authorId) throws IOException {
        byte[] jpegBytes = vipsImageService.bufferedImageToJpeg(sourceImage, 95);
        return saveAuthorImages(jpegBytes, authorId);
    }

    public void deleteAuthorImages(long authorId) {
        String authorImageFolder = getAuthorImagesFolder(authorId);
        Path folderPath = Paths.get(authorImageFolder);
        try {
            if (Files.exists(folderPath) && Files.isDirectory(folderPath)) {
                try (Stream<Path> walk = Files.walk(folderPath)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    log.error("Failed to delete file: {} - {}", path, e.getMessage());
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.error("Error deleting author images for author {}: {}", authorId, e.getMessage());
        }
    }

    // ========================================
    // AUDIOBOOK COVER OPERATIONS
    // ========================================

    public void createAudiobookThumbnailFromFile(long bookId, MultipartFile file) {
        try {
            validateCoverFile(file);
            boolean success = saveAudiobookCoverImages(file.getInputStream(), bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException("Failed to save audiobook cover images");
            }
            log.info("Audiobook cover images created and saved for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating the audiobook thumbnail: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public void createAudiobookThumbnailFromBytes(long bookId, byte[] imageBytes) {
        try {
            if (!vipsImageService.canDecode(imageBytes)) {
                log.warn("Skipping audiobook thumbnail creation for book {}: image decode failed", bookId);
                return;
            }
            boolean success = saveAudiobookCoverImages(imageBytes, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException("Failed to save audiobook cover images");
            }
            log.info("Audiobook cover images created and saved from bytes for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating audiobook thumbnail from bytes: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public void createAudiobookThumbnailFromUrl(long bookId, String imageUrl) {
        try {
            byte[] imageBytes = downloadImageFromUrl(imageUrl);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("Skipping audiobook thumbnail creation for book {}: download failed", bookId);
                return;
            }
            boolean success = saveAudiobookCoverImages(imageBytes, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException("Failed to save audiobook cover images");
            }
            log.info("Audiobook cover images created and saved from URL for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating audiobook thumbnail from URL: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public boolean saveAudiobookCoverImages(byte[] imageData, long bookId) throws IOException {
        String folderPath = getImagesFolder(bookId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        Path coverFile = Path.of(folderPath, AUDIOBOOK_COVER_FILENAME);
        Path thumbnailFile = Path.of(folderPath, AUDIOBOOK_THUMBNAIL_FILENAME);

        // Read dimensions to compute center-square crop
        ImageDimensions dims = vipsImageService.readDimensions(imageData);
        int size = Math.min(dims.width(), dims.height());
        int cropX = (dims.width() - size) / 2;
        int cropY = (dims.height() - size) / 2;

        int coverSize = Math.min(size, MAX_SQUARE_SIZE);

        // Flatten + center-square crop + resize → audiobook cover
        vipsImageService.flattenCropResizeAndSave(imageData, coverFile,
                cropX, cropY, size, size, coverSize, coverSize);

        // Square thumbnail from saved cover
        vipsImageService.cropResizeAndSave(coverFile, thumbnailFile,
                0, 0, coverSize, coverSize, SQUARE_THUMBNAIL_SIZE, SQUARE_THUMBNAIL_SIZE);

        return true;
    }

    public boolean saveAudiobookCoverImages(InputStream imageStream, long bookId) throws IOException {
        String folderPath = getImagesFolder(bookId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        Path coverFile = Path.of(folderPath, AUDIOBOOK_COVER_FILENAME);
        Path thumbnailFile = Path.of(folderPath, AUDIOBOOK_THUMBNAIL_FILENAME);

        // Stream decode+resize into cover file first, then do file-based crop/thumbnail work.
        try (InputStream in = imageStream; var coverOut = Files.newOutputStream(coverFile)) {
            vipsImageService.processStreamToJpeg(in, coverOut, MAX_SQUARE_SIZE, MAX_SQUARE_SIZE);
        }

        ImageDimensions dims = vipsImageService.readDimensionsFromFile(coverFile);
        int size = Math.min(dims.width(), dims.height());
        int cropX = (dims.width() - size) / 2;
        int cropY = (dims.height() - size) / 2;
        Path squareCoverFile = Path.of(folderPath, "audiobook-cover-square.jpg");
        vipsImageService.cropResizeAndSave(coverFile, squareCoverFile, cropX, cropY, size, size, size, size);
        Files.move(squareCoverFile, coverFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        vipsImageService.cropResizeAndSave(coverFile, thumbnailFile,
                0, 0, size, size, SQUARE_THUMBNAIL_SIZE, SQUARE_THUMBNAIL_SIZE);
        return true;
    }

    /**
     * Bridge for callers that still have a BufferedImage (e.g. from pdfium4j).
     */
    public boolean saveAudiobookCoverImages(BufferedImage coverImage, long bookId) throws IOException {
        byte[] jpegBytes = vipsImageService.bufferedImageToJpeg(coverImage, 95);
        return saveAudiobookCoverImages(jpegBytes, bookId);
    }

    public boolean saveCoverImages(byte[] imageData, long bookId) throws IOException {
        String folderPath = getImagesFolder(bookId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        Path coverFile = Path.of(folderPath, COVER_FILENAME);
        Path thumbnailFile = Path.of(folderPath, THUMBNAIL_FILENAME);

        // Read dimensions and compute optional crop region
        ImageDimensions dims = vipsImageService.readDimensions(imageData);
        int[] crop = computeCoverCrop(imageData, dims);

        if (crop != null) {
            vipsImageService.flattenCropResizeAndSave(imageData, coverFile,
                    crop[0], crop[1], crop[2], crop[3], MAX_ORIGINAL_WIDTH, MAX_ORIGINAL_HEIGHT);
        } else {
            vipsImageService.flattenResizeAndSave(imageData, coverFile, MAX_ORIGINAL_WIDTH, MAX_ORIGINAL_HEIGHT);
        }

        // Determine thumbnail dimensions based on saved cover aspect ratio
        ImageDimensions coverDims = vipsImageService.readDimensionsFromFile(coverFile);
        int thumbWidth, thumbHeight;
        double aspectRatio = (double) coverDims.width() / coverDims.height();
        if (aspectRatio >= 0.85 && aspectRatio <= 1.15) {
            thumbWidth = THUMBNAIL_WIDTH;
            thumbHeight = THUMBNAIL_WIDTH;
        } else {
            thumbWidth = THUMBNAIL_WIDTH;
            thumbHeight = THUMBNAIL_HEIGHT;
        }

        // Thumbnail from saved cover (file-to-file, avoids re-reading into memory)
        vipsImageService.flattenThumbnailAndSave(coverFile, thumbnailFile, thumbWidth, thumbHeight);

        return true;
    }

    public boolean saveCoverImages(InputStream imageStream, long bookId) throws IOException {
        String folderPath = getImagesFolder(bookId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        Path coverFile = Path.of(folderPath, COVER_FILENAME);
        Path thumbnailFile = Path.of(folderPath, THUMBNAIL_FILENAME);

        try (InputStream in = imageStream; var coverOut = Files.newOutputStream(coverFile)) {
            vipsImageService.processStreamToJpeg(in, coverOut, MAX_ORIGINAL_WIDTH, MAX_ORIGINAL_HEIGHT);
        }

        ImageDimensions dims = vipsImageService.readDimensionsFromFile(coverFile);
        int[] crop = computeCoverCrop(coverFile, dims);
        if (crop != null) {
            Path croppedCoverFile = Path.of(folderPath, "cover-cropped.jpg");
            vipsImageService.flattenCropResizeAndSave(coverFile, croppedCoverFile,
                    crop[0], crop[1], crop[2], crop[3], MAX_ORIGINAL_WIDTH, MAX_ORIGINAL_HEIGHT);
            Files.move(croppedCoverFile, coverFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        ImageDimensions coverDims = vipsImageService.readDimensionsFromFile(coverFile);
        int thumbWidth, thumbHeight;
        double aspectRatio = (double) coverDims.width() / coverDims.height();
        if (aspectRatio >= 0.85 && aspectRatio <= 1.15) {
            thumbWidth = THUMBNAIL_WIDTH;
            thumbHeight = THUMBNAIL_WIDTH;
        } else {
            thumbWidth = THUMBNAIL_WIDTH;
            thumbHeight = THUMBNAIL_HEIGHT;
        }
        vipsImageService.flattenThumbnailAndSave(coverFile, thumbnailFile, thumbWidth, thumbHeight);
        return true;
    }

    /**
     * Bridge for callers that still have a BufferedImage (e.g. from pdfium4j).
     */
    public boolean saveCoverImages(BufferedImage coverImage, long bookId) throws IOException {
        byte[] jpegBytes = vipsImageService.bufferedImageToJpeg(coverImage, 95);
        return saveCoverImages(jpegBytes, bookId);
    }

    /**
     * Compute crop region based on cover cropping settings, using vips findTrim for smart crop.
     * Returns [left, top, width, height] or null if no cropping needed.
     */
    private int[] computeCoverCrop(byte[] imageData, ImageDimensions dims) throws IOException {
        CoverCroppingSettings settings = appSettingService.getAppSettings().getCoverCroppingSettings();
        if (settings == null) {
            return null;
        }

        int width = dims.width();
        int height = dims.height();
        double heightToWidthRatio = (double) height / width;
        double widthToHeightRatio = (double) width / height;
        double threshold = settings.getAspectRatioThreshold();
        boolean smartCrop = settings.isSmartCroppingEnabled();

        boolean isExtremelyTall = settings.isVerticalCroppingEnabled() && heightToWidthRatio > threshold;
        if (isExtremelyTall) {
            int croppedHeight = (int) (width * TARGET_COVER_ASPECT_RATIO);
            int startY = 0;
            if (smartCrop) {
                TrimBounds bounds = vipsImageService.findContentBounds(imageData);
                int margin = (int) (croppedHeight * SMART_CROP_MARGIN_PERCENT);
                startY = Math.max(0, bounds.top() - margin);
                startY = Math.min(startY, height - croppedHeight);
            }
            log.debug("Cropping tall image: {}x{} (ratio {}) -> {}x{}, smartCrop={}",
                    width, height, String.format("%.2f", heightToWidthRatio), width, croppedHeight, smartCrop);
            return new int[]{0, startY, width, croppedHeight};
        }

        boolean isExtremelyWide = settings.isHorizontalCroppingEnabled() && widthToHeightRatio > threshold;
        if (isExtremelyWide) {
            int croppedWidth = (int) (height / TARGET_COVER_ASPECT_RATIO);
            int startX = 0;
            if (smartCrop) {
                TrimBounds bounds = vipsImageService.findContentBounds(imageData);
                int margin = (int) (croppedWidth * SMART_CROP_MARGIN_PERCENT);
                startX = Math.max(0, bounds.left() - margin);
                startX = Math.min(startX, width - croppedWidth);
            }
            log.debug("Cropping wide image: {}x{} (ratio {}) -> {}x{}, smartCrop={}",
                    width, height, String.format("%.2f", widthToHeightRatio), croppedWidth, height, smartCrop);
            return new int[]{startX, 0, croppedWidth, height};
        }

        return null;
    }

    private int[] computeCoverCrop(Path imagePath, ImageDimensions dims) throws IOException {
        CoverCroppingSettings settings = appSettingService.getAppSettings().getCoverCroppingSettings();
        if (settings == null) {
            return null;
        }

        int width = dims.width();
        int height = dims.height();
        double heightToWidthRatio = (double) height / width;
        double widthToHeightRatio = (double) width / height;
        double threshold = settings.getAspectRatioThreshold();
        boolean smartCrop = settings.isSmartCroppingEnabled();

        boolean isExtremelyTall = settings.isVerticalCroppingEnabled() && heightToWidthRatio > threshold;
        if (isExtremelyTall) {
            int croppedHeight = (int) (width * TARGET_COVER_ASPECT_RATIO);
            int startY = 0;
            if (smartCrop) {
                TrimBounds bounds = vipsImageService.findContentBounds(imagePath);
                int margin = (int) (croppedHeight * SMART_CROP_MARGIN_PERCENT);
                startY = Math.max(0, bounds.top() - margin);
                startY = Math.min(startY, height - croppedHeight);
            }
            return new int[]{0, startY, width, croppedHeight};
        }

        boolean isExtremelyWide = settings.isHorizontalCroppingEnabled() && widthToHeightRatio > threshold;
        if (isExtremelyWide) {
            int croppedWidth = (int) (height / TARGET_COVER_ASPECT_RATIO);
            int startX = 0;
            if (smartCrop) {
                TrimBounds bounds = vipsImageService.findContentBounds(imagePath);
                int margin = (int) (croppedWidth * SMART_CROP_MARGIN_PERCENT);
                startX = Math.max(0, bounds.left() - margin);
                startX = Math.min(startX, width - croppedWidth);
            }
            return new int[]{startX, 0, croppedWidth, height};
        }

        return null;
    }

    public static void setBookCoverPath(BookMetadataEntity bookMetadataEntity) {
        bookMetadataEntity.setCoverUpdatedOn(Instant.now());
    }

    public void deleteBookCovers(Set<Long> bookIds) {
        for (Long bookId : bookIds) {
            String bookCoverFolder = getImagesFolder(bookId);
            Path folderPath = Paths.get(bookCoverFolder);
            try {
                if (Files.exists(folderPath) && Files.isDirectory(folderPath)) {
                    try (Stream<Path> walk = Files.walk(folderPath)) {
                        walk.sorted(Comparator.reverseOrder())
                                .forEach(path -> {
                                    try {
                                        Files.delete(path);
                                    } catch (IOException e) {
                                        log.error("Failed to delete file: {} - {}", path, e.getMessage());
                                    }
                                });
                    }
                }
            } catch (IOException e) {
                log.error("Error processing folder: {} - {}", folderPath, e.getMessage());
            }
        }
        log.info("Deleted {} book covers", bookIds.size());
    }

    public String getIconsSvgFolder() {
        return Paths.get(appProperties.getPathConfig(), ICONS_DIR, SVG_DIR).toString();
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    public static String truncate(String input, int maxLength) {
        if (input == null) return null;
        if (maxLength <= 0) return "";
        return input.length() <= maxLength ? input : input.substring(0, maxLength);
    }

    public void clearCacheDirectory(String cachePath) {
        Path path = Paths.get(cachePath);
        if (Files.exists(path) && Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                log.error("Failed to delete file in cache: {} - {}", p, e.getMessage());
                            }
                        });
                // Recreate the directory after deletion
                Files.createDirectories(path);
            } catch (IOException e) {
                log.error("Failed to clear cache directory: {} - {}", cachePath, e.getMessage());
            }
        }
    }
}
