package com.siberanka.twilight.source;

import com.siberanka.twilight.config.TwilightConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SourceDiscovery {
    private static final Set<String> PROVIDERS = Set.of(
            "itemsadder", "craftengine", "nexo", "oraxen", "bettermodel", "modelengine", "realisticseasons"
    );
    private static final Set<String> PACK_NAMES = Set.of(
            "generated.zip", "resource_pack.zip", "resourcepack.zip", "pack.zip", "build.zip", "resource pack.zip"
    );
    private static final Set<String> PROVIDER_DATA_NAMES = Set.of(
            "cache", ".cache", "data", ".data", "contents", "content", "resources"
    );

    private final Path serverRoot;
    private final TwilightConfig config;

    public SourceDiscovery(Path serverRoot, TwilightConfig config) {
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
        this.config = config;
    }

    public List<ContentSource> discover(Set<Path> worlds) throws IOException {
        List<ContentSource> found = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        if (config.autoDiscoverSources()) {
            Path plugins = serverRoot.resolve("plugins");
            if (Files.isDirectory(plugins)) {
                try (var children = Files.list(plugins)) {
                    for (Path pluginDirectory : children.filter(Files::isDirectory).toList()) {
                        String provider = pluginDirectory.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!PROVIDERS.contains(provider)) continue;
                        discoverProvider(provider, pluginDirectory, found, seen);
                    }
                }
            }
            for (Path world : worlds) discoverDatapacks(world, found, seen);
        }
        for (Path additional : config.additionalSources()) {
            addIfPack("configured", ContentSource.Kind.RESOURCE_PACK, additional, 900, found, seen);
        }
        found.sort(Comparator.comparingInt(ContentSource::priority).thenComparing(source -> source.path().toString()));
        return List.copyOf(found);
    }

    private void discoverProvider(String provider, Path directory, List<ContentSource> found, Set<Path> seen) throws IOException {
        try (var paths = Files.walk(directory, 6)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) continue;
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean knownArchive = Files.isRegularFile(path) && PACK_NAMES.contains(name);
                boolean packDirectory = Files.isDirectory(path) &&
                        (Files.isRegularFile(path.resolve("pack.mcmeta")) || Files.isDirectory(path.resolve("assets")));
                boolean modelSource = Files.isDirectory(path) && path.getParent() != null &&
                        path.getParent().equals(directory) && (name.equals("blueprints") || name.equals("models"));
                if (knownArchive || packDirectory) {
                    ContentSource.Kind kind = provider.equals("realisticseasons")
                            ? ContentSource.Kind.SEASONAL_PACK : ContentSource.Kind.RESOURCE_PACK;
                    add(provider, kind, path, sourcePriority(provider, directory, path, knownArchive), found, seen);
                } else if (modelSource && (provider.equals("modelengine") || provider.equals("bettermodel"))) {
                    add(provider, ContentSource.Kind.MODEL_SOURCE, path, providerPriority(provider) + 90, found, seen);
                } else if (path.getParent() != null && path.getParent().equals(directory) &&
                        Files.isDirectory(path) && PROVIDER_DATA_NAMES.contains(name)) {
                    add(provider, ContentSource.Kind.PROVIDER_DATA, path,
                            providerPriority(provider) + metadataPriority(name), found, seen);
                }
            }
        }
    }

    private void discoverDatapacks(Path world, List<ContentSource> found, Set<Path> seen) throws IOException {
        Path datapacks = world.resolve("datapacks");
        if (!Files.isDirectory(datapacks) || Files.isSymbolicLink(datapacks)) return;
        try (var children = Files.list(datapacks)) {
            for (Path path : children.toList()) {
                if (Files.isSymbolicLink(path)) continue;
                if ((Files.isDirectory(path) && Files.isRegularFile(path.resolve("pack.mcmeta"))) ||
                        (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))) {
                    add("datapack:" + world.getFileName(), ContentSource.Kind.DATAPACK, path, 700, found, seen);
                }
            }
        }
    }

    private void addIfPack(String provider, ContentSource.Kind kind, Path path, int priority,
                           List<ContentSource> found, Set<Path> seen) throws IOException {
        if (!Files.exists(path) || Files.isSymbolicLink(path)) throw new IOException("Configured source is missing or symbolic: " + path);
        add(provider, kind, path, priority, found, seen);
    }

    private void add(String provider, ContentSource.Kind kind, Path path, int priority,
                     List<ContentSource> found, Set<Path> seen) throws IOException {
        Path real = path.toRealPath();
        if (seen.add(real)) found.add(new ContentSource(provider, kind, real, priority));
    }

    private static int providerPriority(String provider) {
        return switch (provider) {
            case "modelengine", "bettermodel" -> 500;
            case "realisticseasons" -> 800;
            default -> 600;
        };
    }

    private static int sourcePriority(String provider, Path providerRoot, Path source, boolean archive) {
        int base = providerPriority(provider);
        if (archive) return base;
        Path relative = providerRoot.relativize(source);
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.equals("contents") || name.equals("content") || name.equals("resources")) return base + 80;
            if (name.equals("data") || name.equals(".data")) return base + 60;
            if (name.equals("cache") || name.equals(".cache") || name.equals("storage")) return base + 40;
        }
        return base + 20;
    }

    private static int metadataPriority(String name) {
        return switch (name) {
            case "contents", "content", "resources" -> 79;
            case "data", ".data" -> 59;
            default -> 39;
        };
    }
}
