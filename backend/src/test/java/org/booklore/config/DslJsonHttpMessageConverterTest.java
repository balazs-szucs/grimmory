package org.booklore.config;

import com.dslplatform.json.CompiledJson;
import org.booklore.context.KomgaCleanContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DslJsonHttpMessageConverterTest {

    private DslJsonHttpMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new DslJsonHttpMessageConverter();
    }

    @AfterEach
    void tearDown() {
        KomgaCleanContext.clear();
    }

    @CompiledJson
    public static class SimpleDto {
        private String title;
        private int pages;

        public SimpleDto() {}

        public SimpleDto(String title, int pages) {
            this.title = title;
            this.pages = pages;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public int getPages() {
            return pages;
        }

        public void setPages(int pages) {
            this.pages = pages;
        }
    }

    @CompiledJson
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public static class CamelCaseDto {
        private String bookTitle;
        private String authorName;

        public CamelCaseDto() {}

        public CamelCaseDto(String bookTitle, String authorName) {
            this.bookTitle = bookTitle;
            this.authorName = authorName;
        }

        public String getBookTitle() {
            return bookTitle;
        }

        public void setBookTitle(String bookTitle) {
            this.bookTitle = bookTitle;
        }

        public String getAuthorName() {
            return authorName;
        }

        public void setAuthorName(String authorName) {
            this.authorName = authorName;
        }
    }

    @CompiledJson
    public static class LockedDto {
        private String name;
        private boolean nameLock;

        public LockedDto() {}

        public LockedDto(String name, boolean nameLock) {
            this.name = name;
            this.nameLock = nameLock;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isNameLock() {
            return nameLock;
        }

        public void setNameLock(boolean nameLock) {
            this.nameLock = nameLock;
        }
    }

    @Test
    @DisplayName("Test supports media type Application JSON")
    void testSupports() {
        assertTrue(converter.supports(SimpleDto.class));
        assertTrue(converter.canRead(SimpleDto.class, MediaType.APPLICATION_JSON));
        assertTrue(converter.canWrite(SimpleDto.class, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Test reading simple JSON")
    void testReadSimpleJson() throws Exception {
        String json = "{\"title\":\"Grimmory\",\"pages\":350}";
        MockHttpInputMessage inputMessage = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));

        SimpleDto result = (SimpleDto) converter.readInternal(SimpleDto.class, inputMessage);

        assertNotNull(result);
        assertEquals("Grimmory", result.getTitle());
        assertEquals(350, result.getPages());
    }

    @Test
    @DisplayName("Test writing simple JSON")
    void testWriteSimpleJson() throws Exception {
        SimpleDto dto = new SimpleDto("Grimmory", 350);
        MockHttpOutputMessage outputMessage = new MockHttpOutputMessage();

        converter.writeInternal(dto, outputMessage);

        String json = outputMessage.getBodyAsString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"title\":\"Grimmory\""));
        assertTrue(json.contains("\"pages\":350"));
    }

    @Test
    @DisplayName("Test reading UpperCamelCase JSON")
    void testReadUpperCamelCaseJson() throws Exception {
        String json = "{\"BookTitle\":\"The Hobbit\",\"AuthorName\":\"J.R.R. Tolkien\"}";
        MockHttpInputMessage inputMessage = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));

        CamelCaseDto result = (CamelCaseDto) converter.readInternal(CamelCaseDto.class, inputMessage);

        assertNotNull(result);
        assertEquals("The Hobbit", result.getBookTitle());
        assertEquals("J.R.R. Tolkien", result.getAuthorName());
    }

    @Test
    @DisplayName("Test writing UpperCamelCase JSON")
    void testWriteUpperCamelCaseJson() throws Exception {
        CamelCaseDto dto = new CamelCaseDto("The Hobbit", "J.R.R. Tolkien");
        MockHttpOutputMessage outputMessage = new MockHttpOutputMessage();

        converter.writeInternal(dto, outputMessage);

        String json = outputMessage.getBodyAsString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"BookTitle\":\"The Hobbit\""));
        assertTrue(json.contains("\"AuthorName\":\"J.R.R. Tolkien\""));
    }

    @Test
    @DisplayName("Test Komga Clean Mode: Lock fields and null values are filtered out")
    void testKomgaCleanModeFiltering() throws Exception {
        LockedDto dto = new LockedDto("Locked Book", true);

        // Without clean mode
        MockHttpOutputMessage outputMessage1 = new MockHttpOutputMessage();
        converter.writeInternal(dto, outputMessage1);
        String json1 = outputMessage1.getBodyAsString(StandardCharsets.UTF_8);
        assertTrue(json1.contains("\"nameLock\":true"));

        // With clean mode
        KomgaCleanContext.setCleanMode(true);
        MockHttpOutputMessage outputMessage2 = new MockHttpOutputMessage();
        converter.writeInternal(dto, outputMessage2);
        String json2 = outputMessage2.getBodyAsString(StandardCharsets.UTF_8);
        assertFalse(json2.contains("nameLock"));
        assertTrue(json2.contains("\"name\":\"Locked Book\""));
    }
}
