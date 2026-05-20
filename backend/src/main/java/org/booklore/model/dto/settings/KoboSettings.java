package org.booklore.model.dto.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class KoboSettings {
    @Builder.Default private boolean convertToKepub = true;
    @Builder.Default private int conversionLimitInMb = 100;
    @Builder.Default private boolean convertCbxToEpub = false;
    @Builder.Default private int conversionLimitInMbForCbx = 100;
    @Builder.Default private boolean forceEnableHyphenation = false;
    @Builder.Default private int conversionImageCompressionPercentage = 85;
    @Builder.Default private boolean forwardToKoboStore = true;
}
