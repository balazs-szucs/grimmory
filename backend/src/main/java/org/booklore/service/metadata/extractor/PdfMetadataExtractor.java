package org.booklore.service.metadata.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.XmpMetadataParser;
import org.grimmory.pdfium4j.model.MetadataTag;
import org.grimmory.pdfium4j.model.XmpMetadata;
import org.booklore.model.dto.BookMetadata;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.DoubleConsumer;

@Component
@Slf4j
public class PdfMetadataExtractor implements FileMetadataExtractor {


    private static final Pattern COMMA_AMPERSAND_PATTERN = Pattern.compile("[,&]");
    private static final Pattern ISBN_CLEANUP_PATTERN = Pattern.compile("[^0-9Xx]");

    @Override
    public byte[] extractCover(File file) {
        try (PdfDocument doc = PdfDocument.open(file.toPath())) {
            return doc.renderPageToBytes(0, 150, "jpeg");
        } catch (Exception e) {
            log.warn("Failed to extract cover from PDF: {}", file.getAbsolutePath(), e);
            return null;
        }
    }

    @Override
    public BookMetadata extractMetadata(File file) {
        if (!file.exists() || !file.isFile()) {
            log.warn("File does not exist or is not a file: {}", file.getPath());
            return BookMetadata.builder().build();
        }

        try (PdfDocument doc = PdfDocument.open(file.toPath())) {
            XmpMetadata xmp = XmpMetadataParser.parseFrom(doc);

            BookMetadata.BookMetadataBuilder metadataBuilder = BookMetadata.builder()
                    .title(xmp.title().filter(StringUtils::isNotBlank)
                            .or(() -> doc.metadata(MetadataTag.TITLE).filter(StringUtils::isNotBlank))
                            .orElse(FilenameUtils.getBaseName(file.getName())))
                    .description(xmp.description().filter(StringUtils::isNotBlank)
                            .or(() -> doc.metadata(MetadataTag.SUBJECT).filter(StringUtils::isNotBlank))
                            .orElse(null))
                    .publisher(xmp.publisher().filter(StringUtils::isNotBlank)
                            .or(() -> doc.metadata("EBX_PUBLISHER").filter(StringUtils::isNotBlank))
                            .orElse(null))
                    .language(xmp.language().filter(StringUtils::isNotBlank)
                            .or(() -> doc.metadata("Language").filter(StringUtils::isNotBlank))
                            .orElse(null))
                    .pageCount(doc.pageCount());

            // Authors
            if (!xmp.creators().isEmpty()) {
                metadataBuilder.authors(xmp.creators());
            } else {
                doc.metadata(MetadataTag.AUTHOR).ifPresent(a -> {
                    List<String> authors = Arrays.stream(COMMA_AMPERSAND_PATTERN.split(a))
                            .map(String::trim)
                            .filter(StringUtils::isNotBlank)
                            .toList();
                    if (!authors.isEmpty()) metadataBuilder.authors(authors);
                });
            }

            // Date
            xmp.date().map(Object::toString).map(this::parsePdfDate)
                    .or(() -> doc.metadata(MetadataTag.CREATION_DATE).map(this::parsePdfDate))
                    .ifPresent(metadataBuilder::publishedDate);

            // Moods and Tags (List/Bag with semicolon fallback)
            Set<String> moodsSet = new LinkedHashSet<>(findCustomListField(xmp, "moods"));
            if (moodsSet.isEmpty()) {
                findCustomField(xmp, "moods").ifPresent(m ->
                        Arrays.stream(m.split(";")).map(String::trim).filter(StringUtils::isNotBlank).forEach(moodsSet::add));
            }
            if (!moodsSet.isEmpty()) metadataBuilder.moods(moodsSet);

            Set<String> tagsSet = new LinkedHashSet<>(findCustomListField(xmp, "tags"));
            if (tagsSet.isEmpty()) {
                findCustomField(xmp, "tags").ifPresent(t ->
                        Arrays.stream(t.split(";")).map(String::trim).filter(StringUtils::isNotBlank).forEach(tagsSet::add));
            }
            if (!tagsSet.isEmpty()) metadataBuilder.tags(tagsSet);

            // Categories (Keywords / Subjects), filtering out moods and tags
            Set<String> categories = new HashSet<>(xmp.subjects());
            if (categories.isEmpty()) {
                doc.metadata(MetadataTag.KEYWORDS).ifPresent(k -> {
                    String[] parts = k.contains(";") ? k.split(";") : k.split(",");
                    Arrays.stream(parts).map(String::trim).filter(StringUtils::isNotBlank).forEach(categories::add);
                });
            }
            categories.removeAll(moodsSet);
            categories.removeAll(tagsSet);
            if (!categories.isEmpty()) metadataBuilder.categories(categories);

            // Calibre & Series
            xmp.calibreSeries().or(() -> doc.metadata("Series")).ifPresent(metadataBuilder::seriesName);
            xmp.calibreSeriesIndex().map(Double::floatValue)
                    .or(() -> doc.metadata("SeriesNumber").flatMap(val -> {
                        try { return Optional.of(Float.parseFloat(val)); } catch (Exception _) { return Optional.empty(); }
                    }))
                    .ifPresent(metadataBuilder::seriesNumber);

            // Calibre fallback for un-prefixed series_index (legacy)
            if (metadataBuilder.build().getSeriesNumber() == null) {
                String xmpStr = doc.xmpMetadataString();
                Matcher siMatcher = Pattern.compile("<series_index>([^<]+)</series_index>").matcher(xmpStr);
                if (siMatcher.find()) {
                    try { metadataBuilder.seriesNumber(Float.parseFloat(siMatcher.group(1).trim())); } catch (Exception _) {}
                }
            }

            // Booklore Custom Fields
            findCustomField(xmp, "seriesName").ifPresent(metadataBuilder::seriesName);
            findCustomField(xmp, "seriesNumber").ifPresent(val -> {
                try { metadataBuilder.seriesNumber(Float.parseFloat(val)); } catch (Exception _) {}
            });
            findCustomField(xmp, "seriesTotal").ifPresent(val -> {
                try { metadataBuilder.seriesTotal(Integer.parseInt(val)); } catch (Exception _) {}
            });
            findCustomField(xmp, "subtitle").or(() -> doc.metadata("Subtitle")).ifPresent(metadataBuilder::subtitle);

            // Identifiers (Low priority fallbacks first)
            xmp.findField("isbn13").ifPresent(val -> metadataBuilder.isbn13(cleanIsbn(val)));
            xmp.findField("isbn10").ifPresent(val -> metadataBuilder.isbn10(cleanIsbn(val)));
            xmp.findField("googleId").ifPresent(metadataBuilder::googleId);
            xmp.findField("goodreadsId").ifPresent(metadataBuilder::goodreadsId);
            xmp.findField("amazonId").ifPresent(metadataBuilder::asin);
            xmp.findField("asin").ifPresent(metadataBuilder::asin);
            xmp.findField("comicvineId").ifPresent(metadataBuilder::comicvineId);
            xmp.findField("ranobedbId").ifPresent(metadataBuilder::ranobedbId);
            xmp.findField("lubimyczytacId").ifPresent(metadataBuilder::lubimyczytacId);
            xmp.findField("hardcoverId").ifPresent(metadataBuilder::hardcoverId);
            xmp.findField("hardcoverBookId").ifPresent(metadataBuilder::hardcoverBookId);

            doc.metadata("ISBN").ifPresent(val -> mapIsbn(val, metadataBuilder));

            // High priority Identifiers (xmp:Identifier and derived)
            xmp.isbns().forEach(val -> mapIsbn(val, metadataBuilder));
            xmp.xmpIdentifier("isbn").ifPresent(val -> mapIsbn(val, metadataBuilder));
            xmp.xmpIdentifier("isbn13").ifPresent(val -> metadataBuilder.isbn13(cleanIsbn(val)));
            xmp.xmpIdentifier("isbn10").ifPresent(val -> metadataBuilder.isbn10(cleanIsbn(val)));
            xmp.xmpIdentifier("google").ifPresent(metadataBuilder::googleId);
            xmp.xmpIdentifier("amazon").ifPresent(metadataBuilder::asin);
            xmp.xmpIdentifier("asin").ifPresent(metadataBuilder::asin);
            xmp.xmpIdentifier("goodreads").ifPresent(metadataBuilder::goodreadsId);
            xmp.xmpIdentifier("comicvine").ifPresent(metadataBuilder::comicvineId);
            xmp.xmpIdentifier("ranobedb").ifPresent(metadataBuilder::ranobedbId);
            xmp.xmpIdentifier("lubimyczytac").ifPresent(metadataBuilder::lubimyczytacId);
            xmp.xmpIdentifier("hardcover").ifPresent(metadataBuilder::hardcoverId);
            xmp.xmpIdentifier("hardcover_book_id").ifPresent(metadataBuilder::hardcoverBookId);

            // Ratings
            mapRating(xmp, "rating", "Rating", metadataBuilder::rating);
            mapRating(xmp, "amazonRating", "AmazonRating", metadataBuilder::amazonRating);
            mapRating(xmp, "goodreadsRating", "GoodreadsRating", metadataBuilder::goodreadsRating);
            mapRating(xmp, "hardcoverRating", "HardcoverRating", metadataBuilder::hardcoverRating);
            mapRating(xmp, "lubimyczytacRating", "LubimyczytacRating", metadataBuilder::lubimyczytacRating);
            mapRating(xmp, "ranobedbRating", "RanobedbRating", metadataBuilder::ranobedbRating);

            return metadataBuilder.build();
        } catch (Exception e) {
            log.error("Failed to load PDF file: {}", file.getPath(), e);
            return BookMetadata.builder().build();
        }
    }

