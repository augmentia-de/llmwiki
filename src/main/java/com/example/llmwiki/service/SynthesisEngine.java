package com.example.llmwiki.service;

import com.example.llmwiki.model.WikiPage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

@Slf4j
@ApplicationScoped
public class SynthesisEngine {

    @Inject
    SynthesisService synthesisService;

    @Inject
    WikiFileService wikiFileService;

    public void refreshCompiledTruth(String slug) throws IOException {
        WikiPage page = wikiFileService.readPage(slug);
        if (page == null) {
            log.warn("Page not found for synthesis: {}", slug);
            return;
        }

        String content = page.content();

        if (!content.contains("---")) {
            log.debug("No timeline separator found, skipping synthesis for: {}", slug);
            return;
        }

        String[] parts = content.split("---");
        String headerPart = parts[0];
        String timelinePart = parts.length > 1 ? parts[1] : "";

        String currentSummary = extractSummary(headerPart);

        String updatedSummary = synthesisService.synthesize(currentSummary, timelinePart);

        String newContent = rebuildFile(headerPart, updatedSummary, timelinePart);

        WikiPage updatedPage = new WikiPage(
            page.slug(),
            page.title(),
            page.category(),
            newContent,
            page.sources(),
            page.created(),
            LocalDate.now().toString()
        );
        wikiFileService.writePage(updatedPage);

        log.info("Compiled Truth refreshed for: {}", slug);
    }

    private String extractSummary(String header) {
        int index = header.indexOf("## Zusammenfassung");
        if (index == -1) {
            index = header.indexOf("## Summary");
        }
        if (index == -1) {
            return "";
        }

        int start = index + (header.contains("## Zusammenfassung") ? 18 : 10);
        String after = header.substring(start).trim();

        if (after.contains("---")) {
            return after.split("---")[0].trim();
        }
        return after;
    }

    private String rebuildFile(String oldHeader, String newSummary, String timeline) {
        int index = oldHeader.indexOf("## Zusammenfassung");
        if (index == -1) {
            index = oldHeader.indexOf("## Summary");
        }

        if (index == -1) {
            return oldHeader + "\n\n## Zusammenfassung\n" + newSummary + "\n\n---\n" + timeline;
        }

        int endOfHeader = index + (oldHeader.contains("## Zusammenfassung") ? 18 : 10);
        String prefix = oldHeader.substring(0, endOfHeader);

        String rest = "";
        String after = oldHeader.substring(endOfHeader);
        if (after.contains("---")) {
            rest = after.split("---")[1];
        }

        return prefix + "\n" + newSummary + "\n\n---\n" + rest + timeline;
    }
}