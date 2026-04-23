package org.booklore.service.migration.migrations;

import org.booklore.config.AppProperties;
import org.booklore.service.migration.Migration;
import org.booklore.util.VipsImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

@Slf4j
@RequiredArgsConstructor
@Component
public class PopulateCoversAndResizeThumbnailsMigration implements Migration {

    private final AppProperties appProperties;
    private final VipsImageService vipsImageService;

    @Override
    public String getKey() {

        return "populateCoversAndResizeThumbnails";
    }

    @Override
    public String getDescription() {
        return "Copy thumbnails to images/{bookId}/cover.jpg and create resized 250x350 images as thumbnail.jpg";
    }

    @Override
    public void execute() {
        long start = System.nanoTime();
        log.info("Starting migration: {}", getKey());

        String dataFolder = appProperties.getPathConfig();
        Path thumbsDir = Paths.get(dataFolder, "thumbs");
        Path imagesDir = Paths.get(dataFolder, "images");

        try {
            if (Files.exists(thumbsDir)) {
                try (var stream = Files.walk(thumbsDir)) {
                    stream.filter(Files::isRegularFile)
                            .forEach(path -> {
                                try {
                                    byte[] imageData = Files.readAllBytes(path);
                                    if (!vipsImageService.canDecode(imageData)) {
                                        log.warn("Skipping non-image file: {}", path);
                                        return;
                                    }

                                    // Extract bookId from folder structure
                                    Path relative = thumbsDir.relativize(path);       // e.g., "11/f.jpg"
                                    String bookId = relative.getParent().toString();  // "11"

                                    Path bookDir = imagesDir.resolve(bookId);
                                    Files.createDirectories(bookDir);

                                    // Flatten + save as cover.jpg
                                    Path coverFile = bookDir.resolve("cover.jpg");
                                    vipsImageService.flattenResizeAndSave(imageData, coverFile, 1000, 1500);

                                    // Resize and save thumbnail.jpg
                                    Path thumbnailFile = bookDir.resolve("thumbnail.jpg");
                                    vipsImageService.flattenThumbnailAndSave(imageData, thumbnailFile, 250, 350);

                                    log.debug("Processed book {}: cover={} thumbnail={}", bookId, coverFile, thumbnailFile);
                                } catch (IOException e) {
                                    log.error("Error processing file {}", path, e);
                                    throw new UncheckedIOException(e);
                                }
                            });
                }

                // Delete old thumbs directory
                log.info("Deleting old thumbs directory: {}", thumbsDir);
                try (var stream = Files.walk(thumbsDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            }
        } catch (IOException e) {
            log.error("Error during migration {}", getKey(), e);
            throw new UncheckedIOException(e);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("Completed migration: {} in {} ms", getKey(), elapsedMs);
    }
}

