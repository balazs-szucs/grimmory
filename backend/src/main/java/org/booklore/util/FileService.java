package org.booklore.util;

import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.service.appsettings.AppSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.grimmory.pdfium4j.PdfPage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.net.UnknownHostException;

@Slf4j
@RequiredArgsConstructor
@Service
public class FileService {

    private final AppProperties appProperties;
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

    private long getMaxFileUploadSizeMb() {
        AppSettings appSettings = this.appSettingService.getAppSettings();

        Integer maxFileUploadSizeMb = appSettings.getMaxFileUploadSizeInMb();

        if (maxFileUploadSizeMb == null) {
            log.warn("Max File Upload Size is unset, cannot continue");
            throw ApiError.INTERNAL_SERVER_ERROR.createException("Max File Upload Size is Unset");
        }

        return maxFileUploadSizeMb.longValue();
    }

    private void validateCoverFile(MultipartFile file) {
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
        long maxSizeMb = getMaxFileUploadSizeMb();
        long maxFileSize = maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxFileSize) {
            throw ApiError.FILE_TOO_LARGE.createException(maxSizeMb);
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
        vipsImageService.flattenResizeAndSave(imageData, outputFile.toPath(), MAX_ORIGINAL_WIDTH, MAX_ORIGINAL_HEIGHT);
        log.info("Image saved successfully to: {}", filePath);
    }

