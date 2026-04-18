package com.example.llmwiki.service;

import com.example.llmwiki.model.WikiPage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/**
 * Karpathy's Ingest Pipeline:
 *
 * 1. Load source (URL → JSoup → Text)
 * 2. LLM reads the source and extracts: summary, entities, concepts, key facts
 * 3. Existing wiki pages are UPDATED (not recreated)
 * 4. New pages only for previously unknown entities/concepts
 * 5. Cross-links are maintained
 * 6. Contradictions are noted
 * 7. index.md is updated
 * 8. log.md is appended
 *
 * A single source can touch 10–15 wiki pages.
 *
 * LLM calls use RetryableChatService:
 *   - Up to 3 retries on primary model (30s delay between retries)
 *   - Falls back to secondary model if all retries fail
 */
@ApplicationScoped
@Slf4j
public class IngestService {

    @Inject
    ChatModel chatModel;

    @Inject
    WikiFileService wikiFileService;

    @Inject
    RetryableChatService retryableChat;

    /**
     * Main ingest method.
     */
    public IngestResult ingest(String url) throws IOException {
        log.info("Starting ingest: {}", url);

        if (url.startsWith("internal://")) {
            return ingestFromNormalizedSource(url);
        }

        // 1. Load source
        String html = Jsoup.connect(url).timeout(30_000).get().html();
        String text = Jsoup.parse(html).body().text();
        String title = Jsoup.parse(html).title();

        if (text.isBlank()) {
            throw new IOException("No text content found");
        }

        // 2. Save raw source
        wikiFileService.saveRawSource(url, text.substring(0, Math.min(text.length(), 50000)));

        // 3. LLM analysis
        LlmAnalysis analysis = analyzeSource(title, text);

        // 4. Update wiki
        int pagesTouched = updateWiki(analysis, url);

        // 5. Log entry
        String logEntry = "## [%s] ingest | %s\nSource: %s | %d pages updated | Entities: %s | Concepts: %s"
            .formatted(LocalDate.now(), title, url, pagesTouched,
                String.join(", ", analysis.entities().keySet()),
                String.join(", ", analysis.concepts().keySet()));
        wikiFileService.appendToLog(logEntry);

        log.info("Ingest complete: {} touched {} pages", title, pagesTouched);
        return new IngestResult(title, pagesTouched, analysis);
    }

    public IngestResult ingestFromNormalizedSource(String syntheticUrl) throws IOException {
        String sourceId = syntheticUrl.replace("internal://", "");
        log.info("Processing normalized source: {}", sourceId);

        throw new IOException("Normalized source processing not yet implemented - use text ingest");
    }

    public IngestResult ingestText(String text, String title, String sourceType) throws IOException {
        log.info("Starting text ingest: title={}", title);

        if (text == null || text.isBlank()) {
            throw new IOException("Text content is empty");
        }

        String effectiveTitle = title != null && !title.isBlank() ? title : extractTitleFromText(text);
        String sourceId = generateSourceId(text);
        String timestamp = java.time.LocalDate.now().toString();

        LlmAnalysis analysis = analyzeSource(effectiveTitle, text);

        String sourceUrl = "internal://text/" + wikiFileService.slugify(effectiveTitle);
        wikiFileService.saveRawSource(sourceUrl, text.substring(0, Math.min(text.length(), 50000)));

        int pagesTouched = updateWikiWithTimeline(analysis, sourceUrl, text, sourceId, timestamp);

        String logEntry = "## [%s] text ingest | %s\nSource: %s | %d pages updated | Entities: %s | Concepts: %s"
            .formatted(java.time.LocalDate.now(), effectiveTitle, sourceUrl, pagesTouched,
                String.join(", ", analysis.entities().keySet()),
                String.join(", ", analysis.concepts().keySet()));
        wikiFileService.appendToLog(logEntry);

        log.info("Text ingest complete: {} touched {} pages", effectiveTitle, pagesTouched);
        return new IngestResult(effectiveTitle, pagesTouched, analysis);
    }

