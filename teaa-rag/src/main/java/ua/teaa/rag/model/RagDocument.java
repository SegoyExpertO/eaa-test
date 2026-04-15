package ua.teaa.rag.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class RagDocument {

    private String content;
    private String source;
    private String criterion;
    private String level;
    private String category;
    private Map<String, Object> metadata;
}
