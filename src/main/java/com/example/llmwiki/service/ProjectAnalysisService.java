package com.example.llmwiki.service;

import com.example.llmwiki.config.WikiConfig;
import com.example.llmwiki.model.ProjectAnalysis;
import com.example.llmwiki.model.WikiPage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Analyzes software projects from local directories or GitHub repositories.
 *
 * Workflow:
 * 1. GitHub URL → clone to temp directory (via git CLI)
 * 2. Scan file tree for relevant files
 * 3. Extract technologies from build files (pom.xml, package.json, etc.)
 * 4. LLM analyzes key files and produces structured analysis
 * 5. Wiki pages created: project page, technology pages, keyword pages
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

    // File patterns to analyze
    private static final Set<String> RELEVANT_EXTENSIONS = Set.of(
        ".java", ".py", ".ts", ".tsx", ".js", ".jsx", ".kt", ".go", ".rs", ".rb",
        ".md", ".rst", ".txt", ".yaml", ".yml", ".json", ".xml", ".toml",
        ".cfg", ".ini", ".properties", ".sql", ".graphql"
    );

    // Files to always include
    private static final Set<String> ALWAYS_INCLUDE = Set.of(
        "README.md", "README.rst", "README.txt",
        "package.json", "pom.xml", "build.gradle", "build.gradle.kts",
        "requirements.txt", "pyproject.toml", "Cargo.toml", "go.mod",
        "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
        "Makefile", "CMakeLists.txt",
        ".env.example", ".gitignore",
        "LICENSE", "LICENSE.md", "LICENSE.txt"
    );

    // GitHub workflow files
    private static final Set<String> WORKFLOW_PATTERNS = Set.of(
        ".github/workflows/"
    );

    void init() {
        projectsDir = resolvePath(config.projectsDir());
        try {
            Files.createDirectories(projectsDir);
            log.info("Projects directory initialized: {}", projectsDir.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize projects directory", e);
        }
    }

    /**
     * Resolves a path string to an absolute Path.
     * Relative paths are resolved against the current working directory.
     */
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

        Path projectDir;
        String projectIdentifier;

        if (githubUrl != null && !githubUrl.isBlank()) {
            // Clone GitHub repo
            projectDir = cloneGitHubRepo(githubUrl);
            projectIdentifier = githubUrl;
        } else if (localPath != null && !localPath.isBlank()) {
            projectDir = Path.of(localPath);
            if (!Files.isDirectory(projectDir)) {
                throw new IOException("Local path is not a directory: " + localPath);
            }
            projectIdentifier = localPath;
        } else {
            throw new IOException("Either githubUrl or localPath must be provided");
        }

        try {
            // Scan project files
            List<Path> relevantFiles = scanProjectFiles(projectDir);
            log.info("Found {} relevant files", relevantFiles.size());

            // Extract technologies from build files
            List<String> detectedTechnologies = detectTechnologies(projectDir, relevantFiles);

            // Read key file contents for LLM analysis
            String fileContentsForLLM = prepareFileContents(relevantFiles, projectDir);

            // LLM analysis
            ProjectAnalysis analysis = analyzeWithLLM(
                projectIdentifier, fileContentsForLLM, detectedTechnologies
            );

            // Create wiki pages
            int pagesCreated = createWikiPages(analysis, projectIdentifier);

            // Update index
            updateProjectIndex();

            log.info("Project analysis complete: {} created {} pages",
                analysis.projectName(), pagesCreated);

            return new AnalysisResult(analysis, pagesCreated);

        } finally {
            // Clean up temp directory if cloned from GitHub
            if (githubUrl != null && !githubUrl.isBlank()) {
                deleteDirectory(projectDir);
            }
        }
    }

    /**
     * Clones a GitHub repository to a temp directory.
     */
    private Path cloneGitHubRepo(String githubUrl) throws IOException {
        Path tempDir = Files.createTempDirectory("scrwiki-clone-");
        String repoName = extractRepoName(githubUrl);

        ProcessBuilder pb = new ProcessBuilder(
            "git", "clone", "--depth", "1", githubUrl, tempDir.resolve(repoName).toString()
        );
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("git clone: {}", line);
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("git clone failed with exit code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git clone interrupted", e);
        }

        return tempDir.resolve(repoName);
    }

    private String extractRepoName(String githubUrl) {
        // https://github.com/user/repo → repo
        String[] parts = githubUrl.replaceAll("\\.git$", "").split("/");
        return parts.length > 0 ? parts[parts.length - 1] : "repo";
    }

    /**
     * Scans the project directory for relevant files.
     */
    private List<Path> scanProjectFiles(Path projectDir) throws IOException {
        List<Path> relevantFiles = new ArrayList<>();
        int maxFiles = 50; // Limit to avoid token overflow
        int count = 0;

        try (Stream<Path> walk = Files.walk(projectDir)) {
            List<Path> sorted = walk
                .filter(Files::isRegularFile)
                .filter(p -> !isInIgnoredDirectory(p, projectDir))
                .sorted(Comparator.comparing(Path::getFileName))
                .toList();

            for (Path file : sorted) {
                if (count >= maxFiles) break;

                String relativePath = projectDir.relativize(file).toString();
                String fileName = file.getFileName().toString();
                String extension = getFileExtension(fileName);

                boolean shouldInclude = ALWAYS_INCLUDE.contains(fileName);
                    //|| RELEVANT_EXTENSIONS.contains(extension)
                    //|| WORKFLOW_PATTERNS.stream().anyMatch(relativePath::contains);

                if (shouldInclude) {
                    relevantFiles.add(file);
                    count++;
                }
            }
        }

        return relevantFiles;
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

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(dotIndex).toLowerCase() : "";
    }

    /**
     * Detects technologies from build files.
     */
    private List<String> detectTechnologies(Path projectDir, List<Path> relevantFiles) {
        Set<String> technologies = new LinkedHashSet<>();

        for (Path file : relevantFiles) {
            String fileName = file.getFileName().toString();

            try {
                String content = Files.readString(file);

                switch (fileName) {
                    case "pom.xml" -> {
                        technologies.add("Java");
                        technologies.add("Maven");
                        // Extract common dependencies
                        extractXmlDependencies(content).forEach(technologies::add);
                    }
                    case "package.json" -> {
                        technologies.add("JavaScript");
                        technologies.add("Node.js");
                        extractJsonDependencies(content).forEach(technologies::add);
                    }
                    case "requirements.txt" -> {
                        technologies.add("Python");
                        content.lines()
                            .map(line -> line.split("==")[0].split(">=")[0].split("<")[0].trim())
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .forEach(technologies::add);
                    }
                    case "pyproject.toml" -> technologies.add("Python");
                    case "build.gradle", "build.gradle.kts" -> {
                        technologies.add("Java");
                        technologies.add("Gradle");
                    }
                    case "Cargo.toml" -> technologies.add("Rust");
                    case "go.mod" -> technologies.add("Go");
                    case "Dockerfile" -> technologies.add("Docker");
                    default -> {
                        if (fileName.endsWith(".java")) technologies.add("Java");
                        else if (fileName.endsWith(".py")) technologies.add("Python");
                        else if (fileName.endsWith(".ts") || fileName.endsWith(".tsx")) technologies.add("TypeScript");
                        else if (fileName.endsWith(".js") || fileName.endsWith(".jsx")) technologies.add("JavaScript");
                        else if (fileName.endsWith(".kt")) technologies.add("Kotlin");
                        else if (fileName.endsWith(".go")) technologies.add("Go");
                        else if (fileName.endsWith(".rs")) technologies.add("Rust");
                    }
                }
            } catch (IOException e) {
                log.debug("Could not read file for tech detection: {}", file, e);
            }
        }

        return new ArrayList<>(technologies);
    }

    private List<String> extractXmlDependencies(String pomXml) {
        List<String> deps = new ArrayList<>();
        // Simple extraction of artifactIds
        var matcher = java.util.regex.Pattern.compile("<artifactId>([^<]+)</artifactId>")
            .matcher(pomXml);
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            // Filter common non-library artifacts
            if (!artifactId.startsWith("maven-") &&
                !artifactId.equals("pom.xml") &&
                !artifactId.contains("parent")) {
                deps.add(artifactId);
            }
        }
        return deps.stream().limit(15).toList();
    }

    private List<String> extractJsonDependencies(String packageJson) {
        List<String> deps = new ArrayList<>();
        try {
            // Simple extraction without JSON library
            var matcher = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(packageJson);
            boolean inDepsSection = false;
            while (matcher.find()) {
                String key = matcher.group(1);
                if (key.equals("dependencies") || key.equals("devDependencies")) {
                    inDepsSection = true;
                    continue;
                }
                if (inDepsSection && key.startsWith("}")) {
                    inDepsSection = false;
                }
                if (inDepsSection && !key.equals("dependencies") && !key.equals("devDependencies")) {
                    deps.add(key);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse package.json dependencies", e);
        }
        return deps.stream().limit(20).toList();
    }

    /**
     * Prepares file contents for LLM analysis, grouping by type.
     */
    private String prepareFileContents(List<Path> files, Path projectDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        int maxContentSize = 15000; // Limit total size

        // Priority files first
        List<Path> priorityFiles = files.stream()
            .filter(f -> ALWAYS_INCLUDE.contains(f.getFileName().toString()))
            .toList();

        List<Path> otherFiles = files.stream()
            .filter(f -> !priorityFiles.contains(f))
            .toList();

        for (Path file : priorityFiles) {
            if (sb.length() >= maxContentSize) break;
            appendFileContent(file, projectDir, sb);
        }
/*
        for (Path file : otherFiles) {
            if (sb.length() >= maxContentSize) break;
            appendFileContent(file, projectDir, sb);
        }
*/
        return sb.toString();
    }

    private void appendFileContent(Path file, Path projectDir, StringBuilder sb) throws IOException {
        String content = Files.readString(file);
        // Skip binary-like files or very large files
        if (content.length() > 5000) {
            content = content.substring(0, 5000) + "\n... (truncated)";
        }
        if (!content.isBlank() && isTextFile(file.getFileName().toString())) {
            String relativePath = projectDir.relativize(file).toString();
            sb.append("\n\n--- FILE: ").append(relativePath).append(" ---\n\n");
            sb.append(content);
        }
    }

    private boolean isTextFile(String fileName) {
        String ext = getFileExtension(fileName);
        return !Set.of(".png", ".jpg", ".jpeg", ".gif", ".ico", ".woff", ".ttf", ".bin", ".jar", ".class").contains(ext);
    }

    /**
     * Calls LLM to analyze the project files.
     */
    private ProjectAnalysis analyzeWithLLM(String projectIdentifier, String fileContents, List<String> detectedTechnologies) {
        String technologiesHint = detectedTechnologies.isEmpty()
            ? ""
            : "\n\nPre-detected technologies: " + String.join(", ", detectedTechnologies);

        try {
            var response = retryableChat.chat(
                SystemMessage.from("""
                    You are a software project analyst. Analyze the provided code project and extract:

                    1. Project name (from README, package.json, pom.xml, or repository name)
                    2. A concise description (2-3 sentences)
                    3. All technologies used (languages, frameworks, libraries, tools)
                    4. Keywords/topics that describe the project (e.g. "RAG", "Agent", "REST API", "Microservice")
                    5. Key dependencies (library names)
                    6. Architecture summary (2-3 sentences describing the overall structure)
                    7. Key files with brief descriptions (max 10 files)

                    Respond ONLY in the following JSON format:
                    {
                      "projectName": "Project Name",
                      "description": "Description...",
                      "technologies": ["Java", "Quarkus", "LangChain4j"],
                      "keywords": ["RAG", "Agent", "Wiki"],
                      "dependencies": ["langchain4j", "jsoup"],
                      "architectureSummary": "Summary...",
                      "keyFiles": {"Main.java": "Entry point for the application"}
                    }
                    """),
                UserMessage.from("Project identifier: " + projectIdentifier
                    + technologiesHint
                    + "\n\nProject files:\n" + fileContents)
            );

            String json = response.aiMessage().text();
            return parseProjectAnalysisJson(json, projectIdentifier);

        } catch (Exception e) {
            log.error("LLM project analysis failed", e);
            return ProjectAnalysis.empty();
        }
    }

    private ProjectAnalysis parseProjectAnalysisJson(String json, String projectIdentifier) {
        try {
            // Simple manual JSON parsing (same approach as IngestService)
            String projectName = extractJsonValue(json, "projectName");
            String description = extractJsonValue(json, "description");
            List<String> technologies = extractJsonList(json, "technologies");
            List<String> keywords = extractJsonList(json, "keywords");
            List<String> dependencies = extractJsonList(json, "dependencies");
            String architectureSummary = extractJsonValue(json, "architectureSummary");

            // Parse keyFiles map (simplified)
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

    private String extractJsonValue(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return "";
        start = json.indexOf(":", start) + 1;
        // Skip whitespace
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
                // Only create if not already a concept page
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
        String currentIndex = wikiFileService.readIndex();

        // Rebuild index with project and technology sections
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
     * Recursively deletes a directory.
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path, e);
                        }
                    });
            }
        }
    }

    public record AnalysisResult(
        ProjectAnalysis analysis,
        int pagesCreated
    ) {}
}
