package org.booklore.controller;

import org.booklore.service.AuthorMetadataService;
import org.booklore.config.security.annotation.CheckBookAccess;
import org.booklore.service.book.BookService;
import org.booklore.service.bookdrop.BookDropService;
import org.booklore.service.reader.CbxReaderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@Tag(name = "Book Media", description = "Endpoints for retrieving book media such as covers, thumbnails, and pages")
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/media")
public class BookMediaController {

    private final BookService bookService;
    private final CbxReaderService cbxReaderService;
    private final BookDropService bookDropService;
    private final AuthorMetadataService authorMetadataService;

    @Operation(summary = "Get book thumbnail", description = "Retrieve the thumbnail image for a specific book.")
    @ApiResponse(responseCode = "200", description = "Book thumbnail returned successfully")
    @GetMapping("/book/{bookId}/thumbnail")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Resource> getBookThumbnail(@Parameter(description = "ID of the book") @PathVariable long bookId) {
        return ResponseEntity.ok(bookService.getBookThumbnail(bookId));
    }

    @Operation(summary = "Get book cover", description = "Retrieve the cover image for a specific book.")
    @ApiResponse(responseCode = "200", description = "Book cover returned successfully")
    @GetMapping("/book/{bookId}/cover")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Resource> getBookCover(@Parameter(description = "ID of the book") @PathVariable long bookId) {
        return ResponseEntity.ok(bookService.getBookCover(bookId));
    }

    @Operation(summary = "Get audiobook thumbnail", description = "Retrieve the audiobook thumbnail image for a specific book.")
    @ApiResponse(responseCode = "200", description = "Audiobook thumbnail returned successfully")
    @GetMapping("/book/{bookId}/audiobook-thumbnail")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Resource> getAudiobookThumbnail(@Parameter(description = "ID of the book") @PathVariable long bookId) {
        return ResponseEntity.ok(bookService.getAudiobookThumbnail(bookId));
    }

    @Operation(summary = "Get audiobook cover", description = "Retrieve the audiobook cover image for a specific book.")
    @ApiResponse(responseCode = "200", description = "Audiobook cover returned successfully")
    @GetMapping("/book/{bookId}/audiobook-cover")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Resource> getAudiobookCover(@Parameter(description = "ID of the book") @PathVariable long bookId) {
        return ResponseEntity.ok(bookService.getAudiobookCover(bookId));
    }

    @Operation(summary = "Get CBX page as image", description = "Retrieve a specific page from a CBX book as an image.")
    @ApiResponse(responseCode = "200", description = "CBX page image returned successfully")
    @GetMapping("/book/{bookId}/cbx/pages/{pageNumber}")
    @CheckBookAccess(bookIdParam = "bookId")
    public void getCbxPage(
            @Parameter(description = "ID of the book") @PathVariable Long bookId,
            @Parameter(description = "Page number to retrieve") @PathVariable int pageNumber,
            @Parameter(description = "Optional book type for alternative format (e.g., PDF, CBX)") @RequestParam(required = false) String bookType,
            @Parameter(description = "Optional output conversion format (jpeg,png)") @RequestParam(required = false) String convert,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        CbxReaderService.PageCacheInfo cacheInfo = cbxReaderService.getPageCacheInfo(bookId, bookType, pageNumber);
        response.setHeader(HttpHeaders.ETAG, cacheInfo.etag());
        response.setDateHeader(HttpHeaders.LAST_MODIFIED, cacheInfo.lastModified());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");

        String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        long ifModifiedSince = request.getDateHeader(HttpHeaders.IF_MODIFIED_SINCE);
        if (cacheInfo.etag().equals(ifNoneMatch) || (ifModifiedSince != -1 && cacheInfo.lastModified() <= ifModifiedSince)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        CbxReaderService.PageConvertFormat convertFormat = CbxReaderService.PageConvertFormat.fromValue(convert).orElse(null);
        if (convertFormat == CbxReaderService.PageConvertFormat.PNG) {
            response.setContentType(MediaType.IMAGE_PNG_VALUE);
        } else {
            response.setContentType(MediaType.IMAGE_JPEG_VALUE);
        }
        cbxReaderService.streamPageImage(bookId, bookType, pageNumber, response.getOutputStream(), convertFormat);
    }

    @Operation(summary = "Get author photo", description = "Retrieve the photo for a specific author.")
    @ApiResponse(responseCode = "200", description = "Author photo returned successfully")
    @GetMapping("/author/{authorId}/photo")
    public ResponseEntity<Resource> getAuthorPhoto(@Parameter(description = "ID of the author") @PathVariable long authorId) {
        Resource photo = authorMetadataService.getAuthorPhoto(authorId);
        if (photo == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(photo);
    }

    @Operation(summary = "Get author thumbnail", description = "Retrieve the thumbnail for a specific author.")
    @ApiResponse(responseCode = "200", description = "Author thumbnail returned successfully")
    @GetMapping("/author/{authorId}/thumbnail")
    public ResponseEntity<Resource> getAuthorThumbnail(@Parameter(description = "ID of the author") @PathVariable long authorId) {
        Resource thumbnail = authorMetadataService.getAuthorThumbnail(authorId);
        if (thumbnail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(thumbnail);
    }

    @Operation(summary = "Get bookdrop cover", description = "Retrieve the cover image for a specific bookdrop file.")
    @ApiResponse(responseCode = "200", description = "Bookdrop cover returned successfully")
    @GetMapping("/bookdrop/{bookdropId}/cover")
    public ResponseEntity<Resource> getBookdropCover(@Parameter(description = "ID of the bookdrop file") @PathVariable long bookdropId) {
        Resource file = bookDropService.getBookdropCover(bookdropId);
        String contentDisposition = "inline; filename=\"cover.jpg\"; filename*=UTF-8''cover.jpg";
        return (file != null)
                ? ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .contentType(MediaType.IMAGE_JPEG)
                .body(file)
                : ResponseEntity.noContent().build();
    }
}