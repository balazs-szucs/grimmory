package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;
import org.booklore.util.SecureXmlUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
public class Fb2MetadataExtractor implements FileMetadataExtractor {

    private static final String FB2_NAMESPACE = "http://www.gribuser.ru/xml/fictionbook/2.0";
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");
    private static final Pattern ISBN_PATTERN = Pattern.compile("\\d{9}[\\dXx]");
    private static final Pattern KEYWORD_SEPARATOR_PATTERN = Pattern.compile("[,;]");
    private static final Pattern ISBN_CLEANER_PATTERN = Pattern.compile("[^0-9Xx]");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    @Override
    public InputStream extractCover(File file) throws IOException {
        try (InputStream inputStream = getInputStream(file)) {
            javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newInstance();
            factory.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
            javax.xml.stream.XMLStreamReader reader = factory.createXMLStreamReader(inputStream);

            String coverId = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    if ("coverpage".equals(localName)) {
                        coverId = findCoverId(reader);
                    } else if ("binary".equals(localName)) {
                        String id = reader.getAttributeValue(null, "id");
                        String contentType = reader.getAttributeValue(null, "content-type");
                        if (id != null && (id.equals(coverId) || id.toLowerCase().contains("cover")) &&
                                contentType != null && contentType.startsWith("image/")) {
                            String base64Data = reader.getElementText().trim();
                            return new ByteArrayInputStream(Base64.getDecoder().decode(base64Data));
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to extract cover from FB2: {}", file.getName(), e);
            return null;
        }
    }

    private String findCoverId(javax.xml.stream.XMLStreamReader reader) throws javax.xml.stream.XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT && "image".equals(reader.getLocalName())) {
                String href = reader.getAttributeValue("http://www.w3.org/1999/xlink", "href");
                if (href != null && href.startsWith("#")) {
                    return href.substring(1);
                }
            } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT && "coverpage".equals(reader.getLocalName())) {
                break;
            }
        }
        return null;
    }

    @Override
    public BookMetadata extractMetadata(File file) {
        try (InputStream inputStream = getInputStream(file)) {
            javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newInstance();
            factory.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
            javax.xml.stream.XMLStreamReader reader = factory.createXMLStreamReader(inputStream);

            BookMetadata.BookMetadataBuilder metadataBuilder = BookMetadata.builder();
            List<String> authors = new ArrayList<>();
            Set<String> categories = new HashSet<>();

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    switch (localName) {
                        case "title-info" -> extractTitleInfo(reader, metadataBuilder, authors, categories);
                        case "publish-info" -> extractPublishInfo(reader, metadataBuilder);
                        case "document-info" -> extractDocumentInfo(reader, metadataBuilder);
                    }
                }
            }

            metadataBuilder.authors(authors);
            metadataBuilder.categories(categories);

            return metadataBuilder.build();
        } catch (Exception e) {
            log.warn("Failed to extract metadata from FB2: {}", file.getName(), e);
            return null;
        }
    }

    private void extractTitleInfo(javax.xml.stream.XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder,
                                   List<String> authors, Set<String> categories) throws javax.xml.stream.XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();
                switch (localName) {
                    case "genre" -> {
                        String genre = reader.getElementText().trim();
                        if (StringUtils.isNotBlank(genre)) categories.add(genre);
                    }
                    case "author" -> {
                        String authorName = extractPersonName(reader);
                        if (StringUtils.isNotBlank(authorName)) authors.add(authorName);
                    }
                    case "book-title" -> builder.title(reader.getElementText().trim());
                    case "annotation" -> {
                        String description = extractTextFromReader(reader);
                        if (StringUtils.isNotBlank(description)) builder.description(description);
                    }
                    case "keywords" -> {
                        String keywordsText = reader.getElementText().trim();
                        if (StringUtils.isNotBlank(keywordsText)) {
                            for (String keyword : KEYWORD_SEPARATOR_PATTERN.split(keywordsText)) {
                                String trimmed = keyword.trim();
                                if (StringUtils.isNotBlank(trimmed)) categories.add(trimmed);
                            }
                        }
                    }
                    case "date" -> {
                        String dateValue = reader.getAttributeValue(null, "value");
                        if (StringUtils.isBlank(dateValue)) {
                            dateValue = reader.getElementText().trim();
                        }
                        LocalDate publishedDate = parseDate(dateValue);
                        if (publishedDate != null) builder.publishedDate(publishedDate);
                    }
                    case "lang" -> builder.language(reader.getElementText().trim());
                    case "sequence" -> {
                        String seriesName = reader.getAttributeValue(null, "name");
                        if (StringUtils.isNotBlank(seriesName)) builder.seriesName(seriesName.trim());
                        String seriesNumber = reader.getAttributeValue(null, "number");
                        if (StringUtils.isNotBlank(seriesNumber)) {
                            try {
                                builder.seriesNumber(Float.parseFloat(seriesNumber));
                            } catch (NumberFormatException e) {
                                log.debug("Failed to parse series number: {}", seriesNumber);
                            }
                        }
                    }
                }
            } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT && "title-info".equals(reader.getLocalName())) {
                break;
            }
        }
    }

    private void extractPublishInfo(javax.xml.stream.XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder) throws javax.xml.stream.XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();
                switch (localName) {
                    case "publisher" -> builder.publisher(reader.getElementText().trim());
                    case "year" -> {
                        String yearText = reader.getElementText().trim();
                        Matcher matcher = YEAR_PATTERN.matcher(yearText);
                        if (matcher.find()) {
                            try {
                                int yearValue = Integer.parseInt(matcher.group());
                                builder.publishedDate(LocalDate.of(yearValue, 1, 1));
                            } catch (NumberFormatException e) {
                                log.debug("Failed to parse year: {}", yearText);
                            }
                        }
                    }
                    case "isbn" -> {
                        String isbnText = ISBN_CLEANER_PATTERN.matcher(reader.getElementText().trim()).replaceAll("");
                        if (isbnText.length() == 13) {
                            builder.isbn13(isbnText);
                        } else if (isbnText.length() == 10) {
                            builder.isbn10(isbnText);
                        } else {
                            Matcher matcher = ISBN_PATTERN.matcher(isbnText);
                            if (matcher.find()) {
                                builder.isbn10(matcher.group());
                            }
                        }
                    }
                }
            } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT && "publish-info".equals(reader.getLocalName())) {
                break;
            }
        }
    }

    private void extractDocumentInfo(javax.xml.stream.XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder) throws javax.xml.stream.XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                if ("id".equals(reader.getLocalName())) {
                    log.debug("FB2 document ID: {}", reader.getElementText().trim());
                }
            } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT && "document-info".equals(reader.getLocalName())) {
                break;
            }
        }
    }

    private String extractPersonName(javax.xml.stream.XMLStreamReader reader) throws javax.xml.stream.XMLStreamException {
        StringBuilder name = new StringBuilder(64);
        String nickname = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();
                String text = reader.getElementText().trim();
                if (StringUtils.isNotBlank(text)) {
                    if ("nickname".equals(localName)) {
                        nickname = text;
                    } else if ("first-name".equals(localName) || "middle-name".equals(localName) || "last-name".equals(localName)) {
                        if (!name.isEmpty()) name.append(" ");
                        name.append(text);
                    }
                }
            } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT && "author".equals(reader.getLocalName())) {
                break;
            }
        }
        return name.isEmpty() ? (nickname != null ? nickname : "") : name.toString();
    }

    private String extractTextFromReader(javax.xml.stream.XMLStreamReader reader) throws javax.xml.stream.XMLStreamException {
        StringBuilder text = new StringBuilder();
        String startTag = reader.getLocalName();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == javax.xml.stream.XMLStreamConstants.CHARACTERS) {
                text.append(reader.getText().trim()).append(" ");
            } else if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                if ("p".equals(reader.getLocalName())) {
                    text.append(reader.getElementText().trim()).append("\n\n");
                }
            } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT && startTag.equals(reader.getLocalName())) {
                break;
            }
        }
        return text.toString().trim();
    }

    private LocalDate parseDate(String dateString) {
        if (StringUtils.isBlank(dateString)) {
            return null;
        }

        try {
            // Try parsing ISO date format (YYYY-MM-DD)
            if (ISO_DATE_PATTERN.matcher(dateString).matches()) {
                return LocalDate.parse(dateString);
            }

            // Try extracting year only
            Matcher matcher = YEAR_PATTERN.matcher(dateString);
            if (matcher.find()) {
                int year = Integer.parseInt(matcher.group());
                return LocalDate.of(year, 1, 1);
            }
        } catch (Exception e) {
            log.debug("Failed to parse date: {}", dateString, e);
        }

        return null;
    }


    private InputStream getInputStream(File file) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        if (file.getName().toLowerCase().endsWith(".gz")) {
            try {
                return new GZIPInputStream(fis);
            } catch (Exception e) {
                fis.close();
                throw e;
            }
        }
        return fis;
    }
}
