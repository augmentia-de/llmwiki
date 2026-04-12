package com.example.llmwiki.service;

import com.example.llmwiki.model.LintReport;
import com.example.llmwiki.model.WikiPage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Lint Service: Wiki health check.
 *
 * 1. Find orphaned pages (no inbound links)
 * 2. Find missing pages (mentioned slugs without a page)
 * 3. Contradictions (LLM-based)
 * 4. Suggestions for new pages
 */
@ApplicationScoped
@Slf4j
public class LintService {

    @Inject
    ChatModel chatModel;

    @Inject
    WikiFileService wikiFileService;

    public LintReport lint() throws IOException {
        log.info("Starting wiki lint check");

        List<WikiPage> allPages = wikiFileService.readAllPages();
        Set<String> allSlugs = allPages.stream().map(WikiPage::slug).collect(Collectors.toSet());

        // 1. Collect cross-links (inbound)
        Set<String> linkedSlugs = new HashSet<>();
        Set<String> mentionedSlugs = new HashSet<>();

        for (WikiPage page : allPages) {
            List<String> links = wikiFileService.extractLinks(page.content());
            linkedSlugs.addAll(links);
            mentionedSlugs.addAll(links);
        }

        // 2. Find orphaned pages
        List<String> orphanPages = allPages.stream()
            .filter(p -> !linkedSlugs.contains(p.slug()))
            .filter(p -> !p.slug().startsWith("index") && !p.slug().startsWith("log") && !p.slug().startsWith("schema"))
            .map(WikiPage::slug)
            .toList();

        // 3. Find missing pages (mentioned but not present)
        List<String> missingPages = mentionedSlugs.stream()
            .filter(slug -> !allSlugs.contains(slug))
            .toList();

        // 4. Check contradictions (LLM-based)
        List<String> suggestions = new ArrayList<>();

        if (!missingPages.isEmpty()) {
            suggestions.add("Create missing pages: " + String.join(", ", missingPages.subList(0, Math.min(5, missingPages.size()))));
        }

        if (!orphanPages.isEmpty()) {
            suggestions.add("Link orphaned pages: " + String.join(", ", orphanPages.subList(0, Math.min(5, orphanPages.size()))));
        }

        // 5. LLM suggestions
        if (allPages.size() >= 3) {
            suggestions.addAll(suggestNewPages(allPages));
        }

        // 6. Log entry
        String logEntry = "## [%s] lint | Health Check\n%d pages | %d orphaned | %d missing | Suggestions: %d"
            .formatted(LocalDate.now(), allPages.size(), orphanPages.size(), missingPages.size(), suggestions.size());
        wikiFileService.appendToLog(logEntry);

        return new LintReport(
            allPages.size(),
            wikiFileService.readCategory("source-summary").size(),
            orphanPages,
            missingPages,
            suggestions
        );
    }

    /**
     * LLM suggests new pages based on the existing wiki.
     */
    private List<String> suggestNewPages(List<WikiPage> allPages) {
        String indexSummary = allPages.stream()
            .map(p -> "- [[" + p.slug() + "]] — " + p.title())
            .collect(Collectors.joining("\n"));

        ChatRequest request = ChatRequest.builder()
            .messages(
                SystemMessage.from("""
                    You are a wiki linter. Analyze the wiki content and suggest 3 concrete
                    improvements:

                    1. Which concepts are mentioned often but don't have their own page?
                    2. Which connections between pages are missing?
                    3. Which queries would make sense?

                    Respond as a list, one suggestion per line.
                    """),
                UserMessage.from("Wiki pages:\n\n" + indexSummary)
            )
            .build();

        try {
            ChatResponse response = chatModel.chat(request);
            return Arrays.stream(response.aiMessage().text().split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(5)
                .toList();
        } catch (Exception e) {
            log.error("LLM suggestions failed", e);
            return List.of("LLM suggestions failed");
        }
    }
}
