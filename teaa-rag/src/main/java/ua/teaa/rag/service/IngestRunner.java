package ua.teaa.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngestRunner implements ApplicationRunner {

    private final IngestService ingestService;
    private final VectorStore vectorStore;

    @Value("${teaa.rag.ingest.auto-ingest-on-startup:true}")
    private boolean autoIngestOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        if (!autoIngestOnStartup) {
            log.info("Auto-ingestion disabled (teaa.rag.ingest.auto-ingest-on-startup=false)");
            return;
        }

        if (isVectorStoreEmpty()) {
            log.info("Vector store is empty — starting full ingestion...");
            IngestService.IngestResult result = ingestService.ingestAll();
            log.info("Auto-ingestion complete: {} documents, {} fragments, {} errors",
                    result.documentsProcessed(), result.chunksStored(), result.errors().size());
            if (!result.errors().isEmpty()) {
                result.errors().forEach(e -> log.warn("  - {}", e));
            }
        } else {
            log.info("Vector store already contains data — ingestion skipped");
        }
    }

    private boolean isVectorStoreEmpty() {
        try {
            // Probe search — if no results, the store is empty
            var results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("WCAG accessibility")
                            .topK(1)
                            .build()
            );
            return results == null || results.isEmpty();
        } catch (Exception e) {
            log.warn("Could not check vector store state: {} — assuming empty", e.getMessage());
            return true;
        }
    }
}