    private int updateWikiWithTimeline(LlmAnalysis analysis, String sourceUrl, String rawText, String sourceId, String timestamp) throws IOException {
        int pagesTouched = 0;
        String sourceSlug = wikiFileService.slugify(sourceUrl);

        for (var entry : analysis.entities().entrySet()) {
            String slug = wikiFileService.slugify(entry.getKey());
            WikiPage existing = wikiFileService.readPage(slug);

            String timelineEntry = String.format("""
                ### Eintrag [%s]
                - **Datum:** %s
                - **Bezug:** %s
                - **Quelle:** [[source-%s]]

                %s
                """, sourceId, timestamp, entry.getValue(), sourceSlug, rawText);

            if (existing != null) {
                String updatedContent = existing.content() + "\n\n---\n" + timelineEntry;
                WikiPage updated = new WikiPage(
                    existing.slug(), existing.title(), existing.category(),
                    updatedContent, mergeSources(existing.sources(), sourceUrl),
                    existing.created(), LocalDate.now().toString()
                );
                wikiFileService.writePage(updated);
            } else {
                String initialContent = String.format("""
                    **Kategorie:** entity

                    ## Zusammenfassung

                    %s

                    ---

                    ## Timeline
                    %s
                    """, entry.getValue(), timelineEntry);

                WikiPage newPage = new WikiPage(
                    slug, entry.getKey().replace("-", " ").toUpperCase(),
                    "entity",
                    initialContent,
                    List.of(sourceUrl),
                    LocalDate.now().toString(), null
                );
                wikiFileService.writePage(newPage);
            }
            pagesTouched++;
        }

        for (var entry : analysis.concepts().entrySet()) {
            String slug = wikiFileService.slugify(entry.getKey());
            WikiPage existing = wikiFileService.readPage(slug);

            String timelineEntry = String.format("""
                ### Eintrag [%s]
                - **Datum:** %s
                - **Bezug:** %s

                %s
                """, sourceId, timestamp, entry.getValue(), rawText);

            if (existing != null) {
                String updatedContent = existing.content() + "\n\n---\n" + timelineEntry;
                WikiPage updated = new WikiPage(
                    existing.slug(), existing.title(), existing.category(),
                    updatedContent, mergeSources(existing.sources(), sourceUrl),
                    existing.created(), LocalDate.now().toString()
                );
                wikiFileService.writePage(updated);
            } else {
                String initialContent = String.format("""
                    **Kategorie:** concept

                    ## Zusammenfassung

                    %s

                    ---

                    ## Timeline
                    %s
                    """, entry.getValue(), timelineEntry);

                WikiPage newPage = new WikiPage(
                    slug, entry.getKey().replace("-", " ").toUpperCase(),
                    "concept",
                    initialContent,
                    List.of(sourceUrl),
                    LocalDate.now().toString(), null
                );
                wikiFileService.writePage(newPage);
            }
            pagesTouched++;
        }

        return pagesTouched;
    }

