package org.booklore.controller;

import org.booklore.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the Scalar API reference UI.
 * Enabled only when {@code app.swagger.enabled=true} (default: false).
 *
 * <p>Scalar is a modern, open-source API reference UI that reads a standard
 * OpenAPI JSON document. This controller generates a self-contained HTML page
 * that loads Scalar from CDN and points it at the springdoc-generated
 * {@code /api/v1/api-docs} endpoint.</p>
 *
 * <p>UI available at: {@code /api/v1/scalar}</p>
 */
@Controller
@ConditionalOnProperty(name = "app.swagger.enabled", havingValue = "true", matchIfMissing = false)
public class ScalarController {

    private static final String SCALAR_CDN =
            "https://cdn.jsdelivr.net/npm/@scalar/api-reference@latest/dist/browser/standalone.min.js";
    private static final String API_DOCS_PATH = "/api/v1/api-docs";

    @GetMapping(value = "/api/v1/scalar", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String scalar() {
        return "<!doctype html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\" />\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n" +
                "  <title>Grimmory API Reference</title>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <script\n" +
                "    id=\"api-reference\"\n" +
                "    data-url=\"" + API_DOCS_PATH + "\"\n" +
                "    data-configuration='{\"theme\":\"purple\",\"hideDownloadButton\":false}'\n" +
                "  ></script>\n" +
                "  <script src=\"" + SCALAR_CDN + "\"></script>\n" +
                "</body>\n" +
                "</html>\n";
    }
}
