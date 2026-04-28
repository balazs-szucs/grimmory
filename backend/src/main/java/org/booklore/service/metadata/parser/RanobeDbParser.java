package org.booklore.service.metadata.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.response.ranobedbapi.RanobedbBookResponse;
import org.booklore.model.dto.response.ranobedbapi.RanobedbSearchResponse;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.BookUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.booklore.service.metadata.RateLimitService;

@Slf4j
@Service
@RequiredArgsConstructor
public class RanobeDbParser implements BookParser {
    private static final String RANOBEDB_URL = "https://ranobedb.org/api/v0/";
    private static final String RANOBEDB_IMAGE_URL = "https://images.ranobedb.org/";

    private final AppSettingService appSettingService;
    private final HttpClient httpClient;
    private final RateLimitService rateLimitService;
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        String searchTerm = getSearchTerm(book, fetchMetadataRequest);
        if (searchTerm == null) {
            log.warn("No valid search term provided for metadata fetch.");
            return Collections.emptyList();
        }
        return getMetadataListByTerm(searchTerm, false);
    }

    @Override
    public void fetchMetadata(Book book, FetchMetadataRequest fetchMetadataRequest, BooleanSupplier isCancelled, Consumer<BookMetadata> consumer) {
        List<BookMetadata> results = fetchMetadata(book, fetchMetadataRequest);
        if (results != null) {
            for (BookMetadata metadata : results) {
                if (isCancelled.getAsBoolean()) return;
                consumer.accept(metadata);
            }
        }
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        String searchTerm = getSearchTerm(book, fetchMetadataRequest);
        if (searchTerm == null) {
            log.warn("No valid search term provided for metadata fetch.");
            return null;
        }
        List<BookMetadata> metadataList = getMetadataListByTerm(searchTerm, true);
        return metadataList.isEmpty() ? null : metadataList.getFirst();
    }
    
    public List<BookMetadata> getMetadataListByTerm(String term, Boolean fetchTop) {
      log.info("Ranobedb: Fetching metadata for term: '{}'", term);

      return rateLimitService.execute("Ranobedb", 500, () -> {
          try {
              URI uri = UriComponentsBuilder.fromUriString(RANOBEDB_URL)
                      .path("/books")
                      .queryParam("q", term)
                      .queryParam("query", term)
                      .queryParam("limit", 5)
                      .queryParam("rl", "en")
                      .queryParam("rll", "or")
                      .queryParam("rf", "digital,print")
                      .queryParam("rfl", "or")
                      .build()
                      .toUri();

              HttpRequest request = HttpRequest.newBuilder()
                      .uri(uri)
                      .header("User-Agent", "BookLore/1.0 (Book and Comic Metadata Fetcher; +https://github.com/booklore-app/booklore)")
                      .GET()
                      .build();

              HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

              if (response.statusCode() == 200) {
                  List<BookMetadata> metadataList = parseRanobeDbApiResponse(response.body(), fetchTop);
                  log.error("Ranobedb: Found {} results for term: '{}'", metadataList.size(), term);
                  return metadataList;
              } else {
                  log.error("Ranobedb Search API returned status code {}", response.statusCode());
              }
          } catch (IOException | InterruptedException e) {
              log.error("Error fetching metadata from Ranobedb Search API", e);
          }
          return Collections.<BookMetadata>emptyList();
      }).join();
    }

    private String getSearchTerm(Book book, FetchMetadataRequest request) {
        if (request.getTitle() != null && !request.getTitle().isEmpty()) {
            return request.getTitle();
        } else if (book.getPrimaryFile() != null && book.getPrimaryFile().getFileName() != null && !book.getPrimaryFile().getFileName().isEmpty()) {
            return BookUtils.cleanFileName(book.getPrimaryFile().getFileName());
        }
        return null;
    }

    private List<BookMetadata> parseRanobeDbApiResponse(String responseBody, Boolean fetchTop) throws IOException {
        RanobedbSearchResponse searchResponse = OBJECT_MAPPER.readValue(responseBody, RanobedbSearchResponse.class);
        if (searchResponse.getBooks() == null) {
            return Collections.emptyList();
        }
        if (fetchTop && !searchResponse.getBooks().isEmpty()) {
            BookMetadata topMetadata = searchResultToBookMetadata(searchResponse.getBooks().getFirst().getId());
            return topMetadata != null ? List.of(topMetadata) : Collections.emptyList();
        } else {
            return searchResponse.getBooks().stream()
                    .map(book -> searchResultToBookMetadata(book.getId()))
                    .collect(Collectors.toList());
        }
    }

    private BookMetadata searchResultToBookMetadata(int bookId) {
        return rateLimitService.execute("Ranobedb", 500, () -> {
            try {
                log.info("Ranobedb: Fetching metadata for book id: '{}'", bookId);
                URI uri = UriComponentsBuilder.fromUriString(RANOBEDB_URL)
                        .pathSegment("book", String.valueOf(bookId))
                        .build()
                        .toUri();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .header("User-Agent", "BookLore/1.0 (Book and Comic Metadata Fetcher; +https://github.com/booklore-app/booklore)")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    RanobedbBookResponse responstObj = OBJECT_MAPPER.readValue(response.body(), RanobedbBookResponse.class);
                    RanobedbBookResponse.Book book = responstObj.getBook();
                    if (book == null) {
                        return null;
                    }

                    RanobedbBookResponse.TitleEntry englishTitleEntry = book.getTitles().stream()
                            .filter(titleEntry -> "en".equalsIgnoreCase(titleEntry.getLang()))
                            .filter(titleEntry -> titleEntry.getOfficial())
                            .findFirst()
                            .orElse(null);

                    RanobedbBookResponse.Release englishRelease = book.getReleases().stream()
                            .filter(release -> "en".equalsIgnoreCase(release.getLang()))
                            .findFirst()
                            .orElse(null);

                    RanobedbBookResponse.Publisher englishPublisher = book.getPublishers().stream()
                            .filter(publisher -> "en".equalsIgnoreCase(publisher.getLang()))
                            .filter(publisher -> RanobedbBookResponse.PublisherType.PUBLISHER.equals(publisher.getPublisherType()))
                            .findFirst()
                            .orElse(null);

                    List<RanobedbBookResponse.SeriesBook> seriesBooks = book.getSeries() != null ? book.getSeries().getBooks() : List.of();
                    int seriesIndex = IntStream.range(0, seriesBooks.size())
                            .filter(i -> seriesBooks.get(i).getId().equals(book.getId()))
                            .findFirst()
                            .orElse(-1);

                    List<String> authors = book.getEditions().stream()
                            .flatMap(edition -> edition.getStaff().stream())
                            .filter(staff -> RanobedbBookResponse.RoleType.AUTHOR.equals(staff.getRoleType()))
                            .map(staff -> staff.getRomaji() != null ? staff.getRomaji() : staff.getName())
                            .toList();

                    HashSet<String> genres = book.getSeries() != null ? book.getSeries().getTags().stream()
                            .filter(tag -> RanobedbBookResponse.TagType.GENRE.equals(tag.getTtype()))
                            .map(RanobedbBookResponse.Tag::getName)
                            .map(genre -> Pattern.compile("\\b(.)(.*?)\\b").matcher(genre).replaceAll(m -> m.group(1).toUpperCase() + m.group(2).toLowerCase()))
                            .collect(Collectors.toCollection(HashSet::new)) : new HashSet<>();

                    String title = englishTitleEntry != null ? englishTitleEntry.getTitle() : book.getTitle();
                    String subtitle = null;
                    if (book.getSeries() != null && book.getSeries().getTitle() != null && title.startsWith(book.getSeries().getTitle())) {
                        String remainingTitle = title.substring(book.getSeries().getTitle().length()).trim();
                        String[] titleParts = remainingTitle.split(":", 2);
                        if (titleParts.length == 2) {
                            title = title.substring(0, title.indexOf(titleParts[1]) - 1).trim();
                            subtitle = titleParts[1].trim();
                        }
                    }

                    return BookMetadata.builder()
                        .provider(MetadataProvider.Ranobedb)
                        .ranobedbId(String.valueOf(book.getId()))
                        .ranobedbRating(book.getRating() != null ? book.getRating().getScore() / 2.0 : null)
                        .title(title) 
                        .subtitle(subtitle)
                        .authors(authors)
                        .categories(genres)
                        .publisher(englishPublisher != null ? englishPublisher.getName() : null)
                        .thumbnailUrl(book.getImage() != null ? RANOBEDB_IMAGE_URL + book.getImage().getFilename() : null)
                        .description(book.getDescription())
                        .language(englishRelease != null ? englishRelease.getLang() : book.getLang())
                        .seriesName(book.getSeries() != null ? book.getSeries().getTitle() : null)
                        .seriesNumber(seriesIndex != -1 ? seriesIndex + 1.0f : null)
                        .seriesTotal(seriesBooks.isEmpty() ? null : seriesBooks.size())
                        .publishedDate(englishRelease != null ? parseDate(englishRelease.getReleaseDate()) : parseDate(book.getCReleaseDate()))
                        .build();
                } else {
                    log.error("Ranobedb Get Book API returned status code {}", response.statusCode());
                }
            } catch (IOException | InterruptedException e) {
                log.error("Error fetching metadata from Ranobedb Search API", e);
            }
            
            return null;
        }).join();
    }

    private LocalDate parseDate(Long dateInt) {
        if (dateInt == null || dateInt == 0) {
            return null;
        }
        try {
            // Parse date from integer of the format (YYYYMMDD)
            return LocalDate.of(
                    (int)(dateInt / 10000),
                    (int)((dateInt / 100) % 100),
                    (int)(dateInt % 100)
            );
        } catch (DateTimeParseException e) {
            log.debug("Could not parse date: {}", dateInt);
            return null;
        }
    }
}
