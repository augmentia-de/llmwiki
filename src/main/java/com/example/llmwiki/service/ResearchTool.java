package com.example.llmwiki.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;

@Slf4j
@ApplicationScoped
public class ResearchTool {

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";

    @ConfigProperty(name = "tavily.api-key", defaultValue = "")
    String tavilyApiKey;

    @Tool("Sucht im Internet nach aktuellen Fakten oder verifiziert Behauptungen.")
    public String searchTavily(String query) {
        log.debug("Tavily search: {}", query);

        if (tavilyApiKey == null || tavilyApiKey.isBlank()) {
            return "Tavily API key not configured. Set tavily.api-key in application.properties";
        }

        try {
            String jsonPayload = """
                {
                    "api_key": "%s",
                    "query": "%s",
                    "search_depth": "basic",
                    "max_results": 5
                }
                """.formatted(tavilyApiKey, query.replace("\"", "\\\""));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_API_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "API returned status: " + response.statusCode();
            }

            return parseTavilyResponse(response.body());
        } catch (Exception e) {
            log.error("Tavily search failed", e);
            return "Search failed: " + e.getMessage();
        }
    }

    private String parseTavilyResponse(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode results = root.get("results");

            if (results == null || results.isEmpty()) {
                return "No results found";
            }

            Iterator<JsonNode> iterator = results.elements();
            int count = 0;
            StringBuilder sb = new StringBuilder();

            while (iterator.hasNext() && count < 5) {
                JsonNode result = iterator.next();
                count++;

                String title = result.has("title") ? result.get("title").asText() : "No title";
                String url = result.has("url") ? result.get("url").asText() : "";
                String content = result.has("content") ? result.get("content").asText() : "";

                sb.append("Source: ").append(url).append("\n");
                sb.append("Title: ").append(title).append("\n");
                sb.append("Content: ").append(content).append("\n");

                if (count < 5) sb.append("\n---\n\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "Failed to parse response: " + e.getMessage();
        }
    }
}