package org.booklore.service.metadata.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.ComicMetadata;
import org.grimmory.comic4j.archive.ComicArchiveReader;
import org.grimmory.comic4j.domain.AgeRating;
import org.grimmory.comic4j.domain.ComicInfo;
import org.grimmory.comic4j.domain.ReadingDirection;
import org.grimmory.comic4j.domain.YesNo;
import org.grimmory.comic4j.util.StringSplitter;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CbxMetadataExtractor implements FileMetadataExtractor {

    private static final Pattern BOOKLORE_NOTE_PATTERN = Pattern.compile("\\[BookLore:([^\\]]+)\\]\\s*(.*)");
    private static final Pattern WEB_SPLIT_PATTERN = Pattern.compile("[,;\\s]+");

    private static final Pattern GOODREADS_URL_PATTERN = Pattern.compile("goodreads\\.com/book/show/(\\d+)(?:-[\\w-]+)?");
    private static final Pattern AMAZON_URL_PATTERN = Pattern.compile("amazon\\.com/dp/([A-Z0-9]{10})");
    private static final Pattern COMICVINE_URL_PATTERN = Pattern.compile("comicvine\\.gamespot\\.com/issue/(?:[^/]+/)?([\\w-]+)");
    private static final Pattern HARDCOVER_URL_PATTERN = Pattern.compile("hardcover\\.app/books/([\\w-]+)");

    @Override
    public BookMetadata extractMetadata(File file) {
        return extractMetadata(file.toPath());
    }

    public BookMetadata extractMetadata(Path path) {
        String baseName = FilenameUtils.getBaseName(path.toString());

        try {
            ComicInfo info = ComicArchiveReader.readComicInfo(path);
            if (info != null) {
                return mapComicInfoToMetadata(info, baseName);
            } else {
                log.warn("No ComicInfo.xml found in archive");
            }
        } catch (Exception e) {
            log.warn("Failed to extract metadata from CBX archive", e);
        }

        return BookMetadata.builder().title(baseName).build();
    }

    private BookMetadata mapComicInfoToMetadata(ComicInfo info, String fallbackTitle) {
        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder();

        String title = info.getTitle();
        builder.title(title == null || title.isBlank() ? fallbackTitle : title);

        builder.description(coalesce(info.getSummary(), null));
        builder.publisher(info.getPublisher());

        builder.seriesName(info.getSeries());
        builder.seriesNumber(parseFloat(info.getNumber()));
        builder.seriesTotal(info.getCount());
        builder.publishedDate(parseDate(info.getYear(), info.getMonth(), info.getDay()));
        builder.pageCount(info.getPageCount());
        builder.language(info.getLanguageISO());

        // GTIN → ISBN-13
        String gtin = info.getGtin();
        if (gtin != null && !gtin.isBlank()) {
            String normalized = gtin.replaceAll("[- ]", "");
            if (normalized.matches("\\d{13}")) {
                builder.isbn13(normalized);
            } else {
                log.debug("Invalid GTIN format (expected 13 digits): {}", gtin);
            }
        }

        List<String> authors = StringSplitter.split(info.getWriter());
        if (!authors.isEmpty()) {
            builder.authors(authors);
        }

        Set<String> categories = new LinkedHashSet<>(StringSplitter.split(info.getGenre()));
        if (!categories.isEmpty()) {
            builder.categories(categories);
        }

        Set<String> tags = new LinkedHashSet<>(StringSplitter.split(info.getTags()));
        if (!tags.isEmpty()) {
            builder.tags(tags);
        }

        // Age rating
        AgeRating ageRating = info.getAgeRating();
        if (ageRating != null && ageRating.minimumAge() != null) {
            builder.ageRating(ageRating.minimumAge());
            builder.contentRating(ageRating.xmlValue());
        }

        // Comic-specific metadata
        ComicMetadata.ComicMetadataBuilder comicBuilder = ComicMetadata.builder();
        boolean hasComicFields = false;

        String issueNumber = info.getNumber();
        if (issueNumber != null && !issueNumber.isBlank()) {
            comicBuilder.issueNumber(issueNumber);
            hasComicFields = true;
        }

        Integer volume = info.getVolume();
        if (volume != null) {
            comicBuilder.volumeName(info.getSeries());
            comicBuilder.volumeNumber(volume);
            hasComicFields = true;
        }

        String storyArc = info.getStoryArc();
        if (storyArc != null && !storyArc.isBlank()) {
            comicBuilder.storyArc(storyArc);
            comicBuilder.storyArcNumber(parseInteger(info.getStoryArcNumber()));
            hasComicFields = true;
        }

        String alternateSeries = info.getAlternateSeries();
        if (alternateSeries != null && !alternateSeries.isBlank()) {
            comicBuilder.alternateSeries(alternateSeries);
            comicBuilder.alternateIssue(info.getAlternateNumber());
            hasComicFields = true;
        }

        hasComicFields |= setIfPresent(comicBuilder::pencillers, StringSplitter.split(info.getPenciller()));
        hasComicFields |= setIfPresent(comicBuilder::inkers, StringSplitter.split(info.getInker()));
        hasComicFields |= setIfPresent(comicBuilder::colorists, StringSplitter.split(info.getColorist()));
        hasComicFields |= setIfPresent(comicBuilder::letterers, StringSplitter.split(info.getLetterer()));
        hasComicFields |= setIfPresent(comicBuilder::coverArtists, StringSplitter.split(info.getCoverArtist()));
        hasComicFields |= setIfPresent(comicBuilder::editors, StringSplitter.split(info.getEditor()));

        String imprint = info.getImprint();
        if (imprint != null && !imprint.isBlank()) {
            comicBuilder.imprint(imprint);
            hasComicFields = true;
        }

        String format = info.getFormat();
        if (format != null && !format.isBlank()) {
            comicBuilder.format(format);
            hasComicFields = true;
        }

        YesNo blackAndWhite = info.getBlackAndWhite();
        if (blackAndWhite == YesNo.YES) {
            comicBuilder.blackAndWhite(Boolean.TRUE);
            hasComicFields = true;
        }

        ReadingDirection manga = info.getManga();
        if (manga != null && manga != ReadingDirection.UNKNOWN) {
            boolean isManga = manga == ReadingDirection.RIGHT_TO_LEFT || manga == ReadingDirection.RIGHT_TO_LEFT_MANGA;
            comicBuilder.manga(isManga);
            if (manga == ReadingDirection.RIGHT_TO_LEFT_MANGA || manga == ReadingDirection.RIGHT_TO_LEFT) {
                comicBuilder.readingDirection("rtl");
            } else {
                comicBuilder.readingDirection("ltr");
            }
            hasComicFields = true;
        }

        hasComicFields |= setIfPresent(comicBuilder::characters, StringSplitter.split(info.getCharacters()));
        hasComicFields |= setIfPresent(comicBuilder::teams, StringSplitter.split(info.getTeams()));
        hasComicFields |= setIfPresent(comicBuilder::locations, StringSplitter.split(info.getLocations()));

        String web = info.getWeb();
        if (web != null && !web.isBlank()) {
            comicBuilder.webLink(web);
            hasComicFields = true;
            parseWebField(web, builder);
        }

        String notes = info.getNotes();
        if (notes != null && !notes.isBlank()) {
            comicBuilder.notes(notes);
            hasComicFields = true;
            parseNotes(notes, builder);

            boolean hasDescription = info.getSummary() != null && !info.getSummary().isBlank();
            if (!hasDescription) {
                String cleanedNotes = notes.replaceAll("\\[BookLore:[^\\]]+\\][^\\n]*(\n|$)", "").trim();
                if (!cleanedNotes.isEmpty()) {
                    builder.description(cleanedNotes);
                }
            }
        }

        if (hasComicFields) {
            builder.comicMetadata(comicBuilder.build());
        }
        return builder.build();
    }

    private boolean setIfPresent(java.util.function.Consumer<Set<String>> setter, List<String> values) {
        if (!values.isEmpty()) {
            setter.accept(new LinkedHashSet<>(values));
            return true;
        }
        return false;
    }

    private void parseWebField(String web, BookMetadata.BookMetadataBuilder builder) {
        String[] urls = WEB_SPLIT_PATTERN.split(web);
        for (String url : urls) {
            if (url.isBlank()) continue;
            url = url.trim();

            java.util.regex.Matcher grMatcher = GOODREADS_URL_PATTERN.matcher(url);
            if (grMatcher.find()) {
                builder.goodreadsId(grMatcher.group(1));
                continue;
            }

            java.util.regex.Matcher azMatcher = AMAZON_URL_PATTERN.matcher(url);
            if (azMatcher.find()) {
                builder.asin(azMatcher.group(1));
                continue;
            }

            java.util.regex.Matcher cvMatcher = COMICVINE_URL_PATTERN.matcher(url);
            if (cvMatcher.find()) {
                builder.comicvineId(cvMatcher.group(1));
                continue;
            }

            java.util.regex.Matcher hcMatcher = HARDCOVER_URL_PATTERN.matcher(url);
            if (hcMatcher.find()) {
                builder.hardcoverId(hcMatcher.group(1));
                continue;
            }
        }
    }

    private void parseNotes(String notes, BookMetadata.BookMetadataBuilder builder) {
        java.util.regex.Matcher matcher = BOOKLORE_NOTE_PATTERN.matcher(notes);
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();

            switch (key) {
                case "Moods" -> {
                    if (!value.isEmpty()) builder.moods(new LinkedHashSet<>(StringSplitter.split(value)));
                }
                case "Tags" -> {
                    if (!value.isEmpty()) {
                        Set<String> parsedTags = new LinkedHashSet<>(StringSplitter.split(value));
                        BookMetadata current = builder.build();
                        if (current.getTags() != null) parsedTags.addAll(current.getTags());
                        builder.tags(parsedTags);
                    }
                }
                case "Subtitle" -> builder.subtitle(value);
                case "ISBN13" -> builder.isbn13(value);
                case "ISBN10" -> builder.isbn10(value);
                case "AmazonRating" -> safeParseDouble(value, builder::amazonRating);
                case "GoodreadsRating" -> safeParseDouble(value, builder::goodreadsRating);
                case "HardcoverRating" -> safeParseDouble(value, builder::hardcoverRating);
                case "LubimyczytacRating" -> safeParseDouble(value, builder::lubimyczytacRating);
                case "RanobedbRating" -> safeParseDouble(value, builder::ranobedbRating);
                case "HardcoverBookId" -> builder.hardcoverBookId(value);
                case "HardcoverId" -> builder.hardcoverId(value);
                case "LubimyczytacId" -> builder.lubimyczytacId(value);
                case "RanobedbId" -> builder.ranobedbId(value);
                case "GoogleId" -> builder.googleId(value);
                case "GoodreadsId" -> builder.goodreadsId(value);
                case "ASIN" -> builder.asin(value);
                case "ComicvineId" -> builder.comicvineId(value);
            }
        }
    }

    private void safeParseDouble(String value, java.util.function.DoubleConsumer consumer) {
        try {
            consumer.accept(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            log.debug("Failed to parse double from value: {}", value);
        }
    }

    private String coalesce(String a, String b) {
        return (a != null && !a.isBlank())
                ? a
                : (b != null && !b.isBlank() ? b : null);
    }

    private Integer parseInteger(String value) {
        try {
            return (value == null || value.isBlank())
                    ? null
                    : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Float parseFloat(String value) {
        try {
            return (value == null || value.isBlank())
                    ? null
                    : Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate parseDate(Integer year, Integer month, Integer day) {
        if (year == null) {
            return null;
        }
        int m = month != null ? month : 1;
        int d = day != null ? day : 1;
        try {
            return LocalDate.of(year, m, d);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public byte[] extractCover(File file) {
        return extractCover(file.toPath());
    }

    public byte[] extractCover(Path path) {
        try {
            byte[] cover = ComicArchiveReader.extractCover(path);
            if (cover != null && cover.length > 0) {
                return cover;
            }
        } catch (Exception e) {
            log.warn("Failed to extract cover from archive {}: {}", path.getFileName(), e.getMessage());
        }
        return null;
    }
}
