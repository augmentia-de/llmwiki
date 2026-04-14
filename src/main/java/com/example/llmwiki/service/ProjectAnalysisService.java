package com.example.llmwiki.service;

import com.example.llmwiki.config.WikiConfig;
import com.example.llmwiki.model.ProjectAnalysis;
import com.example.llmwiki.model.WikiPage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Analyzes software projects from local directories or GitHub repositories.
 *
 * Remote (GitHub): Fetches files via GitHub API/raw URLs — no clone needed.
 * Local: Reads files directly from the filesystem.
 *
 * Iterative workflow:
 * 1. Find files in priority order (README first)
 * 2. Read ONE file at a time, ask LLM if enough info
 * 3. If LLM says "need more" → read next file, accumulate context
 * 4. Create wiki pages from the analysis
 */
@ApplicationScoped
@Slf4j
public class ProjectAnalysisService {

    @Inject
    WikiConfig config;

    @Inject
    WikiFileService wikiFileService;

    @Inject
    RetryableChatService retryableChat;

    private Path projectsDir;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // Files to check — in priority order (README first, then build files, etc.)
    private static final List<String> FILE_PRIORITY_ORDER = List.of(
        "README.md", "README.rst", "README.txt",
        "pom.xml", "package.json", "build.gradle", "build.gradle.kts",
        "requirements.txt", "pyproject.toml", "Cargo.toml", "go.mod",
        "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
        "Makefile", "CMakeLists.txt",
        ".env.example", ".gitignore",
        "LICENSE", "LICENSE.md", "LICENSE.txt"
    );

    private static final int MAX_FILES_TO_READ = 5;

