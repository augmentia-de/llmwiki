package com.example.llmwiki.service;

import com.example.llmwiki.config.WikiConfig;
import com.example.llmwiki.model.Claim;
import com.example.llmwiki.model.WikiPage;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Core service for all wiki file system operations.
 * Reads and writes Markdown files, parses frontmatter and cross-links.
 */
@ApplicationScoped
@Slf4j
public class WikiFileService {

    @Inject
    WikiConfig config;

    private Path wikiDir;
    private Path rawDir;

    @PostConstruct
    void init() {
        wikiDir = Path.of(config.wikiDir());
        rawDir = Path.of(config.rawDir());

        try {
            // Create directory structure
            Files.createDirectories(wikiDir);
            Files.createDirectories(rawDir);
            Files.createDirectories(wikiDir.resolve("entities"));
            Files.createDirectories(wikiDir.resolve("concepts"));
            Files.createDirectories(wikiDir.resolve("sources"));
            Files.createDirectories(wikiDir.resolve("analyses"));
            Files.createDirectories(rawDir.resolve("assets"));

            // Initialize index.md and log.md if not present
            initIndexFile();
            initLogFile();

            log.info("Wiki directories initialized: {}", wikiDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize wiki directories", e);
        }
    }

    private void initIndexFile() throws IOException {
        Path indexFile = wikiDir.resolve("index.md");
        if (!Files.exists(indexFile)) {
            String initial = """
                # Wiki Index

                ## Entities

                ## Concepts

                ## Sources

                ## Analyses
                """;
            Files.writeString(indexFile, initial);
        }
    }

    private void initLogFile() throws IOException {
        Path logFile = wikiDir.resolve("log.md");
        if (!Files.exists(logFile)) {
            Files.writeString(logFile, "# Wiki Log\n\n");
        }
    }

    // ─── Read / Write ────────────────────────────────────────────────

    /**
     * Reads a wiki page from the file system.
     */
    public WikiPage readPage(String slug) throws IOException {
        // Find the file across all categories
        for (String dir : List.of("entities", "concepts", "sources", "analyses")) {
            Path file = wikiDir.resolve(dir).resolve(slug + ".md");
            if (Files.exists(file)) {
                String content = Files.readString(file);
                return WikiPage.fromMarkdown(slug, file.toString(), content);
            }
        }
        return null;
    }

    /**
     * Writes a wiki page to the file system.
     */
    public void writePage(WikiPage page) throws IOException {
        Path dir = wikiDir.resolve(page.directory());
        Files.createDirectories(dir);
        Path file = dir.resolve(page.fileName());
        Files.writeString(file, page.toMarkdown());
        log.debug("Wiki page written: {}", file);
    }

    /**
     * Reads all wiki pages of a category.
     */
    public List<WikiPage> readCategory(String category) {
        String dirName = switch (category) {
            case "entity" -> "entities";
            case "concept" -> "concepts";
            case "source-summary" -> "sources";
            case "analysis" -> "analyses";
            default -> category;
        };

        Path dir = wikiDir.resolve(dirName);
        if (!Files.exists(dir)) return List.of();

        List<WikiPage> pages = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                .forEach(p -> {
                    try {
                        String slug = p.getFileName().toString().replace(".md", "");
                        String content = Files.readString(p);
                        pages.add(WikiPage.fromMarkdown(slug, p.toString(), content));
                    } catch (IOException e) {
                        log.error("Failed to read page: {}", p, e);
                    }
                });
        } catch (IOException e) {
            log.error("Failed to list category: {}", dirName, e);
        }
        return pages;
    }

    /**
     * Reads all wiki pages across all categories.
     */
    public List<WikiPage> readAllPages() {
        List<WikiPage> all = new ArrayList<>();
        for (String cat : List.of("entity", "concept", "source-summary", "analysis")) {
            all.addAll(readCategory(cat));
        }
        return all;
    }

