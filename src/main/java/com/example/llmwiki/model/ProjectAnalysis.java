package com.example.llmwiki.model;

import java.util.List;
import java.util.Map;

/**
 * Represents the analysis result of a software project.
 */
public record ProjectAnalysis(
    String projectName,
    String projectUrl,         // GitHub URL or local path
    String description,
    List<String> technologies, // e.g. ["Java", "Quarkus", "LangChain4j"]
    List<String> keywords,     // e.g. ["RAG", "Agent", "Wiki"]
    List<String> dependencies, // e.g. ["langchain4j", "jsoup"]
    String architectureSummary,
    Map<String, String> keyFiles  // fileName → short description
) {
    public static ProjectAnalysis empty() {
        return new ProjectAnalysis(
            "Unknown", "", "", List.of(), List.of(), List.of(), "", Map.of()
        );
    }

    public String slug() {
        String raw;
        if (projectUrl != null && !projectUrl.isBlank()) {
            raw = projectUrl;
        } else if (projectName != null && !projectName.isBlank()) {
            raw = projectName;
        } else {
            return "unknown";
        }

        String cleaned = raw.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");

        if (cleaned.isEmpty()) return "unknown";

        int len = Math.min(cleaned.length(), 60);
        return cleaned.substring(0, len);
    }
}
