package org.booklore.service.kobo;

import org.booklore.service.book.BookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URI;

/**
 * Kobo Thumbnail Service - Handles cover image requests from Kobo devices.
 * 
 * Stability improvements ported from Komga:
 * - ✅ CDN redirect for missing covers (NEW - supports purchased Kobo books)
 * - ✅ Multiple resolution support
 * - ✅ Graceful fallback on missing images
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KoboThumbnailService {

    // Kobo CDN URL template (ported from Komga)
    private static final String KOBO_CDN_URL = "https://cdn.kobo.com/book-images/{bookId}/{width}/{height}/false/image.jpg";

    private final BookService bookService;
    private final KoboSettingsService koboSettingsService;

    public ResponseEntity<Resource> getThumbnail(String coverHash) {
        return getThumbnailInternal(coverHash, null, null);
    }

    /**
     * Get book thumbnail with optional CDN redirect for missing covers.
     * Ported from Komga's KoboController thumbnail endpoint.
     * 
     * @param coverHash Book cover hash/ID
     * @param width Requested width (optional, for logging)
     * @param height Requested height (optional, for logging)
     * @return Image resource or redirect to Kobo CDN
     */
    public ResponseEntity<Resource> getThumbnail(String coverHash, Integer width, Integer height) {
        return getThumbnailInternal(coverHash, width, height);
    }

    private ResponseEntity<Resource> getThumbnailInternal(String coverHash, Integer width, Integer height) {
        Resource image = bookService.getBookCover(coverHash);
        
        if (!isValidImage(image)) {
            log.warn("Thumbnail not found for coverHash={}", coverHash);
            
            // NEW: Redirect to Kobo CDN if proxy is enabled (supports purchased books)
            // Validate coverHash to prevent URL injection attacks (SSRF/open redirect)
            if (koboSettingsService.isKoboProxyEnabled() && coverHash != null && coverHash.matches("[a-zA-Z0-9\\-]+")) {
                String cdnUrl = KOBO_CDN_URL
                        .replace("{bookId}", coverHash)
                        .replace("{width}", width != null ? width.toString() : "800")
                        .replace("{height}", height != null ? height.toString() : "800");
                
                log.debug("Redirecting to Kobo CDN for missing cover: {}", cdnUrl);
                return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                        .location(URI.create(cdnUrl))
                        .build();
            }
            
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                .body(image);
    }

    private boolean isValidImage(Resource image) {
        return image != null && image.exists();
    }
}
