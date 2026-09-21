package com.siberanka.twilight.deploy;

import com.siberanka.twilight.config.TwilightConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeyserDeploymentServiceTest {
    @TempDir Path root;

    @Test
    void deploysOnlyOwnedFilesRetainsThreeSnapshotsAndRollsBack() throws Exception {
        Path geyser = Files.createDirectories(root.resolve("plugins/Geyser-Spigot"));
        Files.createDirectories(geyser.resolve("packs"));
        Files.writeString(geyser.resolve("packs/unrelated.zip"), "keep");
        Path data = Files.createDirectories(root.resolve("plugins/Twilight"));
        Path output = Files.createDirectories(data.resolve("build/current/custom_mappings"));
        GeyserDeploymentService service = new GeyserDeploymentService(root, data, config());

        for (int version = 1; version <= 5; version++) {
            Files.writeString(data.resolve("build/current/pack.zip"), "pack-" + version);
            Files.writeString(output.resolve("geyser_item_mappings.json"), "mapping-" + version);
            DeploymentResult result = service.deploy(data.resolve("build/current"));
            assertTrue(result.success());
            Thread.sleep(2);
        }

        assertEquals("pack-5", Files.readString(geyser.resolve("packs/twilight.zip")));
        assertEquals("mapping-5", Files.readString(geyser.resolve("custom_mappings/twilight_item_mappings.json")));
        assertEquals("keep", Files.readString(geyser.resolve("packs/unrelated.zip")));
        assertEquals(3, service.snapshots().size());

        service.rollback(1);
        assertEquals("pack-4", Files.readString(geyser.resolve("packs/twilight.zip")));
        assertEquals("mapping-4", Files.readString(geyser.resolve("custom_mappings/twilight_item_mappings.json")));
        assertFalse(Files.exists(root.resolve("outside")));
    }

    private TwilightConfig config() {
        return new TwilightConfig(false, true, false, true, 40, 100, 10_000_000, 1000,
                true, true, List.of(), "auto", false, false, 3);
    }
}
