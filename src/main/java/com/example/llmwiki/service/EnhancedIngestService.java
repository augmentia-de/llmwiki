package com.example.llmwiki.service;

import com.example.llmwiki.model.Claim;
import com.example.llmwiki.model.WikiPage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@ApplicationScoped
public class EnhancedIngestService {

    @Inject
    WikiFileService fileService;

    @Inject
    WikiArchitect architect;

    public EnhancedIngestResult ingest(String url) throws IOException {
        log.info("Starting enhanced ingest: {}", url);

        String html = Jsoup.connect(url).timeout(30_000).get().html();
        String text = Jsoup.parse(html).body().text();
        String title = Jsoup.parse(html).title();

        if (text.isBlank()) {
            throw new IOException("No text content found");
        }

        String sourceText = text.substring(0, Math.min(text.length(), 50000));
        fileService.saveRawSource(url, sourceText);

        EnhancedIngestResult result = processWithResearch(url, title, sourceText);

        String logEntry = "## [%s] enhanced ingest | %s\nSource: %s | Pages: %d | Claims: %d"
            .formatted(LocalDate.now(), title, url, result.pagesTouched(), result.claimsForReview().size());
        fileService.appendToLog(logEntry);

        log.info("Enhanced ingest complete: {} touched {} pages, {} claims for review", 
            title, result.pagesTouched(), result.claimsForReview().size());
        return result;
    }

    private EnhancedIngestResult processWithResearch(String url, String title, String sourceText) throws IOException {
        String sourceSlug = fileService.slugify(url);

        int pagesTouched = 0;
        int claimsForReview = 0;

        try {
            WikiPage sourcePage = fileService.readPage("source-" + sourceSlug);
            String existingContent = sourcePage != null ? sourcePage.content() : "";

            String llmResponse = architect.processUpdate(existingContent, sourceText);
            String newContent = parseContent(llmResponse);
            String claimsPart = parseClaims(llmResponse);

            WikiPage newSourcePage = new WikiPage(
                "source-" + sourceSlug,
                "Source: " + title,
                "source-summary",
                newContent,
                List.of(url),
                LocalDate.now().toString(),
                LocalDate.now().toString()
            );
            fileService.writePage(newSourcePage);
            pagesTouched++;

            if (!claimsPart.equals("NONE")) {
                fileService.appendToReviewLog(title, claimsPart);
                claimsForReview++;
            }

        } catch (Exception e) {
            log.error("Enhanced ingest failed, falling back to basic", e);
            return new EnhancedIngestResult(title, 0, List.of(), e.getMessage());
        }

        return new EnhancedIngestResult(title, pagesTouched, List.of(), null);
    }

    private String parseContent(String response) {
        String[] parts = response.split("---CLAIMS_FOR_REVIEW---");
        if (parts.length > 0) {
            return parts[0].replace("---NEW_CONTENT---", "").trim();
        }
        return response;
    }

    private String parseClaims(String response) {
        String[] parts = response.split("---CLAIMS_FOR_REVIEW---");
        if (parts.length > 1) {
            return parts[1].trim();
        }
        return "NONE";
    }

    public record EnhancedIngestResult(
        String title,
        int pagesTouched,
        List<Claim> claimsForReview,
        String error
    ) {}
}