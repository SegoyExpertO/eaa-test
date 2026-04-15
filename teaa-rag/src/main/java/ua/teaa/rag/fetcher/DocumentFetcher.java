package ua.teaa.rag.fetcher;

import ua.teaa.rag.model.RagDocument;

import java.util.List;

/**
 * Interface for loading and parsing documents from various sources.
 * Each source (WCAG, axe-core, EN 301 549, Tizen) has its own implementation.
 *
 * Supports two modes:
 * - Local: reads from rag-data/ directory (takes priority)
 * - Remote: downloads from the internet via WebClient
 */
public interface DocumentFetcher {

    /**
     * Loads and parses documents from the source.
     * Checks local files first; falls back to remote download if none found.
     *
     * @return list of parsed documents with metadata
     */
    List<RagDocument> fetch();

    /**
     * Source identifier: wcag, axe-core, en301549, tizen
     */
    String getSource();

    /**
     * Whether remote download is supported
     */
    boolean supportsRemote();
}
