package com.example.llmwiki.mcp;

import com.example.llmwiki.model.WikiPage;
import com.example.llmwiki.service.WikiFileService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP Server tools for the LLM Wiki.
 *
 * Only active when PROVIDE_MCP_ENDPOINT=true in .env.
 * When disabled, all tool calls return "MCP endpoint disabled".
 *
 * Transports:
 *   SSE:  GET/POST /mcp/sse  (HTTP transport)
 *   stdio: via quarkus-mcp-server-stdio (CLI transport)
 */
@ApplicationScoped
@Slf4j
public class WikiMcpTools {

    @Inject
    WikiFileService wikiFileService;

    private boolean enabled = Boolean.parseBoolean(
        System.getenv().getOrDefault("PROVIDE_MCP_ENDPOINT", "false"));

    /**
     * Search the wiki for pages matching a query.
     * Searches across titles, slugs, categories, and content.
     */
    @Tool(description = "Search the wiki for pages matching a query. " +
            "Returns matching pages with title, slug, category, and a content preview. " +
            "Use this to find wiki pages before answering questions or when exploring topics.")
    public String wikiSearch(
            @ToolArg(description = "Search query — matches against page titles, slugs, categories, and content") String query,
            @ToolArg(description = "Optional category filter: entity, concept, source-summary, analysis. Leave empty to search all categories.") String category,
            @ToolArg(description = "Maximum number of results to return (default 10)") Integer limit) throws IOException {

        if (!enabled) return "MCP endpoint is disabled. Set PROVIDE_MCP_ENDPOINT=true to enable.";

        int maxResults = limit != null ? limit : 10;
        String queryLower = query.toLowerCase();

        List<WikiPage> allPages;
        if (category != null && !category.isBlank()) {
            allPages = wikiFileService.readCategory(category);
        } else {
            allPages = wikiFileService.readAllPages();
        }

        List<WikiPage> results = allPages.stream()
            .filter(p ->
                (p.title() != null && p.title().toLowerCase().contains(queryLower)) ||
                (p.slug() != null && p.slug().toLowerCase().contains(queryLower)) ||
                (p.category() != null && p.category().toLowerCase().contains(queryLower)) ||
                (p.content() != null && p.content().toLowerCase().contains(queryLower))
            )
            .limit(maxResults)
            .toList();

        if (results.isEmpty()) {
            return "No wiki pages found matching query: \"" + query + "\"";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found %d wiki page(s) matching \"%s\":\n\n".formatted(results.size(), query));

        for (WikiPage page : results) {
            String preview = page.content() != null
                ? page.content().replaceAll("#", "").trim()
                : "";
            if (preview.length() > 300) {
                preview = preview.substring(0, 300) + "...";
            }

            sb.append("### %s\n".formatted(page.title()));
            sb.append("- **Slug:** `%s`\n".formatted(page.slug()));
            sb.append("- **Category:** %s\n".formatted(page.category()));
            sb.append("- **Sources:** %s\n".formatted(page.sources() != null ? String.join(", ", page.sources()) : "none"));
            sb.append("- **Preview:** %s\n".formatted(preview));
            sb.append("- **Full content:** Read page [[%s]] for complete details\n".formatted(page.slug()));
            sb.append("\n---\n\n");
        }

        // Also show related index entries
        String index = wikiFileService.readIndex();
        sb.append("\n---\n**Wiki Index excerpt (related entries):**\n");

        // Extract lines from index that mention the query
        String[] indexLines = index.split("\n");
        int shown = 0;
        for (String line : indexLines) {
            if (line.toLowerCase().contains(queryLower) && shown < 5) {
                sb.append(line).append("\n");
                shown++;
            }
        }
        if (shown == 0) {
            sb.append("(no index entries mention this query)\n");
        }

        return sb.toString();
    }

    /**
     * Read the full content of a specific wiki page by its slug.
     */
    @Tool(description = "Read the full content of a specific wiki page by its slug. " +
            "Use this after wikiSearch to get the complete page content for detailed information.")
    public String wikiReadPage(
            @ToolArg(description = "The slug of the wiki page to read, e.g. 'wiki-pattern', 'entity-karpathy'") String slug) throws IOException {

        if (!enabled) return "MCP endpoint is disabled. Set PROVIDE_MCP_ENDPOINT=true to enable.";

        WikiPage page = wikiFileService.readPage(slug);
        if (page == null) {
            // Try to find similar slugs
            List<WikiPage> allPages = wikiFileService.readAllPages();
            List<String> similar = allPages.stream()
                .filter(p -> p.slug().contains(slug) || slug.contains(p.slug()))
                .map(WikiPage::slug)
                .limit(5)
                .toList();

            String hint = similar.isEmpty()
                ? "No similar pages found."
                : "Similar page slugs: " + String.join(", ", similar);

            return "Page not found: `" + slug + "`. " + hint;
        }

        return """
            # %s

            **Slug:** `%s`
            **Category:** %s
            **Sources:** %s
            **Created:** %s
            **Updated:** %s

            ---

            %s
            """.formatted(
            page.title(), page.slug(), page.category(),
            page.sources() != null ? String.join(", ", page.sources()) : "none",
            page.created(), page.updated() != null ? page.updated() : page.created(),
            page.content()
        );
    }

    /**
     * List all wiki pages, optionally filtered by category.
     */
    @Tool(description = "List all wiki pages, optionally filtered by category. " +
            "Returns a compact list with slug, title, and category. " +
            "Use this to explore what topics the wiki covers.")
    public String wikiListPages(
            @ToolArg(description = "Optional category filter: entity, concept, source-summary, analysis. Leave empty to list all.") String category) {

        if (!enabled) return "MCP endpoint is disabled. Set PROVIDE_MCP_ENDPOINT=true to enable.";

        List<WikiPage> pages;
        if (category != null && !category.isBlank()) {
            pages = wikiFileService.readCategory(category);
        } else {
            pages = wikiFileService.readAllPages();
        }

        if (pages.isEmpty()) {
            return "No wiki pages found" + (category != null ? " in category '" + category + "'" : "") + ".";
        }

        // Group by category
        String byCategory = pages.stream()
            .collect(Collectors.groupingBy(WikiPage::category))
            .entrySet().stream()
            .map(e -> "**%s** (%d pages):\n".formatted(e.getKey(), e.getValue().size()) +
                e.getValue().stream()
                    .map(p -> "- `%s` — %s".formatted(p.slug(), p.title()))
                    .collect(Collectors.joining("\n")))
            .collect(Collectors.joining("\n\n"));

        return """
            Wiki contains **%d** page(s):

            %s
            """.formatted(pages.size(), byCategory);
    }

    /**
     * Read the wiki index (index.md).
     */
    @Tool(description = "Read the wiki index (index.md). Returns the full index with all pages organized by category. " +
            "Use this for a high-level overview of the entire wiki's structure and content.")
    public String wikiReadIndex() throws IOException {
        if (!enabled) return "MCP endpoint is disabled. Set PROVIDE_MCP_ENDPOINT=true to enable.";
        return wikiFileService.readIndex();
    }

    /**
     * Read the wiki log (log.md) to see recent activity.
     */
    @Tool(description = "Read the wiki log (log.md). Shows recent activity like ingests, queries, and lint checks. " +
            "Use this to understand what has been done recently and how the wiki has evolved.")
    public String wikiReadLog() throws IOException {
        if (!enabled) return "MCP endpoint is disabled. Set PROVIDE_MCP_ENDPOINT=true to enable.";
        return wikiFileService.readLog();
    }
}
