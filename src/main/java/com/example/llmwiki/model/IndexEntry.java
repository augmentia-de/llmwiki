package com.example.llmwiki.model;

import java.util.List;

/**
 * Entry in the wiki index (index.md).
 */
public record IndexEntry(
    String slug,
    String title,
    String category,
    String summary      // 1-line summary
) {
    /**
     * Markdown line for the index entry.
     */
    public String toMarkdownLine() {
        return "- [[%s]] — %s".formatted(slug, summary);
    }
}
