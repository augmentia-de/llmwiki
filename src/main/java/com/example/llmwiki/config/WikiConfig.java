package com.example.llmwiki.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for the file-based wiki.
 */
@ConfigMapping(prefix = "llmwiki")
public interface WikiConfig {

    /** Base directory for all wiki data */
    @WithDefault("${HOME}/karpathy-wiki")
    String baseDir();

    /** Directory for immutable raw sources */
    @WithDefault("${HOME}/karpathy-wiki/raw")
    String rawDir();

    /** Directory for the wiki itself */
    @WithDefault("${HOME}/karpathy-wiki/wiki")
    String wikiDir();
}
