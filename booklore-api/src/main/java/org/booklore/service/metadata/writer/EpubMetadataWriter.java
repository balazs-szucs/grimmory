package org.booklore.service.metadata.writer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.SecureXmlUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.booklore.util.MimeDetector;
import org.grimmory.epub4j.native_parsing.NativeArchive;
import org.grimmory.epub4j.native_parsing.NativeImageProcessor;

@Slf4j
@Component
@RequiredArgsConstructor
public class EpubMetadataWriter implements MetadataWriter {

    private static final String OPF_NS = "http://www.idpf.org/2007/opf";
    private static final String DC_NS = "http://purl.org/dc/elements/1.1/";
    private static final Map<String, String> LANGUAGE_NAME_TO_CODE;
    private static final Set<String> ISO_LANGUAGES;
    private static final DateTimeFormatter EPUB_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    static {
        Map<String, String> map = new HashMap<>();
        for (Locale locale : Locale.getAvailableLocales()) {
            String language = locale.getLanguage();
            if (language.isEmpty()) continue;
            
            // Map English name of the language
            map.putIfAbsent(locale.getDisplayLanguage(Locale.ENGLISH).toLowerCase(), language);
            // Map native name of the language
            map.putIfAbsent(locale.getDisplayLanguage(locale).toLowerCase(), language);
        }
        LANGUAGE_NAME_TO_CODE = Collections.unmodifiableMap(map);

        Set<String> langs = new HashSet<>();
        for (String iso : Locale.getISOLanguages()) {
            langs.add(iso.toLowerCase());
        }
        ISO_LANGUAGES = Collections.unmodifiableSet(langs);
    }

    private final AppSettingService appSettingService;

