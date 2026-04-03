package org.booklore.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.grimmory.comic4j.archive.ArchiveDetector;
import org.grimmory.comic4j.archive.ArchiveFormat;

import java.io.File;

@Slf4j
@UtilityClass
public class ArchiveUtils {

    public enum ArchiveType {
        ZIP,
        RAR,
        SEVEN_ZIP,
        UNKNOWN
    }

    public static ArchiveType detectArchiveType(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return ArchiveType.UNKNOWN;
        }
        return mapFormat(ArchiveDetector.detect(file.toPath()));
    }

    public static ArchiveType detectArchiveTypeByExtension(String fileName) {
        if (fileName == null) {
            return ArchiveType.UNKNOWN;
        }
        return mapFormat(ArchiveFormat.fromExtension(fileName));
    }

    private static ArchiveType mapFormat(ArchiveFormat format) {
        return switch (format) {
            case ZIP -> ArchiveType.ZIP;
            case RAR4, RAR5 -> ArchiveType.RAR;
            case SEVEN_ZIP -> ArchiveType.SEVEN_ZIP;
            case TAR, UNKNOWN -> ArchiveType.UNKNOWN;
        };
    }
}