    private String generateSourceId(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hashStr = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return hashStr.substring(0, 8);
        } catch (Exception e) {
            return java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private String extractTitleFromText(String text) {
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.length() > 3) {
                if (trimmed.length() > 60) {
                    trimmed = trimmed.substring(0, 60);
                }
                return trimmed;
            }
        }
        return "Untitled Note";
    }

    /**
     * LLM analyzes the source and extracts structured information.
     */
    private LlmAnalysis analyzeSource(String title, String text) {
        String textForAnalysis = text.length() > 12000 ? text.substring(0, 12000) : text;

        try {
            ChatResponse response = retryableChat.chat(
                SystemMessage.from("""
                    You are a wiki author. Analyze the following source and extract:

                    1. A concise summary (3-5 sentences)
                    2. All mentioned entities (people, organizations, products) — with a 1-sentence description each
                    3. All mentioned concepts/theories/methods — with a 1-sentence description each
                    4. Key facts/claims
                    5. Contradictions to existing knowledge (if recognizable)
                    6. Cross-links to related topics

                    Respond ONLY in the following JSON format:
                    {
                      "summary": "Summary...",
                      "entities": {"entity-slug": "1-sentence description"},
                      "concepts": {"concept-slug": "1-sentence description"},
                      "key_facts": ["Fact 1", "Fact 2"],
                      "contradictions": ["Contradiction 1"],
                      "cross_links": ["related-slug-1", "related-slug-2"]
                    }
                    """),
                UserMessage.from("Title: " + title + "\n\nContent:\n" + textForAnalysis)
            );
            String json = response.aiMessage().text();
            return parseAnalysisJson(json);
        } catch (Exception e) {
            log.error("LLM analysis failed", e);
            return new LlmAnalysis(
                "Analysis failed: " + e.getMessage(),
                Map.of(), Map.of(), List.of(), List.of(), List.of()
            );
        }
    }

    /**
     * Parses the LLM response. Falls back to manual parsing if JSON fails.
     */
    private LlmAnalysis parseAnalysisJson(String json) {
        // Try to parse JSON
        try {
            // Simple manual parsing (no JSON library)
            Map<String, String> entities = extractMap(json, "entities");
            Map<String, String> concepts = extractMap(json, "concepts");
            String summary = extractValue(json, "summary");
            List<String> facts = extractList(json, "key_facts");
            List<String> contradictions = extractList(json, "contradictions");
            List<String> crossLinks = extractList(json, "cross_links");

            return new LlmAnalysis(summary, entities, concepts, facts, contradictions, crossLinks);
        } catch (Exception e) {
            log.warn("JSON parsing failed, using fallback", e);
            return new LlmAnalysis("Analysis (parsing error)", Map.of(), Map.of(), List.of(), List.of(), List.of());
        }
    }

    /**
     * Updates the wiki with the analysis results.
     * Existing pages are extended, new pages created if needed.
     */
    private int updateWiki(LlmAnalysis analysis, String sourceUrl) throws IOException {
        String sourceSlug = wikiFileService.slugify(sourceUrl);
        int pagesTouched = 0;

        // 1. Create source summary
        String sourceContent = """
            Summary of the source.

            %s

            **Key Facts:**
            %s

            **Cross-Links:**
            %s
            """.formatted(
            analysis.summary,
            analysis.keyFacts().stream().map(f -> "- " + f).reduce("", (a, b) -> a + "\n" + b),
            analysis.crossLinks().stream().map(l -> "[[" + l + "]]").reduce("", (a, b) -> a + " " + b)
        );

        WikiPage sourcePage = new WikiPage(
            "source-" + sourceSlug,
            "Source: " + sourceSlug,
            "source-summary",
            sourceContent,
            List.of(sourceUrl),
            LocalDate.now().toString(),
            LocalDate.now().toString()
        );
        wikiFileService.writePage(sourcePage);
        pagesTouched++;

        // 2. Update/create entity pages
        for (var entry : analysis.entities().entrySet()) {
            String slug = wikiFileService.slugify(entry.getKey());
            WikiPage existing = wikiFileService.readPage(slug);

            if (existing != null) {
                // Update existing page
                String updatedContent = existing.content() + """

                    ---

                    ## Added by [[source-%s]]

                    %s
                    """.formatted(sourceSlug, entry.getValue());

                WikiPage updated = new WikiPage(
                    existing.slug(), existing.title(), existing.category(),
                    updatedContent, mergeSources(existing.sources(), sourceUrl),
                    existing.created(), LocalDate.now().toString()
                );
                wikiFileService.writePage(updated);
            } else {
                // Create new page
                WikiPage newPage = new WikiPage(
                    slug, entry.getKey().replace("-", " ").toUpperCase(),
                    "entity",
                    entry.getValue() + "\n\nMentioned in: [[source-" + sourceSlug + "]]",
                    List.of(sourceUrl),
                    LocalDate.now().toString(), null
                );
                wikiFileService.writePage(newPage);
            }
            pagesTouched++;
        }

        // 3. Update/create concept pages
        for (var entry : analysis.concepts().entrySet()) {
            String slug = wikiFileService.slugify(entry.getKey());
            WikiPage existing = wikiFileService.readPage(slug);

            if (existing != null) {
                String updatedContent = existing.content() + """

                    ---

                    ## Added by [[source-%s]]

                    %s
                    """.formatted(sourceSlug, entry.getValue());

                WikiPage updated = new WikiPage(
                    existing.slug(), existing.title(), existing.category(),
                    updatedContent, mergeSources(existing.sources(), sourceUrl),
                    existing.created(), LocalDate.now().toString()
                );
                wikiFileService.writePage(updated);
            } else {
                WikiPage newPage = new WikiPage(
                    slug, entry.getKey().replace("-", " ").toUpperCase(),
                    "concept",
                    entry.getValue() + "\n\nMentioned in: [[source-" + sourceSlug + "]]",
                    List.of(sourceUrl),
                    LocalDate.now().toString(), null
                );
                wikiFileService.writePage(newPage);
            }
            pagesTouched++;
        }

        // 4. Note contradictions
        if (!analysis.contradictions().isEmpty()) {
            String contradictionContent = """
                ## Potential Contradictions from [[source-%s]]

                %s
                """.formatted(
                sourceSlug,
                analysis.contradictions().stream().map(c -> "- ⚠️ " + c).reduce("", (a, b) -> a + "\n" + b)
            );

            WikiPage contradictionPage = new WikiPage(
                "contradictions-" + sourceSlug,
                "Contradictions: " + sourceSlug,
                "analysis",
                contradictionContent,
                List.of(sourceUrl),
                LocalDate.now().toString(), null
            );
            wikiFileService.writePage(contradictionPage);
            pagesTouched++;
        }

        // 5. Maintain cross-links
        for (String linkSlug : analysis.crossLinks()) {
            WikiPage linkedPage = wikiFileService.readPage(linkSlug);
            if (linkedPage != null) {
                String updatedContent = wikiFileService.addLinksIfMissing(
                    linkedPage.content(),
                    List.of("source-" + sourceSlug)
                );
                WikiPage updated = new WikiPage(
                    linkedPage.slug(), linkedPage.title(), linkedPage.category(),
                    updatedContent, linkedPage.sources(),
                    linkedPage.created(), LocalDate.now().toString()
                );
                wikiFileService.writePage(updated);
                pagesTouched++;
            }
        }

        // 6. Update index
        updateIndex();

        return pagesTouched;
    }

    /**
     * Updates index.md based on all existing wiki pages.
     */
    private void updateIndex() throws IOException {
        StringBuilder index = new StringBuilder("# Wiki Index\n\n");

        String[] categories = {"entity", "concept", "source-summary", "analysis"};
        String[] headings = {"## Entities\n", "## Concepts\n", "## Sources\n", "## Analyses\n"};

        for (int i = 0; i < categories.length; i++) {
            index.append(headings[i]);
            List<WikiPage> pages = wikiFileService.readCategory(categories[i]);

            for (WikiPage page : pages) {
                String summary = page.content().split("\n")[0];
                if (summary.length() > 100) summary = summary.substring(0, 100) + "...";
                index.append("- [[%s]] — %s\n".formatted(page.slug(), summary));
            }
            index.append("\n");
        }

        wikiFileService.writeIndex(index.toString());
    }

    private List<String> mergeSources(List<String> existing, String newSource) {
        List<String> merged = new ArrayList<>(existing);
        if (!merged.contains(newSource)) merged.add(newSource);
        return merged;
    }

    // ─── Simple JSON Parsing (no library) ──────────────────────

    private String extractValue(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return "";
        start = json.indexOf(":", start) + 1;
        start = json.indexOf("\"", start) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private Map<String, String> extractMap(String json, String key) {
        Map<String, String> result = new LinkedHashMap<>();
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return result;

        int braceStart = json.indexOf("{", start);
        int braceEnd = json.indexOf("}", braceStart);
        if (braceStart == -1 || braceEnd == -1) return result;

        String inner = json.substring(braceStart + 1, braceEnd);
        String[] pairs = inner.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":");
            if (kv.length == 2) {
                String k = kv[0].trim().replaceAll("\"", "");
                String v = kv[1].trim().replaceAll("\"", "");
                result.put(k, v);
            }
        }
        return result;
    }

    private List<String> extractList(String json, String key) {
        List<String> result = new ArrayList<>();
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return result;

        int bracketStart = json.indexOf("[", start);
        int bracketEnd = json.indexOf("]", bracketStart);
        if (bracketStart == -1 || bracketEnd == -1) return result;

        String inner = json.substring(bracketStart + 1, bracketEnd);
        for (String item : inner.split(",")) {
            String cleaned = item.trim().replaceAll("\"", "");
            if (!cleaned.isEmpty()) result.add(cleaned);
        }
        return result;
    }

    public record LlmAnalysis(
        String summary,
        Map<String, String> entities,
        Map<String, String> concepts,
        List<String> keyFacts,
        List<String> contradictions,
        List<String> crossLinks
    ) {}

    public record IngestResult(
        String title,
        int pagesTouched,
        LlmAnalysis analysis
    ) {}
}