    @Override
    public void saveMetadataToFile(File epubFile, BookMetadataEntity metadata, String thumbnailUrl, MetadataClearFlags clear) {
        if (!shouldSaveMetadataToFile(epubFile)) {
            return;
        }

        File backupFile = new File(epubFile.getParentFile(), epubFile.getName() + ".bak");
        try {
            Files.copy(epubFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            log.warn("Failed to create backup of EPUB {}: {}", epubFile.getName(), ex.getMessage());
            return;
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("epub_edit_" + UUID.randomUUID());
            extractZipToDirectory(epubFile, tempDir);

            File opfFile = findOpfFile(tempDir.toFile());
            if (opfFile == null) {
                log.warn("Could not locate OPF file in EPUB");
                return;
            }

            DocumentBuilder builder = SecureXmlUtils.createSecureDocumentBuilder(true);
            Document opfDoc = builder.parse(opfFile);

            NodeList metadataList = opfDoc.getElementsByTagNameNS(OPF_NS, "metadata");
            Element metadataElement = (Element) metadataList.item(0);

            boolean[] hasChanges = {false};
            MetadataCopyHelper helper = new MetadataCopyHelper(metadata);

            helper.copyTitle(clear != null && clear.isTitle(), val -> {
                replaceAndTrackChange(opfDoc, metadataElement, "title", DC_NS, val, hasChanges);
                if (StringUtils.isNotBlank(metadata.getSubtitle())) {
                    addSubtitleToTitle(metadataElement, opfDoc, metadata.getSubtitle());
                }
            });
            helper.copyDescription(clear != null && clear.isDescription(), val -> replaceAndTrackChange(opfDoc, metadataElement, "description", DC_NS, val, hasChanges));
            helper.copyPublisher(clear != null && clear.isPublisher(), val -> replaceAndTrackChange(opfDoc, metadataElement, "publisher", DC_NS, val, hasChanges));
            helper.copyPublishedDate(clear != null && clear.isPublishedDate(), val -> replaceAndTrackChange(opfDoc, metadataElement, "date", DC_NS, val != null ? val.toString() : null, hasChanges));
            helper.copyLanguage(clear != null && clear.isLanguage(), val -> {
                String normalizedLang = normalizeLanguage(val);
                replaceAndTrackChange(opfDoc, metadataElement, "language", DC_NS, normalizedLang, hasChanges);
            });

            helper.copyAuthors(clear != null && clear.isAuthors(), names -> {
                removeCreatorsByRole(metadataElement, "");
                removeCreatorsByRole(metadataElement, "aut");
                if (names != null) {
                    for (String name : names) {
                        String[] parts = name.split(" ", 2);
                        String first = parts.length > 1 ? parts[0] : "";
                        String last = parts.length > 1 ? parts[1] : parts[0];
                        String fileAs = last + ", " + first;
                        metadataElement.appendChild(createCreatorElement(opfDoc, metadataElement, name, fileAs, "aut"));
                    }
                }
                hasChanges[0] = true;
            });

            helper.copyCategories(clear != null && clear.isCategories(), categories -> {
                removeElementsByTagNameNS(metadataElement, DC_NS, "subject");
                if (categories != null) {
                    for (String cat : categories.stream().map(String::trim).distinct().toList()) {
                        metadataElement.appendChild(createSubjectElement(opfDoc, cat));
                    }
                }
                hasChanges[0] = true;
            });

            helper.copySeriesName(clear != null && clear.isSeriesName(), val -> {
                replaceBelongsToCollection(metadataElement, opfDoc, metadata.getSeriesName(), metadata.getSeriesNumber(), hasChanges);
            });

            helper.copySeriesNumber(clear != null && clear.isSeriesNumber(), val -> {
                replaceBelongsToCollection(metadataElement, opfDoc, metadata.getSeriesName(), metadata.getSeriesNumber(), hasChanges);
            });

            helper.copyIsbn13(clear != null && clear.isIsbn13(), val -> {
                removeIdentifierByUrn(metadataElement, "isbn");
                if (val != null && !val.isBlank()) {
                    metadataElement.appendChild(createIdentifierElement(opfDoc, "isbn", val));
                }
                hasChanges[0] = true;
            });
            helper.copyIsbn10(clear != null && clear.isIsbn10(), val -> {
                if (val != null && !val.isBlank()) {
                    metadataElement.appendChild(createIdentifierElement(opfDoc, "isbn", val));
                }
                hasChanges[0] = true;
            });
            helper.copyAsin(clear != null && clear.isAsin(), val -> {
                removeIdentifierByUrn(metadataElement, "amazon");
                if (val != null && !val.isBlank()) {
                    metadataElement.appendChild(createIdentifierElement(opfDoc, "amazon", val));
                }
                hasChanges[0] = true;
            });
            helper.copyGoodreadsId(clear != null && clear.isGoodreadsId(), val -> {
                removeIdentifierByUrn(metadataElement, "goodreads");
                if (val != null && !val.isBlank()) {
                    metadataElement.appendChild(createIdentifierElement(opfDoc, "goodreads", val));
                }
                hasChanges[0] = true;
            });
            helper.copyGoogleId(clear != null && clear.isGoogleId(), val -> {
                removeIdentifierByUrn(metadataElement, "google");
                if (val != null && !val.isBlank()) {
                    metadataElement.appendChild(createIdentifierElement(opfDoc, "google", val));
                }
                hasChanges[0] = true;
            });
            helper.copyComicvineId(clear != null && clear.isComicvineId(), val -> {
                removeIdentifierByUrn(metadataElement, "comicvine");
                if (val != null && !val.isBlank()) {
                    metadataElement.appendChild(createIdentifierElement(opfDoc, "comicvine", val));
                }
                hasChanges[0] = true;
            });
            helper.copyHardcoverId(clear != null && clear.isHardcoverId(), val -> {
                removeIdentifierByUrn(metadataElement, "hardcover");
                if (val != null && !val.isBlank()) {
                    metadataElement.appendChild(createIdentifierElement(opfDoc, "hardcover", val));
                }
                hasChanges[0] = true;
            });
            helper.copyHardcoverBookId(clear != null && clear.isHardcoverBookId(), val -> {
                removeIdentifierByUrn(metadataElement, "hardcoverbook");
                if (val != null && !val.isBlank()) {
                    metadataElement.appendChild(createIdentifierElement(opfDoc, "hardcoverbook", val));
                }
                hasChanges[0] = true;
            });
            helper.copyLubimyczytacId(clear != null && clear.isLubimyczytacId(), val -> {
                removeIdentifierByUrn(metadataElement, "lubimyczytac");
                if (val != null && !val.isBlank()) {
                    metadataElement.appendChild(createIdentifierElement(opfDoc, "lubimyczytac", val));
                }
                hasChanges[0] = true;
            });
            helper.copyRanobedbId(clear != null && clear.isRanobedbId(), val -> {
                removeIdentifierByUrn(metadataElement, "ranobedb");
                if (val != null && !val.isBlank()) {
                    metadataElement.appendChild(createIdentifierElement(opfDoc, "ranobedb", val));
                }
                hasChanges[0] = true;
            });

            if (StringUtils.isNotBlank(thumbnailUrl)) {
                byte[] coverData = loadImage(thumbnailUrl);
                if (coverData != null) {
                    applyCoverImageToEpub(tempDir, opfDoc, coverData);
                    hasChanges[0] = true;
                }
            }

            if (!hasChanges[0] && hasBookloreMetadataChanges(metadataElement, metadata)) {
                hasChanges[0] = true;
            }

            if (hasChanges[0]) {
                addBookloreMetadata(metadataElement, opfDoc, metadata);
                cleanupCalibreArtifacts(metadataElement, opfDoc);
                organizeMetadataElements(metadataElement);
                removeEmptyTextNodes(opfDoc);
                Transformer transformer = TransformerFactory.newInstance().newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                transformer.transform(new DOMSource(opfDoc), new StreamResult(opfFile));

                File tempEpub = new File(epubFile.getParentFile(), epubFile.getName() + ".tmp");
                createEpubZipFromDirectory(tempDir, tempEpub.toPath());

                if (!epubFile.delete()) throw new IOException("Could not delete original EPUB");
                if (!tempEpub.renameTo(epubFile)) throw new IOException("Could not rename temp EPUB");

                log.info("Metadata updated in EPUB: {}", epubFile.getName());
            } else {
                log.info("No changes detected. Skipping EPUB write for: {}", epubFile.getName());
            }
        } catch (Exception e) {
            log.warn("Failed to write metadata to EPUB file {}: {}", epubFile.getName(), e.getMessage(), e);
            if (backupFile.exists()) {
                try {
                    Files.copy(backupFile.toPath(), epubFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    log.info("Restored EPUB from backup: {}", epubFile.getName());
                } catch (IOException io) {
                    log.error("Failed to restore EPUB from backup for {}: {}", epubFile.getName(), io.getMessage(), io);
                }
            }
        } finally {
            if (tempDir != null) {
                deleteDirectoryRecursively(tempDir);
            }
            if (backupFile.exists()) {
                try {
                    Files.delete(backupFile.toPath());
                } catch (IOException ex) {
                    log.warn("Failed to delete backup for {}: {}", epubFile.getName(), ex.getMessage());
                }
            }
        }
    }

    private void updateIdentifier(Element metadataElement, Document opfDoc, String scheme, String idValue, boolean[] hasChanges) {
        removeIdentifierByScheme(metadataElement, scheme);
        if (idValue != null && !idValue.isBlank()) {
            metadataElement.appendChild(createIdentifierElement(opfDoc, scheme, idValue));
        }
        hasChanges[0] = true;
    }

    private void replaceAndTrackChange(Document doc, Element parent, String tag, String ns, String val, boolean[] flag) {
        if (replaceElementText(doc, parent, tag, ns, val, false)) flag[0] = true;
    }

    private void replaceMetaElement(Element metadataElement, Document doc, String name, String newVal, boolean[] flag) {
        String existing = getMetaContentByName(metadataElement, name);
        if (!Objects.equals(existing, newVal)) {
            removeMetaByName(metadataElement, name);
            if (newVal != null) metadataElement.appendChild(createMetaElement(doc, name, newVal));
            flag[0] = true;
        }
    }

    private boolean replaceElementText(Document doc, Element parent, String tagName, String namespaceURI, String newValue, boolean restoreMode) {
        NodeList nodes = parent.getElementsByTagNameNS(namespaceURI, tagName);
        String currentValue = null;
        List<Node> preservedAttributes = new ArrayList<>();

        if (nodes.getLength() > 0) {
            Element existing = (Element) nodes.item(0);
            currentValue = existing.getTextContent();
            
            // Preserve all attributes of the first existing element
            org.w3c.dom.NamedNodeMap attrMap = existing.getAttributes();
            for (int i = 0; i < attrMap.getLength(); i++) {
                preservedAttributes.add(attrMap.item(i));
            }
        }

        boolean changed = !Objects.equals(currentValue, newValue);

        for (int i = nodes.getLength() - 1; i >= 0; i--) {
            parent.removeChild(nodes.item(i));
        }

        if (newValue != null) {
            Element newElem = doc.createElementNS(namespaceURI, tagName);
            newElem.setPrefix("dc");
            newElem.setTextContent(newValue);
            // Restore preserved attributes with namespace support
            for (Node attr : preservedAttributes) {
                if (attr.getNamespaceURI() != null) {
                    newElem.setAttributeNS(attr.getNamespaceURI(), attr.getNodeName(), attr.getNodeValue());
                } else {
                    newElem.setAttribute(attr.getNodeName(), attr.getNodeValue());
                }
            }
            parent.appendChild(newElem);
        } else if (restoreMode) {
            changed = true;
        }

        return changed;
    }


    public void replaceCoverImageFromBytes(BookEntity bookEntity, byte[] file) {
        if (!shouldSaveMetadataToFile(bookEntity.getFullFilePath().toFile())) {
            return;
        }
        if (file == null || file.length == 0) {
            log.warn("Cover update failed: empty or null byte array.");
            return;
        }

        replaceCoverImageInternal(bookEntity, file, "byte array");
    }

    public void replaceCoverImageFromUpload(BookEntity bookEntity, MultipartFile multipartFile) {
        if (!shouldSaveMetadataToFile(bookEntity.getFullFilePath().toFile())) {
            return;
        }
        if (multipartFile == null || multipartFile.isEmpty()) {
            log.warn("Cover upload failed: empty or null file.");
            return;
        }

        try {
            byte[] coverData = multipartFile.getBytes();
            replaceCoverImageInternal(bookEntity, coverData, "upload");
        } catch (IOException e) {
            log.warn("Failed to read uploaded cover image: {}", e.getMessage(), e);
        }
    }

    @Override
    public void replaceCoverImageFromUrl(BookEntity bookEntity, String url) {
        if (!shouldSaveMetadataToFile(bookEntity.getFullFilePath().toFile())) {
            return;
        }
        if (url == null || url.isBlank()) {
            log.warn("Cover update via URL failed: empty or null URL.");
            return;
        }

        byte[] coverData = loadImage(url);
        if (coverData == null) {
            log.warn("Failed to load image from URL: {}", url);
            return;
        }

        replaceCoverImageInternal(bookEntity, coverData, "URL");
    }

    private void replaceCoverImageInternal(BookEntity bookEntity, byte[] coverData, String source) {
        Path tempDir = null;
        File backupFile = null;
        try {
            File epubFile = new File(bookEntity.getFullFilePath().toUri());

            backupFile = new File(epubFile.getParentFile(), epubFile.getName() + ".bak");
            Files.copy(epubFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            tempDir = Files.createTempDirectory("epub_cover_" + UUID.randomUUID());

            extractZipToDirectory(epubFile, tempDir);

            File opfFile = findOpfFile(tempDir.toFile());
            if (opfFile == null) {
                log.warn("OPF file not found in EPUB: {}", epubFile.getName());
                return;
            }

            DocumentBuilder builder = SecureXmlUtils.createSecureDocumentBuilder(true);
            Document opfDoc = builder.parse(opfFile);

            applyCoverImageToEpub(tempDir, opfDoc, coverData);
            
            // EPUB 3: Update modified timestamp
            NodeList metadataList = opfDoc.getElementsByTagNameNS(OPF_NS, "metadata");
            if (metadataList.getLength() > 0) {
                Element metadataElement = (Element) metadataList.item(0);
                if (isEpub3(opfDoc)) {
                    removeMetaByProperty(metadataElement, "dcterms:modified");
                    removeMetaByName(metadataElement, "calibre:timestamp");

                    Element modified = opfDoc.createElementNS(OPF_NS, "meta");
                    modified.setAttribute("property", "dcterms:modified");
                    modified.setTextContent(ZonedDateTime.now().format(EPUB_DATE_FORMATTER));
                    metadataElement.appendChild(modified);
                }
            }

            removeEmptyTextNodes(opfDoc);
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(opfDoc), new StreamResult(opfFile));

            File tempEpub = new File(epubFile.getParentFile(), epubFile.getName() + ".tmp");
            createEpubZipFromDirectory(tempDir, tempEpub.toPath());

            if (!epubFile.delete()) throw new IOException("Could not delete original EPUB");
            if (!tempEpub.renameTo(epubFile)) throw new IOException("Could not rename temp EPUB");

            log.info("Cover image updated in EPUB from {}: {}", source, epubFile.getName());

        } catch (Exception e) {
            log.warn("Failed to update EPUB cover image from {}: {}", source, e.getMessage(), e);
            if (backupFile != null && backupFile.exists()) {
                try {
                    File epubFile = new File(bookEntity.getFullFilePath().toUri());
                    Files.copy(backupFile.toPath(), epubFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    log.info("Restored EPUB from backup: {}", epubFile.getName());
                } catch (IOException io) {
                    log.error("Failed to restore EPUB from backup: {}", io.getMessage(), io);
                }
            }
        } finally {
            if (tempDir != null) {
                deleteDirectoryRecursively(tempDir);
            }
            if (backupFile != null && backupFile.exists()) {
                try {
                    Files.delete(backupFile.toPath());
                } catch (IOException ex) {
                    log.warn("Failed to delete backup: {}", ex.getMessage());
                }
            log.warn("Failed to update EPUB cover image from {}: {}", source, e.getMessage(), e);
            if (backupFile != null && backupFile.exists()) {
                try {
                    File epubFile = new File(bookEntity.getFullFilePath().toUri());
                    Files.copy(backupFile.toPath(), epubFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    log.info("Restored EPUB from backup: {}", epubFile.getName());
                } catch (IOException io) {
                    log.error("Failed to restore EPUB from backup: {}", io.getMessage(), io);
                }
            }
        } finally {
            if (tempDir != null) {
                deleteDirectoryRecursively(tempDir);
            }
            if (backupFile != null && backupFile.exists()) {
                try {
                    Files.delete(backupFile.toPath());
                } catch (IOException ex) {
                    log.warn("Failed to delete backup: {}", ex.getMessage());
                }
            }
        }
    }

    @Override
    public BookFileType getSupportedBookType() {
        return BookFileType.EPUB;
    }

    private void applyCoverImageToEpub(Path tempDir, Document opfDoc, byte[] coverData) throws IOException {
        NodeList manifestList = opfDoc.getElementsByTagNameNS(OPF_NS, "manifest");
        if (manifestList.getLength() == 0) {
            throw new IOException("No <manifest> element found in OPF document.");
        }

        Element manifest = (Element) manifestList.item(0);
        Element existingCoverItem = null;

        // First, try to find cover via metadata reference (EPUB 3 style)
        NodeList metadataList = opfDoc.getElementsByTagNameNS(OPF_NS, "metadata");
        if (metadataList.getLength() > 0) {
            Element metadataElement = (Element) metadataList.item(0);
            String coverItemId = getMetaContentByName(metadataElement, "cover");

            if (coverItemId != null && !coverItemId.isBlank()) {
                // Find the item with this id
                NodeList items = manifest.getElementsByTagNameNS(OPF_NS, "item");
                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    if (coverItemId.equals(item.getAttribute("id"))) {
                        existingCoverItem = item;
                        break;
                    }
                }
            }
        }

        // If not found, try looking for properties="cover-image" (EPUB 3)
        if (existingCoverItem == null) {
            NodeList items = manifest.getElementsByTagNameNS(OPF_NS, "item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String properties = item.getAttribute("properties");
                if (properties.contains("cover-image")) {
                    existingCoverItem = item;
                    break;
                }
            }
        }

        // If still not found, try common id values (EPUB 2 fallback)
        if (existingCoverItem == null) {
            NodeList items = manifest.getElementsByTagNameNS(OPF_NS, "item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String itemId = item.getAttribute("id");
                if ("cover-image".equals(itemId) || "cover".equals(itemId) || "coverimg".equals(itemId)) {
                    existingCoverItem = item;
                    break;
                }
            }
        }

        if (existingCoverItem == null) {
            throw new IOException("No cover item found in manifest");
        }

        String coverHref = existingCoverItem.getAttribute("href");
        String decodedCoverHref = URLDecoder.decode(coverHref, StandardCharsets.UTF_8);
        if (decodedCoverHref == null || decodedCoverHref.isBlank()) {
            throw new IOException("Cover item has no href attribute");
        }

        Path opfPath;
        try {
            opfPath = findOpfPath(tempDir);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse container.xml to locate OPF path", e);
        }

        Path opfDir = opfPath.getParent();
        Path coverFilePath = opfDir.resolve(decodedCoverHref).normalize();

        // Detect media type and update manifest
        String mediaType = detectMediaType(coverData);
        if (mediaType != null) {
            existingCoverItem.setAttribute("media-type", mediaType);
            
            // If the extension doesn't match the media type, update the href
            String expectedExtension = getExtensionForMediaType(mediaType);
            if (expectedExtension != null) {
                String currentHref = existingCoverItem.getAttribute("href");
                int lastDot = currentHref.lastIndexOf('.');
                if (lastDot > 0) {
                    String currentExtension = currentHref.substring(lastDot);
                    if (!currentExtension.equalsIgnoreCase(expectedExtension)) {
                        // Delete old file if it exists and has different name
                        try {
                            Files.deleteIfExists(coverFilePath);
                        } catch (IOException e) {
                            log.warn("Failed to delete old cover file: {}", coverFilePath);
                        }

                        String newHref = currentHref.substring(0, lastDot) + expectedExtension;
                        existingCoverItem.setAttribute("href", newHref);
                        
                        String newDecodedHref = URLDecoder.decode(newHref, StandardCharsets.UTF_8);
                        coverFilePath = opfDir.resolve(newDecodedHref).normalize();
                    }
                }
            }
        }

        byte[] finalCoverData = coverData;
        if (NativeImageProcessor.isAvailable()) {
            if ("image/jpeg".equals(mediaType)) {
                try (NativeImageProcessor.ImageData optimized = NativeImageProcessor.compressJpeg(coverData, 85, true)) {
                    byte[] optimizedData = optimized.toByteArray();
                    if (optimizedData.length < coverData.length) {
                        finalCoverData = optimizedData;
                        log.debug("Native JPEG optimization reduced cover size from {} to {} bytes", coverData.length, finalCoverData.length);
                    } else {
                        log.debug("Native JPEG optimization did not reduce size ({} vs {}), keeping original", optimizedData.length, coverData.length);
                    }
                } catch (Exception e) {
                    log.warn("Native JPEG optimization failed: {}", e.getMessage());
                }
            } else if ("image/png".equals(mediaType)) {
                try (NativeImageProcessor.ImageData optimized = NativeImageProcessor.optimizePng(coverData, true)) {
                    byte[] optimizedData = optimized.toByteArray();
                    if (optimizedData.length < coverData.length) {
                        finalCoverData = optimizedData;
                        log.debug("Native PNG optimization reduced cover size from {} to {} bytes", coverData.length, finalCoverData.length);
                    } else {
                        log.debug("Native PNG optimization did not reduce size ({} vs {}), keeping original", optimizedData.length, coverData.length);
                    }
                } catch (Exception e) {
                    log.warn("Native PNG optimization failed: {}", e.getMessage());
                }
            }
        }

        Files.createDirectories(coverFilePath.getParent());
        Files.write(coverFilePath, finalCoverData);
    }

    private static final Set<String> SUPPORTED_EPUB_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml"
    );

    private String detectMediaType(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            String mimeType = MimeDetector.detect(new ByteArrayInputStream(data));
            
            // Ensure it's an image type supported by EPUB core media types
            if (mimeType != null && SUPPORTED_EPUB_IMAGE_TYPES.contains(mimeType)) {
                return mimeType;
            }
        } catch (IOException e) {
            log.warn("Failed to detect media type: {}", e.getMessage());
        }
        return null;
    }

    private String getExtensionForMediaType(String mediaType) {
        if (mediaType == null) return null;
        return switch (mediaType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> null;
        };
    }

    private String normalizeLanguage(String lang) {
        if (lang == null || lang.isBlank()) return lang;
        
        String trimmed = lang.trim();
        String lowerTrimmed = trimmed.toLowerCase();

        // 1. Check the static map for display names (e.g. "English" -> "en")
        String code = LANGUAGE_NAME_TO_CODE.get(lowerTrimmed);
        if (code != null) {
            return code;
        }

        // 2. Try as a BCP 47 language tag (e.g. "en-US", "zh-TW")
        Locale tagLocale = Locale.forLanguageTag(trimmed);
        String tagLang = tagLocale.getLanguage();
        if (!tagLang.isEmpty() && isIsoLanguage(tagLang)) {
            return tagLocale.toLanguageTag();
        }

        // 3. Fallback: return as-is
        return trimmed;
    }

    private boolean isIsoLanguage(String lang) {
        if (lang == null) return false;
        return ISO_LANGUAGES.contains(lang.toLowerCase());
    }

    private Path findOpfPath(Path tempDir) throws IOException, ParserConfigurationException, SAXException {
        Path containerXml = tempDir.resolve("META-INF/container.xml");
        if (!Files.exists(containerXml)) {
            throw new IOException("container.xml not found at expected location: " + containerXml);
        }

        DocumentBuilder builder = SecureXmlUtils.createSecureDocumentBuilder(false);
        Document containerDoc = builder.parse(containerXml.toFile());
        Node rootfile = containerDoc.getElementsByTagName("rootfile").item(0);
        if (rootfile == null) {
            throw new IOException("No <rootfile> found in container.xml");
        }

        String opfPath = ((Element) rootfile).getAttribute("full-path");
        if (opfPath.isBlank()) {
            throw new IOException("Missing or empty 'full-path' attribute in <rootfile>");
        }

        return tempDir.resolve(opfPath).normalize();
    }

    private File findOpfFile(File rootDir) {
        File[] matches = rootDir.listFiles(path -> path.isFile() && path.getName().endsWith(".opf"));
        if (matches != null && matches.length > 0) return matches[0];
        for (File file : Objects.requireNonNull(rootDir.listFiles())) {
            if (file.isDirectory()) {
                File child = findOpfFile(file);
                if (child != null) return child;
            }
        }
        return null;
    }

    private byte[] loadImage(String pathOrUrl) {
        try (InputStream stream = pathOrUrl.startsWith("http") ? URI.create(pathOrUrl).toURL().openStream() : new FileInputStream(pathOrUrl)) {
            return stream.readAllBytes();
        } catch (IOException e) {
            log.warn("Failed to load image from {}: {}", pathOrUrl, e.getMessage());
            return null;
        }
    }

    private void extractZipToDirectory(File zipSource, Path targetDir) throws IOException {
        try (NativeArchive archive = NativeArchive.open(zipSource.toPath())) {
            for (String name : archive.listEntries()) {
                Path entryPath = targetDir.resolve(name).normalize();
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("ZIP entry outside target directory: " + name);
                }

                if (name.endsWith("/")) {
                    Files.createDirectories(entryPath);
                    continue;
                }

                Files.createDirectories(entryPath.getParent());
                try (OutputStream out = Files.newOutputStream(entryPath)) {
                    archive.streamEntry(name, out);
                }
            }
        }
    }

    private void createEpubZipFromDirectory(Path sourceDir, Path targetZip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip))) {
            // EPUB spec requires mimetype to be the first entry in the ZIP, uncompressed (STORED)
            Path mimetypeFile = sourceDir.resolve("mimetype");
            if (Files.exists(mimetypeFile)) {
                byte[] mimetypeData = Files.readAllBytes(mimetypeFile);
                ZipEntry mimetypeEntry = new ZipEntry("mimetype");
                mimetypeEntry.setMethod(ZipEntry.STORED);
                mimetypeEntry.setSize(mimetypeData.length);
                mimetypeEntry.setCompressedSize(mimetypeData.length);
                CRC32 crc = new CRC32();
                crc.update(mimetypeData);
                mimetypeEntry.setCrc(crc.getValue());
                zos.putNextEntry(mimetypeEntry);
                zos.write(mimetypeData);
                zos.closeEntry();
            } else {
                log.warn("EPUB mimetype file not found in extracted directory — output may be spec-invalid");
            }

            try (var pathStream = Files.walk(sourceDir)) {
                pathStream
                    .filter(path -> !path.equals(sourceDir))
                    .filter(path -> !path.equals(mimetypeFile))
                    .sorted()
                    .forEach(path -> {
                        try {
                            String relativePath = sourceDir.relativize(path).toString().replace(File.separatorChar, '/');
                            if (Files.isDirectory(path)) {
                                zos.putNextEntry(new ZipEntry(relativePath + "/"));
                                zos.closeEntry();
                            } else {
                                zos.putNextEntry(new ZipEntry(relativePath));
                                Files.copy(path, zos);
                                zos.closeEntry();
                            }
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
            }
        }
    }

    private void removeMetaByName(Element metadataElement, String name) {
        NodeList metas = metadataElement.getElementsByTagNameNS("*", "meta");
        for (int i = metas.getLength() - 1; i >= 0; i--) {
            Element meta = (Element) metas.item(i);
            if (name.equals(meta.getAttribute("name"))) {
                metadataElement.removeChild(meta);
            }
        }
    }

    private void removeMetaByProperty(Element metadataElement, String property) {
        NodeList metas = metadataElement.getElementsByTagNameNS("*", "meta");
        for (int i = metas.getLength() - 1; i >= 0; i--) {
            Element meta = (Element) metas.item(i);
            if (property.equals(meta.getAttribute("property"))) {
                metadataElement.removeChild(meta);
            }
        }
    }

    private void removeMetaByRefines(Element metadataElement, String refines) {
        NodeList metas = metadataElement.getElementsByTagNameNS("*", "meta");
        for (int i = metas.getLength() - 1; i >= 0; i--) {
            Element meta = (Element) metas.item(i);
            if (refines.equals(meta.getAttribute("refines"))) {
                metadataElement.removeChild(meta);
            }
        }
    }

    private Element createMetaElement(Document doc, String name, String content) {
        Element meta = doc.createElementNS(doc.getDocumentElement().getNamespaceURI(), "meta");
        meta.setAttribute("name", name);
        meta.setAttribute("content", content);
        return meta;
    }

    private void removeIdentifierByScheme(Element metadataElement, String scheme) {
        NodeList identifiers = metadataElement.getElementsByTagNameNS("*", "identifier");
        for (int i = identifiers.getLength() - 1; i >= 0; i--) {
            Element idElement = (Element) identifiers.item(i);
            if (scheme.equalsIgnoreCase(idElement.getAttributeNS(OPF_NS, "scheme"))) {
                metadataElement.removeChild(idElement);
            }
        }
    }
    private void removeIdentifierByUrn(Element metadataElement, String urnScheme) {
        NodeList identifiers = metadataElement.getElementsByTagNameNS("*", "identifier");
        String urnPrefix = "urn:" + urnScheme.toLowerCase() + ":";
        String oldPrefix = urnScheme.toLowerCase() + ":";
        for (int i = identifiers.getLength() - 1; i >= 0; i--) {
            Element idElement = (Element) identifiers.item(i);
            String content = idElement.getTextContent().trim().toLowerCase();
            if (content.startsWith(urnPrefix) || content.startsWith(oldPrefix)) {
                metadataElement.removeChild(idElement);
            }
        }
    }

    private Element createIdentifierElement(Document doc, String scheme, String value) {
        Element id = doc.createElementNS("http://purl.org/dc/elements/1.1/", "identifier");
        id.setPrefix("dc");
        id.setTextContent("urn:" + scheme.toLowerCase() + ":" + value);
        return id;
    }

    private void removeElementsByTagNameNS(Element parent, String namespaceURI, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(namespaceURI, localName);
        for (int i = nodes.getLength() - 1; i >= 0; i--) {
            parent.removeChild(nodes.item(i));
        }
    }

    private void removeCreatorsByRole(Element metadataElement, String role) {
        NodeList creators = metadataElement.getElementsByTagNameNS("*", "creator");
        for (int i = creators.getLength() - 1; i >= 0; i--) {
            Element creatorElement = (Element) creators.item(i);
            String id = creatorElement.getAttribute("id");
            String creatorRole = creatorElement.getAttributeNS(OPF_NS, "role");
            if (StringUtils.isNotBlank(id) && StringUtils.isBlank(creatorRole)) {
                // Finds any matching role meta tags for this creator ID
                Element meta = getMetaElementByFilter(metadataElement, el -> ("role".equals(el.getAttribute("property")) && "#".concat(id).equals(el.getAttribute("refines"))));
                if (meta != null) {
                    creatorRole = meta.hasAttribute("content") ? meta.getAttribute("content").trim() : meta.getTextContent().trim();
                }
            }
            if (role.equalsIgnoreCase(creatorRole)) {
                metadataElement.removeChild(creatorElement);
                if (StringUtils.isNotBlank(id)) {
                    removeMetaByRefines(metadataElement, "#".concat(id));
                }
            }
        }
    }

    private Element createCreatorElement(Document doc, Element metadataElement, String fullName, String fileAs, String role) {
        Element creator = doc.createElementNS("http://purl.org/dc/elements/1.1/", "creator");
        creator.setPrefix("dc");
        creator.setTextContent(fullName);

        boolean isEpub3 = isEpub3(doc);

        if (isEpub3) {
            // EPUB3: use <meta refines="#id"> elements instead of opf: attributes
            String creatorId = "creator-" + UUID.randomUUID().toString().substring(0, 8);
            creator.setAttribute("id", creatorId);

            if (fileAs != null) {
                Element fileAsMeta = doc.createElementNS(OPF_NS, "meta");
                fileAsMeta.setAttribute("refines", "#" + creatorId);
                fileAsMeta.setAttribute("property", "file-as");
                fileAsMeta.setTextContent(fileAs);
                metadataElement.appendChild(fileAsMeta);
            }
            if (role != null) {
                Element roleMeta = doc.createElementNS(OPF_NS, "meta");
                roleMeta.setAttribute("refines", "#" + creatorId);
                roleMeta.setAttribute("property", "role");
                roleMeta.setAttribute("scheme", "marc:relators");
                roleMeta.setTextContent(role);
                metadataElement.appendChild(roleMeta);
            }
        } else {
            // EPUB2: use opf: attributes directly on dc:creator
            if (fileAs != null) {
                creator.setAttributeNS(OPF_NS, "opf:file-as", fileAs);
            }
            if (role != null) {
                creator.setAttributeNS(OPF_NS, "opf:role", role);
            }
        }
        return creator;
    }

    private Element createSubjectElement(Document doc, String subject) {
        Element subj = doc.createElementNS("http://purl.org/dc/elements/1.1/", "subject");
        subj.setPrefix("dc");
        subj.setTextContent(subject);
        return subj;
    }

    private Element getMetaElementByFilter(Element metadataElement, java.util.function.Predicate<Element> filter) {
        NodeList metas = metadataElement.getElementsByTagNameNS("*", "meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if (filter.test(meta)) {
                return meta;
            }
        }
        return null;
    }

    private String getMetaContentByName(Element metadataElement, String name) {
        Element meta = getMetaElementByFilter(metadataElement, el -> name.equals(el.getAttribute("name")));
        if (meta != null) {
            return meta.getAttribute("content");
        }
        return null;
    }

    private void deleteDirectoryRecursively(Path dir) {
        try (var pathStream = Files.walk(dir)) {
            pathStream
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete temp file/directory: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to clean up temporary directory: {}", dir, e);
        }
    }

    public boolean shouldSaveMetadataToFile(File epubFile) {
        MetadataPersistenceSettings.SaveToOriginalFile settings = appSettingService.getAppSettings().getMetadataPersistenceSettings().getSaveToOriginalFile();

        MetadataPersistenceSettings.FormatSettings epubSettings = settings.getEpub();
        if (epubSettings == null || !epubSettings.isEnabled()) {
            log.debug("EPUB metadata writing is disabled. Skipping: {}", epubFile.getName());
            return false;
        }

        long fileSizeInMb = epubFile.length() / (1024 * 1024);
        if (fileSizeInMb > epubSettings.getMaxFileSizeInMb()) {
            log.info("EPUB file {} ({} MB) exceeds max size limit ({} MB). Skipping metadata write.", epubFile.getName(), fileSizeInMb, epubSettings.getMaxFileSizeInMb());
            return false;
        }

        return true;
    }

    private void removeEmptyTextNodes(Document doc) {
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            NodeList emptyTextNodes = (NodeList) xpath.evaluate("//text()[normalize-space(.)='']", doc, XPathConstants.NODESET);

            for (int i = 0; i < emptyTextNodes.getLength(); i++) {
                Node emptyTextNode = emptyTextNodes.item(i);
                emptyTextNode.getParentNode().removeChild(emptyTextNode);
            }
        } catch (Exception e) {
            log.warn("Failed to remove empty text nodes", e);
        }
    }

    private boolean isEpub3(Document doc) {
        String version = doc.getDocumentElement().getAttribute("version");
        return version != null && version.trim().startsWith("3");
    }

    private boolean hasBookloreMetadataChanges(Element metadataElement, BookMetadataEntity metadata) {
        Map<String, String> existing = new TreeMap<>();
        NodeList metas = metadataElement.getElementsByTagNameNS("*", "meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            String property = meta.getAttribute("property");
            String name = meta.getAttribute("name");
            String key = property.startsWith("booklore:") ? property : (name.startsWith("booklore:") ? name : null);
            if (key != null) {
                String value = meta.getAttribute("content").isEmpty() ? meta.getTextContent() : meta.getAttribute("content");
                if (!isEffectivelyZeroOrBlank(value)) {
                    existing.put(key, value);
                }
            }
        }

        Map<String, String> expected = new TreeMap<>();
        if (StringUtils.isNotBlank(metadata.getSubtitle())) {
            expected.put("booklore:subtitle", metadata.getSubtitle());
        }
        if (metadata.getPageCount() != null && metadata.getPageCount() > 0) {
            expected.put("booklore:page_count", String.valueOf(metadata.getPageCount()));
        }
        if (metadata.getSeriesTotal() != null && metadata.getSeriesTotal() > 0) {
            expected.put("booklore:series_total", String.valueOf(metadata.getSeriesTotal()));
        }
        if (metadata.getAmazonRating() != null && metadata.getAmazonRating() > 0) {
            expected.put("booklore:amazon_rating", String.valueOf(metadata.getAmazonRating()));
        }
        if (metadata.getAmazonReviewCount() != null && metadata.getAmazonReviewCount() > 0) {
            expected.put("booklore:amazon_review_count", String.valueOf(metadata.getAmazonReviewCount()));
        }
        if (metadata.getGoodreadsRating() != null && metadata.getGoodreadsRating() > 0) {
            expected.put("booklore:goodreads_rating", String.valueOf(metadata.getGoodreadsRating()));
        }
        if (metadata.getGoodreadsReviewCount() != null && metadata.getGoodreadsReviewCount() > 0) {
            expected.put("booklore:goodreads_review_count", String.valueOf(metadata.getGoodreadsReviewCount()));
        }
        if (metadata.getHardcoverRating() != null && metadata.getHardcoverRating() > 0) {
            expected.put("booklore:hardcover_rating", String.valueOf(metadata.getHardcoverRating()));
        }
        if (metadata.getHardcoverReviewCount() != null && metadata.getHardcoverReviewCount() > 0) {
            expected.put("booklore:hardcover_review_count", String.valueOf(metadata.getHardcoverReviewCount()));
        }
        if (metadata.getLubimyczytacRating() != null && metadata.getLubimyczytacRating() > 0) {
            expected.put("booklore:lubimyczytac_rating", String.valueOf(metadata.getLubimyczytacRating()));
        }
        if (metadata.getRanobedbRating() != null && metadata.getRanobedbRating() > 0) {
            expected.put("booklore:ranobedb_rating", String.valueOf(metadata.getRanobedbRating()));
        }
        if (metadata.getMoods() != null && !metadata.getMoods().isEmpty()) {
            String moodsJson = "[" + metadata.getMoods().stream()
                .map(mood -> "\"" + mood.getName().replace("\"", "\\\"") + "\"")
                .sorted()
                .collect(Collectors.joining(", ")) + "]";
            expected.put("booklore:moods", moodsJson);
        }
        if (metadata.getTags() != null && !metadata.getTags().isEmpty()) {
            String tagsJson = "[" + metadata.getTags().stream()
                .map(tag -> "\"" + tag.getName().replace("\"", "\\\"") + "\"")
                .sorted()
                .collect(Collectors.joining(", ")) + "]";
            expected.put("booklore:tags", tagsJson);
        }
        if (metadata.getAgeRating() != null) {
            expected.put("booklore:age_rating", String.valueOf(metadata.getAgeRating()));
        }
        if (StringUtils.isNotBlank(metadata.getContentRating())) {
            expected.put("booklore:content_rating", metadata.getContentRating());
        }

        return !existing.equals(expected);
    }

    private static boolean isEffectivelyZeroOrBlank(String value) {
        if (value == null || value.isBlank()) return true;
        try {
            return Double.parseDouble(value) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void removeAllBookloreMetadata(Element metadataElement) {
        NodeList metas = metadataElement.getElementsByTagNameNS("*", "meta");
        for (int i = metas.getLength() - 1; i >= 0; i--) {
            Element meta = (Element) metas.item(i);
            String property = meta.getAttribute("property");
            String name = meta.getAttribute("name");
            if (property.startsWith("booklore:") || name.startsWith("booklore:")) {
                metadataElement.removeChild(meta);
            }
        }
    }

    private void replaceBelongsToCollection(Element metadataElement, Document doc, String seriesName, Float seriesNumber, boolean[] hasChanges) {
        boolean epub3 = isEpub3(doc);

        // Remove existing EPUB3 collection metas
        NodeList metas = metadataElement.getElementsByTagNameNS("*", "meta");
        for (int i = metas.getLength() - 1; i >= 0; i--) {
            Element meta = (Element) metas.item(i);
            String property = meta.getAttribute("property");
            String name = meta.getAttribute("name");
            if ("belongs-to-collection".equals(property) || "collection-type".equals(property) || "group-position".equals(property)) {
                String id = meta.getAttribute("id");
                metadataElement.removeChild(meta);
                if (StringUtils.isNotBlank(id)) {
                    removeMetaByRefines(metadataElement, "#" + id);
                }
            }
            // Also remove EPUB2-style series metas
            if ("calibre:series".equals(name) || "calibre:series_index".equals(name)) {
                metadataElement.removeChild(meta);
            }
        }
        
        if (StringUtils.isNotBlank(seriesName)) {
            if (epub3) {
                // EPUB3: use belongs-to-collection with refines
                String collectionId = "collection-" + UUID.randomUUID().toString().substring(0, 8);

                Element collectionMeta = doc.createElementNS(OPF_NS, "meta");
                collectionMeta.setAttribute("id", collectionId);
                collectionMeta.setAttribute("property", "belongs-to-collection");
                collectionMeta.setTextContent(seriesName);
                metadataElement.appendChild(collectionMeta);

                Element typeMeta = doc.createElementNS(OPF_NS, "meta");
                typeMeta.setAttribute("property", "collection-type");
                typeMeta.setAttribute("refines", "#" + collectionId);
                typeMeta.setTextContent("series");
                metadataElement.appendChild(typeMeta);

                if (seriesNumber != null && seriesNumber > 0) {
                    Element positionMeta = doc.createElementNS(OPF_NS, "meta");
                    positionMeta.setAttribute("property", "group-position");
                    positionMeta.setAttribute("refines", "#" + collectionId);
                    if (seriesNumber % 1.0f == 0) {
                        positionMeta.setTextContent(String.format("%.0f", seriesNumber));
                    } else {
                        positionMeta.setTextContent(String.valueOf(seriesNumber));
                    }
                    metadataElement.appendChild(positionMeta);
                }
            } else {
                // EPUB2: use calibre:series convention (widely supported by e-readers)
                Element seriesMeta = doc.createElementNS(doc.getDocumentElement().getNamespaceURI(), "meta");
                seriesMeta.setAttribute("name", "calibre:series");
                seriesMeta.setAttribute("content", seriesName);
                metadataElement.appendChild(seriesMeta);

                if (seriesNumber != null && seriesNumber > 0) {
                    Element indexMeta = doc.createElementNS(doc.getDocumentElement().getNamespaceURI(), "meta");
                    indexMeta.setAttribute("name", "calibre:series_index");
                    if (seriesNumber % 1.0f == 0) {
                        indexMeta.setAttribute("content", String.format("%.0f", seriesNumber));
                    } else {
                        indexMeta.setAttribute("content", String.valueOf(seriesNumber));
                    }
                    metadataElement.appendChild(indexMeta);
                }
            }
            
            hasChanges[0] = true;
        }
    }

    private void addSubtitleToTitle(Element metadataElement, Document doc, String subtitle) {
        boolean epub3 = isEpub3(doc);

        // Remove existing subtitle elements (both EPUB2 and EPUB3 forms)
        NodeList metas = metadataElement.getElementsByTagNameNS("*", "meta");
        for (int i = metas.getLength() - 1; i >= 0; i--) {
            Element meta = (Element) metas.item(i);
            String property = meta.getAttribute("property");
            String refines = meta.getAttribute("refines");
            if ("title-type".equals(property) && "subtitle".equals(meta.getTextContent())) {
                if (StringUtils.isNotBlank(refines)) {
                    NodeList titles = metadataElement.getElementsByTagNameNS(DC_NS, "title");
                    for (int j = titles.getLength() - 1; j >= 0; j--) {
                        Element title = (Element) titles.item(j);
                        if (("#" + title.getAttribute("id")).equals(refines)) {
                            metadataElement.removeChild(title);
                            break;
                        }
                    }
                }
                metadataElement.removeChild(meta);
            }
        }

        if (epub3) {
            // EPUB3: add subtitle as separate dc:title with title-type refinement
            String subtitleId = "subtitle-" + UUID.randomUUID().toString().substring(0, 8);
            Element subtitleElement = doc.createElementNS(DC_NS, "title");
            subtitleElement.setPrefix("dc");
            subtitleElement.setAttribute("id", subtitleId);
            subtitleElement.setTextContent(subtitle);
            metadataElement.appendChild(subtitleElement);

            Element typeMeta = doc.createElementNS(OPF_NS, "meta");
            typeMeta.setAttribute("refines", "#" + subtitleId);
            typeMeta.setAttribute("property", "title-type");
            typeMeta.setTextContent("subtitle");
            metadataElement.appendChild(typeMeta);
        }
        // EPUB2: subtitle is stored only via booklore:subtitle metadata (written in addBookloreMetadata).
        // No modification to dc:title is needed — this preserves round-trip fidelity.
    }

    private void addBookloreMetadata(Element metadataElement, Document doc, BookMetadataEntity metadata) {
        boolean epub3 = isEpub3(doc);

        if (epub3) {
            Element packageElement = doc.getDocumentElement();
            String existingPrefix = packageElement.getAttribute("prefix");
            String bookloreNamespace = "booklore: http://booklore.org/metadata/1.0/";

            if (!existingPrefix.contains("booklore:")) {
                if (existingPrefix.isEmpty()) {
                    packageElement.setAttribute("prefix", bookloreNamespace);
                } else {
                    packageElement.setAttribute("prefix", existingPrefix.trim() + " " + bookloreNamespace);
                }
            }
            
            // EPUB3: Ensure only one dcterms:modified exists and it is current
            removeMetaByProperty(metadataElement, "dcterms:modified");
            removeMetaByName(metadataElement, "calibre:timestamp");
            
            Element modified = doc.createElementNS(OPF_NS, "meta");
            modified.setAttribute("property", "dcterms:modified");
            modified.setTextContent(ZonedDateTime.now().format(EPUB_DATE_FORMATTER));
            metadataElement.appendChild(modified);
        }
        
        removeAllBookloreMetadata(metadataElement);
        
        if (StringUtils.isNotBlank(metadata.getSubtitle())) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "subtitle", metadata.getSubtitle(), epub3));
        }
        
        if (metadata.getPageCount() != null && metadata.getPageCount() > 0) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "page_count", String.valueOf(metadata.getPageCount()), epub3));
        }
        
        if (metadata.getSeriesTotal() != null && metadata.getSeriesTotal() > 0) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "series_total", String.valueOf(metadata.getSeriesTotal()), epub3));
        }
        
        if (metadata.getAmazonRating() != null && metadata.getAmazonRating() > 0) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "amazon_rating", String.valueOf(metadata.getAmazonRating()), epub3));
        }
        
        if (metadata.getAmazonReviewCount() != null && metadata.getAmazonReviewCount() > 0) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "amazon_review_count", String.valueOf(metadata.getAmazonReviewCount()), epub3));
        }
        
        if (metadata.getGoodreadsRating() != null && metadata.getGoodreadsRating() > 0) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "goodreads_rating", String.valueOf(metadata.getGoodreadsRating()), epub3));
        }
        
        if (metadata.getGoodreadsReviewCount() != null && metadata.getGoodreadsReviewCount() > 0) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "goodreads_review_count", String.valueOf(metadata.getGoodreadsReviewCount()), epub3));
        }
        
        if (metadata.getHardcoverRating() != null && metadata.getHardcoverRating() > 0) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "hardcover_rating", String.valueOf(metadata.getHardcoverRating()), epub3));
        }
        
        if (metadata.getHardcoverReviewCount() != null && metadata.getHardcoverReviewCount() > 0) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "hardcover_review_count", String.valueOf(metadata.getHardcoverReviewCount()), epub3));
        }
        
        if (metadata.getLubimyczytacRating() != null && metadata.getLubimyczytacRating() > 0) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "lubimyczytac_rating", String.valueOf(metadata.getLubimyczytacRating()), epub3));
        }
        
        if (metadata.getRanobedbRating() != null && metadata.getRanobedbRating() > 0) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "ranobedb_rating", String.valueOf(metadata.getRanobedbRating()), epub3));
        }
        
        if (metadata.getMoods() != null && !metadata.getMoods().isEmpty()) {
            String moodsJson = "[" + metadata.getMoods().stream()
                .map(mood -> "\"" + mood.getName().replace("\"", "\\\"") + "\"")
                .sorted()
                .collect(Collectors.joining(", ")) + "]";
            metadataElement.appendChild(createBookloreMetaElement(doc, "moods", moodsJson, epub3));
        }

        if (metadata.getTags() != null && !metadata.getTags().isEmpty()) {
            String tagsJson = "[" + metadata.getTags().stream()
                .map(tag -> "\"" + tag.getName().replace("\"", "\\\"") + "\"")
                .sorted()
                .collect(Collectors.joining(", ")) + "]";
            metadataElement.appendChild(createBookloreMetaElement(doc, "tags", tagsJson, epub3));
        }

        if (metadata.getAgeRating() != null) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "age_rating", String.valueOf(metadata.getAgeRating()), epub3));
        }

        if (StringUtils.isNotBlank(metadata.getContentRating())) {
            metadataElement.appendChild(createBookloreMetaElement(doc, "content_rating", metadata.getContentRating(), epub3));
        }
    }

    private Element createBookloreMetaElement(Document doc, String property, String value, boolean epub3) {
        if (epub3) {
            Element meta = doc.createElementNS(OPF_NS, "meta");
            meta.setAttribute("property", "booklore:" + property);
            meta.setTextContent(value);
            return meta;
        } else {
            // EPUB2: use name/content attribute form
            Element meta = doc.createElementNS(doc.getDocumentElement().getNamespaceURI(), "meta");
            meta.setAttribute("name", "booklore:" + property);
            meta.setAttribute("content", value);
            return meta;
        }
    }

    private void cleanupCalibreArtifacts(Element metadataElement, Document doc) {
        Element packageElement = doc.getDocumentElement();
        if (packageElement.hasAttribute("prefix")) {
            String prefix = packageElement.getAttribute("prefix");
            if (prefix.contains("calibre:")) {
                prefix = prefix.replaceAll("calibre:\\s*https?://[^\\s]+", "").trim();
                if (prefix.isEmpty()) {
                    packageElement.removeAttribute("prefix");
                } else {
                    packageElement.setAttribute("prefix", prefix);
                }
            }
        }
        
        if (metadataElement.hasAttribute("xmlns:calibre")) {
            metadataElement.removeAttribute("xmlns:calibre");
        }

        NodeList identifiers = metadataElement.getElementsByTagNameNS(DC_NS, "identifier");
        for (int i = identifiers.getLength() - 1; i >= 0; i--) {
            Element idElement = (Element) identifiers.item(i);
            String content = idElement.getTextContent().trim().toLowerCase();
            if (content.startsWith("calibre:") || content.startsWith("urn:calibre:")) {
                metadataElement.removeChild(idElement);
            }
        }
        
        NodeList contributors = metadataElement.getElementsByTagNameNS(DC_NS, "contributor");
        for (int i = contributors.getLength() - 1; i >= 0; i--) {
            Element contributor = (Element) contributors.item(i);
            String text = contributor.getTextContent().toLowerCase();
            if (text.contains("calibre")) {
                String id = contributor.getAttribute("id");
                metadataElement.removeChild(contributor);
                if (StringUtils.isNotBlank(id)) {
                    removeMetaByRefines(metadataElement, "#" + id);
                }
            }
        }
        
        NodeList metas = metadataElement.getElementsByTagNameNS("*", "meta");
        for (int i = metas.getLength() - 1; i >= 0; i--) {
            Element meta = (Element) metas.item(i);
            String property = meta.getAttribute("property");
            String name = meta.getAttribute("name");
            
            boolean isCalibreSeries = !isEpub3(doc) && ("calibre:series".equals(name) || "calibre:series_index".equals(name));
            if (!isCalibreSeries && (property.startsWith("calibre:") || name.startsWith("calibre:"))) {
                metadataElement.removeChild(meta);
            }
        }
    }

    private void organizeMetadataElements(Element metadataElement) {
        java.util.List<Element> identifiers = new java.util.ArrayList<>();
        java.util.List<Element> titles = new java.util.ArrayList<>();
        java.util.List<Element> creators = new java.util.ArrayList<>();
        java.util.List<Element> contributors = new java.util.ArrayList<>();
        java.util.List<Element> languages = new java.util.ArrayList<>();
        java.util.List<Element> dates = new java.util.ArrayList<>();
        java.util.List<Element> publishers = new java.util.ArrayList<>();
        java.util.List<Element> descriptions = new java.util.ArrayList<>();
        java.util.List<Element> subjects = new java.util.ArrayList<>();
        java.util.List<Element> otherDcElements = new java.util.ArrayList<>();
        java.util.List<Element> seriesMetas = new java.util.ArrayList<>();
        java.util.List<Element> bookloreMetas = new java.util.ArrayList<>();
        java.util.List<Element> modifiedMetas = new java.util.ArrayList<>();
        java.util.List<Element> otherMetas = new java.util.ArrayList<>();
        java.util.List<Element> linkElements = new java.util.ArrayList<>();
        java.util.List<Element> uncategorized = new java.util.ArrayList<>();

        // Collect IDs of elements that have refines pointing to them
        Set<String> allIds = new HashSet<>();
        Map<String, java.util.List<Element>> refinesMap = new HashMap<>();

        NodeList allChildren = metadataElement.getChildNodes();
        for (int i = 0; i < allChildren.getLength(); i++) {
            Node node = allChildren.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String id = elem.getAttribute("id");
            if (!id.isEmpty()) allIds.add(id);
        }

        // First pass: identify refines meta elements and build lookup map
        for (int i = 0; i < allChildren.getLength(); i++) {
            Node node = allChildren.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            if ("meta".equals(elem.getLocalName())) {
                String refines = elem.getAttribute("refines");
                if (refines != null && refines.startsWith("#") && allIds.contains(refines.substring(1))) {
                    refinesMap.computeIfAbsent(refines.substring(1), k -> new java.util.ArrayList<>()).add(elem);
                }
            }
        }

        // Second pass: categorize all elements
        Set<Element> handledRefines = new HashSet<>();
        for (int i = 0; i < allChildren.getLength(); i++) {
            Node node = allChildren.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String localName = elem.getLocalName();
            String ns = elem.getNamespaceURI();
            
            if (DC_NS.equals(ns)) {
                switch (localName) {
                    case "identifier" -> identifiers.add(elem);
                    case "title" -> titles.add(elem);
                    case "creator" -> creators.add(elem);
                    case "contributor" -> contributors.add(elem);
                    case "language" -> languages.add(elem);
                    case "date" -> dates.add(elem);
                    case "publisher" -> publishers.add(elem);
                    case "description" -> descriptions.add(elem);
                    case "subject" -> subjects.add(elem);
                    default -> otherDcElements.add(elem);
                }
            } else if ("meta".equals(localName)) {
                String refines = elem.getAttribute("refines");
                if (refines != null && refines.startsWith("#") && allIds.contains(refines.substring(1))) {
                    // This is a refines meta — it will be appended after its parent element
                    handledRefines.add(elem);
                    continue;
                }
                String property = elem.getAttribute("property");
                String name = elem.getAttribute("name");
                if (property.startsWith("booklore:") || name.startsWith("booklore:")) {
                    bookloreMetas.add(elem);
                } else if (property.equals("dcterms:modified") || property.equals("calibre:timestamp")) {
                    modifiedMetas.add(elem);
                } else if (property.equals("belongs-to-collection") || property.equals("collection-type") || property.equals("group-position")
                        || "calibre:series".equals(name) || "calibre:series_index".equals(name)) {
                    seriesMetas.add(elem);
                } else {
                    otherMetas.add(elem);
                }
            } else if ("link".equals(localName)) {
                linkElements.add(elem);
            } else {
                uncategorized.add(elem);
            }
        }
        
        while (metadataElement.hasChildNodes()) {
            metadataElement.removeChild(metadataElement.getFirstChild());
        }

        // Helper to append an element followed by any refines meta that reference it
        java.util.function.Consumer<Element> appendWithRefines = elem -> {
            metadataElement.appendChild(elem);
            String id = elem.getAttribute("id");
            if (!id.isEmpty()) {
                java.util.List<Element> refs = refinesMap.get(id);
                if (refs != null) {
                    refs.forEach(metadataElement::appendChild);
                }
            }
        };

        identifiers.forEach(appendWithRefines);
        titles.forEach(appendWithRefines);
        creators.forEach(appendWithRefines);
        contributors.forEach(appendWithRefines);
        languages.forEach(appendWithRefines);
        dates.forEach(appendWithRefines);
        publishers.forEach(appendWithRefines);
        descriptions.forEach(appendWithRefines);
        subjects.forEach(appendWithRefines);
        otherDcElements.forEach(appendWithRefines);
        seriesMetas.forEach(appendWithRefines);
        modifiedMetas.forEach(metadataElement::appendChild);
        otherMetas.forEach(metadataElement::appendChild);
        linkElements.forEach(metadataElement::appendChild);
        bookloreMetas.forEach(metadataElement::appendChild);
        uncategorized.forEach(metadataElement::appendChild);
    }
}
