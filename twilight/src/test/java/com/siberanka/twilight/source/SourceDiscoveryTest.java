package com.siberanka.twilight.source;

import com.siberanka.twilight.config.TwilightConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceDiscoveryTest {
    @TempDir Path root;

    @Test
    void disablingAutomaticDiscoveryStillHonorsExplicitSources() throws Exception {
        Path automatic = Files.createDirectories(root.resolve("plugins/ItemsAdder/output"));
        Files.writeString(automatic.resolve("pack.mcmeta"), "{}");
        Path datapack = Files.createDirectories(root.resolve("world/datapacks/example"));
        Files.writeString(datapack.resolve("pack.mcmeta"), "{}");
        Path explicit = Files.createDirectories(root.resolve("explicit"));
        Files.writeString(explicit.resolve("pack.mcmeta"), "{}");

        TwilightConfig config = new TwilightConfig(false, true, false, true, 40, 100, 10_000_000, 1000,
                true, false, List.of(explicit), "auto", false, false, 3);
        List<ContentSource> sources = new SourceDiscovery(root, config)
                .discover(Set.of(root.resolve("world")));

        assertEquals(List.of(explicit.toRealPath()), sources.stream().map(ContentSource::path).toList());
    }

    @Test
    void providerAuthoredAssetsOverrideCachesAndGeneratedPackIsOnlyFallback() throws Exception {
        Path provider = Files.createDirectories(root.resolve("plugins/ItemsAdder"));
        Path generated = Files.createDirectories(provider.resolve("output"));
        Files.write(generated.resolve("generated.zip"), emptyZip());
        Path cache = Files.createDirectories(provider.resolve("storage/cache/tmp/resource_pack/assets/demo"));
        Files.writeString(cache.resolve("cached.json"), "{}");
        Files.createDirectories(cache.resolve("models/item"));
        Files.writeString(cache.resolve("models/item/shared.json"), "{\"layer\":\"cache\"}");
        Path data = Files.createDirectories(provider.resolve("data/resource_pack/assets/demo"));
        Files.writeString(data.resolve("data.json"), "{}");
        Files.createDirectories(data.resolve("models/item"));
        Files.writeString(data.resolve("models/item/shared.json"), "{\"layer\":\"data\"}");
        Path contents = Files.createDirectories(provider.resolve("contents/example/resourcepack/assets/demo"));
        Files.writeString(contents.resolve("authored.json"), "{}");
        Files.createDirectories(contents.resolve("models/item"));
        Files.writeString(contents.resolve("models/item/shared.json"), "{\"layer\":\"contents\"}");
        Files.createDirectories(provider.resolve("contents/example/configs"));
        Files.writeString(provider.resolve("contents/example/configs/items.yml"), "info: {}\n");

        TwilightConfig config = new TwilightConfig(false, true, true, true, 40, 100, 10_000_000, 1000,
                true, true, List.of(), "auto", false, false, 3);
        List<ContentSource> sources = new SourceDiscovery(root, config).discover(Set.of());

        ContentSource generatedSource = source(sources, "generated.zip");
        ContentSource cachedSource = source(sources, "resource_pack", "storage");
        ContentSource dataSource = source(sources, "resource_pack", "data");
        ContentSource authoredSource = source(sources, "resourcepack", "contents");
        assertTrue(generatedSource.priority() < cachedSource.priority());
        assertTrue(cachedSource.priority() < dataSource.priority());
        assertTrue(dataSource.priority() < authoredSource.priority());
        assertTrue(sources.stream().anyMatch(source -> source.kind() == ContentSource.Kind.PROVIDER_DATA &&
                source.path().getFileName().toString().equalsIgnoreCase("contents")));
        try (ResourceIndex resources = ResourceIndex.build(sources, config)) {
            assertEquals("{\"layer\":\"contents\"}", resources.find("assets/demo/models/item/shared.json")
                    .orElseThrow().readUtf8());
            assertTrue(resources.find("provider-data/itemsadder/contents/example/configs/items.yml").isPresent());
        }
    }

    private static ContentSource source(List<ContentSource> sources, String fileName, String... ancestor) {
        return sources.stream().filter(source -> source.path().getFileName().toString().equalsIgnoreCase(fileName))
                .filter(source -> ancestor.length == 0 || java.util.stream.StreamSupport.stream(source.path().spliterator(), false)
                        .anyMatch(part -> part.toString().equalsIgnoreCase(ancestor[0])))
                .findFirst().orElseThrow();
    }

    private static byte[] emptyZip() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) { }
        return bytes.toByteArray();
    }
}
