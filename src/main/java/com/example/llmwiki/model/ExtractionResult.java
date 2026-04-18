package com.example.llmwiki.model;

import java.util.List;

public class ExtractionResult {
    public List<Entity> entities;

    public static class Entity {
        public String fileName;
        public String displayName;
        public String category;
        public String relation;
        public String initialSummary;
    }
}