    /**
     * Extracts cross-links ([[slug]]) from Markdown content.
     */
    public List<String> extractLinks(String content) {
        List<String> links = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[\\[([a-z0-9-]+)\\]\\]");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            links.add(matcher.group(1));
        }
        return links;
    }

    /**
     * Adds cross-links to content if they don't already exist.
     */
    public String addLinksIfMissing(String content, List<String> newLinks) {
        List<String> existing = extractLinks(content);
        StringBuilder sb = new StringBuilder(content);

        for (String link : newLinks) {
            if (!existing.contains(link)) {
                sb.append("\n\nSee also: [[%s]]".formatted(link));
            }
        }
        return sb.toString();
    }

    /**
     * Saves a raw source in the raw directory.
     */
    public Path saveRawSource(String url, String content) throws IOException {
        String fileName = slugify(url) + ".md";
        Path file = rawDir.resolve(fileName);
        String markdown = """
            ---
            url: %s
            fetched: %s
            ---

            # Raw Source

            %s
            """.formatted(url, LocalDate.now(), content);
        Files.writeString(file, markdown);
        return file;
    }

    /**
     * Reads index.md and returns its content.
     */
    public String readIndex() throws IOException {
        return Files.readString(wikiDir.resolve("index.md"));
    }

    /**
     * Writes index.md.
     */
    public void writeIndex(String content) throws IOException {
        Files.writeString(wikiDir.resolve("index.md"), content);
    }

    /**
     * Reads log.md and returns its content.
     */
    public String readLog() throws IOException {
        return Files.readString(wikiDir.resolve("log.md"));
    }

    /**
     * Appends an entry to log.md.
     */
    public void appendToLog(String entry) throws IOException {
        Files.writeString(
            wikiDir.resolve("log.md"),
            "\n" + entry + "\n",
            StandardOpenOption.APPEND
        );
    }

    /**
     * Converts a URL/text into a slug.
     */
    public String slugify(String input) {
        if (input == null) return "unknown";
        String cleaned = input.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
        return cleaned.substring(0, Math.min(cleaned.length(), 60));
    }

    // ─── File Browser ────────────────────────────────────────────────

    /**
     * Deletes a wiki page from the file system.
     * Searches across all categories for the file.
     *
     * @return true if deleted, false if not found
     */
    public boolean deletePage(String slug) throws IOException {
        for (String dir : List.of("entities", "concepts", "sources", "analyses")) {
            Path file = wikiDir.resolve(dir).resolve(slug + ".md");
            if (Files.exists(file)) {
                Files.delete(file);
                log.debug("Wiki page deleted: {}", file);
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the raw content of any file in the wiki directory.
     *
     * @param relativePath relative path from wiki root, e.g. "index.md" or "entities/karpathy.md"
     * @return file content or null if not found
     */
    public String readFileContent(String relativePath) throws IOException {
        Path file = wikiDir.resolve(relativePath);
        // Security: ensure we don't read outside the wiki directory
        if (!file.normalize().startsWith(wikiDir.normalize())) {
            throw new SecurityException("Access outside wiki directory not allowed");
        }
        if (!Files.exists(file) || Files.isDirectory(file)) {
            return null;
        }
        return Files.readString(file);
    }

    /**
     * Builds the file tree of the wiki directory.
     */
    public List<FileNode> getFilesTree() throws IOException {
        return buildTree(wikiDir, "");
    }

    private List<FileNode> buildTree(Path directory, String relativeBase) throws IOException {
        List<FileNode> nodes = new ArrayList<>();

        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> sorted = stream.sorted(Comparator.comparing(Path::getFileName)).toList();

            for (Path path : sorted) {
                String name = path.getFileName().toString();
                String relPath = relativeBase.isEmpty() ? name : relativeBase + "/" + name;

                if (Files.isDirectory(path)) {
                    List<FileNode> children = buildTree(path, relPath);
                    nodes.add(new FileNode(name, relPath, "directory", children));
                } else if (name.endsWith(".md")) {
                    nodes.add(new FileNode(name, relPath, "file", List.of()));
                }
            }
        }

        return nodes;
    }

    // ─── HITL Review ───────────────────────────────────────────────────────────

    public List<Claim> readPendingClaims() {
        Path path = wikiDir.resolve("needs_review.md");
        if (!Files.exists(path)) return List.of();

        try {
            String content = Files.readString(path);
            return parseClaimsFromMarkdown(content);
        } catch (IOException e) {
            log.error("Failed to read review log", e);
            return List.of();
        }
    }

    public void appendToReviewLog(String title, String claimsJson) {
        try {
            Path reviewFile = wikiDir.resolve("needs_review.md");
            String entry = """

                ## %s

                ```json
                %s
                ```
                """.formatted(title, claimsJson);

            Files.writeString(reviewFile, Files.readString(reviewFile) + entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("Claim appended to review log for: {}", title);
        } catch (IOException e) {
            log.error("Failed to append to review log", e);
        }
    }

    public void resolveClaim(int claimId, String action) {
        List<Claim> allClaims = readPendingClaims();
        if (claimId < 0 || claimId >= allClaims.size()) {
            log.warn("Invalid claim id: {}", claimId);
            return;
        }

        Claim target = allClaims.get(claimId);

        if ("ACCEPT".equals(action)) {
            applyFactToWikiPage(target.subject(), target.assertion());
            log.info("Claim ACCEPTED: {}", target.subject());
        } else if ("REJECT".equals(action)) {
            log.info("Claim REJECTED: {}", target.subject());
        }

        allClaims.remove(claimId);
        saveClaimsToMarkdown(allClaims);

        try {
            appendToLog("HITL: Claim " + action + " for " + target.subject());
        } catch (IOException e) {
            log.error("Failed to log claim resolution", e);
        }
    }

    private void applyFactToWikiPage(String subject, String assertion) {
        try {
            WikiPage page = readPage(subject);
            if (page != null) {
                String updated = page.content() + "\n\n## Verified\n\n" + assertion;
                WikiPage updatedPage = new WikiPage(
                    page.slug(), page.title(), page.category(),
                    updated, page.sources(),
                    page.created(), LocalDate.now().toString()
                );
                writePage(updatedPage);
            }
        } catch (IOException e) {
            log.error("Failed to apply fact", e);
        }
    }

    private List<Claim> parseClaimsFromMarkdown(String content) {
        List<Claim> claims = new ArrayList<>();
        if (content == null || content.isEmpty()) return claims;

        try {
            String[] sections = content.split("## ");
            for (String section : sections) {
                if (section.contains("```json")) {
                    String jsonPart = section.split("```json")[1].split("```")[0].trim();
                    if (!jsonPart.isEmpty() && !jsonPart.equals("[]")) {
                        claims.add(parseClaim(jsonPart));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse claims from markdown", e);
        }
        return claims;
    }

    private Claim parseClaim(String json) {
        try {
            String subject = extractValue(json, "subject");
            String assertion = extractValue(json, "assertion");
            String sourceUrl = extractValue(json, "sourceUrl");
            String status = extractValue(json, "status");

            return new Claim(subject, assertion, "", sourceUrl, status, List.of());
        } catch (Exception e) {
            return new Claim("unknown", json, "", "", "PENDING", List.of());
        }
    }

    private String extractValue(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return "";
        start = json.indexOf(":", start) + 1;
        start = json.indexOf("\"", start) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private void saveClaimsToMarkdown(List<Claim> claims) {
        try {
            StringBuilder sb = new StringBuilder("# Pending Reviews\n\n");
            for (Claim claim : claims) {
                sb.append("## ").append(claim.subject()).append("\n\n");
                sb.append("```json\n");
                sb.append(String.format("""
                    {
                      "subject": "%s",
                      "assertion": "%s",
                      "sourceUrl": "%s",
                      "status": "PENDING"
                    }
                    """, claim.subject(), claim.assertion(), claim.sourceUrl()));
                sb.append("```\n\n");
            }
            Files.writeString(wikiDir.resolve("needs_review.md"), sb.toString());
        } catch (IOException e) {
            log.error("Failed to save claims", e);
        }
    }
}
