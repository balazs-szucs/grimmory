package org.booklore.service;

import org.booklore.model.dto.BookMetadata;
import org.booklore.service.metadata.extractor.CbxMetadataExtractor;
import org.booklore.util.VipsImageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("org.booklore.service.ArchiveService#isAvailable")
@ExtendWith(MockitoExtension.class)
class CbxArchiveIntegrationTest {

    @Mock
    private VipsImageService vipsImageService;

    @Test
    void metadataExtractor_extractsCoverAndMetadataFromCbz(@TempDir Path tempDir) throws Exception {
        org.mockito.Mockito.when(vipsImageService.canDecode(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(true);

        Path cbzPath = tempDir.resolve("sample.cbz");
        createCbz(cbzPath);

        CbxMetadataExtractor extractor = new CbxMetadataExtractor(new ArchiveService(), vipsImageService);

        byte[] coverBytes = extractor.extractCover(cbzPath.toFile());
        BookMetadata metadata = extractor.extractMetadata(cbzPath.toFile());

        assertThat(coverBytes).isNotNull();
        assertThat(coverBytes.length).isGreaterThan(0);
        assertThat(ImageIO.read(new ByteArrayInputStream(coverBytes))).isNotNull();
        assertThat(metadata.getTitle()).isEqualTo("Sandman Midnight Theatre");
        assertThat(metadata.getSeriesName()).isEqualTo("Sandman Presents");
        assertThat(metadata.getSeriesNumber()).isEqualTo(1.0f);
    }

    private void createCbz(Path cbzPath) throws Exception {
        byte[] coverBytes = createMinimalJpeg();
        String comicInfo = """
                <?xml version=\"1.0\" encoding=\"utf-8\"?>
                <ComicInfo>
                  <Title>Sandman Midnight Theatre</Title>
                  <Series>Sandman Presents</Series>
                  <Number>1</Number>
                  <Pages>
                    <Page Image=\"0\" Type=\"FrontCover\" ImageFile=\"cover.jpg\"/>
                  </Pages>
                </ComicInfo>
                """;

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(cbzPath))) {
            zipOutputStream.putNextEntry(new ZipEntry("ComicInfo.xml"));
            zipOutputStream.write(comicInfo.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();

            zipOutputStream.putNextEntry(new ZipEntry("cover.jpg"));
            zipOutputStream.write(coverBytes);
            zipOutputStream.closeEntry();

            zipOutputStream.putNextEntry(new ZipEntry("page001.jpg"));
            zipOutputStream.write(coverBytes);
            zipOutputStream.closeEntry();
        }
    }

    private byte[] createMinimalJpeg() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00FF00);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", outputStream);
        return outputStream.toByteArray();
    }
}