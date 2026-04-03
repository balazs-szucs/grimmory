package org.booklore.service.metadata.writer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ComicCreatorRole;
import org.booklore.service.appsettings.AppSettingService;
import org.grimmory.comic4j.archive.ComicArchiveReader;
import org.grimmory.comic4j.archive.ComicArchiveWriter;
import org.grimmory.comic4j.domain.AgeRating;
import org.grimmory.comic4j.domain.ComicInfo;
import org.grimmory.comic4j.domain.ReadingDirection;
import org.grimmory.comic4j.domain.YesNo;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CbxMetadataWriter implements MetadataWriter {

    private final AppSettingService appSettingService;

    @Override
    public void saveMetadataToFile(File file, BookMetadataEntity metadata, String thumbnailUrl, MetadataClearFlags clearFlags) {
        if (!shouldSaveMetadataToFile(file)) {
            return;
        }

        try {
            ComicInfo comicInfo = loadOrCreateComicInfo(file.toPath());
            applyMetadataChanges(comicInfo, metadata, clearFlags);

            Path backupDir = file.toPath().getParent();
            ComicArchiveWriter.writeToZip(file.toPath(), comicInfo, backupDir);
            log.info("CbxMetadataWriter: Successfully wrote metadata to CBZ file: {}", file.getName());
        } catch (Exception e) {
            log.warn("Failed to write metadata for {}: {}", file.getName(), e.getMessage(), e);
        }
    }

    public boolean shouldSaveMetadataToFile(File file) {
        MetadataPersistenceSettings.SaveToOriginalFile settings = appSettingService.getAppSettings().getMetadataPersistenceSettings().getSaveToOriginalFile();

        MetadataPersistenceSettings.FormatSettings cbxSettings = settings.getCbx();
        if (cbxSettings == null || !cbxSettings.isEnabled()) {
            log.debug("CBX metadata writing is disabled. Skipping: {}", file.getName());
            return false;
        }

        long fileSizeInMb = file.length() / (1024 * 1024);
        if (fileSizeInMb > cbxSettings.getMaxFileSizeInMb()) {
            log.info("CBX file {} ({} MB) exceeds max size limit ({} MB). Skipping metadata write.", file.getName(), fileSizeInMb, cbxSettings.getMaxFileSizeInMb());
            return false;
        }

        return true;
    }

    private ComicInfo loadOrCreateComicInfo(Path path) {
        try {
            ComicInfo existing = ComicArchiveReader.readComicInfo(path);
            if (existing != null) {
                return existing;
            }
        } catch (Exception e) {
            log.warn("Could not read ComicInfo from {}: {}", path.getFileName(), e.getMessage());
        }
        return new ComicInfo();
    }

    private void applyMetadataChanges(ComicInfo info, BookMetadataEntity metadata, MetadataClearFlags clearFlags) {
        MetadataCopyHelper helper = new MetadataCopyHelper(metadata);

        helper.copyTitle(clearFlags != null && clearFlags.isTitle(), info::setTitle);

        helper.copyDescription(clearFlags != null && clearFlags.isDescription(), val -> {
            if (val != null) {
                String clean = Jsoup.clean(val, Safelist.none()).trim();
                info.setSummary(clean);
            } else {
                info.setSummary(null);
            }
        });

        helper.copyPublisher(clearFlags != null && clearFlags.isPublisher(), info::setPublisher);
        helper.copySeriesName(clearFlags != null && clearFlags.isSeriesName(), info::setSeries);
        helper.copySeriesNumber(clearFlags != null && clearFlags.isSeriesNumber(), val -> info.setNumber(formatFloatValue(val)));
        helper.copySeriesTotal(clearFlags != null && clearFlags.isSeriesTotal(), info::setCount);

        helper.copyPublishedDate(clearFlags != null && clearFlags.isPublishedDate(), date -> {
            if (date != null) {
                info.setYear(date.getYear());
                info.setMonth(date.getMonthValue());
                info.setDay(date.getDayOfMonth());
            } else {
                info.setYear(null);
                info.setMonth(null);
                info.setDay(null);
            }
        });

        helper.copyPageCount(clearFlags != null && clearFlags.isPageCount(), info::setPageCount);
        helper.copyLanguage(clearFlags != null && clearFlags.isLanguage(), info::setLanguageISO);

        helper.copyAuthors(clearFlags != null && clearFlags.isAuthors(), set -> {
            info.setWriter(joinStrings(set));
            info.setPenciller(null);
            info.setInker(null);
            info.setColorist(null);
            info.setLetterer(null);
            info.setCoverArtist(null);
        });

        helper.copyCategories(clearFlags != null && clearFlags.isCategories(), set -> info.setGenre(joinStrings(set)));

        if (metadata.getTags() != null && !metadata.getTags().isEmpty()) {
            info.setTags(joinStrings(metadata.getTags().stream().map(TagEntity::getName).collect(Collectors.toSet())));
        }

        helper.copyRating(false, rating -> {
            if (rating != null) {
                float normalized = (float) Math.min(5.0, Math.max(0.0, rating / 2.0));
                info.setCommunityRating(normalized);
            } else {
                info.setCommunityRating(null);
            }
        });

        // Web field
        String primaryUrl = null;
        if (metadata.getHardcoverBookId() != null && !metadata.getHardcoverBookId().isBlank()) {
            primaryUrl = "https://hardcover.app/books/" + metadata.getHardcoverBookId();
        } else if (metadata.getComicvineId() != null && !metadata.getComicvineId().isBlank()) {
            primaryUrl = "https://comicvine.gamespot.com/issue/" + metadata.getComicvineId();
        } else if (metadata.getGoodreadsId() != null && !metadata.getGoodreadsId().isBlank()) {
            primaryUrl = "https://www.goodreads.com/book/show/" + metadata.getGoodreadsId();
        } else if (metadata.getAsin() != null && !metadata.getAsin().isBlank()) {
            primaryUrl = "https://www.amazon.com/dp/" + metadata.getAsin();
        }
        info.setWeb(primaryUrl);

        // Notes - Custom Metadata
        StringBuilder notesBuilder = new StringBuilder();
        String existingNotes = info.getNotes();

        if (existingNotes != null && !existingNotes.isBlank()) {
            String preservedRules = existingNotes.lines()
                    .map(String::trim)
                    .filter(line -> !line.startsWith("[BookLore:") && !line.startsWith("[BookLore]"))
                    .collect(Collectors.joining("\n"));
            if (!preservedRules.isEmpty()) {
                notesBuilder.append(preservedRules);
            }
        }

        if (metadata.getMoods() != null) {
            appendBookLoreTag(notesBuilder, "Moods", joinStrings(metadata.getMoods().stream().map(MoodEntity::getName).collect(Collectors.toSet())));
        }
        if (metadata.getTags() != null) {
            appendBookLoreTag(notesBuilder, "Tags", joinStrings(metadata.getTags().stream().map(TagEntity::getName).collect(Collectors.toSet())));
        }
        appendBookLoreTag(notesBuilder, "Subtitle", metadata.getSubtitle());

        if (metadata.getIsbn13() != null && !metadata.getIsbn13().isBlank()) {
            info.setGtin(metadata.getIsbn13());
        }
        appendBookLoreTag(notesBuilder, "ISBN10", metadata.getIsbn10());

        appendBookLoreTag(notesBuilder, "AmazonRating", metadata.getAmazonRating());
        appendBookLoreTag(notesBuilder, "GoodreadsRating", metadata.getGoodreadsRating());
        appendBookLoreTag(notesBuilder, "HardcoverRating", metadata.getHardcoverRating());
        appendBookLoreTag(notesBuilder, "LubimyczytacRating", metadata.getLubimyczytacRating());
        appendBookLoreTag(notesBuilder, "RanobedbRating", metadata.getRanobedbRating());

        appendBookLoreTag(notesBuilder, "HardcoverBookId", metadata.getHardcoverBookId());
        appendBookLoreTag(notesBuilder, "HardcoverId", metadata.getHardcoverId());
        appendBookLoreTag(notesBuilder, "LubimyczytacId", metadata.getLubimyczytacId());
        appendBookLoreTag(notesBuilder, "RanobedbId", metadata.getRanobedbId());
        appendBookLoreTag(notesBuilder, "GoogleId", metadata.getGoogleId());
        appendBookLoreTag(notesBuilder, "GoodreadsId", metadata.getGoodreadsId());
        appendBookLoreTag(notesBuilder, "ASIN", metadata.getAsin());
        appendBookLoreTag(notesBuilder, "ComicvineId", metadata.getComicvineId());

        // Comic-specific metadata
        ComicMetadataEntity comic = metadata.getComicMetadata();
        if (comic != null) {
            if (comic.getVolumeNumber() != null) {
                info.setVolume(comic.getVolumeNumber());
            }

            if (comic.getAlternateSeries() != null && !comic.getAlternateSeries().isBlank()) {
                info.setAlternateSeries(comic.getAlternateSeries());
            }
            if (comic.getAlternateIssue() != null && !comic.getAlternateIssue().isBlank()) {
                info.setAlternateNumber(comic.getAlternateIssue());
            }

            if (comic.getStoryArc() != null && !comic.getStoryArc().isBlank()) {
                info.setStoryArc(comic.getStoryArc());
            }

            if (comic.getFormat() != null && !comic.getFormat().isBlank()) {
                info.setFormat(comic.getFormat());
            }

            if (comic.getImprint() != null && !comic.getImprint().isBlank()) {
                info.setImprint(comic.getImprint());
            }

            if (comic.getBlackAndWhite() != null) {
                info.setBlackAndWhite(comic.getBlackAndWhite() ? YesNo.YES : YesNo.NO);
            }

            if (comic.getManga() != null && comic.getManga()) {
                if (comic.getReadingDirection() != null && "RTL".equalsIgnoreCase(comic.getReadingDirection())) {
                    info.setManga(ReadingDirection.RIGHT_TO_LEFT_MANGA);
                } else {
                    info.setManga(ReadingDirection.RIGHT_TO_LEFT);
                }
            } else if (comic.getManga() != null) {
                info.setManga(ReadingDirection.LEFT_TO_RIGHT);
            }

            if (comic.getCharacters() != null && !comic.getCharacters().isEmpty()) {
                info.setCharacters(comic.getCharacters().stream()
                        .map(ComicCharacterEntity::getName)
                        .collect(Collectors.joining(", ")));
            }

            if (comic.getTeams() != null && !comic.getTeams().isEmpty()) {
                info.setTeams(comic.getTeams().stream()
                        .map(ComicTeamEntity::getName)
                        .collect(Collectors.joining(", ")));
            }

            if (comic.getLocations() != null && !comic.getLocations().isEmpty()) {
                info.setLocations(comic.getLocations().stream()
                        .map(ComicLocationEntity::getName)
                        .collect(Collectors.joining(", ")));
            }

            if (comic.getCreatorMappings() != null && !comic.getCreatorMappings().isEmpty()) {
                String pencillers = getCreatorsByRole(comic, ComicCreatorRole.PENCILLER);
                String inkers = getCreatorsByRole(comic, ComicCreatorRole.INKER);
                String colorists = getCreatorsByRole(comic, ComicCreatorRole.COLORIST);
                String letterers = getCreatorsByRole(comic, ComicCreatorRole.LETTERER);
                String coverArtists = getCreatorsByRole(comic, ComicCreatorRole.COVER_ARTIST);
                String editors = getCreatorsByRole(comic, ComicCreatorRole.EDITOR);

                if (!pencillers.isEmpty()) info.setPenciller(pencillers);
                if (!inkers.isEmpty()) info.setInker(inkers);
                if (!colorists.isEmpty()) info.setColorist(colorists);
                if (!letterers.isEmpty()) info.setLetterer(letterers);
                if (!coverArtists.isEmpty()) info.setCoverArtist(coverArtists);
                if (!editors.isEmpty()) info.setEditor(editors);
            }

            appendBookLoreTag(notesBuilder, "VolumeName", comic.getVolumeName());
            appendBookLoreTag(notesBuilder, "StoryArcNumber", comic.getStoryArcNumber());
            appendBookLoreTag(notesBuilder, "IssueNumber", comic.getIssueNumber());
        }

        // Age Rating
        if (metadata.getAgeRating() != null) {
            info.setAgeRating(mapAgeRating(metadata.getAgeRating()));
        }

        info.setNotes(notesBuilder.length() > 0 ? notesBuilder.toString() : null);
    }

    private String getCreatorsByRole(ComicMetadataEntity comic, ComicCreatorRole role) {
        if (comic.getCreatorMappings() == null) return "";
        return comic.getCreatorMappings().stream()
                .filter(m -> m.getRole() == role)
                .map(m -> m.getCreator().getName())
                .collect(Collectors.joining(", "));
    }

    private AgeRating mapAgeRating(Integer ageRating) {
        if (ageRating == null) return null;
        if (ageRating >= 18) return AgeRating.ADULTS_ONLY_18_PLUS;
        if (ageRating >= 17) return AgeRating.MATURE_17_PLUS;
        if (ageRating >= 15) return AgeRating.MA_15_PLUS;
        if (ageRating >= 13) return AgeRating.TEEN;
        if (ageRating >= 10) return AgeRating.EVERYONE_10_PLUS;
        if (ageRating >= 6) return AgeRating.EVERYONE;
        return AgeRating.EARLY_CHILDHOOD;
    }

    private String joinStrings(Set<String> values) {
        return (values == null || values.isEmpty()) ? null : String.join(", ", values);
    }

    private String formatFloatValue(Float value) {
        if (value == null) return null;
        if (value % 1 == 0) return Integer.toString(value.intValue());
        return value.toString();
    }

    @Override
    public BookFileType getSupportedBookType() {
        return BookFileType.CBX;
    }

    private void appendBookLoreTag(StringBuilder sb, String tag, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[BookLore:").append(tag).append("] ").append(value);
        }
    }

    private void appendBookLoreTag(StringBuilder sb, String tag, Number value) {
        if (value != null) {
            if (sb.length() > 0) sb.append("\n");
            String formatted = (value instanceof Double || value instanceof Float)
                    ? String.format(Locale.US, "%.2f", value.doubleValue())
                    : value.toString();
            sb.append("[BookLore:").append(tag).append("] ").append(formatted);
        }
    }
}