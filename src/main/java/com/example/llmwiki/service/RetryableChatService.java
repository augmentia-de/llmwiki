package com.example.llmwiki.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Retry and fallback logic for LLM calls.
 *
 * Retries the primary model up to N times with a configurable delay.
 * If all retries fail, falls back to the secondary model.
 */
@ApplicationScoped
@Slf4j
public class RetryableChatService {

    @Inject
    ChatModel primaryModel;

    private String fallbackBaseUrl = System.getenv().getOrDefault("LLM_FALLBACK_BASE_URL", "https://openrouter.ai/api/v1");
    private String fallbackApiKey = System.getenv().getOrDefault("LLM_FALLBACK_API_KEY", System.getenv().getOrDefault("LLM_CHAT_API_KEY", "change-me"));
    private String fallbackModelName = System.getenv().getOrDefault("LLM_FALLBACK_MODEL", "anthropic/claude-sonnet-4-20250514");

    private int maxAttempts = Integer.parseInt(System.getenv().getOrDefault("LLMWIKI_RETRY_MAX_ATTEMPTS", "3"));
    private int retryDelaySeconds = Integer.parseInt(System.getenv().getOrDefault("LLMWIKI_RETRY_DELAY_SECONDS", "30"));

    private ChatModel fallbackModel;

    /**
     * Lazy-creates the fallback model only when needed.
     */
    private ChatModel getFallbackModel() {
        if (fallbackModel == null) {
            fallbackModel = OpenAiChatModel.builder()
                .baseUrl(fallbackBaseUrl)
                .apiKey(fallbackApiKey)
                .modelName(fallbackModelName)
                .build();
            log.info("Fallback model initialized: {}", fallbackModelName);
        }
        return fallbackModel;
    }

    /**
     * Executes a chat request with retry and fallback.
     *
     * Retry strategy:
     *   Attempt 1: Primary model
     *   If fails → wait retryDelaySeconds
     *   Attempt 2: Primary model
     *   If fails → wait retryDelaySeconds
     *   Attempt 3: Primary model
     *   If fails → switch to fallback model (1 attempt)
     */
    public ChatResponse chat(ChatRequest request) {
        Exception lastException = null;

        // Try primary model with retries
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ChatResponse response = primaryModel.chat(request);
                if (attempt > 1) {
                    log.info("Primary model succeeded on attempt {}/{}", attempt, maxAttempts);
                }
                return response;
            } catch (Exception e) {
                lastException = e;
                log.warn("Primary model attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());

                if (attempt < maxAttempts) {
                    log.info("Retrying in {} seconds...", retryDelaySeconds);
                    try {
                        Thread.sleep(retryDelaySeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                }
            }
        }

        // All primary retries exhausted → try fallback
        log.warn("All {} primary attempts failed. Switching to fallback model: {}", maxAttempts, fallbackModelName);
        try {
            ChatResponse response = getFallbackModel().chat(request);
            log.info("Fallback model succeeded: {}", fallbackModelName);
            return response;
        } catch (Exception fallbackException) {
            log.error("Fallback model also failed: {}", fallbackModelName, fallbackException);
            throw new RuntimeException(
                "LLM call failed after %d primary attempts + 1 fallback attempt. Last primary error: %s | Fallback error: %s"
                    .formatted(maxAttempts, lastException.getMessage(), fallbackException.getMessage()),
                lastException
            );
        }
    }

    /**
     * Convenience method: build request and execute with retry/fallback.
     */
    public ChatResponse chat(SystemMessage system, UserMessage user) {
        ChatRequest request = ChatRequest.builder()
            .messages(system, user)
            .build();
        return chat(request);
    }
}
