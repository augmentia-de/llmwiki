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

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Karpathy's Query Pipeline:
 *
 * 1. Read index.md → find relevant pages (text-based, no vectors)
 * 2. LLM filters and weights index entries by relevance
 * 3. Read found pages in detail
 * 4. LLM synthesizes answer with citations
 * 5. Answer saved as analysis page
 * 6. log.md appended
 *
 * LLM calls use RetryableChatService:
 *   - Up to 3 retries on primary model (30s delay between retries)
 *   - Falls back to secondary model if all retries fail
 */
@ApplicationScoped
@Slf4j
public class QueryService {

    @Inject
    ChatModel chatModel;

    @Inject
    WikiFileService wikiFileService;

    @Inject
    RetryableChatService retryableChat;

    public QueryResponse query(String question, boolean saveAsPage) throws IOException {
        log.info("Query: {}", question);

        // 1. index.md lesen
        String indexContent = wikiFileService.readIndex();

        // 2. LLM findet relevante Seiten im Index
        List<String> relevantSlugs = findRelevantPages(indexContent, question);

        if (relevantSlugs.isEmpty()) {
            return new QueryResponse(
                "No relevant wiki pages found. Please add more sources or ask a more specific question.",
                List.of()
            );
        }

        log.info("Found {} relevant pages from index: {}", relevantSlugs.size(), relevantSlugs);

        // 3. Seiten im Detail lesen
        List<WikiPage> pages = new ArrayList<>();
        for (String slug : relevantSlugs) {
            WikiPage page = wikiFileService.readPage(slug);
            if (page != null) {
                pages.add(page);
            }
        }

        if (pages.isEmpty()) {
            return new QueryResponse("Pages found but not readable.", List.of());
        }

        // 4. Build context
        String context = pages.stream()
            .map(p -> "## " + p.title() + " [[" + p.slug() + "]]\n\n" + p.content())
            .collect(Collectors.joining("\n\n---\n\n"));

        // 5. Generate LLM answer
        ChatResponse response = retryableChat.chat(
            SystemMessage.from("""
                You are a wiki assistant. Answer questions based on the
                provided wiki context.

                Rules:
                1. Be structured
                2. Cite sources at the end of each statement like [Source: [[slug]]]
                3. Explicitly mention contradictions or uncertainties
                4. If the answer cannot be derived from the context,
                   say so explicitly and suggest missing knowledge
                """),
            UserMessage.from("Context:\n\n" + context + "\n\nQuestion: " + question)
        );
        String answer = response.aiMessage().text();

        // 6. Optionally save as analysis page
        if (saveAsPage) {
            String slug = "analysis-" + System.currentTimeMillis();
            String pageTitle = "Analysis: " + question.substring(0, Math.min(question.length(), 60));

            WikiPage analysisPage = new WikiPage(
                slug, pageTitle, "analysis",
                "# Question\n\n" + question + "\n\n# Answer\n\n" + answer,
                List.of(),
                LocalDate.now().toString(), null
            );
            wikiFileService.writePage(analysisPage);
            log.info("Analysis page saved: {}", slug);
        }

        // 7. Log entry
        String logEntry = "## [%s] query | %s\nAnswer generated, sources: %s%s"
            .formatted(LocalDate.now(), question,
                String.join(", ", pages.stream().map(WikiPage::slug).toList()),
                saveAsPage ? " | Saved as analysis page" : "");
        wikiFileService.appendToLog(logEntry);

        List<String> citedPages = pages.stream().map(WikiPage::title).toList();
        return new QueryResponse(answer, citedPages);
    }

    /**
     * LLM reads the index and finds the most relevant pages for the question.
     */
    private List<String> findRelevantPages(String indexContent, String question) {
        try {
            ChatResponse response = retryableChat.chat(
                SystemMessage.from("""
                    You are a wiki index reader. Given the index of a wiki,
                    find ALL entries that could be relevant for the given question.

                    Respond ONLY with a comma-separated list of slugs.
                    No explanations, just slugs.

                    Example answer: wiki-pattern, rag, karpathy
                    """),
                UserMessage.from("Wiki-Index:\n\n" + indexContent + "\n\nQuestion: " + question)
            );
            String slugsStr = response.aiMessage().text().trim();
            return Arrays.stream(slugsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        } catch (Exception e) {
            log.error("Failed to find relevant pages from index", e);
            return List.of();
        }
    }

    public record QueryResponse(String answer, List<String> citedPages) {}
}