    public void saveImage(InputStream inputStream, String filePath) throws IOException {
        if (inputStream == null) {
            log.warn("Skipping saveImage for {}: input stream is null", filePath);
            return;
        }
        File outputFile = new File(filePath);
        File parentDir = outputFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + parentDir);
        }
        try (var out = Files.newOutputStream(outputFile.toPath())) {
            vipsImageService.processStreamToJpeg(inputStream, out,
                    MAX_ORIGINAL_WIDTH, MAX_ORIGINAL_HEIGHT);
        }
        log.info("Image saved successfully to: {}", filePath);
    }

    public byte[] downloadImageFromUrl(String imageUrl) throws IOException {
        Path downloadedImage = null;
        try {
            downloadedImage = downloadImageToTempFile(imageUrl);
            return Files.readAllBytes(downloadedImage);
        } catch (IOException e) {
            log.warn("Failed to download image from {}: {}", imageUrl, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("Failed to download image from {}: {}", imageUrl, e.getMessage());
            throw new IOException("Failed to download image from " + imageUrl + ": " + e.getMessage(), e);
        } finally {
            deleteTempFileQuietly(downloadedImage);
        }
    }

    public Path downloadImageToTempFile(String imageUrl) throws IOException {
        Path tempFile = Files.createTempFile("booklore-remote-image-", ".img");
        boolean completed = false;
        try {
            downloadImageToPath(imageUrl, tempFile);
            completed = true;
            return tempFile;
        } finally {
            if (!completed) {
                deleteTempFileQuietly(tempFile);
            }
        }
    }

    private void downloadImageToPath(String imageUrl, Path targetPath) throws IOException {
        String currentUrl = imageUrl;
        int redirectCount = 0;

        while (redirectCount <= MAX_REDIRECTS) {
            URI uri = validateRemoteImageUri(currentUrl);
            String host = uri.getHost();
            validateRemoteImageHost(host);

            log.debug("Downloading image from: {}", currentUrl);

            String redirectLocation = noRedirectRestTemplate.execute(
                    currentUrl,
                    HttpMethod.GET,
                    request -> request.getHeaders().putAll(createImageDownloadHeaders()),
                    response -> handleImageDownloadResponse(response, uri, host, targetPath)
            );

            if (redirectLocation == null) {
                return;
            }

            currentUrl = redirectLocation;
            redirectCount++;
        }

        throw new IOException("Too many redirects (max " + MAX_REDIRECTS + ")");
    }

    private URI validateRemoteImageUri(String imageUrl) throws IOException {
        URI uri = URI.create(imageUrl);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Only HTTP and HTTPS protocols are allowed");
        }
        if (uri.getHost() == null) {
            throw new IOException("Invalid URL: no host found in " + imageUrl);
        }
        return uri;
    }

    private void validateRemoteImageHost(String host) throws IOException {
        InetAddress[] inetAddresses = InetAddress.getAllByName(host);
        if (inetAddresses.length == 0) {
            throw new IOException("Could not resolve host: " + host);
        }
        for (InetAddress inetAddress : inetAddresses) {
            if (isInternalAddress(inetAddress)) {
                throw new SecurityException("URL points to a local or private internal network address: " + host + " (" + inetAddress.getHostAddress() + ")");
            }
        }
    }

    private HttpHeaders createImageDownloadHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "BookLore/1.0 (Book and Comic Metadata Fetcher; +https://github.com/booklore-app/booklore)");
        headers.set(HttpHeaders.ACCEPT, "image/*");
        return headers;
    }

    private String handleImageDownloadResponse(org.springframework.http.client.ClientHttpResponse response,
                                               URI requestUri,
                                               String originalHost,
                                               Path targetPath) throws IOException {
        if (response.getStatusCode().is2xxSuccessful()) {
            try (InputStream body = response.getBody(); var output = Files.newOutputStream(targetPath)) {
                if (body == null) {
                    throw new IOException("Image download returned an empty response body");
                }
                transferWithLimit(body, output, getRemoteImageLimitBytes());
            }
            return null;
        }

        if (response.getStatusCode().is3xxRedirection()) {
            String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (location == null) {
                throw new IOException("Redirection response without Location header");
            }
            URI redirectUri = requestUri.resolve(location);

            if (isRawIpAddress(redirectUri.getHost())) {
                try {
                    redirectUri = new URI(
                            redirectUri.getScheme(),
                            redirectUri.getUserInfo(),
                            originalHost,
                            redirectUri.getPort(),
                            redirectUri.getPath(),
                            redirectUri.getQuery(),
                            redirectUri.getFragment()
                    );
                } catch (URISyntaxException e) {
                    throw new IOException("Invalid redirect URI: " + e.getMessage(), e);
                }
            }

            return redirectUri.toString();
        }

        throw new IOException("Failed to download image. HTTP Status: " + response.getStatusCode());
    }

    private long getRemoteImageLimitBytes() {
        try {
            return getMaxFileUploadSizeMb() * 1024 * 1024;
        } catch (RuntimeException e) {
            log.warn("Falling back to default remote image limit {} bytes: {}", MAX_FILE_SIZE_BYTES, e.getMessage());
            return MAX_FILE_SIZE_BYTES;
        }
    }

    private void transferWithLimit(InputStream inputStream, OutputStream outputStream, long maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Image exceeds maximum size of " + maxBytes + " bytes");
            }
            outputStream.write(buffer, 0, read);
        }
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
            } catch (UnknownHostException e) {
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
        Path downloadedImage = null;
        try {
            downloadedImage = downloadImageToTempFile(imageUrl);
            createThumbnailFromPath(bookId, downloadedImage);
            log.info("Cover images created and saved from URL for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating thumbnail from URL: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        } finally {
            deleteTempFileQuietly(downloadedImage);
        }
    }

    public void createThumbnailFromPath(long bookId, Path imagePath) {
        try {
            boolean success = saveCoverImages(imagePath, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException("Failed to save cover images");
            }
            log.info("Cover images created and saved from path for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating thumbnail from path: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    // ========================================
    // AUTHOR PHOTO OPERATIONS
    // ========================================

    public void createAuthorThumbnailFromUrl(long authorId, String imageUrl) {
        Path downloadedImage = null;
        try {
            downloadedImage = downloadImageToTempFile(imageUrl);
            boolean success = saveAuthorImages(downloadedImage, authorId);
            if (!success) {
                log.warn("Failed to save author images for author ID: {}", authorId);
            }
            log.info("Author images created and saved from URL for author ID: {}", authorId);
        } catch (Exception e) {
            log.warn("Failed to create author thumbnail from URL for author {}: {}", authorId, e.getMessage());
        } finally {
            deleteTempFileQuietly(downloadedImage);
        }
    }

    public boolean saveAuthorImages(Path imagePath, long authorId) throws IOException {
        String folderPath = getAuthorImagesFolder(authorId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        Path photoFile = Path.of(folderPath, AUTHOR_PHOTO_FILENAME);
        Path thumbnailFile = Path.of(folderPath, AUTHOR_THUMBNAIL_FILENAME);

        vipsImageService.processPhotoUnified(
                imagePath,
                photoFile,
                thumbnailFile,
                MAX_ORIGINAL_WIDTH,
                MAX_ORIGINAL_HEIGHT,
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT
        );

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

        vipsImageService.processPhotoUnified(
                imageStream,
                photoFile,
                thumbnailFile,
                MAX_ORIGINAL_WIDTH,
                MAX_ORIGINAL_HEIGHT,
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT
        );

        return true;
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
        Path downloadedImage = null;
        try {
            downloadedImage = downloadImageToTempFile(imageUrl);
            createAudiobookThumbnailFromPath(bookId, downloadedImage);
            log.info("Audiobook cover images created and saved from URL for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating audiobook thumbnail from URL: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        } finally {
            deleteTempFileQuietly(downloadedImage);
        }
    }

    public void createAudiobookThumbnailFromPath(long bookId, Path imagePath) {
        try {
            boolean success = saveAudiobookCoverImages(imagePath, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException("Failed to save audiobook cover images");
            }
            log.info("Audiobook cover images created and saved from path for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating audiobook thumbnail from path: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public boolean saveAudiobookCoverImages(byte[] imageData, long bookId) throws IOException {
        if (imageData == null || imageData.length == 0) {
            return false;
        }
        try (InputStream is = new java.io.ByteArrayInputStream(imageData)) {
            return saveAudiobookCoverImages(is, bookId);
        }
    }

    public boolean saveAudiobookCoverImages(Path imagePath, long bookId) throws IOException {
        String folderPath = getImagesFolder(bookId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        Path coverFile = Path.of(folderPath, AUDIOBOOK_COVER_FILENAME);
        Path thumbnailFile = Path.of(folderPath, AUDIOBOOK_THUMBNAIL_FILENAME);

        vipsImageService.processAudiobookCoverUnified(
                imagePath,
                coverFile,
                thumbnailFile,
                MAX_SQUARE_SIZE,
                SQUARE_THUMBNAIL_SIZE
        );

        return true;
    }

    public boolean saveAudiobookCoverImages(InputStream imageStream, long bookId) throws IOException {
        Path tempImage = Files.createTempFile("booklore-audiobook-cover-", ".img");
        try (InputStream in = imageStream; var tempOut = Files.newOutputStream(tempImage)) {
            transferWithLimit(in, tempOut, MAX_FILE_SIZE_BYTES);
            return saveAudiobookCoverImages(tempImage, bookId);
        } finally {
            deleteTempFileQuietly(tempImage);
        }
    }

    public boolean saveCoverImages(byte[] imageData, long bookId) throws IOException {
        if (imageData == null || imageData.length == 0) {
            return false;
        }
        try (InputStream is = new java.io.ByteArrayInputStream(imageData)) {
            return saveCoverImages(is, bookId);
        }
    }

    public boolean saveCoverImages(Path imagePath, long bookId) throws IOException {
        String folderPath = getImagesFolder(bookId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        Path coverFile = Path.of(folderPath, COVER_FILENAME);
        Path thumbnailFile = Path.of(folderPath, THUMBNAIL_FILENAME);

        CoverCroppingSettings settings = appSettingService.getAppSettings().getCoverCroppingSettings();
        boolean verticalCrop = settings != null && settings.isVerticalCroppingEnabled();
        boolean horizontalCrop = settings != null && settings.isHorizontalCroppingEnabled();
        double threshold = settings != null ? settings.getAspectRatioThreshold() : 1.5;
        boolean smartCrop = settings != null && settings.isSmartCroppingEnabled();

        vipsImageService.processCoverUnified(
                imagePath,
                coverFile,
                thumbnailFile,
                MAX_ORIGINAL_WIDTH,
                MAX_ORIGINAL_HEIGHT,
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT,
                verticalCrop,
                horizontalCrop,
                threshold,
                smartCrop,
                TARGET_COVER_ASPECT_RATIO,
                SMART_CROP_MARGIN_PERCENT
        );

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

        CoverCroppingSettings settings = appSettingService.getAppSettings().getCoverCroppingSettings();
        boolean verticalCrop = settings != null && settings.isVerticalCroppingEnabled();
        boolean horizontalCrop = settings != null && settings.isHorizontalCroppingEnabled();
        double threshold = settings != null ? settings.getAspectRatioThreshold() : 1.5;
        boolean smartCrop = settings != null && settings.isSmartCroppingEnabled();

        vipsImageService.processCoverUnified(
                imageStream,
                coverFile,
                thumbnailFile,
                MAX_ORIGINAL_WIDTH,
                MAX_ORIGINAL_HEIGHT,
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT,
                verticalCrop,
                horizontalCrop,
                threshold,
                smartCrop,
                TARGET_COVER_ASPECT_RATIO,
                SMART_CROP_MARGIN_PERCENT
        );

        return true;
    }

    public boolean savePdfCoverImages(long bookId, PdfPage page) throws IOException {
        String folderPath = getImagesFolder(bookId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        Path coverFile = Path.of(folderPath, COVER_FILENAME);
        Path thumbnailFile = Path.of(folderPath, THUMBNAIL_FILENAME);

        CoverCroppingSettings settings = appSettingService.getAppSettings().getCoverCroppingSettings();
        boolean verticalCrop = settings != null && settings.isVerticalCroppingEnabled();
        boolean horizontalCrop = settings != null && settings.isHorizontalCroppingEnabled();
        double threshold = settings != null ? settings.getAspectRatioThreshold() : 1.5;
        boolean smartCrop = settings != null && settings.isSmartCroppingEnabled();

        vipsImageService.processPdfCoverUnified(
                page,
                coverFile,
                thumbnailFile,
                MAX_ORIGINAL_WIDTH,
                MAX_ORIGINAL_HEIGHT,
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT,
                verticalCrop,
                horizontalCrop,
                threshold,
                smartCrop,
                TARGET_COVER_ASPECT_RATIO,
                SMART_CROP_MARGIN_PERCENT
        );

        return true;
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

    private void deleteTempFileQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temporary file {}: {}", path, e.getMessage());
        }
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
