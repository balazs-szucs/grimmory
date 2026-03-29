package org.booklore.service.metadata.writer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.model.MetadataTag;
import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.SecureXmlUtils;
import org.booklore.service.metadata.BookLoreMetadata;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class PdfMetadataWriter implements MetadataWriter {

    private final AppSettingService appSettingService;

    @Override
    public void saveMetadataToFile(File file, BookMetadataEntity metadataEntity, String thumbnailUrl, MetadataClearFlags clear) {
        if (!shouldSaveMetadataToFile(file)) {
            return;
        }

        if (!file.exists() || !file.getName().toLowerCase().endsWith(".pdf")) {
            log.warn("Invalid PDF file: {}", file.getAbsolutePath());
            return;
        }

        Path filePath = file.toPath();
        Path backupPath = null;
        boolean backupCreated = false;
        File tempFile = null;

        try {
            String prefix = "pdfBackup-" + UUID.randomUUID() + "-";
            backupPath = Files.createTempFile(prefix, ".pdf");
            Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            backupCreated = true;
        } catch (IOException e) {
            log.warn("Could not create PDF temp backup for {}: {}", file.getName(), e.getMessage());
        }

        try (PdfDocument doc = PdfDocument.open(filePath)) {
            applyMetadataToDocument(doc, metadataEntity, clear);
            tempFile = File.createTempFile("pdfmeta-", ".pdf");
            doc.save(tempFile.toPath());
            Files.move(tempFile.toPath(), filePath, StandardCopyOption.REPLACE_EXISTING);
            tempFile = null; // Prevent deletion in finally block after successful move
            log.info("Successfully embedded metadata into PDF: {}", file.getName());
        } catch (Exception e) {
            log.warn("Failed to write metadata to PDF {}: {}", file.getName(), e.getMessage(), e);
            if (backupCreated) {
                try {
                    Files.copy(backupPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("Restored PDF {} from temp backup after failure", file.getName());
                } catch (IOException ex) {
                    log.error("Failed to restore PDF temp backup for {}: {}", file.getName(), ex.getMessage(), ex);
                }
            }
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            if (backupCreated) {
                try {
                    Files.deleteIfExists(backupPath);
                } catch (IOException e) {
                    log.warn("Could not delete PDF temp backup for {}: {}", file.getName(), e.getMessage());
                }
            }
        }
    }

    @Override
    public BookFileType getSupportedBookType() {
        return BookFileType.PDF;
    }

    public boolean shouldSaveMetadataToFile(File pdfFile) {
        MetadataPersistenceSettings.SaveToOriginalFile settings = appSettingService.getAppSettings().getMetadataPersistenceSettings().getSaveToOriginalFile();

        MetadataPersistenceSettings.FormatSettings pdfSettings = settings.getPdf();
        if (pdfSettings == null || !pdfSettings.isEnabled()) {
            log.debug("PDF metadata writing is disabled. Skipping: {}", pdfFile.getName());
            return false;
        }

        long fileSizeInMb = pdfFile.length() / (1024 * 1024);
        if (fileSizeInMb > pdfSettings.getMaxFileSizeInMb()) {
            log.info("PDF file {} ({} MB) exceeds max size limit ({} MB). Skipping metadata write.", pdfFile.getName(), fileSizeInMb, pdfSettings.getMaxFileSizeInMb());
            return false;
        }

        return true;
    }

    // Maximum length for PDF Info Dictionary keywords (some older PDF specs limit to 255 bytes)
    private static final int MAX_INFO_KEYWORDS_LENGTH = 255;

    private void applyMetadataToDocument(PdfDocument doc, BookMetadataEntity entity, MetadataClearFlags clear) {
        MetadataCopyHelper helper = new MetadataCopyHelper(entity);

        // --- PDF Info Dictionary (legacy) via PDFium4j ---
        StringBuilder keywordsBuilder = new StringBuilder();
        helper.copyCategories(clear != null && clear.isCategories(), cats -> {
            if (cats != null && !cats.isEmpty()) {
                keywordsBuilder.append(String.join("; ", cats));
            }
        });

        helper.copyTitle(clear != null && clear.isTitle(), title -> doc.setMetadata(MetadataTag.TITLE, title != null ? title : ""));
        helper.copyPublisher(clear != null && clear.isPublisher(), pub -> doc.setMetadata(MetadataTag.PRODUCER, pub != null ? pub : ""));
        helper.copyAuthors(clear != null && clear.isAuthors(), authors -> doc.setMetadata(MetadataTag.AUTHOR, authors != null ? String.join(", ", authors) : ""));
        helper.copyPublishedDate(clear != null && clear.isPublishedDate(), date -> {
            if (date != null) {
                // PDF date format: D:YYYYMMDDHHmmSS
                String pdfDate = String.format("D:%04d%02d%02d000000", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
                doc.setMetadata(MetadataTag.CREATION_DATE, pdfDate);
            }
        });

        String keywords = keywordsBuilder.toString();
        if (keywords.length() > MAX_INFO_KEYWORDS_LENGTH) {
            keywords = keywords.substring(0, MAX_INFO_KEYWORDS_LENGTH - 3) + "...";
            log.debug("PDF keywords truncated from {} to {} characters for legacy compatibility", 
                keywordsBuilder.length(), keywords.length());
        }
        doc.setMetadata(MetadataTag.KEYWORDS, keywords);

        // --- XMP metadata via raw XML ---
        try {
            byte[] baseXmpBytes = buildDublinCoreXmp(helper, clear);
            byte[] newXmpBytes = addCustomIdentifiersToXmp(baseXmpBytes, entity, helper, clear);

            String existingXmp = doc.xmpMetadataString();
            byte[] existingXmpBytes = (existingXmp != null && !existingXmp.isBlank()) 
                    ? existingXmp.getBytes(StandardCharsets.UTF_8) : null;

            if (!isXmpMetadataDifferent(existingXmpBytes, newXmpBytes)) {
                log.info("XMP metadata unchanged, skipping write");
                return;
            }

            doc.setXmpMetadata(new String(newXmpBytes, StandardCharsets.UTF_8));
            log.info("XMP metadata updated for PDF");
        } catch (Exception e) {
            log.warn("Failed to embed XMP metadata: {}", e.getMessage(), e);
        }
    }

    /**
     * Builds a Dublin Core XMP document as a base for further custom field additions.
     * Replaces the old XMPBox-based approach with direct DOM construction.
     */
    private byte[] buildDublinCoreXmp(MetadataCopyHelper helper, MetadataClearFlags clear) throws Exception {
        DocumentBuilder builder = SecureXmlUtils.createSecureDocumentBuilder(true);
        Document doc = builder.newDocument();

        Element xmpmeta = doc.createElementNS("adobe:ns:meta/", "x:xmpmeta");
        doc.appendChild(xmpmeta);

        Element rdf = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:RDF");
        xmpmeta.appendChild(rdf);

        Element dcDesc = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:Description");
        dcDesc.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:dc", "http://purl.org/dc/elements/1.1/");
        dcDesc.setAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:about", "");

        helper.copyTitle(clear != null && clear.isTitle(), title -> {
            if (title != null) appendDcAlt(doc, dcDesc, "dc:title", title);
        });
        helper.copyDescription(clear != null && clear.isDescription(), desc -> {
            if (desc != null) appendDcAlt(doc, dcDesc, "dc:description", desc);
        });
        helper.copyPublisher(clear != null && clear.isPublisher(), pub -> {
            if (pub != null) appendDcBag(doc, dcDesc, "dc:publisher", List.of(pub));
        });
        helper.copyLanguage(clear != null && clear.isLanguage(), lang -> {
            if (lang != null && !lang.isBlank()) appendDcBag(doc, dcDesc, "dc:language", List.of(lang));
        });
        helper.copyPublishedDate(clear != null && clear.isPublishedDate(), date -> {
            if (date != null) appendDcSeq(doc, dcDesc, "dc:date", List.of(date.toString()));
        });
        helper.copyAuthors(clear != null && clear.isAuthors(), authors -> {
            if (authors != null && !authors.isEmpty()) {
                List<String> cleaned = authors.stream()
                        .map(name -> name.replaceAll("\\s+", " ").trim())
                        .filter(name -> !name.isBlank())
                        .toList();
                if (!cleaned.isEmpty()) appendDcSeq(doc, dcDesc, "dc:creator", cleaned);
            }
        });
        helper.copyCategories(clear != null && clear.isCategories(), cats -> {
            if (cats != null && !cats.isEmpty()) appendDcBag(doc, dcDesc, "dc:subject", new ArrayList<>(cats));
        });

        if (dcDesc.hasChildNodes()) {
            rdf.appendChild(dcDesc);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        tf.setOutputProperty(OutputKeys.INDENT, "yes");
        tf.transform(new DOMSource(doc), new StreamResult(baos));
        return baos.toByteArray();
    }

    private void appendDcAlt(Document doc, Element parent, String tagName, String value) {
        Element elem = doc.createElementNS("http://purl.org/dc/elements/1.1/", tagName);
        Element alt = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:Alt");
        Element li = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:li");
        li.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:lang", "x-default");
        li.setTextContent(value);
        alt.appendChild(li);
        elem.appendChild(alt);
        parent.appendChild(elem);
    }

    private void appendDcSeq(Document doc, Element parent, String tagName, List<String> values) {
        Element elem = doc.createElementNS("http://purl.org/dc/elements/1.1/", tagName);
        Element seq = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:Seq");
        for (String v : values) {
            Element li = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:li");
            li.setTextContent(v);
            seq.appendChild(li);
        }
        elem.appendChild(seq);
        parent.appendChild(elem);
    }

    private void appendDcBag(Document doc, Element parent, String tagName, List<String> values) {
        Element elem = doc.createElementNS("http://purl.org/dc/elements/1.1/", tagName);
        Element bag = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:Bag");
        for (String v : values) {
            Element li = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:li");
            li.setTextContent(v);
            bag.appendChild(li);
        }
        elem.appendChild(bag);
        parent.appendChild(elem);
    }


    /**
     * Adds custom metadata to XMP using Booklore namespace for all custom fields.
     * <p>
     * Namespace strategy:
     * - Dublin Core (dc:) for title, description, creator, publisher, date, subject, language
     * - XMP Basic (xmp:) for metadata dates, creator tool
     * - Booklore (booklore:) for series, subtitle, ISBNs, external IDs, ratings, moods, tags, page count
     */
    private byte[] addCustomIdentifiersToXmp(byte[] xmpBytes, BookMetadataEntity metadata, MetadataCopyHelper helper, MetadataClearFlags clear) throws Exception {
        DocumentBuilder builder = SecureXmlUtils.createSecureDocumentBuilder(true);
        Document doc = builder.parse(new ByteArrayInputStream(xmpBytes));

        Element rdfRoot = (Element) doc.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "RDF").item(0);
        if (rdfRoot == null) throw new IllegalStateException("RDF root missing in XMP");

        // XMP Basic namespace for tool and date info
        Element xmpBasicDescription = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:Description");
        xmpBasicDescription.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xmp", "http://ns.adobe.com/xap/1.0/");
        xmpBasicDescription.setAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:about", "");

        xmpBasicDescription.appendChild(createXmpElement(doc, "xmp:CreatorTool", "Booklore"));
        // Use ISO-8601 format for current timestamps
        String nowIso = ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ISO_INSTANT);
        xmpBasicDescription.appendChild(createXmpElement(doc, "xmp:MetadataDate", nowIso));
        xmpBasicDescription.appendChild(createXmpElement(doc, "xmp:ModifyDate", nowIso));
        if (metadata.getPublishedDate() != null) {
            // Use date-only format (YYYY-MM-DD) when we only have a date, not a full timestamp
            xmpBasicDescription.appendChild(createXmpElement(doc, "xmp:CreateDate", 
                    metadata.getPublishedDate().toString()));
        }

        rdfRoot.appendChild(xmpBasicDescription);

        // Booklore namespace for all custom metadata
        Element bookloreDescription = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:Description");
        bookloreDescription.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:" + BookLoreMetadata.NS_PREFIX, BookLoreMetadata.NS_URI);
        bookloreDescription.setAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:about", "");

        // Series Information - ONLY write if BOTH name AND number are valid
        // A series name without a number is broken/incomplete data
        if (hasValidSeries(metadata, clear)) {
            appendBookloreElement(doc, bookloreDescription, "seriesName", metadata.getSeriesName());
            appendBookloreElement(doc, bookloreDescription, "seriesNumber", formatSeriesNumber(metadata.getSeriesNumber()));
            
            // Series total is optional, only write if > 0
            if (metadata.getSeriesTotal() != null && metadata.getSeriesTotal() > 0) {
                helper.copySeriesTotal(clear != null && clear.isSeriesTotal(), total -> {
                    if (total != null && total > 0) {
                        appendBookloreElement(doc, bookloreDescription, "seriesTotal", total.toString());
                    }
                });
            }
        }

        // Subtitle
        helper.copySubtitle(clear != null && clear.isSubtitle(), subtitle -> {
            if (subtitle != null && !subtitle.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "subtitle", subtitle);
            }
        });

        // ISBN Identifiers
        helper.copyIsbn13(clear != null && clear.isIsbn13(), isbn -> {
            if (isbn != null && !isbn.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "isbn13", isbn);
            }
        });

        helper.copyIsbn10(clear != null && clear.isIsbn10(), isbn -> {
            if (isbn != null && !isbn.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "isbn10", isbn);
            }
        });

        // External IDs (only if not blank)
        helper.copyGoogleId(clear != null && clear.isGoogleId(), id -> {
            if (id != null && !id.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "googleId", id);
            }
        });

        helper.copyGoodreadsId(clear != null && clear.isGoodreadsId(), id -> {
            String normalizedId = normalizeGoodreadsId(id);
            if (normalizedId != null && !normalizedId.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "goodreadsId", normalizedId);
            }
        });

        helper.copyHardcoverId(clear != null && clear.isHardcoverId(), id -> {
            if (id != null && !id.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "hardcoverId", id);
            }
        });

        helper.copyHardcoverBookId(clear != null && clear.isHardcoverBookId(), id -> {
            if (id != null && !id.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "hardcoverBookId", id);
            }
        });

        helper.copyAsin(clear != null && clear.isAsin(), id -> {
            if (id != null && !id.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "asin", id);
            }
        });

        helper.copyComicvineId(clear != null && clear.isComicvineId(), id -> {
            if (id != null && !id.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "comicvineId", id);
            }
        });

        helper.copyLubimyczytacId(clear != null && clear.isLubimyczytacId(), id -> {
            if (id != null && !id.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "lubimyczytacId", id);
            }
        });

        helper.copyRanobedbId(clear != null && clear.isRanobedbId(), id -> {
            if (id != null && !id.isBlank()) {
                appendBookloreElement(doc, bookloreDescription, "ranobedbId", id);
            }
        });

        // Ratings (only if > 0)
        helper.copyRating(false, rating -> appendBookloreRating(doc, bookloreDescription, "rating", rating));
        helper.copyHardcoverRating(clear != null && clear.isHardcoverRating(), rating -> appendBookloreRating(doc, bookloreDescription, "hardcoverRating", rating));
        helper.copyGoodreadsRating(clear != null && clear.isGoodreadsRating(), rating -> appendBookloreRating(doc, bookloreDescription, "goodreadsRating", rating));
        helper.copyAmazonRating(clear != null && clear.isAmazonRating(), rating -> appendBookloreRating(doc, bookloreDescription, "amazonRating", rating));
        helper.copyLubimyczytacRating(clear != null && clear.isLubimyczytacRating(), rating -> appendBookloreRating(doc, bookloreDescription, "lubimyczytacRating", rating));
        helper.copyRanobedbRating(clear != null && clear.isRanobedbRating(), rating -> appendBookloreRating(doc, bookloreDescription, "ranobedbRating", rating));

        // Tags (as RDF Bag)
        helper.copyTags(clear != null && clear.isTags(), tags -> {
            if (tags != null && !tags.isEmpty()) {
                appendBookloreBag(doc, bookloreDescription, "tags", tags);
            }
        });

        // Moods (as RDF Bag)
        helper.copyMoods(clear != null && clear.isMoods(), moods -> {
            if (moods != null && !moods.isEmpty()) {
                appendBookloreBag(doc, bookloreDescription, "moods", moods);
            }
        });

        // Page Count
        helper.copyPageCount(false, pageCount -> {
            if (pageCount != null && pageCount > 0) {
                appendBookloreElement(doc, bookloreDescription, "pageCount", pageCount.toString());
            }
        });

        if (bookloreDescription.hasChildNodes()) {
            rdfRoot.appendChild(bookloreDescription);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        tf.setOutputProperty(OutputKeys.INDENT, "yes");
        tf.transform(new DOMSource(doc), new StreamResult(baos));

        // Wrap in xpacket PIs required by PDF XMP specification
        byte[] xmpBody = baos.toByteArray();
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream(xmpBody.length + 2200);
        wrapped.write("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n".getBytes(StandardCharsets.UTF_8));
        wrapped.write(xmpBody);
        // Padding for in-place editing (PDF spec recommendation)
        wrapped.write("\n".getBytes(StandardCharsets.UTF_8));
        byte[] pad = new byte[2048];
        java.util.Arrays.fill(pad, (byte) ' ');
        pad[pad.length - 1] = '\n';
        wrapped.write(pad);
        wrapped.write("<?xpacket end=\"w\"?>".getBytes(StandardCharsets.UTF_8));
        return wrapped.toByteArray();
    }

    private Element createXmpElement(Document doc, String name, String content) {
        Element el = doc.createElementNS("http://ns.adobe.com/xap/1.0/", name);
        el.setTextContent(content);
        return el;
    }

    private void appendBookloreElement(Document doc, Element parent, String localName, String value) {
        Element elem = doc.createElementNS(BookLoreMetadata.NS_URI, BookLoreMetadata.NS_PREFIX + ":" + localName);
        elem.setTextContent(value);
        parent.appendChild(elem);
    }

    private void appendBookloreRating(Document doc, Element parent, String localName, Double rating) {
        if (rating != null && rating > 0) {
            Element elem = doc.createElementNS(BookLoreMetadata.NS_URI, BookLoreMetadata.NS_PREFIX + ":" + localName);
            elem.setTextContent(String.format(Locale.US, "%.1f", rating));
            parent.appendChild(elem);
        }
    }

    private void appendBookloreBag(Document doc, Element parent, String localName, Set<String> values) {
        Element elem = doc.createElementNS(BookLoreMetadata.NS_URI, BookLoreMetadata.NS_PREFIX + ":" + localName);
        Element rdfBag = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:Bag");
        
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                Element li = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:li");
                li.setTextContent(value);
                rdfBag.appendChild(li);
            }
        }
        
        elem.appendChild(rdfBag);
        parent.appendChild(elem);
    }

    private boolean isXmpMetadataDifferent(byte[] existingBytes, byte[] newBytes) {
        if (existingBytes == null || newBytes == null) return true;
        try {
            DocumentBuilder builder = SecureXmlUtils.createSecureDocumentBuilder(false);
            Document doc1 = builder.parse(new ByteArrayInputStream(existingBytes));
            Document doc2 = builder.parse(new ByteArrayInputStream(newBytes));
            return !Objects.equals(
                    doc1.getDocumentElement().getTextContent().trim(),
                    doc2.getDocumentElement().getTextContent().trim()
            );
        } catch (Exception e) {
            log.warn("XMP diff failed: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Validates that both series name AND series number are present and valid.
     * A series name without a number (or vice versa) is broken/incomplete data and should not be written.
     */
    private boolean hasValidSeries(BookMetadataEntity metadata, MetadataClearFlags clear) {
        // If clearing series, don't write it
        if (clear != null && (clear.isSeriesName() || clear.isSeriesNumber())) {
            return false;
        }
        
        // Check if either field is locked - if so, respect the lock
        if (Boolean.TRUE.equals(metadata.getSeriesNameLocked()) || Boolean.TRUE.equals(metadata.getSeriesNumberLocked())) {
            return false;
        }
        
        // Both name AND number must be valid
        return metadata.getSeriesName() != null 
                && !metadata.getSeriesName().isBlank()
                && metadata.getSeriesNumber() != null 
                && metadata.getSeriesNumber() > 0;
    }

    /**
     * Formats series number nicely: "22" for whole numbers, "1.5" for decimals.
     * Avoids unnecessary ".00" suffix.
     */
    private String formatSeriesNumber(Float number) {
        if (number == null) return "0";
        
        // If it's a whole number, don't show decimal places
        if (number % 1 == 0) {
            return String.valueOf(number.intValue());
        }
        
        // For decimals, show up to 2 decimal places but trim trailing zeros
        String formatted = String.format(Locale.US, "%.2f", number);
        // Remove trailing zeros after decimal point: "1.50" -> "1.5"
        formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        return formatted;
    }

    /**
     * Normalizes Goodreads ID to extract just the numeric part.
     * Goodreads URLs/IDs can be in formats like:
     * - "52555538" (just ID)
     * - "52555538-dead-simple-python" (ID with slug)
     * The slug can change but the numeric ID is stable.
     */
    private String normalizeGoodreadsId(String goodreadsId) {
        if (goodreadsId == null || goodreadsId.isBlank()) {
            return null;
        }
        
        // Extract numeric ID from slug format "12345678-book-title"
        int dashIndex = goodreadsId.indexOf('-');
        if (dashIndex > 0) {
            String numericPart = goodreadsId.substring(0, dashIndex);
            // Validate it's actually numeric
            if (numericPart.matches("\\d+")) {
                return numericPart;
            }
        }
        
        // Already just the ID, or return as-is if it's all numeric
        return goodreadsId.matches("\\d+") ? goodreadsId : goodreadsId;
    }

}
