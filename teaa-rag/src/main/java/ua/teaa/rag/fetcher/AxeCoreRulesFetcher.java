package ua.teaa.rag.fetcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class AxeCoreRulesFetcher implements DocumentFetcher {

    private static final String SOURCE = "axe-core";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${teaa.fetcher.axe-core.github-api-url}")
    private String githubApiUrl;

    @Value("${teaa.fetcher.axe-core.raw-base-url}")
    private String rawBaseUrl;

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
            log.info("Loading axe-core from local files: {}", localDir);
            return fetchFromLocal(localDir);
        }

        log.info("Loading axe-core from GitHub: {}", rawBaseUrl);
        return fetchFromRemote();
    }

    private List<RagDocument> fetchFromLocal(Path localDir) {
        List<RagDocument> documents = new ArrayList<>();

        try (Stream<Path> files = Files.walk(localDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(file -> {
                        try {
                            String json = Files.readString(file);
                            documents.addAll(parseRuleJson(json, file.getFileName().toString()));
                        } catch (IOException e) {
                            log.error("Error reading file {}: {}", file, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Error scanning directory {}: {}", localDir, e.getMessage());
        }

        // Also read rule-descriptions.md if present
        Path descriptionsFile = localDir.resolve("rule-descriptions.md");
        if (Files.exists(descriptionsFile)) {
            try {
                documents.addAll(parseRuleDescriptionsMd(Files.readString(descriptionsFile)));
            } catch (IOException e) {
                log.error("Error reading rule-descriptions.md: {}", e.getMessage());
            }
        }

        log.info("axe-core: loaded {} documents from local files", documents.size());
        return documents;
    }

    private List<RagDocument> fetchFromRemote() {
        List<RagDocument> documents = new ArrayList<>();
        WebClient webClient = webClientBuilder
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        try {
            // 1. Fetch rule-descriptions.md — summary table of all rules
            String ruleDescsMd = fetchFile(webClient, "/doc/rule-descriptions.md");
            if (ruleDescsMd != null) {
                documents.addAll(parseRuleDescriptionsMd(ruleDescsMd));
                cacheLocally(ruleDescsMd, "rule-descriptions.md");
            }

            // 2. Fetch individual rule JSON files from lib/rules/
            List<String> ruleFiles = listGitHubDirectory(webClient, "lib/rules");

            for (String ruleFile : ruleFiles) {
                if (!ruleFile.endsWith(".json")) continue;

                try {
                    String ruleJson = fetchFile(webClient, "/lib/rules/" + ruleFile);
                    if (ruleJson != null) {
                        documents.addAll(parseRuleJson(ruleJson, ruleFile));
                        cacheLocally(ruleJson, ruleFile);
                    }
                    Thread.sleep(200); // Avoid overloading the GitHub API
                } catch (Exception e) {
                    log.warn("Could not load rule {}: {}", ruleFile, e.getMessage());
                }
            }

            // 3. Fetch check JSON files from lib/checks/
            List<String> checkFiles = listGitHubDirectory(webClient, "lib/checks");
            for (String dir : checkFiles) {
                List<String> checkJsonFiles = listGitHubDirectory(webClient, "lib/checks/" + dir);
                for (String checkFile : checkJsonFiles) {
                    if (!checkFile.endsWith(".json")) continue;
                    try {
                        String checkJson = fetchFile(webClient, "/lib/checks/" + dir + "/" + checkFile);
                        if (checkJson != null) {
                            documents.addAll(parseCheckJson(checkJson, dir, checkFile));
                            cacheLocally(checkJson, "check-" + dir + "-" + checkFile);
                        }
                        Thread.sleep(200);
                    } catch (Exception e) {
                        log.warn("Could not load check {}/{}: {}", dir, checkFile, e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error loading axe-core from GitHub: {}", e.getMessage());
        }

        log.info("axe-core: loaded {} documents from GitHub", documents.size());
        return documents;
    }

    /**
     * Parses an axe-core rule JSON file.
     * Structure: { id, selector, tags, metadata: { description, help } }
     */
    private List<RagDocument> parseRuleJson(String json, String filename) {
        List<RagDocument> documents = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            String ruleId = root.path("id").asText(filename.replace(".json", ""));
            String description = root.path("metadata").path("description").asText("");
            String help = root.path("metadata").path("help").asText("");
            String selector = root.path("selector").asText("");

            // Extract tags — map to WCAG criteria
            List<String> tags = new ArrayList<>();
            JsonNode tagsNode = root.path("tags");
            if (tagsNode.isArray()) {
                tagsNode.forEach(tag -> tags.add(tag.asText()));
            }

            String wcagCriterion = extractWcagFromTags(tags);
            String level = extractLevelFromTags(tags);
            String category = extractCategoryFromTags(tags);

            StringBuilder content = new StringBuilder();
            content.append(String.format("axe-core правило '%s': %s", ruleId, description));
            if (!help.isEmpty()) {
                content.append(String.format(" Допомога: %s.", help));
            }
            if (!selector.isEmpty()) {
                content.append(String.format(" CSS селектор: %s.", selector));
            }
            if (!tags.isEmpty()) {
                content.append(String.format(" Теги: %s.", String.join(", ", tags)));
            }
            if (!wcagCriterion.isEmpty()) {
                content.append(String.format(" Відповідає WCAG критерію %s.", wcagCriterion));
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("rule_id", ruleId);
            metadata.put("tags", tags);
            metadata.put("type", "axe-rule");
            if (!selector.isEmpty()) metadata.put("selector", selector);

            documents.add(RagDocument.builder()
                    .content(content.toString())
                    .source(SOURCE)
                    .criterion(wcagCriterion)
                    .level(level)
                    .category(category)
                    .metadata(metadata)
                    .build());

        } catch (Exception e) {
            log.warn("Error parsing axe-core rule {}: {}", filename, e.getMessage());
        }

        return documents;
    }

    /**
     * Parses an axe-core check JSON file.
     */
    private List<RagDocument> parseCheckJson(String json, String checkDir, String filename) {
        List<RagDocument> documents = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            String checkId = root.path("id").asText(filename.replace(".json", ""));

            documents.add(RagDocument.builder()
                    .content(String.format("axe-core перевірка '%s' (категорія: %s): %s",
                            checkId, checkDir, root.toString()))
                    .source(SOURCE)
                    .criterion("")
                    .level("")
                    .category(checkDir)
                    .metadata(Map.of("type", "axe-check", "check_id", checkId, "check_category", checkDir))
                    .build());

        } catch (Exception e) {
            log.warn("Error parsing axe-core check {}/{}: {}", checkDir, filename, e.getMessage());
        }

        return documents;
    }

    /**
     * Parses rule-descriptions.md — the summary table with one row per rule.
     * Format: | rule-id | Description | Impact | Tags | ...
     */
    private List<RagDocument> parseRuleDescriptionsMd(String markdown) {
        List<RagDocument> documents = new ArrayList<>();
        String[] lines = markdown.split("\n");

        for (String line : lines) {
            if (line.startsWith("|") && !line.contains("---") && !line.toLowerCase().contains("rule id")) {
                String[] cells = line.split("\\|");
                if (cells.length >= 4) {
                    String ruleId = cells[1].trim().replaceAll("[`\\[\\]()]", "").trim();
                    String description = cells[2].trim();
                    String impact = cells.length > 3 ? cells[3].trim() : "";
                    String tags = cells.length > 4 ? cells[4].trim() : "";

                    if (!ruleId.isEmpty() && !description.isEmpty()) {
                        documents.add(RagDocument.builder()
                                .content(String.format("axe-core правило '%s': %s. Вплив: %s. Теги: %s",
                                        ruleId, description, impact, tags))
                                .source(SOURCE)
                                .criterion("")
                                .level("")
                                .category("general")
                                .metadata(Map.of("type", "axe-rule-description", "rule_id", ruleId, "impact", impact))
                                .build());
                    }
                }
            }
        }

        return documents;
    }

    // --- GitHub API helpers ---

    private String fetchFile(WebClient webClient, String path) {
        try {
            return webClient.get()
                    .uri(rawBaseUrl + path)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.warn("Could not fetch {}: {}", path, e.getMessage());
            return null;
        }
    }

    private List<String> listGitHubDirectory(WebClient webClient, String path) {
        try {
            String response = webClient.get()
                    .uri(githubApiUrl + "/contents/" + path)
                    .header("Accept", "application/vnd.github.v3+json")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response != null) {
                JsonNode items = objectMapper.readTree(response);
                List<String> names = new ArrayList<>();
                items.forEach(item -> names.add(item.path("name").asText()));
                return names;
            }
        } catch (Exception e) {
            log.warn("Could not list directory {}: {}", path, e.getMessage());
        }
        return List.of();
    }

    // --- Tag metadata extraction helpers ---

    private String extractWcagFromTags(List<String> tags) {
        return tags.stream()
                .filter(t -> t.matches("wcag\\d+"))
                .map(t -> {
                    // wcag111 -> 1.1.1, wcag143 -> 1.4.3
                    String nums = t.replace("wcag", "");
                    if (nums.length() == 3) {
                        return nums.charAt(0) + "." + nums.charAt(1) + "." + nums.charAt(2);
                    }
                    if (nums.length() == 4) {
                        return nums.charAt(0) + "." + nums.charAt(1) + "." + nums.substring(2);
                    }
                    return nums;
                })
                .findFirst()
                .orElse("");
    }

    private String extractLevelFromTags(List<String> tags) {
        if (tags.contains("wcag2aaa")) return "AAA";
        if (tags.contains("wcag2aa")) return "AA";
        if (tags.contains("wcag2a") || tags.contains("wcag21a")) return "A";
        return "";
    }

    private String extractCategoryFromTags(List<String> tags) {
        if (tags.contains("cat.color")) return "contrast";
        if (tags.contains("cat.aria")) return "aria";
        if (tags.contains("cat.forms")) return "forms";
        if (tags.contains("cat.keyboard")) return "keyboard";
        if (tags.contains("cat.language")) return "language";
        if (tags.contains("cat.name-role-value")) return "name-role-value";
        if (tags.contains("cat.parsing")) return "parsing";
        if (tags.contains("cat.semantics")) return "semantics";
        if (tags.contains("cat.sensory-and-visual-cues")) return "sensory";
        if (tags.contains("cat.structure")) return "structure";
        if (tags.contains("cat.tables")) return "tables";
        if (tags.contains("cat.text-alternatives")) return "text-alternatives";
        if (tags.contains("cat.time-and-media")) return "media";
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
