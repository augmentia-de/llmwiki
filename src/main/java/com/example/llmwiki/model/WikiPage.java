package com.example.llmwiki.model;

import java.util.List;

/**
 * Represents a wiki page as a data object.
 * Storage is done as Markdown files.
 */
public record WikiPage(
    String slug,
    String title,
    String category,       // entity, concept, source-summary, analysis
    String content,        // Markdown content (without frontmatter)
    List<String> sources,  // References to ingested sources
    String created,
    String updated
) {
    public String directory() {
        return switch (category) {
            case "entity" -> "entities";
            case "concept" -> "concepts";
            case "source-summary" -> "sources";
            case "analysis" -> "analyses";
            case "project" -> "projects";
            case "technology" -> "technologies";
            default -> "other";
        };
    }

    public String fileName() {
        return slug + ".md";
    }

    /**
     * Generates the full Markdown content with frontmatter.
     */
    public String toMarkdown() {
        return """
            ---
            slug: %s
            title: %s
            category: %s
            sources: %s
            created: %s
            updated: %s
            ---
            
            # %s
            
            %s
            """.formatted(
            slug, title, category,
            sources != null ? sources.toString() : "[]",
            created, updated != null ? updated : created,
            title, content
        );
    }

    /**
     * Parses a wiki page from Markdown content with frontmatter.
     */
    public static WikiPage fromMarkdown(String slug, String fullPath, String markdown) {
        String[] parts = markdown.split("---", 3);
        if (parts.length < 3) {
            // No frontmatter — only content
            return new WikiPage(slug, extractTitle(markdown), "unknown", markdown.trim(),
                List.of(), "", "");
        }

        String frontmatter = parts[1].trim();
        String content = parts[2].trim();

        String title = extractYamlValue(frontmatter, "title");
        String category = extractYamlValue(frontmatter, "category");
        String created = extractYamlValue(frontmatter, "created");
        String updated = extractYamlValue(frontmatter, "updated");
        String sourcesStr = extractYamlValue(frontmatter, "sources");

        List<String> sources = List.of();
        if (sourcesStr != null && !sourcesStr.equals("[]")) {
            sources = List.of(sourcesStr.replaceAll("[\\[\\] ]", "").split(","));
        }

        return new WikiPage(slug, title, category, content, sources,
            created != null ? created : "", updated != null ? updated : "");
    }

    private static String extractTitle(String markdown) {
        String[] lines = markdown.split("\n");
        for (String line : lines) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "Untitled";
    }

    private static String extractYamlValue(String yaml, String key) {
        for (String line : yaml.split("\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }
}
