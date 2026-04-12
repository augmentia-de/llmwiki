package com.example.llmwiki.model;

import java.util.List;

/**
 * Result of the lint health check.
 */
public record LintReport(
    int totalPages,
    int totalSources,
    List<String> orphanPages,       // Pages without inbound links
    List<String> missingPages,      // Mentioned slugs without their own page
    List<String> suggestions        // Suggestions for new pages/queries
) {}
