package ua.teaa.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ua.teaa.rag.service.IngestService;
import ua.teaa.rag.service.IngestService.IngestResult;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/rag/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingestService;

    /**
     * Triggers full ingestion of all sources.
     * POST /api/v1/rag/ingest
     */
    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> ingestAll() {
        log.info("REST: full ingestion request");
        return Mono.fromCallable(ingestService::ingestAll)
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.ok(Map.<String, Object>of(
                        "documentsProcessed", result.documentsProcessed(),
                        "chunksStored", result.chunksStored(),
                        "errors", result.errors(),
                        "status", result.errors().isEmpty() ? "SUCCESS" : "PARTIAL"
                )));
    }

    /**
     * Triggers ingestion of a specific source.
     * POST /api/v1/rag/ingest/{source}
     */
    @PostMapping("/{source}")
    public Mono<ResponseEntity<Map<String, Object>>> ingestSource(@PathVariable String source) {
        log.info("REST: ingestion request for source '{}'", source);
        return Mono.fromCallable(() -> ingestService.ingestSource(source))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.ok(Map.<String, Object>of(
                        "source", source,
                        "documentsProcessed", result.documentsProcessed(),
                        "chunksStored", result.chunksStored(),
                        "errors", result.errors(),
                        "status", result.errors().isEmpty() ? "SUCCESS" : "PARTIAL"
                )))
                .onErrorReturn(IllegalArgumentException.class,
                        ResponseEntity.badRequest().body(Map.of(
                                "error", "Unknown source: " + source,
                                "availableSources", ingestService.getAvailableSources()
                        )));
    }

    /**
     * Returns list of available source identifiers.
     * GET /api/v1/rag/ingest/sources
     */
    @GetMapping("/sources")
    public Mono<ResponseEntity<Map<String, Object>>> getSources() {
        List<String> sources = ingestService.getAvailableSources();
        return Mono.just(ResponseEntity.ok(Map.<String, Object>of("sources", sources)));
    }
}
