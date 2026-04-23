package org.booklore.model.dto.response;

import java.util.Map;

public record MenuCountsResponse(
        Map<Long, Long> libraryCounts,
        Map<Long, Long> shelfCounts,
        Map<Long, Long> magicShelfCounts,
        long totalBookCount,
        long unshelvedBookCount
) {
}
