/*
 * Copyright (c) 2026 siberanka.
 * Licensed under the GNU LGPL v3.0 or later.
 */
package com.siberanka.twilight.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.List;

public record TwilightConfig(
        boolean vanillaOverride,
        boolean strict,
        boolean autoBuildOnStartup,
        boolean syncProviderChanges,
        long startupDelayTicks,
        long providerCommandDelayTicks,
        long maximumSourceBytes,
        int maximumArchiveEntries,
        boolean downloadVanillaAssets,
        boolean autoDiscoverSources,
        List<Path> additionalSources,
        String geyserDirectory,
        boolean deployAfterBuild,
        boolean reloadAfterDeploy,
        int backupsToKeep
) {
    public static TwilightConfig read(FileConfiguration source, Path serverRoot) {
        long maximumBytes = source.getLong("generation.maximum-source-bytes", 1_073_741_824L);
        int maximumEntries = source.getInt("generation.maximum-archive-entries", 100_000);
        int backups = source.getInt("geyser.backups-to-keep", 3);
        long startupDelay = boundedTicks(source.getLong("generation.startup-delay-ticks", 40L), "startup-delay-ticks");
        long providerDelay = boundedTicks(source.getLong("generation.provider-command-delay-ticks", 100L), "provider-command-delay-ticks");
        if (maximumBytes < 1_048_576L) throw new IllegalArgumentException("maximum-source-bytes must be at least 1 MiB");
        if (maximumEntries < 100) throw new IllegalArgumentException("maximum-archive-entries must be at least 100");
        if (backups < 1 || backups > 20) throw new IllegalArgumentException("backups-to-keep must be between 1 and 20");

        List<Path> additional = source.getStringList("sources.additional").stream()
                .map(serverRoot::resolve).map(Path::normalize).toList();
        return new TwilightConfig(
                source.getBoolean("vanilla-override", false),
                source.getBoolean("generation.strict", true),
                source.getBoolean("generation.auto-build-on-startup", true),
                source.getBoolean("generation.sync-provider-changes", true),
                startupDelay,
                providerDelay,
                maximumBytes,
                maximumEntries,
                source.getBoolean("generation.download-vanilla-assets", true),
                source.getBoolean("sources.auto-discover", true),
                additional,
                source.getString("geyser.directory", "auto"),
                source.getBoolean("geyser.deploy-after-build", true),
                source.getBoolean("geyser.reload-after-deploy", false),
                backups
        );
    }

    private static long boundedTicks(long value, String name) {
        if (value < 1L || value > 72_000L) throw new IllegalArgumentException(name + " must be between 1 and 72000 ticks");
        return value;
    }
}
