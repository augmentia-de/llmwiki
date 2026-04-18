package com.example.llmwiki.model;

import java.util.List;

public record Claim(
    String subject,
    String assertion,
    String source,
    String sourceUrl,
    String researchStatus,
    List<String> citations
) {}