    void init() {
        projectsDir = resolvePath(config.projectsDir());
        try {
            Files.createDirectories(projectsDir);
            log.info("Projects directory initialized: {}", projectsDir.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize projects directory", e);
        }
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path).normalize();
        }
        return path;
    }

    /**
     * Main entry point: analyze a project from GitHub URL or local path.
     */
    public AnalysisResult analyzeProject(String githubUrl, String localPath) throws IOException {
        log.info("Starting project analysis: url={}, localPath={}", githubUrl, localPath);

        ProjectAnalysis analysis;
        String projectIdentifier;

        if (githubUrl != null && !githubUrl.isBlank()) {
            // Remote analysis via GitHub API — no clone
            analysis = analyzeIterativelyRemote(githubUrl);
            projectIdentifier = githubUrl;
        } else if (localPath != null && !localPath.isBlank()) {
            // Local analysis
            Path projectDir = Path.of(localPath);
            if (!Files.isDirectory(projectDir)) {
                throw new IOException("Local path is not a directory: " + localPath);
            }

            List<Path> priorityFiles = scanProjectFilesInPriorityOrder(projectDir);
            log.info("Found {} files in priority order, will read max {}", priorityFiles.size(), MAX_FILES_TO_READ);

            analysis = analyzeIterativelyLocal(localPath, priorityFiles, projectDir);
            projectIdentifier = localPath;
        } else {
            throw new IOException("Either githubUrl or localPath must be provided");
        }

        // Create wiki pages
        int pagesCreated = createWikiPages(analysis, projectIdentifier);

        // Update index
        updateProjectIndex();

        log.info("Project analysis complete: {} created {} pages",
            analysis.projectName(), pagesCreated);

        return new AnalysisResult(analysis, pagesCreated);
    }

    // ─── GitHub Remote Methods ───────────────────────────────────────────

    /**
     * Parses a GitHub URL and extracts owner, repo.
     * E.g. "https://github.com/user/repo" → ["user", "repo"]
     */
    private String[] parseGitHubUrl(String githubUrl) {
        String cleaned = githubUrl.replaceAll("\\.git$", "").trim();
        String[] parts = cleaned.split("/");
        if (parts.length >= 2) {
            return new String[]{parts[parts.length - 2], parts[parts.length - 1]};
        }
        return null;
    }

    /**
     * Fetches the default branch name from GitHub API.
     */
    private String fetchDefaultBranch(String owner, String repo) {
        try {
            String url = "https://api.github.com/repos/" + owner + "/" + repo;
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ScrWiki/1.0")
                .GET()
                .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("GitHub API returned {}: {}", resp.statusCode(), resp.body().substring(0, Math.min(200, resp.body().length())));
                return "main";
            }

            String json = resp.body();
            int idx = json.indexOf("\"default_branch\"");
            if (idx >= 0) {
                int start = json.indexOf(":", idx) + 1;
                while (start < json.length() && json.charAt(start) != '"') start++;
                start++;
                int end = json.indexOf("\"", start);
                if (end > start) return json.substring(start, end);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch default branch, defaulting to 'main': {}", e.getMessage());
        }
        return "main";
    }

    /**
     * Lists files in the root of a GitHub repo that match FILE_PRIORITY_ORDER.
     * Returns file names in priority order.
     */
    private List<String> fetchRootFileList(String owner, String repo, String branch) {
        try {
            String url = String.format("https://api.github.com/repos/%s/%s/contents/?ref=%s",
                owner, repo, branch);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ScrWiki/1.0")
                .GET()
                .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("GitHub API list files returned {}: {}", resp.statusCode(), resp.body().substring(0, Math.min(200, resp.body().length())));
                return Collections.emptyList();
            }

            // Parse JSON response to find matching files
            Set<String> availableFiles = new LinkedHashSet<>();
            var matcher = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(resp.body());
            while (matcher.find()) {
                availableFiles.add(matcher.group(1));
            }

            // Return in priority order
            List<String> result = new ArrayList<>();
            for (String priorityFile : FILE_PRIORITY_ORDER) {
                if (availableFiles.contains(priorityFile)) {
                    result.add(priorityFile);
                }
            }

            log.info("Found {} priority files in remote repo: {}", result.size(), result);
            return result;

        } catch (Exception e) {
            log.warn("Failed to list remote files: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetches a single file's content from raw.githubusercontent.com.
     */
    private String fetchGitHubFileContent(String owner, String repo, String branch, String fileName) {
        try {
            String url = String.format("https://raw.githubusercontent.com/%s/%s/%s/%s",
                owner, repo, branch, fileName);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "ScrWiki/1.0")
                .GET()
                .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("File not found ({}): {}", resp.statusCode(), fileName);
                return null;
            }

            String content = resp.body();
            if (content.isBlank()) return null;

            // Truncate very large files
            if (content.length() > 10000) {
                content = content.substring(0, 10000) + "\n... (truncated)";
            }

            return content;

        } catch (Exception e) {
            log.debug("Failed to fetch remote file {}: {}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * Iterative analysis via GitHub API: fetches one file at a time remotely.
     */
    private ProjectAnalysis analyzeIterativelyRemote(String githubUrl) {
        String[] parsed = parseGitHubUrl(githubUrl);
        if (parsed == null) {
            log.error("Invalid GitHub URL: {}", githubUrl);
            return ProjectAnalysis.empty();
        }

        String owner = parsed[0];
        String repo = parsed[1];
        String branch = fetchDefaultBranch(owner, repo);

        log.info("Remote analysis: {}/{}, branch={}", owner, repo, branch);

        // Get list of available files
        List<String> availableFiles = fetchRootFileList(owner, repo, branch);
        if (availableFiles.isEmpty()) {
            log.warn("No priority files found in remote repo");
            return ProjectAnalysis.empty();
        }

        StringBuilder accumulatedContext = new StringBuilder();
        int filesRead = 0;
        ProjectAnalysis bestAnalysis = null;

        for (String fileName : availableFiles) {
            if (filesRead >= MAX_FILES_TO_READ) {
                log.info("Max files limit ({}) reached, stopping", MAX_FILES_TO_READ);
                break;
            }

            // Fetch ONE file remotely
            String content = fetchGitHubFileContent(owner, repo, branch, fileName);
            if (content == null || content.isBlank()) {
                continue;
            }

            filesRead++;
            accumulatedContext.append("\n\n--- FILE ").append(filesRead).append(": ")
                .append(fileName).append(" ---\n\n").append(content);

            log.info("Fetched remote file {}/{}: {} ({} chars)", filesRead, MAX_FILES_TO_READ, fileName, content.length());

            // Ask LLM to analyze accumulated context
            LlmIterativeResponse llmResponse = analyzeIncremental(
                githubUrl, accumulatedContext.toString(), filesRead
            );

            bestAnalysis = llmResponse.analysis;

            if (llmResponse.sufficient) {
                log.info("LLM determined analysis is sufficient after {} remote file(s): {}",
                    filesRead, fileName);
                break;
            } else {
                log.info("LLM needs more info (reason: {}), fetching next file...",
                    llmResponse.reason);
            }
        }

        if (bestAnalysis == null) {
            log.warn("No analysis result from LLM, returning empty");
            return ProjectAnalysis.empty();
        }

        return bestAnalysis;
    }

    // ─── Local File Methods ──────────────────────────────────────────────

    /**
     * Scans the project directory and returns files sorted by priority order.
     * Root-level files are preferred over files in subdirectories.
     */
    private List<Path> scanProjectFilesInPriorityOrder(Path projectDir) throws IOException {
        Map<String, Path> availableFiles = new LinkedHashMap<>();

        try (Stream<Path> walk = Files.walk(projectDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !isInIgnoredDirectory(p, projectDir))
                .sorted(Comparator.comparing(Path::getFileName)
                    .thenComparing(p -> p.getParent() != null ? p.getParent().getNameCount() : 0))
                .forEach(file -> {
                    String fileName = file.getFileName().toString();
                    if (FILE_PRIORITY_ORDER.contains(fileName)) {
                        // Prefer root-level files over subdirectory files
                        Path relative = projectDir.relativize(file);
                        boolean isRootFile = relative.getNameCount() == 1;
                        if (isRootFile || !availableFiles.containsKey(fileName)) {
                            availableFiles.put(fileName, file);
                        }
                    }
                });
        }

        List<Path> result = new ArrayList<>();
        for (String priorityFile : FILE_PRIORITY_ORDER) {
            if (availableFiles.containsKey(priorityFile)) {
                result.add(availableFiles.get(priorityFile));
            }
        }

        return result;
    }

    private boolean isInIgnoredDirectory(Path file, Path projectDir) {
        String relative = projectDir.relativize(file).toString();
        return relative.startsWith(".git/")
            || relative.startsWith("node_modules/")
            || relative.startsWith("target/")
            || relative.startsWith("build/")
            || relative.startsWith("dist/")
            || relative.startsWith(".venv/")
            || relative.startsWith("venv/")
            || relative.startsWith("__pycache__/")
            || relative.startsWith(".idea/")
            || relative.startsWith(".vscode/");
    }

    /**
     * Iterative analysis: reads one file at a time, asks LLM if sufficient.
     */
    private ProjectAnalysis analyzeIterativelyLocal(
        String projectIdentifier, List<Path> priorityFiles, Path projectDir
    ) throws IOException {
        StringBuilder accumulatedContext = new StringBuilder();
        int filesRead = 0;
        ProjectAnalysis bestAnalysis = null;

        for (Path file : priorityFiles) {
            if (filesRead >= MAX_FILES_TO_READ) {
                log.info("Max files limit ({}) reached, stopping", MAX_FILES_TO_READ);
                break;
            }

            // Read ONE file
            String content = readSingleFile(file, projectDir);
            if (content == null || content.isBlank()) {
                continue;
            }

            String fileName = projectDir.relativize(file).toString();
            accumulatedContext.append("\n\n--- FILE ").append(filesRead + 1).append(": ")
                .append(fileName).append(" ---\n\n").append(content);

            filesRead++;
            log.info("Read file {}/{}: {} ({} chars)", filesRead, MAX_FILES_TO_READ, fileName, content.length());

            // Ask LLM to analyze accumulated context
            LlmIterativeResponse llmResponse = analyzeIncremental(
                projectIdentifier, accumulatedContext.toString(), filesRead
            );

            bestAnalysis = llmResponse.analysis;

            if (llmResponse.sufficient) {
                log.info("LLM determined analysis is sufficient after {} file(s): {}",
                    filesRead, fileName);
                break;
            } else {
                log.info("LLM needs more info (reason: {}), reading next file...",
                    llmResponse.reason);
            }
        }

        if (bestAnalysis == null) {
            log.warn("No analysis result from LLM, returning empty");
            return ProjectAnalysis.empty();
        }

        return bestAnalysis;
    }

    /**
     * Reads a single file and returns its content.
     */
    private String readSingleFile(Path file, Path projectDir) throws IOException {
        String content = Files.readString(file);

        if (content.isBlank()) return null;

        if (content.length() > 10000) {
            content = content.substring(0, 10000) + "\n... (truncated)";
        }

        if (!isTextFile(file.getFileName().toString())) return null;

        return content;
    }

    private boolean isTextFile(String fileName) {
        String ext = getFileExtension(fileName);
        return !Set.of(".png", ".jpg", ".jpeg", ".gif", ".ico", ".woff", ".ttf", ".bin", ".jar", ".class").contains(ext);
    }

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(dotIndex).toLowerCase() : "";
    }

    /**
     * Calls LLM to analyze accumulated file contents.
     * LLM decides if more files are needed.
     */
    private LlmIterativeResponse analyzeIncremental(
        String projectIdentifier, String accumulatedContext, int filesRead
    ) {
        try {
            var response = retryableChat.chat(
                SystemMessage.from("""
                    You are a software project analyst analyzing a project ITERATIVELY.

                    You receive file contents ONE AT A TIME. After each file, decide:

                    **If you have enough information:**
                    - Set "sufficient": true
                    - Provide complete analysis with all fields

                    **If you need more information:**
                    - Set "sufficient": false
                    - Explain what's missing in "reason"
                    - Suggest what type of file to read next in "nextFileHint"
                    - Still provide your best analysis so far (partial)

                    Respond ONLY in the following JSON format:
                    {
                      "sufficient": true/false,
                      "reason": "Why you need more info (or empty if sufficient)",
                      "nextFileHint": "e.g. 'build file for dependencies' or 'architecture docs'",
                      "analysis": {
                        "projectName": "Project Name",
                        "description": "2-3 sentences...",
                        "technologies": ["Java", "Quarkus"],
                        "keywords": ["RAG", "Agent", "Wiki"],
                        "dependencies": ["langchain4j", "jsoup"],
                        "architectureSummary": "2-3 sentences...",
                        "keyFiles": {"Main.java": "Entry point"}
                      }
                    }

                    Note: For a typical project, README.md alone often provides enough information.
                    Only request more files if critical details (dependencies, architecture) are missing.
                    """),
                UserMessage.from("""
                    Project: %s
                    Files read so far: %d

                    %s

                    Do you have enough information to complete the analysis?
                    """.formatted(projectIdentifier, filesRead, accumulatedContext))
            );

            String json = response.aiMessage().text();
            return parseLlmIterativeResponse(json, projectIdentifier);

        } catch (Exception e) {
            log.error("LLM incremental analysis failed", e);
            return new LlmIterativeResponse(true, "", "", ProjectAnalysis.empty());
        }
    }

    private LlmIterativeResponse parseLlmIterativeResponse(String json, String projectIdentifier) {
        try {
            boolean sufficient = extractJsonBoolean(json, "sufficient");
            String reason = extractJsonValue(json, "reason");
            String nextFileHint = extractJsonValue(json, "nextFileHint");

            // Parse nested analysis object
            int analysisStart = json.indexOf("\"analysis\"");
            ProjectAnalysis analysis = ProjectAnalysis.empty();

            if (analysisStart >= 0) {
                int braceStart = json.indexOf("{", analysisStart);
                int braceEnd = findMatchingBrace(json, braceStart);
                if (braceStart >= 0 && braceEnd >= 0) {
                    String inner = json.substring(braceStart, braceEnd + 1);
                    analysis = parseProjectAnalysisJson(inner, projectIdentifier);
                }
            }

            return new LlmIterativeResponse(sufficient, reason, nextFileHint, analysis);

        } catch (Exception e) {
            log.warn("JSON parsing failed for LLM iterative response", e);
            return new LlmIterativeResponse(true, "", "", ProjectAnalysis.empty());
        }
    }

    private boolean extractJsonBoolean(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return true;
        start = json.indexOf(":", start) + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return true;

        if (json.startsWith("true", start)) return true;
        if (json.startsWith("false", start)) return false;
        return true;
    }

    private ProjectAnalysis parseProjectAnalysisJson(String json, String projectIdentifier) {
        try {
            String projectName = extractJsonValue(json, "projectName");
            String description = extractJsonValue(json, "description");
            List<String> technologies = extractJsonList(json, "technologies");
            List<String> keywords = extractJsonList(json, "keywords");
            List<String> dependencies = extractJsonList(json, "dependencies");
            String architectureSummary = extractJsonValue(json, "architectureSummary");

            Map<String, String> keyFiles = new LinkedHashMap<>();
            int start = json.indexOf("\"keyFiles\"");
            if (start >= 0) {
                int braceStart = json.indexOf("{", start);
                int braceEnd = findMatchingBrace(json, braceStart);
                if (braceStart >= 0 && braceEnd >= 0) {
                    String inner = json.substring(braceStart + 1, braceEnd);
                    var matcher = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
                        .matcher(inner);
                    while (matcher.find()) {
                        keyFiles.put(matcher.group(1), matcher.group(2));
                    }
                }
            }

            return new ProjectAnalysis(
                projectName, projectIdentifier, description,
                technologies, keywords, dependencies, architectureSummary, keyFiles
            );
        } catch (Exception e) {
            log.warn("JSON parsing failed for project analysis", e);
            return ProjectAnalysis.empty();
        }
    }

    private int findMatchingBrace(String json, int start) {
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int findMatchingBracket(String json, int start) {
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private String extractJsonValue(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return "";
        start = json.indexOf(":", start) + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "";

        if (json.charAt(start) == '"') {
            start++;
            int end = json.indexOf("\"", start);
            return end > start ? json.substring(start, end) : "";
        }
        return "";
    }

    private List<String> extractJsonList(String json, String key) {
        List<String> result = new ArrayList<>();
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return result;

        int bracketStart = json.indexOf("[", start);
        if (bracketStart == -1) return result;

        int bracketEnd = findMatchingBracket(json, bracketStart);
        if (bracketEnd == -1) return result;

        String inner = json.substring(bracketStart + 1, bracketEnd);
        var matcher = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(inner);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * Creates wiki pages from the analysis.
     */
    private int createWikiPages(ProjectAnalysis analysis, String projectIdentifier) throws IOException {
        int pagesCreated = 0;
        String projectSlug = analysis.slug();

        // 1. Main project page
        String projectContent = """
            # %s

            %s

            **Source:** `%s`

            ## Architecture

            %s

            ## Technologies

            %s

            ## Key Dependencies

            %s

            ## Key Files

            %s

            ## Keywords

            %s
            """.formatted(
            analysis.projectName(),
            analysis.description(),
            projectIdentifier,
            analysis.architectureSummary(),
            analysis.technologies().stream().map(t -> "- " + t).collect(Collectors.joining("\n")),
            analysis.dependencies().stream().map(d -> "- " + d).collect(Collectors.joining("\n")),
            analysis.keyFiles().entrySet().stream()
                .map(e -> "- `%s` — %s".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n")),
            analysis.keywords().stream().map(k -> "[[" + wikiFileService.slugify(k) + "]]").collect(Collectors.joining(" "))
        );

        WikiPage projectPage = new WikiPage(
            projectSlug,
            analysis.projectName(),
            "project",
            projectContent,
            List.of(projectIdentifier),
            LocalDate.now().toString(),
            null
        );
        wikiFileService.writePage(projectPage);
        pagesCreated++;

        // 2. Technology pages
        for (String tech : analysis.technologies()) {
            String techSlug = wikiFileService.slugify(tech);
            WikiPage existing = wikiFileService.readPage(techSlug);

            String techContent;
            List<String> sources = new ArrayList<>();

            if (existing != null) {
                techContent = existing.content() + "\n\n---\n\n## Used in [[%s]]\n- %s".formatted(
                    projectSlug, analysis.description()
                );
                sources.addAll(existing.sources());
            } else {
                techContent = "# %s\n\nTechnology used in projects.\n\n## Projects\n- [[%s]] — %s".formatted(
                    tech, projectSlug, analysis.description()
                );
            }
            sources.add(projectIdentifier);

            WikiPage techPage = new WikiPage(
                techSlug, tech.toUpperCase(), "technology",
                techContent, sources,
                existing != null ? existing.created() : LocalDate.now().toString(),
                LocalDate.now().toString()
            );
            wikiFileService.writePage(techPage);
            pagesCreated++;
        }

        // 3. Keyword/concept pages
        for (String keyword : analysis.keywords()) {
            String keywordSlug = wikiFileService.slugify(keyword);
            WikiPage existing = wikiFileService.readPage(keywordSlug);

            if (existing == null) {
                String keywordContent = "# %s\n\nKeyword/Topic mentioned in projects.\n\n## Related Projects\n- [[%s]]".formatted(
                    keyword, projectSlug
                );
                WikiPage keywordPage = new WikiPage(
                    keywordSlug, keyword, "concept",
                    keywordContent, List.of(projectIdentifier),
                    LocalDate.now().toString(), null
                );
                wikiFileService.writePage(keywordPage);
                pagesCreated++;
            }
        }

        // 4. Update log
        String logEntry = "## [%s] project-analysis | %s\nTechnologies: %s | Keywords: %s | %d pages created"
            .formatted(LocalDate.now(), analysis.projectName(),
                String.join(", ", analysis.technologies()),
                String.join(", ", analysis.keywords()),
                pagesCreated);
        wikiFileService.appendToLog(logEntry);

        return pagesCreated;
    }

    /**
     * Updates the wiki index to include projects and technologies.
     */
    private void updateProjectIndex() throws IOException {
        StringBuilder index = new StringBuilder("# Wiki Index\n\n");

        String[] categories = {"entity", "concept", "project", "technology", "source-summary", "analysis"};
        String[] headings = {"## Entities\n", "## Concepts\n", "## Projects\n", "## Technologies\n", "## Sources\n", "## Analyses\n"};

        for (int i = 0; i < categories.length; i++) {
            index.append(headings[i]);

            String dirName = switch (categories[i]) {
                case "entity" -> "entities";
                case "concept" -> "concepts";
                case "project" -> "projects";
                case "technology" -> "technologies";
                case "source-summary" -> "sources";
                case "analysis" -> "analyses";
                default -> categories[i];
            };

            Path dir = Path.of(config.wikiDir()).resolve(dirName);
            if (Files.exists(dir)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.filter(p -> p.toString().endsWith(".md"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                String content = Files.readString(p);
                                String title = extractMarkdownTitle(content);
                                String summary = content.split("\n")[0];
                                if (summary.length() > 120) summary = summary.substring(0, 120) + "...";
                                String slug = p.getFileName().toString().replace(".md", "");
                                index.append("- [[%s]] — %s\n".formatted(slug, summary));
                            } catch (IOException e) {
                                log.error("Failed to read page for index: {}", p, e);
                            }
                        });
                }
            }
            index.append("\n");
        }

        wikiFileService.writeIndex(index.toString());
    }

    private String extractMarkdownTitle(String markdown) {
        for (String line : markdown.split("\n")) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "Untitled";
    }

    /**
     * LLM response for iterative analysis.
     */
    record LlmIterativeResponse(
        boolean sufficient,
        String reason,
        String nextFileHint,
        ProjectAnalysis analysis
    ) {}

    public record AnalysisResult(
        ProjectAnalysis analysis,
        int pagesCreated
    ) {}
}
