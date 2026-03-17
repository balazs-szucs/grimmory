package org.booklore.service.metadata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MetadataUserAgentService {

    private static final String USER_AGENT_TEMPLATE =
            "Grimmory/%s (Book and Comic Metadata Fetcher; +https://github.com/grimmory-tools/grimmory)";

    private final String metadataFetcherUserAgent;

    public MetadataUserAgentService(@Value("${app.version:development}") String appVersion) {
        String resolvedVersion = appVersion == null || appVersion.isBlank() ? "development" : appVersion;
        this.metadataFetcherUserAgent = USER_AGENT_TEMPLATE.formatted(resolvedVersion);
    }

    public String getMetadataFetcherUserAgent() {
        return metadataFetcherUserAgent;
    }
}