    private void mapIsbn(String value, BookMetadata.BookMetadataBuilder builder) {
        String cleaned = cleanIsbn(value);
        if (cleaned.length() == 13) builder.isbn13(cleaned);
        else if (cleaned.length() == 10) builder.isbn10(cleaned);
        else builder.isbn13(cleaned);
    }


    private Optional<String> findCustomField(XmpMetadata xmp, String name) {
        Optional<String> val = xmp.findField(name);
        if (val.isPresent()) return val;
        // Try PascalCase
        String pascal = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
        return xmp.findField(pascal);
    }

    private List<String> findCustomListField(XmpMetadata xmp, String name) {
        List<String> values = xmp.findListField(name);
        if (!values.isEmpty()) {
            return values;
        }

        String pascal = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
        return xmp.findListField(pascal);
    }

    private static String cleanIsbn(String value) {
        return ISBN_CLEANUP_PATTERN.matcher(value).replaceAll("");
    }

    /**
     * Parses PDF date strings in the standard D:YYYYMMDDHHmmSS format.
     */
    private LocalDate parsePdfDate(String pdfDate) {
        if (pdfDate == null || pdfDate.isBlank()) return null;
        try {
            String s = pdfDate.startsWith("D:") ? pdfDate.substring(2) : pdfDate;
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(s);
            }
            if (s.length() >= 8 && s.substring(0, 8).matches("\\d{8}")) {
                int year = Integer.parseInt(s.substring(0, 4));
                int month = Integer.parseInt(s.substring(4, 6));
                int day = Integer.parseInt(s.substring(6, 8));
                return LocalDate.of(year, month, day);
            }
            if (s.length() >= 4 && s.substring(0, 4).matches("\\d{4}")) {
                return LocalDate.of(Integer.parseInt(s.substring(0, 4)), 1, 1);
            }
        } catch (Exception _) {
        }
        return null;
    }

    private void mapRating(XmpMetadata xmp, String name, String fallbackName, DoubleConsumer setter) {
        Optional<String> val = findCustomField(xmp, name);
        if (val.isEmpty()) val = findCustomField(xmp, fallbackName);
        val.ifPresent(v -> {
            try {
                setter.accept(Double.parseDouble(v));
            } catch (Exception _) {
                // Ignore invalid ratings
            }
        });
    }
}
