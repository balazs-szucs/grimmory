package org.booklore.service.metadata.writer;

import com.adobe.epubcheck.api.EpubCheck;
import com.adobe.epubcheck.util.DefaultReportImpl;
import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.service.appsettings.AppSettingService;
import org.grimmory.epub4j.native_parsing.NativeImageProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EpubMetadataWriterValidationTest {

    private EpubMetadataWriter writer;
    private AppSettingService appSettingService;
    private BookEntity bookEntity;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        appSettingService = mock(AppSettingService.class);
        MetadataPersistenceSettings.FormatSettings epubSettings = MetadataPersistenceSettings.FormatSettings.builder()
                .enabled(true)
                .maxFileSizeInMb(100)
                .build();
        MetadataPersistenceSettings.SaveToOriginalFile saveToOriginalFile = MetadataPersistenceSettings.SaveToOriginalFile.builder()
                .epub(epubSettings)
                .build();
        MetadataPersistenceSettings metadataPersistenceSettings = new MetadataPersistenceSettings();
        metadataPersistenceSettings.setSaveToOriginalFile(saveToOriginalFile);

        AppSettings appSettings = mock(AppSettings.class);
        when(appSettings.getMetadataPersistenceSettings()).thenReturn(metadataPersistenceSettings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        writer = new EpubMetadataWriter(appSettingService);

        bookEntity = new BookEntity();
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(tempDir.toString());
        bookEntity.setLibraryPath(libraryPath);
        BookFileEntity primaryFile = new BookFileEntity();
        primaryFile.setBook(bookEntity);
        bookEntity.setBookFiles(Collections.singletonList(primaryFile));
        bookEntity.getPrimaryBookFile().setFileSubPath("");
        bookEntity.getPrimaryBookFile().setFileName("test.epub");
    }

    @Test
    @DisplayName("Should preserve title ID and pass epubcheck")
    void validate_titleIdPreservedAndValid() throws Exception {
        String opfContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title id="title">Original Title</dc:title>
                        <dc:language>en</dc:language>
                        <dc:identifier id="id">test-id</dc:identifier>
                        <meta property="dcterms:modified">2024-04-19T10:00:00Z</meta>
                        <meta property="title-type" refines="#title">main</meta>
                    </metadata>
                    <manifest>
                        <item id="text" href="index.html" media-type="application/xhtml+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="text"/>
                    </spine>
                </package>
                """;
        File epubFile = createFullEpub(opfContent, "title-id-valid.epub");

        BookMetadataEntity metadata = new BookMetadataEntity();
        metadata.setTitle("New Title");
        
        writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

        Document doc = parseOpf(epubFile);
        Element titleElem = (Element) doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title").item(0);
        assertThat(titleElem.getAttribute("id")).isEqualTo("title");

        validateEpub(epubFile);
    }

    @Test
    @DisplayName("Should update media-type and pass epubcheck when replacing cover")
    void validate_coverMediaTypeUpdatedAndValid() throws Exception {
        String opfContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Cover Test</dc:title>
                        <dc:language>en</dc:language>
                        <dc:identifier id="id">test-id</dc:identifier>
                        <meta property="dcterms:modified">2024-04-19T10:00:00Z</meta>
                        <meta name="cover" content="cover-image"/>
                    </metadata>
                    <manifest>
                        <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        <item id="text" href="index.html" media-type="application/xhtml+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="text"/>
                    </spine>
                </package>
                """;
        File epubFile = createFullEpub(opfContent, "cover-valid.epub");
        bookEntity.getPrimaryBookFile().setFileName(epubFile.getName());

        byte[] pngData = createMinimalValidImage("png");
        MockMultipartFile multipartFile = new MockMultipartFile("cover", "cover.png", "image/png", pngData);
        
        writer.replaceCoverImageFromUpload(bookEntity, multipartFile);

        Document doc = parseOpf(epubFile);
        NodeList items = doc.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "item");
        Element coverItem = null;
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if ("cover-image".equals(item.getAttribute("id"))) {
                coverItem = item;
                break;
            }
        }
        
        assertThat(coverItem).isNotNull();
        assertThat(coverItem.getAttribute("media-type")).isEqualTo("image/png");
        assertThat(coverItem.getAttribute("href")).endsWith(".png");

        validateEpub(epubFile);
    }

    @Test
    @DisplayName("Should normalize language from full name to ISO code")
    void validate_languageNormalized() throws Exception {
        String opfContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Language Test</dc:title>
                        <dc:language>en</dc:language>
                        <dc:identifier id="id">test-id</dc:identifier>
                        <meta property="dcterms:modified">2024-04-19T10:00:00Z</meta>
                    </metadata>
                    <manifest>
                        <item id="text" href="index.html" media-type="application/xhtml+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="text"/>
                    </spine>
                </package>
                """;
        File epubFile = createFullEpub(opfContent, "language-valid.epub");

        BookMetadataEntity metadata = new BookMetadataEntity();
        metadata.setTitle("Language Test");
        metadata.setLanguage("English"); // Should be normalized to "en"
        
        writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

        Document doc = parseOpf(epubFile);
        Element langElem = (Element) doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "language").item(0);
        assertThat(langElem.getTextContent()).isEqualTo("en");

        validateEpub(epubFile);
    }

    @Test
    @DisplayName("Should preserve BCP 47 language tags")
    void validate_languageBcp47Preserved() throws Exception {
        String opfContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>BCP 47 Test</dc:title>
                        <dc:language>en</dc:language>
                        <dc:identifier id="id">test-id</dc:identifier>
                        <meta property="dcterms:modified">2024-04-19T10:00:00Z</meta>
                    </metadata>
                    <manifest>
                        <item id="text" href="index.html" media-type="application/xhtml+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="text"/>
                    </spine>
                </package>
                """;
        File epubFile = createFullEpub(opfContent, "language-bcp47.epub");

        BookMetadataEntity metadata = new BookMetadataEntity();
        metadata.setTitle("BCP 47 Test");
        metadata.setLanguage("zh-TW");
        
        writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

        Document doc = parseOpf(epubFile);
        Element langElem = (Element) doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "language").item(0);
        String expected = Locale.forLanguageTag("zh-TW").toLanguageTag();
        assertThat(langElem.getTextContent()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should return original language string if unrecognized")
    void validate_languageUnrecognizedReturnsAsIs() throws Exception {
        String opfContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Unrecognized Lang Test</dc:title>
                        <dc:language>en</dc:language>
                        <dc:identifier id="id">test-id</dc:identifier>
                        <meta property="dcterms:modified">2024-04-19T10:00:00Z</meta>
                    </metadata>
                    <manifest>
                        <item id="text" href="index.html" media-type="application/xhtml+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="text"/>
                    </spine>
                </package>
                """;
        File epubFile = createFullEpub(opfContent, "language-unrecognized.epub");

        BookMetadataEntity metadata = new BookMetadataEntity();
        metadata.setTitle("Unrecognized Lang Test");
        metadata.setLanguage("Klingon");
        
        writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

        Document doc = parseOpf(epubFile);
        Element langElem = (Element) doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "language").item(0);
        assertThat(langElem.getTextContent()).isEqualTo("Klingon");
    }

    @Test
    @DisplayName("Should use NativeImageProcessor if available and reduction is achieved")
    @EnabledIf("isNativeImageProcessorAvailable")
    void validate_nativeImageOptimization() throws Exception {
        String opfContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Native Opt Test</dc:title>
                        <dc:language>en</dc:language>
                        <dc:identifier id="id">test-id</dc:identifier>
                        <meta property="dcterms:modified">2024-04-19T10:00:00Z</meta>
                        <meta name="cover" content="cover-image"/>
                    </metadata>
                    <manifest>
                        <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        <item id="text" href="index.html" media-type="application/xhtml+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="text"/>
                    </spine>
                </package>
                """;
        File epubFile = createFullEpub(opfContent, "native-opt.epub");
        bookEntity.getPrimaryBookFile().setFileName(epubFile.getName());

        // Use a 100x100 image to ensure there's something to optimize
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        byte[] largeJpg = baos.toByteArray();
        
        MockMultipartFile multipartFile = new MockMultipartFile("cover", "cover.jpg", "image/jpeg", largeJpg);
        
        writer.replaceCoverImageFromUpload(bookEntity, multipartFile);

        // We can't easily assert on the exact byte count here without mocking the native call,
        // but we verify it still produces a valid EPUB.
        validateEpub(epubFile);
    }

    boolean isNativeImageProcessorAvailable() {
        return NativeImageProcessor.isAvailable();
    }

    private void validateEpub(File epubFile) {
        DefaultReportImpl report = new DefaultReportImpl(epubFile.getName());
        EpubCheck checker = new EpubCheck(epubFile, report);
        checker.check();
        
        int errors = report.getErrorCount();
        int fatalErrors = report.getFatalErrorCount();
        
        if (errors + fatalErrors > 0) {
            System.err.println("EpubCheck failed for " + epubFile.getName());
        }
        assertThat(errors + fatalErrors).withFailMessage("EPUB validation failed with " + errors + " errors and " + fatalErrors + " fatal errors. Check console for details.").isEqualTo(0);
    }

    private File createFullEpub(String opfContent, String filename) throws IOException {
        File epubFile = tempDir.resolve(filename).toFile();

        String containerXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
                """;
        
        String htmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>Test</title></head>
                <body><h1>Test</h1></body>
                </html>
                """;

        String navContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>Navigation</title></head>
                <body>
                    <nav epub:type="toc">
                        <ol>
                            <li><a href="index.html">Test</a></li>
                        </ol>
                    </nav>
                </body>
                </html>
                """;

        String ncxContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                    <head><meta name="dtb:uid" content="test-id"/></head>
                    <docTitle><text>Test</text></docTitle>
                    <navMap>
                        <navPoint id="nav1" playOrder="1">
                            <navLabel><text>Test</text></navLabel>
                            <content src="index.html"/>
                        </navPoint>
                    </navMap>
                </ncx>
                """;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(containerXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(opfContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/index.html"));
            zos.write(htmlContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/nav.xhtml"));
            zos.write(navContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/toc.ncx"));
            zos.write(ncxContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            
            // Add a dummy cover.jpg if needed
            if (opfContent.contains("cover.jpg")) {
                zos.putNextEntry(new ZipEntry("OEBPS/cover.jpg"));
                zos.write(createMinimalValidImage("jpg"));
                zos.closeEntry();
            }
        }

        return epubFile;
    }

    private Document parseOpf(File epubFile) throws Exception {
        try (ZipFile zf = new ZipFile(epubFile)) {
            ZipEntry ze = zf.getEntry("OEBPS/content.opf");
            try (InputStream is = zf.getInputStream(ze)) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                DocumentBuilder builder = factory.newDocumentBuilder();
                return builder.parse(is);
            }
        }
    }

    private byte[] createMinimalValidImage(String format) throws IOException {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }
}
