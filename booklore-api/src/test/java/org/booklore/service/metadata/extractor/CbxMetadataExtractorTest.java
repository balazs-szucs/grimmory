package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.ComicMetadata;
import org.grimmory.comic4j.archive.ComicArchiveReader;
import org.grimmory.comic4j.domain.ComicInfo;
import org.grimmory.comic4j.domain.ReadingDirection;
import org.grimmory.comic4j.domain.YesNo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CbxMetadataExtractorTest {
    private CbxMetadataExtractor extractor;
    private MockedStatic<ComicArchiveReader> readerStatic;
    private static final Path CBZ_PATH = Path.of("test.cbz");

    @BeforeEach
    void setUp() {
        extractor = new CbxMetadataExtractor();
        readerStatic = Mockito.mockStatic(ComicArchiveReader.class);
    }

    @AfterEach
    void tearDown() {
        readerStatic.close();
    }

    private void mockComicInfo(ComicInfo info) {
        readerStatic.when(() -> ComicArchiveReader.readComicInfo(CBZ_PATH)).thenReturn(info);
    }

    private void mockNoComicInfo() {
        readerStatic.when(() -> ComicArchiveReader.readComicInfo(CBZ_PATH)).thenReturn(null);
    }

    private void mockReadException() {
        readerStatic.when(() -> ComicArchiveReader.readComicInfo(CBZ_PATH)).thenThrow(new IOException("test"));
        readerStatic.when(() -> ComicArchiveReader.extractCover(CBZ_PATH)).thenThrow(new IOException("test"));
    }

    private void mockCover(byte[] coverBytes) {
        readerStatic.when(() -> ComicArchiveReader.extractCover(CBZ_PATH)).thenReturn(coverBytes);
    }

    @Nested
    class ExtractMetadataFromZip {

        @Test
        void extractsTitleFromComicInfo() {
            var info = new ComicInfo();
            info.setTitle("Batman: Year One");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getTitle()).isEqualTo("Batman: Year One");
        }

        @Test
        void fallsBackToFilenameWhenTitleMissing() {
            var info = new ComicInfo();
            info.setPublisher("DC Comics");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getTitle()).isEqualTo("test");
        }

        @Test
        void fallsBackToFilenameWhenTitleBlank() {
            var info = new ComicInfo();
            info.setTitle("   ");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getTitle()).isEqualTo("test");
        }

        @Test
        void fallsBackToFilenameWhenNoComicInfo() {
            mockNoComicInfo();

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getTitle()).isEqualTo("test");
        }

        @Test
        void extractsPublisher() {
            var info = new ComicInfo();
            info.setPublisher("Marvel Comics");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getPublisher()).isEqualTo("Marvel Comics");
        }

        @Test
        void extractsDescriptionFromSummary() {
            var info = new ComicInfo();
            info.setSummary("A dark tale of vengeance.");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getDescription()).isEqualTo("A dark tale of vengeance.");
        }

        @Test
        void returnsNullDescriptionWhenSummaryBlank() {
            var info = new ComicInfo();
            info.setSummary("   ");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getDescription()).isNull();
        }

        @Test
        void extractsLanguageISO() {
            var info = new ComicInfo();
            info.setLanguageISO("en");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getLanguage()).isEqualTo("en");
        }

        @Test
        void returnsMetadataForCorruptFile() {
            mockReadException();

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getTitle()).isEqualTo("test");
        }
    }

    @Nested
    class SeriesAndNumberParsing {

        @Test
        void extractsSeriesName() {
            var info = new ComicInfo();
            info.setSeries("The Sandman");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getSeriesName()).isEqualTo("The Sandman");
        }

        @Test
        void extractsSeriesNumberAsFloat() {
            var info = new ComicInfo();
            info.setNumber("3.5");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getSeriesNumber()).isEqualTo(3.5f);
        }

        @Test
        void extractsWholeSeriesNumber() {
            var info = new ComicInfo();
            info.setNumber("12");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getSeriesNumber()).isEqualTo(12f);
        }

        @Test
        void handlesInvalidSeriesNumber() {
            var info = new ComicInfo();
            info.setNumber("abc");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getSeriesNumber()).isNull();
        }

        @Test
        void extractsSeriesTotal() {
            var info = new ComicInfo();
            info.setCount(75);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getSeriesTotal()).isEqualTo(75);
        }

        @Test
        void extractsPageCount() {
            var info = new ComicInfo();
            info.setPageCount(32);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getPageCount()).isEqualTo(32);
        }
    }

    @Nested
    class DateParsing {

        @Test
        void parsesFullDate() {
            var info = new ComicInfo();
            info.setYear(2023);
            info.setMonth(6);
            info.setDay(15);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2023, 6, 15));
        }

        @Test
        void parsesYearOnly() {
            var info = new ComicInfo();
            info.setYear(1986);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(1986, 1, 1));
        }

        @Test
        void parsesYearAndMonth() {
            var info = new ComicInfo();
            info.setYear(2020);
            info.setMonth(11);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2020, 11, 1));
        }

        @Test
        void returnsNullForMissingYear() {
            var info = new ComicInfo();
            info.setMonth(6);
            info.setDay(15);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getPublishedDate()).isNull();
        }

        @Test
        void returnsNullForInvalidDate() {
            var info = new ComicInfo();
            info.setYear(2023);
            info.setMonth(13);
            info.setDay(32);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getPublishedDate()).isNull();
        }
    }

    @Nested
    class IsbnParsing {

        @Test
        void extractsValid13DigitGtin() {
            var info = new ComicInfo();
            info.setGtin("9781234567890");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getIsbn13()).isEqualTo("9781234567890");
        }

        @Test
        void normalizesGtinWithDashes() {
            var info = new ComicInfo();
            info.setGtin("978-1-234-56789-0");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getIsbn13()).isEqualTo("9781234567890");
        }

        @Test
        void normalizesGtinWithSpaces() {
            var info = new ComicInfo();
            info.setGtin("978 1 234 56789 0");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getIsbn13()).isEqualTo("9781234567890");
        }

        @Test
        void rejectsInvalidGtin() {
            var info = new ComicInfo();
            info.setGtin("12345");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getIsbn13()).isNull();
        }

        @Test
        void rejectsNonNumericGtin() {
            var info = new ComicInfo();
            info.setGtin("978ABC1234567");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getIsbn13()).isNull();
        }

        @Test
        void ignoresBlankGtin() {
            var info = new ComicInfo();
            info.setGtin("   ");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getIsbn13()).isNull();
        }
    }

    @Nested
    class AuthorsAndCategories {

        @Test
        void extractsSingleWriter() {
            var info = new ComicInfo();
            info.setWriter("Alan Moore");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getAuthors()).containsExactly("Alan Moore");
        }

        @Test
        void splitsMultipleWritersByComma() {
            var info = new ComicInfo();
            info.setWriter("Alan Moore, Dave Gibbons");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getAuthors()).containsExactlyInAnyOrder("Alan Moore", "Dave Gibbons");
        }

        @Test
        void splitsWritersBySemicolon() {
            var info = new ComicInfo();
            info.setWriter("Neil Gaiman; Mike Carey");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getAuthors()).containsExactlyInAnyOrder("Neil Gaiman", "Mike Carey");
        }

        @Test
        void extractsGenreAsCategories() {
            var info = new ComicInfo();
            info.setGenre("Superhero, Action");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getCategories()).containsExactlyInAnyOrder("Superhero", "Action");
        }

        @Test
        void extractsTagsFromComicInfo() {
            var info = new ComicInfo();
            info.setTags("dark, gritty; mature");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getTags()).containsExactlyInAnyOrder("dark", "gritty", "mature");
        }

        @Test
        void returnsNullAuthorsWhenWriterMissing() {
            var info = new ComicInfo();
            info.setTitle("No Writer");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getAuthors()).isNull();
        }

        @Test
        void ignoresEmptyValuesInSplit() {
            var info = new ComicInfo();
            info.setWriter("Alan Moore,,, Dave Gibbons");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getAuthors()).containsExactlyInAnyOrder("Alan Moore", "Dave Gibbons");
        }
    }

    @Nested
    class ComicMetadataExtraction {

        @Test
        void extractsIssueNumber() {
            var info = new ComicInfo();
            info.setNumber("42");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicMetadata()).isNotNull();
            assertThat(metadata.getComicMetadata().getIssueNumber()).isEqualTo("42");
        }

        @Test
        void extractsVolume() {
            var info = new ComicInfo();
            info.setSeries("Batman");
            info.setVolume(2016);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicMetadata()).isNotNull();
            assertThat(metadata.getComicMetadata().getVolumeName()).isEqualTo("Batman");
            assertThat(metadata.getComicMetadata().getVolumeNumber()).isEqualTo(2016);
        }

        @Test
        void extractsStoryArc() {
            var info = new ComicInfo();
            info.setStoryArc("Court of Owls");
            info.setStoryArcNumber("3");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            ComicMetadata comic = metadata.getComicMetadata();
            assertThat(comic.getStoryArc()).isEqualTo("Court of Owls");
            assertThat(comic.getStoryArcNumber()).isEqualTo(3);
        }

        @Test
        void extractsAlternateSeries() {
            var info = new ComicInfo();
            info.setAlternateSeries("Detective Comics");
            info.setAlternateNumber("500");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            ComicMetadata comic = metadata.getComicMetadata();
            assertThat(comic.getAlternateSeries()).isEqualTo("Detective Comics");
            assertThat(comic.getAlternateIssue()).isEqualTo("500");
        }

        @Test
        void extractsCreatorRoles() {
            var info = new ComicInfo();
            info.setPenciller("Jim Lee, Greg Capullo");
            info.setInker("Scott Williams");
            info.setColorist("Alex Sinclair");
            info.setLetterer("Richard Starkings");
            info.setCoverArtist("Jim Lee");
            info.setEditor("Bob Harras");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            ComicMetadata comic = metadata.getComicMetadata();
            assertThat(comic.getPencillers()).containsExactlyInAnyOrder("Jim Lee", "Greg Capullo");
            assertThat(comic.getInkers()).containsExactly("Scott Williams");
            assertThat(comic.getColorists()).containsExactly("Alex Sinclair");
            assertThat(comic.getLetterers()).containsExactly("Richard Starkings");
            assertThat(comic.getCoverArtists()).containsExactly("Jim Lee");
            assertThat(comic.getEditors()).containsExactly("Bob Harras");
        }

        @Test
        void extractsImprint() {
            var info = new ComicInfo();
            info.setImprint("Vertigo");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicMetadata().getImprint()).isEqualTo("Vertigo");
        }

        @Test
        void extractsFormat() {
            var info = new ComicInfo();
            info.setFormat("Trade Paperback");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicMetadata().getFormat()).isEqualTo("Trade Paperback");
        }

        @Test
        void extractsBlackAndWhiteYes() {
            var info = new ComicInfo();
            info.setBlackAndWhite(YesNo.YES);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicMetadata().getBlackAndWhite()).isTrue();
        }

        @Test
        void blackAndWhiteNotSetForNo() {
            var info = new ComicInfo();
            info.setBlackAndWhite(YesNo.NO);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicMetadata()).isNull();
        }

        @Test
        void extractsMangaRightToLeft() {
            var info = new ComicInfo();
            info.setManga(ReadingDirection.RIGHT_TO_LEFT);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            ComicMetadata comic = metadata.getComicMetadata();
            assertThat(comic.getManga()).isTrue();
            assertThat(comic.getReadingDirection()).isEqualTo("rtl");
        }

        @Test
        void extractsMangaRightToLeftManga() {
            var info = new ComicInfo();
            info.setManga(ReadingDirection.RIGHT_TO_LEFT_MANGA);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            ComicMetadata comic = metadata.getComicMetadata();
            assertThat(comic.getManga()).isTrue();
            assertThat(comic.getReadingDirection()).isEqualTo("rtl");
        }

        @Test
        void extractsMangaLeftToRight() {
            var info = new ComicInfo();
            info.setManga(ReadingDirection.LEFT_TO_RIGHT);
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            ComicMetadata comic = metadata.getComicMetadata();
            assertThat(comic.getManga()).isFalse();
            assertThat(comic.getReadingDirection()).isEqualTo("ltr");
        }

        @Test
        void extractsCharactersTeamsLocations() {
            var info = new ComicInfo();
            info.setCharacters("Batman, Robin");
            info.setTeams("Justice League; Teen Titans");
            info.setLocations("Gotham City, Metropolis");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            ComicMetadata comic = metadata.getComicMetadata();
            assertThat(comic.getCharacters()).containsExactlyInAnyOrder("Batman", "Robin");
            assertThat(comic.getTeams()).containsExactlyInAnyOrder("Justice League", "Teen Titans");
            assertThat(comic.getLocations()).containsExactlyInAnyOrder("Gotham City", "Metropolis");
        }

        @Test
        void noComicMetadataWhenNoComicFieldsPresent() {
            var info = new ComicInfo();
            info.setTitle("Just a title");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicMetadata()).isNull();
        }

        @Test
        void extractsWebLink() {
            var info = new ComicInfo();
            info.setWeb("https://example.com/comic");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicMetadata().getWebLink()).isEqualTo("https://example.com/comic");
        }

        @Test
        void extractsNotes() {
            var info = new ComicInfo();
            info.setNotes("Some notes here");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicMetadata().getNotes()).isEqualTo("Some notes here");
        }
    }

    @Nested
    class WebFieldParsing {

        @Test
        void extractsGoodreadsIdFromUrl() {
            var info = new ComicInfo();
            info.setWeb("https://www.goodreads.com/book/show/12345-some-book");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getGoodreadsId()).isEqualTo("12345");
        }

        @Test
        void extractsAsinFromAmazonUrl() {
            var info = new ComicInfo();
            info.setWeb("https://www.amazon.com/dp/B08N5WRWNW");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getAsin()).isEqualTo("B08N5WRWNW");
        }

        @Test
        void extractsComicvineIdFromUrl() {
            var info = new ComicInfo();
            info.setWeb("https://comicvine.gamespot.com/issue/batman-1/4000-12345");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicvineId()).isEqualTo("4000-12345");
        }

        @Test
        void extractsHardcoverIdFromUrl() {
            var info = new ComicInfo();
            info.setWeb("https://hardcover.app/books/batman-year-one");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getHardcoverId()).isEqualTo("batman-year-one");
        }

        @Test
        void extractsMultipleIdsFromSpaceSeparatedUrls() {
            var info = new ComicInfo();
            info.setWeb("https://www.goodreads.com/book/show/99999 https://www.amazon.com/dp/B012345678");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getGoodreadsId()).isEqualTo("99999");
            assertThat(metadata.getAsin()).isEqualTo("B012345678");
        }
    }

    @Nested
    class BookLoreNoteParsing {

        @Test
        void extractsMoodsFromNotes() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:Moods] dark, brooding");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getMoods()).containsExactlyInAnyOrder("dark", "brooding");
        }

        @Test
        void extractsSubtitleFromNotes() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:Subtitle] The Dark Knight Returns");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getSubtitle()).isEqualTo("The Dark Knight Returns");
        }

        @Test
        void extractsIsbn13FromNotes() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:ISBN13] 9781234567890");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getIsbn13()).isEqualTo("9781234567890");
        }

        @Test
        void extractsIsbn10FromNotes() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:ISBN10] 0123456789");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getIsbn10()).isEqualTo("0123456789");
        }

        @Test
        void extractsAsinFromNotes() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:ASIN] B08N5WRWNW");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getAsin()).isEqualTo("B08N5WRWNW");
        }

        @Test
        void extractsGoodreadsIdFromNotes() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:GoodreadsId] 12345");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getGoodreadsId()).isEqualTo("12345");
        }

        @Test
        void extractsComicvineIdFromNotes() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:ComicvineId] 4000-12345");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getComicvineId()).isEqualTo("4000-12345");
        }

        @Test
        void extractsRatingsFromNotes() {
            var info = new ComicInfo();
            info.setNotes("""
                    [BookLore:AmazonRating] 4.5
                    [BookLore:GoodreadsRating] 4.2
                    [BookLore:HardcoverRating] 3.8""");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getAmazonRating()).isEqualTo(4.5);
            assertThat(metadata.getGoodreadsRating()).isEqualTo(4.2);
            assertThat(metadata.getHardcoverRating()).isEqualTo(3.8);
        }

        @Test
        void extractsHardcoverBookIdFromNotes() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:HardcoverBookId] abc-123");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getHardcoverBookId()).isEqualTo("abc-123");
        }

        @Test
        void extractsHardcoverIdFromNotes() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:HardcoverId] hc-456");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getHardcoverId()).isEqualTo("hc-456");
        }

        @Test
        void extractsGoogleIdFromNotes() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:GoogleId] google-789");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getGoogleId()).isEqualTo("google-789");
        }

        @Test
        void extractsLubimyczytacFromNotes() {
            var info = new ComicInfo();
            info.setNotes("""
                    [BookLore:LubimyczytacId] lub-123
                    [BookLore:LubimyczytacRating] 4.1""");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getLubimyczytacId()).isEqualTo("lub-123");
            assertThat(metadata.getLubimyczytacRating()).isEqualTo(4.1);
        }

        @Test
        void extractsRanobedbFromNotes() {
            var info = new ComicInfo();
            info.setNotes("""
                    [BookLore:RanobedbId] rdb-456
                    [BookLore:RanobedbRating] 3.9""");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getRanobedbId()).isEqualTo("rdb-456");
            assertThat(metadata.getRanobedbRating()).isEqualTo(3.9);
        }

        @Test
        void mergesTagsFromComicInfoAndNotes() {
            var info = new ComicInfo();
            info.setTags("existing-tag");
            info.setNotes("[BookLore:Tags] new-tag, another-tag");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getTags()).containsExactlyInAnyOrder("existing-tag", "new-tag", "another-tag");
        }

        @Test
        void notesUsedAsDescriptionWhenSummaryMissing() {
            var info = new ComicInfo();
            info.setNotes("This is a great comic.\n[BookLore:ISBN13] 9780000000000");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getDescription()).isEqualTo("This is a great comic.");
            assertThat(metadata.getIsbn13()).isEqualTo("9780000000000");
        }

        @Test
        void notesNotUsedAsDescriptionWhenSummaryPresent() {
            var info = new ComicInfo();
            info.setSummary("Official summary");
            info.setNotes("This is a great comic.\n[BookLore:ISBN13] 9780000000000");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getDescription()).isEqualTo("Official summary");
        }

        @Test
        void handlesInvalidRatingGracefully() {
            var info = new ComicInfo();
            info.setNotes("[BookLore:AmazonRating] not-a-number");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getAmazonRating()).isNull();
        }
    }

    @Nested
    class CoverExtraction {

        @Test
        void extractsCoverFromArchive() {
            byte[] expected = new byte[]{1, 2, 3, 4, 5};
            mockCover(expected);

            byte[] actual = extractor.extractCover(CBZ_PATH);

            assertThat(actual).isEqualTo(expected);
        }

        @Test
        void returnsNullWhenNoCover() {
            mockCover(null);

            byte[] cover = extractor.extractCover(CBZ_PATH);

            assertThat(cover).isNull();
        }

        @Test
        void returnsNullForEmptyCover() {
            mockCover(new byte[0]);

            byte[] cover = extractor.extractCover(CBZ_PATH);

            assertThat(cover).isNull();
        }

        @Test
        void returnsNullOnException() {
            mockReadException();

            byte[] cover = extractor.extractCover(CBZ_PATH);

            assertThat(cover).isNull();
        }
    }

    @Nested
    class FullComicInfoIntegration {

        @Test
        void extractsAllFieldsFromRichComicInfo() {
            var info = new ComicInfo();
            info.setTitle("Batman: The Dark Knight Returns");
            info.setSeries("Batman");
            info.setNumber("1");
            info.setCount(4);
            info.setVolume(1986);
            info.setSummary("In a bleak future, Bruce Wayne returns as Batman.");
            info.setYear(1986);
            info.setMonth(2);
            info.setDay(1);
            info.setWriter("Frank Miller");
            info.setPenciller("Frank Miller");
            info.setInker("Klaus Janson");
            info.setColorist("Lynn Varley");
            info.setPublisher("DC Comics");
            info.setGenre("Superhero, Action, Drama");
            info.setTags("dark, classic");
            info.setPageCount(48);
            info.setLanguageISO("en");
            info.setGtin("9781563893421");
            info.setStoryArc("The Dark Knight Returns");
            info.setStoryArcNumber("1");
            info.setBlackAndWhite(YesNo.NO);
            info.setManga(ReadingDirection.LEFT_TO_RIGHT);
            info.setCharacters("Batman, Superman, Robin");
            info.setTeams("Justice League");
            info.setLocations("Gotham City");
            info.setWeb("https://www.goodreads.com/book/show/59960-the-dark-knight-returns");
            info.setNotes("[BookLore:Subtitle] Part One\n[BookLore:Moods] dark, intense, brooding\n[BookLore:ISBN10] 1563893428");
            mockComicInfo(info);

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getTitle()).isEqualTo("Batman: The Dark Knight Returns");
            assertThat(metadata.getSeriesName()).isEqualTo("Batman");
            assertThat(metadata.getSeriesNumber()).isEqualTo(1f);
            assertThat(metadata.getSeriesTotal()).isEqualTo(4);
            assertThat(metadata.getDescription()).isEqualTo("In a bleak future, Bruce Wayne returns as Batman.");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(1986, 2, 1));
            assertThat(metadata.getAuthors()).containsExactly("Frank Miller");
            assertThat(metadata.getPublisher()).isEqualTo("DC Comics");
            assertThat(metadata.getCategories()).containsExactlyInAnyOrder("Superhero", "Action", "Drama");
            assertThat(metadata.getTags()).containsExactlyInAnyOrder("dark", "classic");
            assertThat(metadata.getPageCount()).isEqualTo(48);
            assertThat(metadata.getLanguage()).isEqualTo("en");
            assertThat(metadata.getIsbn13()).isEqualTo("9781563893421");
            assertThat(metadata.getSubtitle()).isEqualTo("Part One");
            assertThat(metadata.getMoods()).containsExactlyInAnyOrder("dark", "intense", "brooding");
            assertThat(metadata.getIsbn10()).isEqualTo("1563893428");
            assertThat(metadata.getGoodreadsId()).isEqualTo("59960");

            ComicMetadata comic = metadata.getComicMetadata();
            assertThat(comic).isNotNull();
            assertThat(comic.getIssueNumber()).isEqualTo("1");
            assertThat(comic.getVolumeName()).isEqualTo("Batman");
            assertThat(comic.getVolumeNumber()).isEqualTo(1986);
            assertThat(comic.getStoryArc()).isEqualTo("The Dark Knight Returns");
            assertThat(comic.getStoryArcNumber()).isEqualTo(1);
            assertThat(comic.getPencillers()).containsExactly("Frank Miller");
            assertThat(comic.getInkers()).containsExactly("Klaus Janson");
            assertThat(comic.getColorists()).containsExactly("Lynn Varley");
            assertThat(comic.getManga()).isFalse();
            assertThat(comic.getReadingDirection()).isEqualTo("ltr");
            assertThat(comic.getCharacters()).containsExactlyInAnyOrder("Batman", "Superman", "Robin");
            assertThat(comic.getTeams()).containsExactly("Justice League");
            assertThat(comic.getLocations()).containsExactly("Gotham City");
            assertThat(comic.getWebLink()).isEqualTo("https://www.goodreads.com/book/show/59960-the-dark-knight-returns");
        }
    }

    @Nested
    class UnknownArchiveType {

        @Test
        void returnsFallbackForNonArchiveFile() {
            mockReadException();

            BookMetadata metadata = extractor.extractMetadata(CBZ_PATH);

            assertThat(metadata.getTitle()).isEqualTo("test");
        }

        @Test
        void returnsNullCoverForNonArchiveFile() {
            mockReadException();

            byte[] cover = extractor.extractCover(CBZ_PATH);

            assertThat(cover).isNull();
        }
    }
}
