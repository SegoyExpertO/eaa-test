package ua.teaa.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ua.teaa.rag.fetcher.DocumentFetcher;
import ua.teaa.rag.model.RagDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    private final List<DocumentFetcher> fetchers;
    private final VectorStore vectorStore;

    @Value("${teaa.rag.ingest.chunk-size:800}")
    private int chunkSize;

    @Value("${teaa.rag.ingest.chunk-overlap:200}")
    private int chunkOverlap;

    /**
     * Runs full ingestion of all sources.
     */
    public IngestResult ingestAll() {
        log.info("Starting full ingestion of all sources...");
        int totalDocuments = 0;
        int totalChunks = 0;
        List<String> errors = new ArrayList<>();

        for (DocumentFetcher fetcher : fetchers) {
            try {
                IngestResult result = ingestSource(fetcher.getSource());
                totalDocuments += result.documentsProcessed();
                totalChunks += result.chunksStored();
                errors.addAll(result.errors());
            } catch (Exception e) {
                String error = String.format("Ingestion error for %s: %s", fetcher.getSource(), e.getMessage());
                log.error(error, e);
                errors.add(error);
            }
        }

        log.info("Ingestion complete: {} documents, {} fragments, {} errors",
                totalDocuments, totalChunks, errors.size());

        return new IngestResult(totalDocuments, totalChunks, errors);
    }

    /**
     * Runs ingestion for a specific source.
     */
    public IngestResult ingestSource(String source) {
        DocumentFetcher fetcher = fetchers.stream()
                .filter(f -> f.getSource().equals(source))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown source: " + source + ". Available: " +
                                fetchers.stream().map(DocumentFetcher::getSource).toList()));

        log.info("Ingesting source: {}", source);

        // 1. Fetch and parse documents
        List<RagDocument> ragDocuments = fetcher.fetch();
        log.info("{}: received {} documents", source, ragDocuments.size());

        if (ragDocuments.isEmpty()) {
            return new IngestResult(0, 0, List.of("Source " + source + " returned no documents"));
        }

        // 2. Convert to Spring AI Documents
        List<Document> springDocs = ragDocuments.stream()
                .map(this::toSpringAiDocument)
                .toList();

        // 3. Split into chunks
        TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, chunkOverlap, 5, 10000, true);
        List<Document> chunks = splitter.apply(springDocs);
        log.info("{}: split into {} fragments (size: {}, overlap: {})",
                source, chunks.size(), chunkSize, chunkOverlap);

        // 4. Store in pgvector — embeddings are generated automatically by Spring AI
        List<String> errors = new ArrayList<>();
        int batchSize = 10;
        int stored = 0;

        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<Document> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            try {
                vectorStore.add(batch);
                stored += batch.size();
                log.debug("{}: stored {}/{} fragments", source, stored, chunks.size());
            } catch (Exception e) {
                String error = String.format("%s: batch save error %d-%d: %s",
                        source, i, i + batch.size(), e.getMessage());
                log.error(error);
                errors.add(error);
            }
        }

        log.info("{}: ingestion complete — {} fragments stored", source, stored);
        return new IngestResult(ragDocuments.size(), stored, errors);
    }

    /**
     * Returns list of available source identifiers.
     */
    public List<String> getAvailableSources() {
        return fetchers.stream()
                .map(DocumentFetcher::getSource)
                .toList();
    }

    private Document toSpringAiDocument(RagDocument ragDoc) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", ragDoc.getSource());

        if (ragDoc.getCriterion() != null && !ragDoc.getCriterion().isEmpty()) {
            metadata.put("criterion", ragDoc.getCriterion());
        }
        if (ragDoc.getLevel() != null && !ragDoc.getLevel().isEmpty()) {
            metadata.put("level", ragDoc.getLevel());
        }
        if (ragDoc.getCategory() != null && !ragDoc.getCategory().isEmpty()) {
            metadata.put("category", ragDoc.getCategory());
        }
        if (ragDoc.getMetadata() != null) {
            metadata.putAll(ragDoc.getMetadata());
        }

        return new Document(ragDoc.getContent(), metadata);
    }

    public record IngestResult(int documentsProcessed, int chunksStored, List<String> errors) {
    }
}
