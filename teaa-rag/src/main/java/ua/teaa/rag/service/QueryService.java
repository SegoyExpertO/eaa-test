package ua.teaa.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {

    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    @Value("${teaa.rag.query.top-k:12}")
    private int topK;

    @Value("${teaa.rag.query.similarity-threshold:0.7}")
    private double similarityThreshold;

    private static final String SYSTEM_PROMPT = """
            Ти — експерт з доступності (accessibility) для Tizen TV застосунків.
            Твоя задача — аналізувати код застосунків на відповідність стандартам:
            - European Accessibility Act (EAA)
            - WCAG 2.2 (рівні A та AA)
            - EN 301 549

            Для кожного порушення ти повинен:
            1. Визначити конкретний критерій WCAG/EN 301 549
            2. Описати порушення
            3. Вказати severity (critical, serious, moderate, minor)
            4. Надати конкретну рекомендацію щодо виправлення
            5. Враховувати TV-специфіку (D-pad навігація, відстань перегляду 3+ м, TTS)

            Відповідай у форматі JSON масиву:
            [
              {
                "criterion": "1.1.1",
                "wcagLevel": "A",
                "severity": "critical",
                "element": "<img src='...'> at line 15",
                "description": "Зображення не має атрибуту alt",
                "recommendation": "Додати атрибут alt з описовим текстом",
                "tvSpecific": true/false,
                "tvNote": "Опціональна примітка для TV-специфіки"
              }
            ]

            Якщо порушень не знайдено, поверни порожній масив [].
            Відповідай ТІЛЬКИ JSON, без додаткового тексту.
            """;

    /**
     * Analyses app code via RAG.
     *
     * @param appContext structured code context from teaa-parser
     * @return JSON response listing accessibility violations
     */
    public String analyzeCode(String appContext) {
        log.info("Code analysis request (context length: {} chars)", appContext.length());

        // 1. Find relevant standard fragments
        List<Document> relevantChunks = findRelevantChunks(appContext);
        log.info("Found {} relevant standard fragments", relevantChunks.size());

        // 2. Build standards context string
        String standardsContext = relevantChunks.stream()
                .map(doc -> {
                    String source = doc.getMetadata().getOrDefault("source", "").toString();
                    String criterion = doc.getMetadata().getOrDefault("criterion", "").toString();
                    return String.format("[%s %s] %s", source, criterion, doc.getText());
                })
                .collect(Collectors.joining("\n\n"));

        // 3. Build user prompt
        String userPrompt = String.format("""
                Проаналізуй наступний код Tizen TV застосунку на відповідність EAA/WCAG 2.2.

                === СТАНДАРТИ ТА ПРАВИЛА ===
                %s

                === КОД ЗАСТОСУНКУ ===
                %s
                """, standardsContext, appContext);

        // 4. Send to LLM
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        log.info("LLM response received (length: {} chars)", response.length());
        return response;
    }

    /**
     * Interactive chat — user asks about a specific violation or criterion.
     */
    public String chat(String question, String auditContext) {
        List<Document> relevantChunks = findRelevantChunks(question);

        String standardsContext = relevantChunks.stream()
                .map(doc -> String.format("[%s] %s",
                        doc.getMetadata().getOrDefault("source", ""),
                        doc.getText()))
                .collect(Collectors.joining("\n\n"));

        String chatSystemPrompt = """
                Ти — експерт з доступності для Tizen TV застосунків.
                Відповідай українською мовою.
                Посилайся на конкретні критерії WCAG 2.2 та EN 301 549.
                Враховуй TV-специфіку: D-pad навігація, TTS, відстань перегляду.
                """;

        String userPrompt = String.format("""
                === СТАНДАРТИ ===
                %s

                === КОНТЕКСТ АУДИТУ ===
                %s

                === ЗАПИТАННЯ ===
                %s
                """, standardsContext, auditContext != null ? auditContext : "Немає", question);

        ChatClient chatClient = ChatClient.builder(chatModel).build();

        return chatClient.prompt()
                .system(chatSystemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    /**
     * Searches relevant standard fragments via pgvector similarity search.
     */
    private List<Document> findRelevantChunks(String query) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();

        return vectorStore.similaritySearch(searchRequest);
    }

    /**
     * Searches fragments filtered by source.
     */
    public List<Document> findChunksBySource(String query, String source) {
        var filterBuilder = new FilterExpressionBuilder();

        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(filterBuilder.eq("source", source).build())
                .build();

        return vectorStore.similaritySearch(searchRequest);
    }
}
