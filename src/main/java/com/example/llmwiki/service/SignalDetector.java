package com.example.llmwiki.service;

import com.example.llmwiki.model.WikiPage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@ApplicationScoped
public class SignalDetector {

    @Inject
    ChatModel chatModel;

    @Inject
    WikiFileService wikiFileService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String SIGNAL_PROMPT = """
        Du bist ein Signal-Detektor. Scanne den Text nach neuen Signalen/Entitäten, die noch nicht vollständig im Wiki erfasst sind.
        Signale sind: neue Personen, neue Projekte, neue Konzepte, neue Technologien.

        Gib eine JSON-Liste zurück im Format:
        [{"signal": "entity_name", "type": "PERSON|PROJECT|CONCEPT|ORGANIZATION", "reason": "kurze Begründung"}]

        Wenn keine neuen Signale gefunden wurden, gib eine leere Liste zurück: []
        """;

    public void processSignalAsync(String text) {
        CompletableFuture.runAsync(() -> {
            try {
                List<Signal> signals = detectSignals(text);

                for (Signal signal : signals) {
                    WikiPage existing = wikiFileService.readPage(signal.signal());
                    if (existing == null) {
                        createStub(signal);
                    }
                }

                if (!signals.isEmpty()) {
                    log.info("Detected {} new signals", signals.size());
                }
            } catch (Exception e) {
                log.error("Signal detection failed", e);
            }
        }, executor);
    }

    public List<Signal> detectSignals(String text) {
        try {
            ChatRequest request = ChatRequest.builder()
                .messages(
                    SystemMessage.from(SIGNAL_PROMPT),
                    UserMessage.from("Text:\n" + text.substring(0, Math.min(text.length(), 8000))))
                .build();

            ChatResponse response = chatModel.chat(request);
            String json = response.aiMessage().text();

            return parseSignals(json);
        } catch (Exception e) {
            log.error("Signal detection failed", e);
            return List.of();
        }
    }

    private List<Signal> parseSignals(String json) {
        List<Signal> signals = new ArrayList<>();

        try {
            json = json.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();

            if (!json.equals("[]") && !json.isEmpty()) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

                if (root.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode node : root) {
                        signals.add(new Signal(
                            node.has("signal") ? node.get("signal").asText() : "",
                            node.has("type") ? node.get("type").asText() : "CONCEPT",
                            node.has("reason") ? node.get("reason").asText() : ""
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse signals: {}", e.getMessage());
        }

        return signals;
    }

    private void createStub(Signal signal) {
        try {
            String slug = wikiFileService.slugify(signal.signal());
            String content = String.format("""
                **Kategorie:** %s
                **Tier:** 3 (Stub)

                ## Zusammenfassung

                [Stub - weitere Recherche nötig]

                ---

                ## Timeline
                ### Signal [%s]
                - **Erkannt durch:** Signal Detector
                - **Grund:** %s
                """, signal.type(), java.time.LocalDate.now(), signal.reason());

            WikiPage page = new WikiPage(
                slug,
                signal.signal(),
                "entity",
                content,
                List.of("signal-detection"),
                java.time.LocalDate.now().toString(),
                null
            );
            wikiFileService.writePage(page);

            log.info("Created stub for: {}", signal.signal());
        } catch (Exception e) {
            log.error("Failed to create stub for: {}", signal.signal(), e);
        }
    }

    public record Signal(String signal, String type, String reason) {}
}