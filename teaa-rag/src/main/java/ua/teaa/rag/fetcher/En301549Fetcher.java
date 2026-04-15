package ua.teaa.rag.fetcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class En301549Fetcher implements DocumentFetcher {

    private static final String SOURCE = "en301549";

    // Matches clause numbers like "9.1.1.1", "5.2", "11.3.2.4"
    private static final Pattern CLAUSE_PATTERN = Pattern.compile(
            "^(\\d+\\.(?:\\d+\\.?){1,3})\\s+(.+)$", Pattern.MULTILINE);

    private final WebClient.Builder webClientBuilder;

    @Value("${teaa.fetcher.en301549.pdf-url}")
    private String pdfUrl;

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
            log.info("Loading EN 301 549 from local files: {}", localDir);
            return fetchFromLocal(localDir);
        }

        log.info("Loading EN 301 549 PDF from: {}", pdfUrl);
        return fetchFromRemote();
    }

    private List<RagDocument> fetchFromLocal(Path localDir) {
        List<RagDocument> documents = new ArrayList<>();

        try (Stream<Path> files = Files.walk(localDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".pdf") || p.toString().endsWith(".txt"))
                    .forEach(file -> {
                        try {
                            if (file.toString().endsWith(".pdf")) {
                                documents.addAll(parsePdfBytes(Files.readAllBytes(file)));
                            } else {
                                documents.addAll(parseTextContent(Files.readString(file)));
                            }
                        } catch (IOException e) {
                            log.error("Error reading file {}: {}", file, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Error scanning directory {}: {}", localDir, e.getMessage());
        }

        log.info("EN 301 549: loaded {} documents from local files", documents.size());
        return documents;
    }

    private List<RagDocument> fetchFromRemote() {
        WebClient webClient = webClientBuilder
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();

        try {
            byte[] pdfBytes = webClient.get()
                    .uri(pdfUrl)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();

            if (pdfBytes == null || pdfBytes.length == 0) {
                log.warn("EN 301 549: PDF not downloaded");
                return List.of();
            }

            log.info("EN 301 549: downloaded PDF {} bytes", pdfBytes.length);
            cacheLocallyBytes(pdfBytes, "en_301549.pdf");

            return parsePdfBytes(pdfBytes);

        } catch (Exception e) {
            log.error("Error loading EN 301 549 PDF: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RagDocument> parsePdfBytes(byte[] pdfBytes) {
        try (PDDocument pdDoc = Loader.loadPDF(pdfBytes)) {

            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(pdDoc);

            log.info("EN 301 549: extracted {} characters from PDF", fullText.length());
            return parseTextContent(fullText);

        } catch (IOException e) {
            log.error("Error parsing EN 301 549 PDF: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Parses EN 301 549 text — splits into clauses/sections.
     * Each numbered clause (e.g. 9.1.1.1, 11.5.2.3) becomes a separate RagDocument.
     */
    List<RagDocument> parseTextContent(String text) {
        List<RagDocument> documents = new ArrayList<>();

        // Split on clause boundaries
        String[] sections = text.split("(?=\\n\\d+\\.\\d+)");

        for (String section : sections) {
            section = section.strip();
            if (section.length() < 50) continue;

            // Look for clause number at the start
            Matcher m = Pattern.compile("^(\\d+\\.(?:\\d+\\.?){0,3})\\s+(.{5,100})").matcher(section);
            if (!m.find()) continue;

            String clauseNum = m.group(1).stripTrailing().replaceAll("\\.$", "");
            String clauseTitle = m.group(2).strip();

            String category = detectCategory(clauseNum);
            String wcagCriterion = mapToWcag(clauseNum);
            String level = detectLevel(section);

            // Truncate to a reasonable size for a single document
            String content = section.length() > 1500 ? section.substring(0, 1500) : section;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "en301549-clause");
            metadata.put("clause", clauseNum);
            metadata.put("title", clauseTitle);
            if (!wcagCriterion.isEmpty()) metadata.put("wcag_mapping", wcagCriterion);

            documents.add(RagDocument.builder()
                    .content(String.format("EN 301 549 пункт %s %s: %s", clauseNum, clauseTitle, content))
                    .source(SOURCE)
                    .criterion(wcagCriterion)
                    .level(level)
                    .category(category)
                    .metadata(metadata)
                    .build());
        }

        // Fallback: if no clauses found, store text as plain chunks
        if (documents.isEmpty()) {
            documents.addAll(splitIntoChunks(text));
        }

        log.info("EN 301 549: parsed {} documents", documents.size());
        return documents;
    }

    private List<RagDocument> splitIntoChunks(String text) {
        List<RagDocument> documents = new ArrayList<>();
        int chunkSize = 1500;

        for (int i = 0; i < text.length(); i += chunkSize) {
            String chunk = text.substring(i, Math.min(i + chunkSize, text.length())).strip();
            if (chunk.length() < 100) continue;

            documents.add(RagDocument.builder()
                    .content("EN 301 549: " + chunk)
                    .source(SOURCE)
                    .criterion("")
                    .level("")
                    .category("general")
                    .metadata(Map.of("type", "en301549-chunk", "offset", i))
                    .build());
        }

        return documents;
    }

    /**
     * Determines category from EN 301 549 clause number.
     * Chapter 9 — Web, Chapter 10 — Non-web, Chapter 11 — Software.
     */
    private String detectCategory(String clauseNum) {
        if (clauseNum.startsWith("9.")) return "web";
        if (clauseNum.startsWith("10.")) return "non-web";
        if (clauseNum.startsWith("11.")) return "software";
        if (clauseNum.startsWith("5.")) return "generic";
        if (clauseNum.startsWith("6.")) return "ict-with-voice";
        if (clauseNum.startsWith("7.")) return "ict-with-video";
        if (clauseNum.startsWith("8.")) return "hardware";
        if (clauseNum.startsWith("12.")) return "documentation";
        if (clauseNum.startsWith("13.")) return "ict-providing-relay";
        return "general";
    }

    /**
     * Maps EN 301 549 chapter 9 clauses to WCAG criteria.
     * Chapter 9 of EN 301 549 maps 1:1 to WCAG 2.1.
     */
    private String mapToWcag(String clauseNum) {
        if (clauseNum.startsWith("9.")) {
            return clauseNum.substring(2); // "9.1.1.1" -> "1.1.1"
        }
        // Chapter 11 (Software) partially maps too
        if (clauseNum.startsWith("11.")) {
            String sub = clauseNum.substring(3);
            if (sub.matches("\\d+\\.\\d+\\.\\d+")) return sub;
        }
        return "";
    }

    private String detectLevel(String text) {
        String upper = text.toUpperCase();
        if (upper.contains("LEVEL AAA") || upper.contains("(AAA)")) return "AAA";
        if (upper.contains("LEVEL AA") || upper.contains("(AA)")) return "AA";
        if (upper.contains("LEVEL A") || upper.contains("(A)")) return "A";
        return "";
    }

    private void cacheLocallyBytes(byte[] content, String filename) {
        try {
            Path cacheDir = Path.of(localDataPath, SOURCE);
            Files.createDirectories(cacheDir);
            Files.write(cacheDir.resolve(filename), content);
            log.debug("Cached PDF: {}", filename);
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
