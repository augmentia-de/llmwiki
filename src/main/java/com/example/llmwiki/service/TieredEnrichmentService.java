package com.example.llmwiki.service;

import com.example.llmwiki.model.WikiPage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@ApplicationScoped
public class TieredEnrichmentService {

    @Inject
    WikiFileService wikiFileService;

    @Inject
    SignalDetector signalDetector;

    @Inject
    CrossModalReviewService reviewService;

    @Inject
    SynthesisEngine synthesisEngine;

    public TierResult determineTier(String slug) {
        try {
            WikiPage page = wikiFileService.readPage(slug);
            if (page == null) {
                return new TierResult(3, "NEW", "Neue Entität");
            }

            String content = page.content();
            int mentionCount = countMentions(content);
            int tierLevel = calculateTierLevel(content);
            String reason = determineReason(tierLevel, mentionCount);

            return new TierResult(tierLevel, extractStatus(tierLevel), reason);
        } catch (Exception e) {
            log.error("Failed to determine tier", e);
            return new TierResult(3, "ERROR", e.getMessage());
        }
    }

    public void processWithTiering(String rawText, String sourceUrl) {
        TierResult result = processInitialScan(rawText);

        if (result.tierLevel() == 3) {
            signalDetector.processSignalAsync(rawText);
            log.debug("Tier 3: Signals detected asynchronously for: {}", sourceUrl);
        } else if (result.tierLevel() == 2 && result.conflict()) {
            log.debug("Tier 2: Cross-modal review needed for: {}", sourceUrl);
        } else if (result.tierLevel() == 1) {
            try {
                String slug = wikiFileService.slugify(sourceUrl);
                synthesisEngine.refreshCompiledTruth(slug);
                log.debug("Tier 1: Deep synthesis triggered for: {}", sourceUrl);
            } catch (IOException e) {
                log.error("Synthesis failed", e);
            }
        }
    }

    private TierResult processInitialScan(String text) {
        try {
            List<SignalDetector.Signal> signals = signalDetector.detectSignals(text);

            if (signals.isEmpty()) {
                return new TierResult(2, "CLEAN", "Keine neuen Signale");
            }

            return new TierResult(3, "NEW_SIGNALS", "Neue Signale erkannt: " + signals.size());
        } catch (Exception e) {
            return new TierResult(3, "ERROR", e.getMessage());
        }
    }

    private int calculateTierLevel(String content) {
        if (content.contains("Tier 1") || content.contains("**Tier:** 1")) {
            return 1;
        }
        if (content.contains("Tier 2") || content.contains("**Tier:** 2")) {
            return 2;
        }
        return 3;
    }

    private int countMentions(String content) {
        int count = 0;
        if (content.contains("### Eintrag")) {
            String[] entries = content.split("### Eintrag");
            count = entries.length - 1;
        }
        return Math.max(count, 1);
    }

    private String determineReason(int tier, int mentions) {
        return switch (tier) {
            case 1 -> "Full Deep Dive - Mehr als 3 Erwähnungen";
            case 2 -> "Active - 2-3 Erwähnungen";
            default -> "Stub - Erste Erwähnung";
        };
    }

    private String extractStatus(int tier) {
        return switch (tier) {
            case 1 -> "FULL";
            case 2 -> "ACTIVE";
            default -> "STUB";
        };
    }

    public record TierResult(int tierLevel, String status, String reason) {
        public boolean conflict() {
            return status != null && status.contains("CONFLICT");
        }
    }
}