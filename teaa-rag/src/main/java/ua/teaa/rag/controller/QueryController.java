package ua.teaa.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ua.teaa.rag.service.QueryService;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;

    /**
     * Analyses Tizen TV app code for accessibility violations via RAG.
     * POST /api/v1/rag/analyze
     * Body: { "appContext": "..." }
     */
    @PostMapping("/analyze")
    public Mono<ResponseEntity<Map<String, Object>>> analyzeCode(@RequestBody Map<String, String> request) {
        String appContext = request.get("appContext");
        if (appContext == null || appContext.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "appContext is required")));
        }

        log.info("REST: code analysis request");
        return Mono.fromCallable(() -> queryService.analyzeCode(appContext))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.ok(Map.<String, Object>of(
                        "findings", result,
                        "status", "COMPLETED"
                )));
    }

    /**
     * Interactive AI chat about a specific violation or accessibility criterion.
     * POST /api/v1/rag/chat
     * Body: { "question": "...", "auditContext": "..." }
     */
    @PostMapping("/chat")
    public Mono<ResponseEntity<Map<String, Object>>> chat(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "question is required")));
        }

        String auditContext = request.get("auditContext");
        log.info("REST: chat request — {}", question);

        return Mono.fromCallable(() -> queryService.chat(question, auditContext))
                .subscribeOn(Schedulers.boundedElastic())
                .map(answer -> ResponseEntity.ok(Map.<String, Object>of(
                        "answer", answer,
                        "status", "COMPLETED"
                )));
    }
}
