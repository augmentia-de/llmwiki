package com.example.llmwiki.service;

import com.example.llmwiki.model.WikiPage;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@ApplicationScoped
public class MaintenanceScheduler {

    @Inject
    WikiFileService wikiFileService;

    @Inject
    SynthesisEngine synthesisEngine;

    @Inject
    SignalDetector signalDetector;

    @Scheduled(every = "1h")
    public void hourlyMaintenance() {
        log.info("Running hourly maintenance...");
        checkStalePages();
    }

    @Scheduled(every = "6h")
    public void sixHourlyMaintenance() {
        checkOrphanPages();
    }

    private void checkStalePages() {
        try {
            List<WikiPage> pages = wikiFileService.readAllPages();
            int staleCount = 0;

            for (WikiPage page : pages) {
                if (page.content().contains("## Zusammenfassung") && page.content().contains("---")) {
                    String[] parts = page.content().split("---");
                    if (parts.length >= 2) {
                        String timeline = parts[1];

                        boolean hasTimeline = timeline.contains("### Eintrag") || timeline.contains("## Timeline");
                        if (!hasTimeline) continue;

                        int entryCount = countEntries(timeline);
                        int tierLevel = calculateTierLevel(page.content());

                        if (tierLevel < 3 && entryCount >= 3) {
                            synthesisEngine.refreshCompiledTruth(page.slug());
                            staleCount++;
                        }
                    }
                }
            }

            if (staleCount > 0) {
                log.info("Refreshed {} stale pages", staleCount);
            }
        } catch (Exception e) {
            log.error("Stale page check failed", e);
        }
    }

    private void checkOrphanPages() {
        try {
            List<WikiPage> pages = wikiFileService.readAllPages();
            StringBuilder orphans = new StringBuilder();

            for (WikiPage page : pages) {
                if (!page.content().contains("[[")) {
                    orphans.append(page.slug()).append(", ");
                }
            }

            if (orphans.length() > 0) {
                log.warn("Orphan pages (no back-links): {}", orphans);
            }
        } catch (Exception e) {
            log.error("Orphan check failed", e);
        }
    }

    private int countEntries(String timeline) {
        int count = 0;
        String[] lines = timeline.split("\n");
        for (String line : lines) {
            if (line.contains("### Eintrag") || line.contains("- **Datum:**")) {
                count++;
            }
        }
        return count;
    }

    private int calculateTierLevel(String content) {
        if (content.contains("Tier 1")) return 1;
        if (content.contains("Tier 2")) return 2;
        if (content.contains("Tier 3")) return 3;
        if (content.contains("**Tier** 3")) return 3;
        return 3;
    }
}