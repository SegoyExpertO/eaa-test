package ua.teaa.rag.fetcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import ua.teaa.rag.model.RagDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class WcagCriteriaFetcher implements DocumentFetcher {

    private static final String SOURCE = "wcag";

    private final WebClient.Builder webClientBuilder;

    @Value("${teaa.fetcher.wcag.base-url}")
    private String wcagBaseUrl;

    @Value("${teaa.fetcher.wcag.understanding-url}")
    private String understandingUrl;

    @Value("${teaa.rag.ingest.local-data-path}")
    private String localDataPath;

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public boolean supportsRemote() {
        return true;
    }

    @Override
    public List<RagDocument> fetch() {
        Path localDir = Path.of(localDataPath, SOURCE);

        if (Files.exists(localDir) && hasFiles(localDir)) {
            log.info("Loading WCAG from local files: {}", localDir);
            return fetchFromLocal(localDir);
        }

        log.info("Loading WCAG from internet: {}", wcagBaseUrl);
        return fetchFromRemote();
    }

    private List<RagDocument> fetchFromLocal(Path localDir) {
        List<RagDocument> documents = new ArrayList<>();

        try (Stream<Path> files = Files.walk(localDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html") || p.toString().endsWith(".htm"))
                    .forEach(file -> {
                        try {
                            String html = Files.readString(file);
                            Document doc = Jsoup.parse(html);
                            documents.addAll(parseWcagDocument(doc, file.getFileName().toString()));
                        } catch (IOException e) {
                            log.error("Error reading file {}: {}", file, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Error scanning directory {}: {}", localDir, e.getMessage());
        }

        log.info("WCAG: loaded {} documents from local files", documents.size());
        return documents;
    }

    private List<RagDocument> fetchFromRemote() {
        List<RagDocument> documents = new ArrayList<>();
        WebClient webClient = webClientBuilder
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        try {
            // 1. Fetch the main WCAG 2.2 specification page
            String mainPageHtml = webClient.get()
                    .uri(wcagBaseUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (mainPageHtml != null) {
                Document mainDoc = Jsoup.parse(mainPageHtml, wcagBaseUrl);
                documents.addAll(parseWcagMainPage(mainDoc));
                cacheLocally(mainPageHtml, "wcag22-main.html");
            }

            // 2. Fetch Understanding pages for each success criterion
            documents.addAll(fetchUnderstandingPages(webClient));

        } catch (Exception e) {
            log.error("Error loading WCAG from internet: {}", e.getMessage());
        }

        log.info("WCAG: loaded {} documents from internet", documents.size());
        return documents;
    }

    /**
     * Parses the main WCAG 2.2 page — extracts success criteria.
     * Each criterion becomes a separate RagDocument with metadata.
     */
    private List<RagDocument> parseWcagMainPage(Document doc) {
        List<RagDocument> documents = new ArrayList<>();

        // Success criteria use class="sc" in the WCAG 2.2 specification
        Elements successCriteria = doc.select("section.sc");

        for (Element sc : successCriteria) {
            String criterionId = extractCriterionId(sc);
            String level = extractLevel(sc);
            String title = extractTitle(sc);
            String content = sc.text();
            String category = detectCategory(criterionId, content);

            RagDocument ragDoc = RagDocument.builder()
                    .content(String.format("WCAG 2.2 Критерій %s (%s) - %s: %s",
                            criterionId, level, title, content))
                    .source(SOURCE)
                    .criterion(criterionId)
                    .level(level)
                    .category(category)
                    .metadata(Map.of(
                            "title", title,
                            "type", "success-criterion",
                            "url", wcagBaseUrl + "#" + sc.id()
                    ))
                    .build();

            documents.add(ragDoc);
        }

        // Also include principles and guidelines
        Elements guidelines = doc.select("section.guideline");
        for (Element gl : guidelines) {
            String title = extractTitle(gl);
            String content = gl.ownText();
            if (!content.isBlank()) {
                documents.add(RagDocument.builder()
                        .content(String.format("WCAG 2.2 Настанова - %s: %s", title, content))
                        .source(SOURCE)
                        .criterion("")
                        .level("")
                        .category(detectCategoryFromTitle(title))
                        .metadata(Map.of("type", "guideline", "title", title))
                        .build());
            }
        }

        return documents;
    }

    /**
     * Fetches Understanding pages — detailed explanations for each criterion.
     */
    private List<RagDocument> fetchUnderstandingPages(WebClient webClient) {
        List<RagDocument> documents = new ArrayList<>();

        // All criterion slugs for Understanding pages
        List<String> criteriaIds = List.of(
                "non-text-content", "audio-only-and-video-only-prerecorded",
                "captions-prerecorded", "audio-description-or-media-alternative-prerecorded",
                "info-and-relationships", "meaningful-sequence", "sensory-characteristics",
                "use-of-color", "audio-control", "contrast-minimum", "resize-text",
                "images-of-text", "keyboard", "no-keyboard-trap", "timing-adjustable",
                "pause-stop-hide", "three-flashes-or-below-threshold",
                "bypass-blocks", "page-titled", "focus-order", "link-purpose-in-context",
                "multiple-ways", "headings-and-labels", "focus-visible",
                "language-of-page", "language-of-parts",
                "on-input", "on-focus", "consistent-navigation", "consistent-identification",
                "error-identification", "labels-or-instructions", "error-suggestion",
                "name-role-value",
                // WCAG 2.2 new criteria
                "focus-not-obscured-minimum", "dragging-movements",
                "target-size-minimum", "accessible-authentication-minimum",
                "redundant-entry"
        );

        for (String criterionSlug : criteriaIds) {
            try {
                String url = understandingUrl + criterionSlug;
                String html = webClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                if (html != null) {
                    Document doc = Jsoup.parse(html, url);
                    documents.addAll(parseUnderstandingPage(doc, criterionSlug, url));
                    cacheLocally(html, "understanding-" + criterionSlug + ".html");
                }

                // Small delay to avoid overloading the W3C server
                Thread.sleep(500);

            } catch (Exception e) {
                log.warn("Could not load Understanding page for {}: {}", criterionSlug, e.getMessage());
            }
        }

        return documents;
    }

    /**
     * Parses an Understanding page — extracts Intent, Benefits, Examples, Techniques sections.
     */
    private List<RagDocument> parseUnderstandingPage(Document doc, String criterionSlug, String url) {
        List<RagDocument> documents = new ArrayList<>();

        String title = doc.title();
        String criterionId = extractCriterionIdFromTitle(title);

        // Intent section
        Element intentSection = doc.selectFirst("#intent");
        if (intentSection != null) {
            String intentText = intentSection.text();
            documents.add(RagDocument.builder()
                    .content(String.format("WCAG Understanding %s - Намір (Intent): %s", criterionId, intentText))
                    .source(SOURCE)
                    .criterion(criterionId)
                    .level("")
                    .category(detectCategory(criterionId, intentText))
                    .metadata(Map.of("type", "understanding-intent", "url", url, "slug", criterionSlug))
                    .build());
        }

        // Benefits section
        Element benefitsSection = doc.selectFirst("#benefits");
        if (benefitsSection != null) {
            documents.add(RagDocument.builder()
                    .content(String.format("WCAG Understanding %s - Переваги (Benefits): %s", criterionId, benefitsSection.text()))
                    .source(SOURCE)
                    .criterion(criterionId)
                    .level("")
                    .category(detectCategory(criterionId, ""))
                    .metadata(Map.of("type", "understanding-benefits", "url", url))
                    .build());
        }

        // Techniques section
        Element techniquesSection = doc.selectFirst("#techniques");
        if (techniquesSection != null) {
            documents.add(RagDocument.builder()
                    .content(String.format("WCAG Understanding %s - Техніки (Techniques): %s", criterionId, techniquesSection.text()))
                    .source(SOURCE)
                    .criterion(criterionId)
                    .level("")
                    .category(detectCategory(criterionId, ""))
                    .metadata(Map.of("type", "understanding-techniques", "url", url))
                    .build());
        }

        // Examples section
        Element examplesSection = doc.selectFirst("#examples");
        if (examplesSection != null) {
            documents.add(RagDocument.builder()
                    .content(String.format("WCAG Understanding %s - Приклади (Examples): %s", criterionId, examplesSection.text()))
                    .source(SOURCE)
                    .criterion(criterionId)
                    .level("")
                    .category(detectCategory(criterionId, ""))
                    .metadata(Map.of("type", "understanding-examples", "url", url))
                    .build());
        }

        return documents;
    }

    private List<RagDocument> parseWcagDocument(Document doc, String filename) {
        if (filename.startsWith("understanding-")) {
            String slug = filename.replace("understanding-", "").replace(".html", "");
            return parseUnderstandingPage(doc, slug, "local://" + filename);
        }
        return parseWcagMainPage(doc);
    }

    // --- Utility methods ---

    private String extractCriterionId(Element sc) {
        String id = sc.id(); // e.g., "non-text-content" or "sc_1.1.1"
        Element heading = sc.selectFirst("h4, h3");
        if (heading != null) {
            String text = heading.text();
            // Look for pattern like "1.1.1" or "Success Criterion 1.1.1"
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+\\.\\d+\\.\\d+)")
                    .matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        }
        return id;
    }

    private String extractLevel(Element sc) {
        Element levelEl = sc.selectFirst(".conformance-level");
        if (levelEl != null) {
            String text = levelEl.text().toUpperCase();
            if (text.contains("AAA")) return "AAA";
            if (text.contains("AA")) return "AA";
            if (text.contains("A")) return "A";
        }
        return "";
    }

    private String extractTitle(Element el) {
        Element heading = el.selectFirst("h2, h3, h4");
        return heading != null ? heading.text() : "";
    }

    private String extractCriterionIdFromTitle(String title) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+\\.\\d+)")
                .matcher(title);
        return m.find() ? m.group(1) : "";
    }

    private String detectCategory(String criterionId, String content) {
        if (criterionId.isEmpty()) return "general";

        String prefix = criterionId.substring(0, Math.min(3, criterionId.length()));
        return switch (prefix) {
            case "1.1" -> "text-alternatives";
            case "1.2" -> "media";
            case "1.3" -> "structure";
            case "1.4" -> "contrast";
            case "2.1" -> "keyboard";
            case "2.2" -> "timing";
            case "2.3" -> "seizures";
            case "2.4" -> "navigation";
            case "2.5" -> "input-modalities";
            case "3.1" -> "language";
            case "3.2" -> "predictable";
            case "3.3" -> "input-assistance";
            case "4.1" -> "compatible";
            default -> "general";
        };
    }

    private String detectCategoryFromTitle(String title) {
        String lower = title.toLowerCase();
        if (lower.contains("perceivable") || lower.contains("сприйнят")) return "perceivable";
        if (lower.contains("operable") || lower.contains("керован")) return "operable";
        if (lower.contains("understandable") || lower.contains("зрозуміл")) return "understandable";
        if (lower.contains("robust") || lower.contains("надійн")) return "robust";
        return "general";
    }

    private void cacheLocally(String content, String filename) {
        try {
            Path cacheDir = Path.of(localDataPath, SOURCE);
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(filename), content);
            log.debug("Cached: {}", filename);
        } catch (IOException e) {
            log.warn("Could not cache {}: {}", filename, e.getMessage());
        }
    }

    private boolean hasFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }
}
