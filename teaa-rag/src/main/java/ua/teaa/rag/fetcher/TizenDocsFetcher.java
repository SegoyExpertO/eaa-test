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
public class TizenDocsFetcher implements DocumentFetcher {

    private static final String SOURCE = "tizen";

    /**
     * Key Tizen accessibility documentation pages to ingest.
     * These cover the primary accessibility APIs and guides for Tizen TV apps.
     */
    private static final List<String> TIZEN_ACCESSIBILITY_PAGES = List.of(
            "develop/guides/fundamentals/accessibility.html",
            "develop/guides/user-interaction/user-interaction.html",
            "develop/guides/user-interaction/remote-control.html",
            "develop/guides/user-interaction/keyboardime.html",
            "develop/faq/user-interaction.html"
    );

    private final WebClient.Builder webClientBuilder;

    @Value("${teaa.fetcher.tizen.docs-url}")
    private String docsUrl;

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
            log.info("Loading Tizen docs from local files: {}", localDir);
            return fetchFromLocal(localDir);
        }

        log.info("Loading Tizen docs from internet: {}", docsUrl);
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
                            documents.addAll(parseTizenPage(doc, file.getFileName().toString(),
                                    "local://" + file.getFileName()));
                        } catch (IOException e) {
                            log.error("Error reading file {}: {}", file, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Error scanning directory {}: {}", localDir, e.getMessage());
        }

        log.info("Tizen: loaded {} documents from local files", documents.size());
        return documents;
    }

    private List<RagDocument> fetchFromRemote() {
        List<RagDocument> documents = new ArrayList<>();
        WebClient webClient = webClientBuilder
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        for (String page : TIZEN_ACCESSIBILITY_PAGES) {
            String url = docsUrl.endsWith("/") ? docsUrl + page : docsUrl + "/" + page;
            url = url.stripTrailing().replaceAll("/$", "");

            try {
                String html = webClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                if (html != null) {
                    Document doc = Jsoup.parse(html, url);
                    documents.addAll(parseTizenPage(doc, page.isEmpty() ? "index" : page, url));
                    String filename = page.isEmpty() ? "tizen-accessibility-main.html"
                            : "tizen-" + page.replace("/", "-");
                    cacheLocally(html, filename);
                }

                Thread.sleep(500);

            } catch (Exception e) {
                log.warn("Could not load Tizen page {}: {}", url, e.getMessage());
            }
        }

        // Additionally try to discover more pages from the main page's navigation
        documents.addAll(fetchDiscoveredPages(webClient, documents));

        log.info("Tizen: loaded {} documents from internet", documents.size());
        return documents;
    }

    /**
     * Tries to discover additional accessibility pages from navigation links on the main page.
     */
    private List<RagDocument> fetchDiscoveredPages(WebClient webClient, List<RagDocument> alreadyFetched) {
        List<RagDocument> extra = new ArrayList<>();

        try {
            String mainHtml = webClient.get()
                    .uri(docsUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (mainHtml == null) return extra;

            Document mainDoc = Jsoup.parse(mainHtml, docsUrl);

            // Find links in navigation or content that point to accessibility-related pages
            Elements links = mainDoc.select("a[href]");
            for (Element link : links) {
                String href = link.attr("abs:href");
                String linkText = link.text().toLowerCase();

                if (!href.contains("tizen.org")) continue;
                if (!isAccessibilityRelated(linkText, href)) continue;

                // Skip already fetched pages
                boolean alreadyCovered = alreadyFetched.stream()
                        .anyMatch(d -> href.equals(d.getMetadata().getOrDefault("url", "").toString()));
                if (alreadyCovered) continue;

                try {
                    String html = webClient.get()
                            .uri(href)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();

                    if (html != null) {
                        Document doc = Jsoup.parse(html, href);
                        extra.addAll(parseTizenPage(doc, linkText, href));
                        String filename = "tizen-discovered-" + Math.abs(href.hashCode()) + ".html";
                        cacheLocally(html, filename);
                    }

                    Thread.sleep(500);
                } catch (Exception e) {
                    log.debug("Could not load discovered page {}: {}", href, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Could not discover additional Tizen pages: {}", e.getMessage());
        }

        return extra;
    }

    /**
     * Parses a Tizen documentation page into RagDocuments.
     * Extracts sections, code examples, and API descriptions.
     */
    private List<RagDocument> parseTizenPage(Document doc, String pageName, String url) {
        List<RagDocument> documents = new ArrayList<>();

        String pageTitle = doc.title();

        // Remove navigation, header, footer noise
        doc.select("nav, header, footer, .sidebar, #sidebar, .navigation, script, style").remove();

        // Main content area
        Element mainContent = doc.selectFirst("main, article, .content, #content, .main-content, body");
        if (mainContent == null) return documents;

        // Process h2/h3 sections as separate documents
        Elements headings = mainContent.select("h2, h3");

        if (!headings.isEmpty()) {
            for (Element heading : headings) {
                StringBuilder sectionText = new StringBuilder();
                sectionText.append(heading.text()).append("\n");

                // Collect siblings until next heading of same/higher level
                Element next = heading.nextElementSibling();
                while (next != null && !next.tagName().matches("h[123]")) {
                    sectionText.append(next.text()).append("\n");
                    next = next.nextElementSibling();
                }

                String content = sectionText.toString().strip();
                if (content.length() < 50) continue;

                String category = detectCategory(heading.text(), content);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("type", "tizen-section");
                metadata.put("page", pageName);
                metadata.put("url", url);
                metadata.put("section", heading.text());

                documents.add(RagDocument.builder()
                        .content(String.format("Tizen TV доступність — %s — %s: %s",
                                pageTitle, heading.text(), content))
                        .source(SOURCE)
                        .criterion("")
                        .level("")
                        .category(category)
                        .metadata(metadata)
                        .build());
            }
        } else {
            // No headings — use full page text
            String fullText = mainContent.text().strip();
            if (fullText.length() > 100) {
                documents.add(RagDocument.builder()
                        .content(String.format("Tizen TV доступність — %s: %s", pageTitle, fullText))
                        .source(SOURCE)
                        .criterion("")
                        .level("")
                        .category(detectCategory(pageTitle, fullText))
                        .metadata(Map.of("type", "tizen-page", "page", pageName, "url", url))
                        .build());
            }
        }

        // Extract code examples separately — they're valuable for TV-specific patterns
        Elements codeBlocks = mainContent.select("pre code, pre.highlight, .code-block");
        for (Element code : codeBlocks) {
            String codeText = code.text().strip();
            if (codeText.length() < 30) continue;

            documents.add(RagDocument.builder()
                    .content(String.format("Tizen TV код — %s: %s", pageTitle, codeText))
                    .source(SOURCE)
                    .criterion("")
                    .level("")
                    .category("code-example")
                    .metadata(Map.of("type", "tizen-code-example", "page", pageName, "url", url))
                    .build());
        }

        log.debug("Tizen: page '{}' -> {} documents", pageName, documents.size());
        return documents;
    }

    private boolean isAccessibilityRelated(String linkText, String href) {
        String combined = (linkText + " " + href).toLowerCase();
        return combined.contains("accessib") || combined.contains("tts") || combined.contains("text-to-speech")
                || combined.contains("focus") || combined.contains("contrast") || combined.contains("font")
                || combined.contains("screen-reader") || combined.contains("keyboard");
    }

    private String detectCategory(String title, String content) {
        String combined = (title + " " + content).toLowerCase();
        if (combined.contains("tts") || combined.contains("text-to-speech") || combined.contains("screen reader"))
            return "tts";
        if (combined.contains("focus") || combined.contains("d-pad") || combined.contains("remote control"))
            return "focus-navigation";
        if (combined.contains("contrast") || combined.contains("color"))
            return "contrast";
        if (combined.contains("font") || combined.contains("text size"))
            return "text-size";
        if (combined.contains("aria") || combined.contains("role") || combined.contains("semantic"))
            return "aria-semantics";
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
