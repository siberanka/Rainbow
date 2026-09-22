package com.siberanka.twilight.compiler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.siberanka.twilight.config.TwilightConfig;
import com.siberanka.twilight.source.ContentSource;
import com.siberanka.twilight.source.CustomItemDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPackCompilerTest {
    @TempDir Path root;

    @Test
    void convertsLayeredLegacyAndVolumetricModernVariantsDeterministically() throws Exception {
        Path source = root.resolve("source");
        write(source, "assets/minecraft/models/item/paper.json", """
                {"parent":"minecraft:item/generated","overrides":[
                  {"predicate":{"custom_model_data":7},"model":"demo:item/layered"}
                ]}
                """);
        write(source, "assets/demo/models/item/layered.json", """
                {"parent":"minecraft:item/generated","textures":{"layer0":"demo:item/base","layer1":"demo:item/top"}}
                """);
        png(source.resolve("assets/demo/textures/item/base.png"), Color.RED);
        png(source.resolve("assets/demo/textures/item/top.png"), new Color(0, 0, 255, 128));

        write(source, "assets/demo/items/hammer.json", """
                {"model":{"type":"minecraft:condition","property":"minecraft:damaged",
                  "on_true":{"type":"minecraft:model","model":"demo:item/hammer_damaged"},
                  "on_false":{"type":"minecraft:composite","models":[
                    {"type":"minecraft:model","model":"demo:item/hammer"},
                    {"type":"minecraft:model","model":"demo:item/handle"}]}}}
                """);
        write(source, "assets/demo/models/item/hammer.json", cube("demo:item/hammer"));
        write(source, "assets/demo/models/item/hammer_damaged.json", cube("demo:item/hammer_damaged"));
        write(source, "assets/demo/models/item/handle.json", cube("demo:item/handle"));
        png(source.resolve("assets/demo/textures/item/hammer.png"), Color.ORANGE);
        png(source.resolve("assets/demo/textures/item/hammer_damaged.png"), Color.GRAY);
        png(source.resolve("assets/demo/textures/item/handle.png"), Color.DARK_GRAY);

        ContentSource pack = new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1);
        CustomItemDescriptor live = new CustomItemDescriptor("test", "minecraft:iron_pickaxe",
                Optional.of("demo:hammer"), OptionalInt.empty(), "Hammer");
        Path data = root.resolve("data");
        BedrockPackCompiler compiler = new BedrockPackCompiler(data, config());
        BuildResult first = compiler.build(List.of(pack), List.of(live));
        BuildResult second = compiler.build(List.of(pack), List.of(live));

        assertEquals(3, first.converted());
        assertEquals(2, first.threeDimensional());
        assertEquals(first.packSha256(), second.packSha256());
        try (ZipFile zip = new ZipFile(second.outputDirectory().resolve("pack.zip").toFile())) {
            assertNotNull(zip.getEntry("textures/item_texture.json"));
            JsonObject manifest = read(zip, "manifest.json");
            JsonArray headerVersion = manifest.getAsJsonObject("header").getAsJsonArray("version");
            JsonArray moduleVersion = manifest.getAsJsonArray("modules").get(0).getAsJsonObject()
                    .getAsJsonArray("version");
            assertEquals(headerVersion, moduleVersion);
            assertTrue(headerVersion.get(2).getAsInt() >= 1);
            assertTrue(headerVersion.get(2).getAsInt() <= 65_535,
                    "pack version must remain in the Bedrock client-safe component range");
            long geometries = zip.stream().filter(entry -> entry.getName().startsWith("models/entity/")).count();
            assertEquals(3, geometries);
            assertTrue(zip.stream().filter(entry -> entry.getName().startsWith("models/entity/"))
                    .map(entry -> {
                        try { return read(zip, entry.getName()); } catch (Exception failure) { throw new RuntimeException(failure); }
                    }).anyMatch(value -> value.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                            .getAsJsonArray("bones").get(0).getAsJsonObject().getAsJsonArray("cubes").size() == 2),
                    "static composite parts must be merged into one Bedrock geometry");
            String geometryName = zip.stream().filter(entry -> entry.getName().startsWith("models/entity/"))
                    .findFirst().orElseThrow().getName();
            JsonObject geometry = read(zip, geometryName);
            JsonArray size = geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                    .getAsJsonArray("bones").get(0).getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject()
                    .getAsJsonArray("size");
            assertTrue(size.get(2).getAsDouble() > 0, "3D depth must remain volumetric");
        }

        JsonObject mappings = JsonParser.parseString(Files.readString(second.outputDirectory()
                .resolve("custom_mappings/geyser_item_mappings.json"))).getAsJsonObject();
        JsonObject items = mappings.getAsJsonObject("items");
        assertTrue(items.has("minecraft:paper"));
        assertTrue(items.has("minecraft:iron_pickaxe"));
        assertEquals(1, items.getAsJsonArray("minecraft:paper").size());
        assertEquals(2, items.getAsJsonArray("minecraft:iron_pickaxe").size());
        assertFalse(items.has("minecraft:stick"), "unmodified vanilla items must never be emitted");
        for (var definition : items.getAsJsonArray("minecraft:iron_pickaxe")) {
            assertEquals("demo:hammer", definition.getAsJsonObject().get("model").getAsString());
            assertTrue(definition.getAsJsonObject().has("predicate"));
        }
    }

    @Test
    void preservesLargeModelJavaHandTransformWithoutImplicitFitting() throws Exception {
        Path source = root.resolve("wide-model-source");
        write(source, "assets/demo/items/wide.json", """
                {"model":{"type":"minecraft:model","model":"demo:item/wide"}}
                """);
        write(source, "assets/demo/models/item/wide.json", """
                {"textures":{"all":"demo:item/wide"},
                 "display":{"firstperson_righthand":{"rotation":[-163,75,170],
                   "translation":[0.5,4,-2],"scale":[0.5,0.5,0.5]}},
                 "elements":[{"from":[-32,-16,-24],"to":[48,40,32],"faces":{
                   "north":{"texture":"#all"},"south":{"texture":"#all"},
                   "east":{"texture":"#all"},"west":{"texture":"#all"},
                   "up":{"texture":"#all"},"down":{"texture":"#all"}}}]}
                """);
        png(source.resolve("assets/demo/textures/item/wide.png"), Color.MAGENTA);
        CustomItemDescriptor live = new CustomItemDescriptor("test", "minecraft:crossbow",
                Optional.of("demo:wide"), OptionalInt.empty(), "Wide model");

        BuildResult result = new BedrockPackCompiler(root.resolve("wide-model-data"), config()).build(
                List.of(new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)), List.of(live));

        try (ZipFile zip = new ZipFile(result.outputDirectory().resolve("pack.zip").toFile())) {
            String animationName = zip.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.startsWith("animations/") && name.endsWith(".animation.json"))
                    .findFirst().orElseThrow();
            JsonObject definitions = read(zip, animationName).getAsJsonObject("animations");
            JsonObject firstPerson = definitions.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith(".first_person_right"))
                    .findFirst().orElseThrow().getValue().getAsJsonObject();
            JsonArray scale = firstPerson.getAsJsonObject("bones").getAsJsonObject("bone")
                    .getAsJsonArray("scale");
            JsonArray position = firstPerson.getAsJsonObject("bones").getAsJsonObject("bone")
                    .getAsJsonArray("position");
            assertEquals(0.5, scale.get(0).getAsDouble(), 1.0e-9);
            assertEquals(0.5, scale.get(1).getAsDouble(), 1.0e-9);
            assertEquals(0.5, scale.get(2).getAsDouble(), 1.0e-9);
            assertEquals(-4.0, position.get(0).getAsDouble(), 1.0e-9);
            assertEquals(10.5, position.get(1).getAsDouble(), 1.0e-9);
            assertEquals(0.5, position.get(2).getAsDouble(), 1.0e-9);
        }
    }

    @Test
    void derivesHandheldPoseFromTheResolvedJavaModelInsteadOfTheBaseItem() throws Exception {
        Path source = root.resolve("model-parent-pose-source");
        write(source, "assets/demo/models/item/axe_up.json", """
                {"parent":"minecraft:item/handheld","textures":{"all":"demo:item/axe_up"},
                 "display":{
                   "firstperson_righthand":{"rotation":[17,-90,0],"translation":[2.6,7,-0.008],"scale":[1.6,1.6,1.6]},
                   "thirdperson_righthand":{"rotation":[0,-90,0],"translation":[0,7,1],"scale":[2.4,2.4,2.4]}},
                 "elements":[{"from":[2,0,7],"to":[14,16,9],"faces":{
                   "north":{"texture":"#all"},"south":{"texture":"#all"},
                   "east":{"texture":"#all"},"west":{"texture":"#all"},
                   "up":{"texture":"#all"},"down":{"texture":"#all"}}}]}
                """);
        write(source, "assets/demo/models/item/generated_box.json", """
                {"parent":"minecraft:item/generated","textures":{"all":"demo:item/generated_box"},
                 "elements":[{"from":[2,0,7],"to":[14,16,9],"faces":{
                   "north":{"texture":"#all"},"south":{"texture":"#all"},
                   "east":{"texture":"#all"},"west":{"texture":"#all"},
                   "up":{"texture":"#all"},"down":{"texture":"#all"}}}]}
                """);
        png(source.resolve("assets/demo/textures/item/axe_up.png"), Color.ORANGE);
        png(source.resolve("assets/demo/textures/item/generated_box.png"), Color.GREEN);
        List<CustomItemDescriptor> live = List.of(
                new CustomItemDescriptor("test", "minecraft:paper", Optional.of("demo:item/axe_up"),
                        OptionalInt.empty(), "Upright axe"),
                new CustomItemDescriptor("test", "minecraft:iron_axe", Optional.of("demo:item/generated_box"),
                        OptionalInt.empty(), "Generated box"));

        BuildResult result = new BedrockPackCompiler(root.resolve("model-parent-pose-data"), config()).build(
                List.of(new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)), live);
        JsonObject items = JsonParser.parseString(Files.readString(result.outputDirectory()
                .resolve("custom_mappings/geyser_item_mappings.json"))).getAsJsonObject().getAsJsonObject("items");
        JsonObject paper = items.getAsJsonArray("minecraft:paper").get(0).getAsJsonObject();
        JsonObject ironAxe = items.getAsJsonArray("minecraft:iron_axe").get(0).getAsJsonObject();
        assertTrue(paper.getAsJsonObject("bedrock_options").get("display_handheld").getAsBoolean());
        assertFalse(ironAxe.getAsJsonObject("bedrock_options").has("display_handheld"));

        String safe = paper.get("bedrock_identifier").getAsString().substring("twilight:".length());
        try (ZipFile zip = new ZipFile(result.outputDirectory().resolve("pack.zip").toFile())) {
            JsonObject animations = read(zip, "animations/" + safe + ".animation.json")
                    .getAsJsonObject("animations");
            JsonObject first = animations.getAsJsonObject("animation.twilight." + safe + ".first_person_right")
                    .getAsJsonObject("bones").getAsJsonObject("bone");
            JsonObject third = animations.getAsJsonObject("animation.twilight." + safe + ".third_person_right")
                    .getAsJsonObject("bones").getAsJsonObject("bone");
            assertVector(first.getAsJsonArray("position"), -7.0, 12.492, 2.6);
            assertVector(first.getAsJsonArray("scale"), 1.6, 1.6, 1.6);
            assertVector(third.getAsJsonArray("position"), 0.0, 13.5, -7.0);
            assertVector(third.getAsJsonArray("scale"), 2.4, 2.4, 2.4);
        }
    }

    @Test
    void givesChangedFlatWeaponsJavaTransformsAndRuntimeStates() throws Exception {
        Path source = root.resolve("native-flat-source");
        write(source, "assets/minecraft/models/item/bow.json", """
                {"overrides":[
                  {"predicate":{"custom_model_data":100},"model":"demo:item/astral_bow"},
                  {"predicate":{"custom_model_data":100,"pulling":1,"pull":0.0},"model":"demo:item/astral_bow_0"},
                  {"predicate":{"custom_model_data":100,"pulling":1,"pull":0.65},"model":"demo:item/astral_bow_1"},
                  {"predicate":{"custom_model_data":100,"pulling":1,"pull":0.9},"model":"demo:item/astral_bow_2"}]}
                """);
        for (String name : List.of("astral_bow", "astral_bow_0", "astral_bow_1", "astral_bow_2")) {
            write(source, "assets/demo/models/item/" + name + ".json",
                    "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"demo:item/" + name + "\"}}");
            png(source.resolve("assets/demo/textures/item/" + name + ".png"), Color.CYAN);
        }
        write(source, "assets/minecraft/models/item/fishing_rod.json", """
                {"overrides":[
                  {"predicate":{"custom_model_data":101},"model":"demo:item/astral_rod"},
                  {"predicate":{"custom_model_data":101,"cast":1},"model":"demo:item/astral_rod_cast"}]}
                """);
        for (String name : List.of("astral_rod", "astral_rod_cast")) {
            write(source, "assets/demo/models/item/" + name + ".json",
                    "{\"parent\":\"minecraft:item/handheld_rod\",\"textures\":{\"layer0\":\"demo:item/" + name + "\"}}");
            png(source.resolve("assets/demo/textures/item/" + name + ".png"), Color.BLUE);
        }

        BuildResult result = new BedrockPackCompiler(root.resolve("native-flat-data"), config()).build(List.of(
                new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)), List.of());

        assertEquals(3, result.converted());
        assertEquals(0, result.threeDimensional());
        try (ZipFile zip = new ZipFile(result.outputDirectory().resolve("pack.zip").toFile())) {
            assertEquals(6, zip.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("models/entity/")).count());
            assertEquals(3, zip.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("attachables/")).count());
            String bowAttachable = zip.stream().map(ZipEntry::getName).filter(name -> name.startsWith("attachables/"))
                    .filter(name -> {
                        try { return read(zip, name).toString().contains("twilight_model_index"); }
                        catch (Exception failure) { throw new RuntimeException(failure); }
                    }).findFirst().orElseThrow();
            JsonObject description = read(zip, bowAttachable).getAsJsonObject("minecraft:attachable")
                    .getAsJsonObject("description");
            assertTrue(description.getAsJsonObject("geometry").get("state_3").getAsString()
                    .startsWith("geometry.twilight."));
            assertTrue(description.getAsJsonArray("render_controllers").get(0).getAsString()
                    .startsWith("controller.render.twilight."));
        }
        JsonObject mappings = JsonParser.parseString(Files.readString(result.outputDirectory()
                .resolve("custom_mappings/geyser_item_mappings.json"))).getAsJsonObject().getAsJsonObject("items");
        JsonArray bow = mappings.getAsJsonArray("minecraft:bow");
        assertEquals(1, bow.size());
        assertFalse(bow.get(0).getAsJsonObject().getAsJsonObject("bedrock_options").has("display_handheld"));
        JsonArray rods = mappings.getAsJsonArray("minecraft:fishing_rod");
        assertEquals(2, rods.size());
        assertTrue(rods.asList().stream().map(JsonElement::getAsJsonObject)
                .allMatch(mapping -> mapping.getAsJsonObject("bedrock_options")
                        .get("display_handheld").getAsBoolean()));
        assertTrue(rods.asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(mapping -> mapping.has("predicate") && mapping.get("predicate").toString().contains("fishing_rod_cast")));
    }

    @Test
    void keepsOnlyExactSilhouetteBowRecoloursOnVanillaBedrockGeometry() throws Exception {
        Path source = root.resolve("verified-native-bow-source");
        String bowDisplay = """
                "display":{
                  "thirdperson_righthand":{"rotation":[-80,260,-40],"translation":[-1,-2,2.5],"scale":[0.9,0.9,0.9]},
                  "thirdperson_lefthand":{"rotation":[-80,-280,40],"translation":[-1,-2,2.5],"scale":[0.9,0.9,0.9]},
                  "firstperson_righthand":{"rotation":[0,-90,25],"translation":[1.13,3.2,1.13],"scale":[0.68,0.68,0.68]},
                  "firstperson_lefthand":{"rotation":[0,90,-25],"translation":[1.13,3.2,1.13],"scale":[0.68,0.68,0.68]}}
                """;
        write(source, "assets/minecraft/models/item/bow.json", """
                {"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:item/bow"},%s,
                 "overrides":[
                  {"predicate":{"custom_model_data":400},"model":"demo:item/recolour"},
                  {"predicate":{"custom_model_data":400,"pulling":1,"pull":0.0},"model":"demo:item/recolour_0"},
                  {"predicate":{"custom_model_data":400,"pulling":1,"pull":0.65},"model":"demo:item/recolour_1"},
                  {"predicate":{"custom_model_data":400,"pulling":1,"pull":0.9},"model":"demo:item/recolour_2"}]}
                """.formatted(bowDisplay));
        for (String name : List.of("recolour", "recolour_0", "recolour_1", "recolour_2")) {
            write(source, "assets/demo/models/item/" + name + ".json",
                    "{\"parent\":\"minecraft:item/bow\",\"textures\":{\"layer0\":\"demo:item/" + name + "\"}}");
            png(source.resolve("assets/demo/textures/item/" + name + ".png"), Color.MAGENTA);
        }
        write(source, "assets/minecraft/models/item/fishing_rod.json", """
                {"overrides":[
                  {"predicate":{"custom_model_data":401},"model":"demo:item/recolour_rod"},
                  {"predicate":{"custom_model_data":401,"cast":1},"model":"demo:item/recolour_rod_cast"}]}
                """);
        for (String name : List.of("recolour_rod", "recolour_rod_cast")) {
            write(source, "assets/demo/models/item/" + name + ".json",
                    "{\"parent\":\"minecraft:item/handheld_rod\",\"textures\":{\"layer0\":\"demo:item/" + name + "\"}}");
            png(source.resolve("assets/demo/textures/item/" + name + ".png"), Color.BLUE);
        }
        String crossbowDisplay = """
                "display":{
                  "thirdperson_righthand":{"rotation":[-90,0,-60],"translation":[2,0.1,-3],"scale":[0.9,0.9,0.9]},
                  "thirdperson_lefthand":{"rotation":[-90,0,30],"translation":[2,0.1,-3],"scale":[0.9,0.9,0.9]},
                  "firstperson_righthand":{"rotation":[-90,0,-55],"translation":[1.13,3.2,1.13],"scale":[0.68,0.68,0.68]},
                  "firstperson_lefthand":{"rotation":[-90,0,35],"translation":[1.13,3.2,1.13],"scale":[0.68,0.68,0.68]}}
                """;
        write(source, "assets/minecraft/models/item/crossbow.json", """
                {"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:item/crossbow_standby"},%s,
                 "overrides":[
                  {"predicate":{"custom_model_data":402},"model":"demo:item/crossbow"},
                  {"predicate":{"custom_model_data":402,"pulling":1,"pull":0.0},"model":"demo:item/crossbow_pulling_0"},
                  {"predicate":{"custom_model_data":402,"pulling":1,"pull":0.58},"model":"demo:item/crossbow_pulling_1"},
                  {"predicate":{"custom_model_data":402,"pulling":1,"pull":1.0},"model":"demo:item/crossbow_pulling_2"},
                  {"predicate":{"custom_model_data":402,"charged":1},"model":"demo:item/crossbow_arrow"},
                  {"predicate":{"custom_model_data":402,"charged":1,"firework":1},"model":"demo:item/crossbow_firework"}]}
                """.formatted(crossbowDisplay));
        for (String name : List.of("crossbow", "crossbow_pulling_0", "crossbow_pulling_1",
                "crossbow_pulling_2", "crossbow_arrow", "crossbow_firework")) {
            write(source, "assets/demo/models/item/" + name + ".json",
                    "{\"parent\":\"minecraft:item/crossbow\",\"textures\":{\"layer0\":\"demo:item/" + name + "\"}}");
            png(source.resolve("assets/demo/textures/item/" + name + ".png"), Color.GREEN);
        }

        Map<String, byte[]> vanilla = new LinkedHashMap<>();
        vanilla.put("assets/minecraft/models/item/bow.json", ("{\"parent\":\"minecraft:item/generated\"," +
                "\"textures\":{\"layer0\":\"minecraft:item/bow\"}," + bowDisplay + '}').getBytes(StandardCharsets.UTF_8));
        String[] vanillaNames = {"bow", "bow_pulling_0", "bow_pulling_1", "bow_pulling_2"};
        for (int index = 0; index < vanillaNames.length; index++) {
            String name = vanillaNames[index];
            if (index > 0) vanilla.put("assets/minecraft/models/item/" + name + ".json",
                    ("{\"parent\":\"minecraft:item/bow\",\"textures\":{\"layer0\":\"minecraft:item/" + name + "\"}}").getBytes(StandardCharsets.UTF_8));
            vanilla.put("assets/minecraft/textures/item/" + name + ".png", pngBytes(Color.WHITE));
        }
        for (String name : List.of("fishing_rod", "fishing_rod_cast")) {
            vanilla.put("assets/minecraft/models/item/" + name + ".json",
                    ("{\"parent\":\"minecraft:item/handheld_rod\",\"textures\":{\"layer0\":\"minecraft:item/" + name + "\"}}").getBytes(StandardCharsets.UTF_8));
            vanilla.put("assets/minecraft/textures/item/" + name + ".png", pngBytes(Color.WHITE));
        }
        vanilla.put("assets/minecraft/models/item/crossbow.json", ("{\"parent\":\"minecraft:item/generated\"," +
                "\"textures\":{\"layer0\":\"minecraft:item/crossbow_standby\"}," + crossbowDisplay + '}').getBytes(StandardCharsets.UTF_8));
        for (String name : List.of("crossbow_pulling_0", "crossbow_pulling_1", "crossbow_pulling_2",
                "crossbow_arrow", "crossbow_firework")) {
            vanilla.put("assets/minecraft/models/item/" + name + ".json",
                    ("{\"parent\":\"minecraft:item/crossbow\",\"textures\":{\"layer0\":\"minecraft:item/" + name + "\"}}").getBytes(StandardCharsets.UTF_8));
        }
        for (String name : List.of("crossbow_standby", "crossbow_pulling_0", "crossbow_pulling_1",
                "crossbow_pulling_2", "crossbow_arrow", "crossbow_firework")) {
            vanilla.put("assets/minecraft/textures/item/" + name + ".png", pngBytes(Color.WHITE));
        }
        Path data = root.resolve("verified-native-bow-data");
        seedClientCache(data, vanilla);

        BuildResult recolour = new BedrockPackCompiler(data, config(), "26.2").build(List.of(
                new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)), List.of());
        assertEquals(6, recolour.converted());
        try (ZipFile zip = new ZipFile(recolour.outputDirectory().resolve("pack.zip").toFile())) {
            assertEquals(0, zip.stream().filter(entry -> entry.getName().startsWith("models/entity/")).count());
            assertEquals(4, zip.stream().filter(entry -> entry.getName().startsWith("attachables/")).count());
            String attachable = zip.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("attachables/"))
                    .filter(name -> {
                        try { return read(zip, name).toString().contains("geometry.bow_pulling_2"); }
                        catch (Exception failure) { throw new RuntimeException(failure); }
                    }).findFirst().orElseThrow();
            assertTrue(read(zip, attachable).toString().contains("geometry.bow_pulling_2"));
            assertEquals(3, zip.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("attachables/"))
                    .filter(name -> {
                        try { return read(zip, name).toString().contains("geometry.crossbow_arrow"); }
                        catch (Exception failure) { throw new RuntimeException(failure); }
                    }).count());
        }

        Path animationMetadata = source.resolve("assets/demo/textures/item/recolour_0.png.mcmeta");
        write(source, "assets/demo/textures/item/recolour_0.png.mcmeta", "{\"animation\":{\"frametime\":2}}");
        Path animatedData = root.resolve("animated-native-bow-data");
        seedClientCache(animatedData, vanilla);
        BuildResult animated = new BedrockPackCompiler(animatedData, config(), "26.2").build(List.of(
                new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)), List.of());
        try (ZipFile zip = new ZipFile(animated.outputDirectory().resolve("pack.zip").toFile())) {
            assertEquals(4, zip.stream().filter(entry -> entry.getName().startsWith("models/entity/")).count());
        }
        Files.delete(animationMetadata);

        BufferedImage changed = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) changed.setRGB(x, y, Color.MAGENTA.getRGB());
        changed.setRGB(0, 0, 0);
        ImageIO.write(changed, "PNG", source.resolve("assets/demo/textures/item/recolour_1.png").toFile());
        Path changedData = root.resolve("changed-native-bow-data");
        seedClientCache(changedData, vanilla);
        BuildResult changedShape = new BedrockPackCompiler(changedData, config(), "26.2").build(List.of(
                new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)), List.of());
        try (ZipFile zip = new ZipFile(changedShape.outputDirectory().resolve("pack.zip").toFile())) {
            assertEquals(4, zip.stream().filter(entry -> entry.getName().startsWith("models/entity/")).count());
        }
    }

    @Test
    void keepsVolumetricBowPullStagesAsRuntimeJavaGeometryAndTransforms() throws Exception {
        Path source = root.resolve("dynamic-volumetric-source");
        write(source, "assets/minecraft/models/item/bow.json", """
                {"overrides":[
                  {"predicate":{"custom_model_data":200},"model":"demo:item/extraordinarily_long_volumetric_weapon_bow"},
                  {"predicate":{"custom_model_data":200,"pulling":1,"pull":0.0},"model":"demo:item/extraordinarily_long_volumetric_weapon_bow_0"},
                  {"predicate":{"custom_model_data":200,"pulling":1,"pull":0.65},"model":"demo:item/extraordinarily_long_volumetric_weapon_bow_1"},
                  {"predicate":{"custom_model_data":200,"pulling":1,"pull":0.9},"model":"demo:item/extraordinarily_long_volumetric_weapon_bow_2"}]}
                """);
        for (String name : List.of("extraordinarily_long_volumetric_weapon_bow",
                "extraordinarily_long_volumetric_weapon_bow_0",
                "extraordinarily_long_volumetric_weapon_bow_1",
                "extraordinarily_long_volumetric_weapon_bow_2")) {
            write(source, "assets/demo/models/item/" + name + ".json", cube("demo:item/" + name));
            png(source.resolve("assets/demo/textures/item/" + name + ".png"), Color.ORANGE);
        }

        BuildResult result = new BedrockPackCompiler(root.resolve("dynamic-volumetric-data"), config()).build(List.of(
                new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)), List.of());

        assertEquals(1, result.converted());
        assertEquals(1, result.threeDimensional());
        try (ZipFile zip = new ZipFile(result.outputDirectory().resolve("pack.zip").toFile())) {
            assertEquals(4, zip.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("models/entity/") && name.endsWith(".geo.json")).count());
            assertEquals(4, zip.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("animations/") && name.endsWith(".animation.json")).count());
            assertTrue(zip.stream().map(ZipEntry::getName).allMatch(name -> name.length() < 80),
                    "Bedrock pack paths must stay below Geyser's cross-platform portability limit");
            String controllerPath = zip.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("render_controllers/")).findFirst().orElseThrow();
            JsonObject controller = read(zip, controllerPath).getAsJsonObject("render_controllers")
                    .entrySet().iterator().next().getValue().getAsJsonObject();
            assertEquals("Array.twilight_geometries[v.twilight_model_index]",
                    controller.get("geometry").getAsString());
            String attachablePath = zip.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("attachables/")).findFirst().orElseThrow();
            JsonObject scripts = read(zip, attachablePath).getAsJsonObject("minecraft:attachable")
                    .getAsJsonObject("description").getAsJsonObject("scripts");
            assertTrue(scripts.getAsJsonArray("pre_animation").get(0).getAsString().contains("twilight_model_index"));
            assertTrue(scripts.getAsJsonArray("animate").size() >= 20);
        }
    }

    @Test
    void mapsLegacyCrossbowArrowAndRocketChargeTypesWithoutFlatteningThemTogether() throws Exception {
        Path source = root.resolve("crossbow-charge-source");
        write(source, "assets/minecraft/models/item/crossbow.json", """
                {"overrides":[
                  {"predicate":{"custom_model_data":300},"model":"demo:item/crossbow"},
                  {"predicate":{"custom_model_data":300,"pulling":1,"pull":0.0},"model":"demo:item/crossbow_0"},
                  {"predicate":{"custom_model_data":300,"pulling":1,"pull":0.58},"model":"demo:item/crossbow_1"},
                  {"predicate":{"custom_model_data":300,"pulling":1,"pull":1.0},"model":"demo:item/crossbow_2"},
                  {"predicate":{"custom_model_data":300,"charged":1},"model":"demo:item/crossbow_arrow"},
                  {"predicate":{"custom_model_data":300,"charged":1,"firework":1},"model":"demo:item/crossbow_rocket"}]}
                """);
        for (String name : List.of("crossbow", "crossbow_0", "crossbow_1", "crossbow_2", "crossbow_arrow", "crossbow_rocket")) {
            write(source, "assets/demo/models/item/" + name + ".json",
                    "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"demo:item/" + name + "\"}}");
            png(source.resolve("assets/demo/textures/item/" + name + ".png"), Color.GRAY);
        }

        BuildResult result = new BedrockPackCompiler(root.resolve("crossbow-charge-data"), config()).build(List.of(
                new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)), List.of());
        JsonArray mappings = JsonParser.parseString(Files.readString(result.outputDirectory()
                .resolve("custom_mappings/geyser_item_mappings.json"))).getAsJsonObject()
                .getAsJsonObject("items").getAsJsonArray("minecraft:crossbow");

        assertEquals(3, mappings.size());
        assertTrue(mappings.toString().contains("\"value\":\"arrow\""));
        assertTrue(mappings.toString().contains("\"value\":\"rocket\""));
        assertTrue(mappings.asList().stream().map(JsonElement::getAsJsonObject)
                .noneMatch(mapping -> mapping.getAsJsonObject("bedrock_options").has("display_handheld")));
    }

    @Test
    void strictFailurePreservesLastKnownGoodBuild() throws Exception {
        Path source = root.resolve("strict-source");
        write(source, "assets/minecraft/models/item/stick.json", """
                {"parent":"minecraft:item/handheld","overrides":[
                  {"predicate":{"custom_model_data":1},"model":"demo:item/good"}
                ]}
                """);
        write(source, "assets/demo/models/item/good.json", """
                {"parent":"minecraft:item/generated","textures":{"layer0":"demo:item/good"}}
                """);
        png(source.resolve("assets/demo/textures/item/good.png"), Color.GREEN);
        ContentSource pack = new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1);
        Path data = root.resolve("strict-data");
        BedrockPackCompiler compiler = new BedrockPackCompiler(data, config());
        compiler.build(List.of(pack), List.of());
        Path current = data.resolve("build/current/pack.zip");
        byte[] knownGood = Files.readAllBytes(current);

        write(source, "assets/minecraft/models/item/stick.json", """
                {"parent":"minecraft:item/handheld","overrides":[
                  {"predicate":{"custom_model_data":1},"model":"demo:item/good"},
                  {"predicate":{"custom_model_data":2},"model":"demo:item/missing"}
                ]}
                """);
        ConversionException failure = assertThrows(ConversionException.class,
                () -> compiler.build(List.of(pack), List.of()));
        assertEquals(1, failure.problems().size());
        assertArrayEquals(knownGood, Files.readAllBytes(current));
    }

    @Test
    void convertsDefaultBitmapGlyphsIntoBedrockUnicodePages() throws Exception {
        Path source = root.resolve("font-source");
        write(source, "assets/minecraft/font/default.json", """
                {"providers":[{"type":"bitmap","file":"demo:font/icons.png",
                  "height":9,"ascent":8,"chars":[""]}]}
                """);
        Path texture = source.resolve("assets/demo/textures/font/icons.png");
        Files.createDirectories(texture.getParent());
        BufferedImage icons = new BufferedImage(18, 9, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 9; y++) for (int x = 0; x < 18; x++) {
            icons.setRGB(x, y, (x < 9 ? Color.MAGENTA : Color.CYAN).getRGB());
        }
        ImageIO.write(icons, "PNG", texture.toFile());

        ContentSource pack = new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1);
        BuildResult result = new BedrockPackCompiler(root.resolve("font-data"), config())
                .build(List.of(pack), List.of());

        assertEquals(2, result.glyphs());
        assertEquals(1, result.fontPages());
        try (ZipFile zip = new ZipFile(result.outputDirectory().resolve("pack.zip").toFile())) {
            var entry = zip.getEntry("font/glyph_E0.png");
            assertNotNull(entry);
            BufferedImage page = ImageIO.read(zip.getInputStream(entry));
            assertEquals(256, page.getWidth());
            assertEquals(256, page.getHeight());
            assertEquals(0, page.getRGB(0, 0));
            assertEquals(Color.MAGENTA.getRGB(), page.getRGB(3, 7));
            assertEquals(Color.CYAN.getRGB(), page.getRGB(19, 7));
        }
    }

    @Test
    void keepsChatEmojiHeightStableWhenTheSamePageContainsAnOversizedGuiGlyph() throws Exception {
        Path source = root.resolve("mixed-font-height-source");
        String emoji = Character.toString(0xE000);
        String gui = Character.toString(0xE00F);
        write(source, "assets/minecraft/font/default.json", """
                {"providers":[
                  {"type":"bitmap","file":"demo:font/emoji.png","height":9,"ascent":8,"chars":["%s"]},
                  {"type":"bitmap","file":"demo:font/gui.png","height":256,"ascent":255,"chars":["%s"]}
                ]}
                """.formatted(emoji, gui));
        Path emojiTexture = source.resolve("assets/demo/textures/font/emoji.png");
        Files.createDirectories(emojiTexture.getParent());
        BufferedImage emojiImage = new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 9; y++) for (int x = 0; x < 9; x++) {
            emojiImage.setRGB(x, y, Color.MAGENTA.getRGB());
        }
        ImageIO.write(emojiImage, "PNG", emojiTexture.toFile());
        BufferedImage guiImage = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            guiImage.setRGB(x, y, Color.CYAN.getRGB());
        }
        ImageIO.write(guiImage, "PNG", source.resolve("assets/demo/textures/font/gui.png").toFile());

        BuildResult result = new BedrockPackCompiler(root.resolve("mixed-font-height-data"), config()).build(
                List.of(new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)), List.of());

        try (ZipFile zip = new ZipFile(result.outputDirectory().resolve("pack.zip").toFile())) {
            BufferedImage page = ImageIO.read(zip.getInputStream(zip.getEntry("font/glyph_E0.png")));
            assertEquals(256, page.getWidth());
            assertEquals(0, page.getRGB(3, 6));
            assertEquals(Color.MAGENTA.getRGB(), page.getRGB(3, 7));
            assertEquals(Color.MAGENTA.getRGB(), page.getRGB(3, 15));
            assertEquals(Color.CYAN.getRGB(), page.getRGB(240, 0));
            assertEquals(Color.CYAN.getRGB(), page.getRGB(255, 15));
        }
    }

    @Test
    void fallsBackToAValidLowerPackLayerWhenGeneratedFontTextureIsUnreadable() throws Exception {
        Path lower = root.resolve("font-lower");
        Path upper = root.resolve("font-upper");
        png(lower.resolve("assets/demo/textures/font/icon.png"), Color.GREEN);
        write(upper, "assets/minecraft/font/default.json", """
                {"providers":[{"type":"bitmap","file":"demo:font/icon.png",
                  "height":8,"ascent":7,"chars":[""]}]}
                """);
        Path unreadable = upper.resolve("assets/demo/textures/font/icon.png");
        Files.createDirectories(unreadable.getParent());
        Files.write(unreadable, new byte[]{1, 2, 3, 4});

        BuildResult result = new BedrockPackCompiler(root.resolve("font-fallback-data"), config()).build(List.of(
                new ContentSource("lower", ContentSource.Kind.RESOURCE_PACK, lower, 1),
                new ContentSource("upper", ContentSource.Kind.RESOURCE_PACK, upper, 2)
        ), List.of());

        assertEquals(1, result.glyphs());
        assertTrue(result.problems().isEmpty());
    }

    @Test
    void resolvesExplicitVanillaFontReferencesFromVerifiedLocalClientCache() throws Exception {
        Path source = root.resolve("vanilla-font-source");
        write(source, "assets/minecraft/font/default.json", """
                {"providers":[{"type":"bitmap","file":"minecraft:item/yellow_dye.png",
                  "height":8,"ascent":7,"chars":[""]}]}
                """);
        Path data = root.resolve("vanilla-font-data");
        Path cache = data.resolve("cache/vanilla/26.2");
        Files.createDirectories(cache);
        Path client = cache.resolve("client.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(client))) {
            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/item/yellow_dye.png"));
            zip.write(pngBytes(Color.YELLOW));
            zip.closeEntry();
        }
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(Files.readAllBytes(client)));
        Files.writeString(cache.resolve("client.sha1"), checksum);

        BuildResult result = new BedrockPackCompiler(data, config(), "26.2").build(List.of(
                new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)
        ), List.of());

        assertEquals(1, result.glyphs());
        assertEquals(1, result.vanillaFallbackTextures());
        assertTrue(result.problems().isEmpty());
    }

    @Test
    void compilesGloballySafePrivateUseGlyphsFromNamedFonts() throws Exception {
        Path source = root.resolve("named-font-source");
        write(source, "assets/demo/font/menu.json", """
                {"providers":[{"type":"bitmap","file":"demo:font/menu.png",
                  "height":8,"ascent":7,"chars":["\uE120"]}]}
                """);
        png(source.resolve("assets/demo/textures/font/menu.png"), Color.BLUE);

        BuildResult result = new BedrockPackCompiler(root.resolve("named-font-data"), config()).build(List.of(
                new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)
        ), List.of());

        assertEquals(1, result.namedFonts());
        assertEquals(1, result.namedGlyphs());
        assertEquals(1, result.glyphs());
        assertTrue(result.problems().isEmpty());
        try (ZipFile zip = new ZipFile(result.outputDirectory().resolve("pack.zip").toFile())) {
            assertNotNull(zip.getEntry("font/glyph_E1.png"));
        }
    }

    @Test
    void rejectsNamedFontCodepointCollisionsInsteadOfPublishingWrongMenus() throws Exception {
        Path source = root.resolve("named-font-conflict");
        write(source, "assets/demo/font/a.json", """
                {"providers":[{"type":"bitmap","file":"demo:font/a.png",
                  "height":8,"ascent":7,"chars":["\uE121"]}]}
                """);
        write(source, "assets/demo/font/b.json", """
                {"providers":[{"type":"bitmap","file":"demo:font/b.png",
                  "height":8,"ascent":7,"chars":["\uE121"]}]}
                """);
        png(source.resolve("assets/demo/textures/font/a.png"), Color.RED);
        png(source.resolve("assets/demo/textures/font/b.png"), Color.GREEN);

        ConversionException failure = assertThrows(ConversionException.class,
                () -> new BedrockPackCompiler(root.resolve("named-font-conflict-data"), config()).build(List.of(
                        new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)
                ), List.of()));

        assertTrue(failure.problems().stream().anyMatch(problem ->
                problem.contains("conflict on Bedrock's global codepoint map")));
    }

    @Test
    void protectsVanillaFontCellsWhenVanillaOverrideIsDisabled() throws Exception {
        Path source = root.resolve("vanilla-font-override");
        write(source, "assets/minecraft/font/default.json", """
                {"providers":[{"type":"bitmap","file":"demo:font/letters.png",
                  "height":8,"ascent":7,"chars":["A"]}]}
                """);
        png(source.resolve("assets/demo/textures/font/letters.png"), Color.RED);

        ConversionException failure = assertThrows(ConversionException.class,
                () -> new BedrockPackCompiler(root.resolve("vanilla-font-override-data"), config()).build(List.of(
                        new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)
                ), List.of()));

        assertTrue(failure.problems().stream().anyMatch(problem ->
                problem.contains("default font glyphs require vanilla-override")));
    }

    @Test
    void convertsLayeredAndReferencedJavaSoundsToBedrockDefinitions() throws Exception {
        Path lower = root.resolve("sound-lower");
        Path upper = root.resolve("sound-upper");
        write(lower, "assets/demo/sounds.json", """
                {"bell":{"sounds":["bell/one"]}}
                """);
        write(upper, "assets/demo/sounds.json", """
                {"bell":{"sounds":[{"name":"demo:bell/two","volume":0.5,"pitch":1.2,
                                      "weight":3,"stream":true,"attenuation_distance":24}]},
                 "combo":{"sounds":[{"name":"demo:bell","type":"event","volume":0.5}]}}
                """);
        ogg(lower.resolve("assets/minecraft/sounds/bell/one.ogg"), 1);
        ogg(upper.resolve("assets/demo/sounds/bell/two.ogg"), 2);

        BuildResult result = new BedrockPackCompiler(root.resolve("sound-data"), config()).build(List.of(
                new ContentSource("lower", ContentSource.Kind.RESOURCE_PACK, lower, 1),
                new ContentSource("upper", ContentSource.Kind.RESOURCE_PACK, upper, 2)
        ), List.of());

        assertEquals(2, result.soundDefinitions());
        assertEquals(2, result.soundFiles());
        assertTrue(result.problems().isEmpty());
        try (ZipFile zip = new ZipFile(result.outputDirectory().resolve("pack.zip").toFile())) {
            assertNotNull(zip.getEntry("sounds/minecraft/bell/one.ogg"));
            assertNotNull(zip.getEntry("sounds/demo/bell/two.ogg"));
            JsonObject definitions = read(zip, "sounds/sound_definitions.json").getAsJsonObject("sound_definitions");
            assertEquals(2, definitions.getAsJsonObject("demo:bell").getAsJsonArray("sounds").size());
            JsonObject combo = definitions.getAsJsonObject("demo:combo").getAsJsonArray("sounds")
                    .get(0).getAsJsonObject();
            assertEquals(0.5, combo.get("volume").getAsDouble());
        }
    }

    @Test
    void rejectsMissingCustomSoundFilesInStrictMode() throws Exception {
        Path source = root.resolve("sound-missing");
        write(source, "assets/demo/sounds.json", """
                {"missing":{"sounds":["demo:not/present"]}}
                """);

        ConversionException failure = assertThrows(ConversionException.class,
                () -> new BedrockPackCompiler(root.resolve("sound-missing-data"), config()).build(List.of(
                        new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)
                ), List.of()));
        assertTrue(failure.problems().stream().anyMatch(problem -> problem.contains("references missing sound")));
    }

    @Test
    void skipsVerifiedVanillaSoundsButAllowsNewMinecraftNamespaceEvents() throws Exception {
        Path source = root.resolve("sound-vanilla-registry");
        String sharedDefinition = "{\"sounds\":[\"minecraft:existing\"]}";
        write(source, "assets/minecraft/sounds.json", """
                {"vanilla.same":%s,"custom.new":{"sounds":["minecraft:custom/new"]}}
                """.formatted(sharedDefinition));

        Path data = root.resolve("sound-vanilla-registry-data");
        Path cache = data.resolve("cache/vanilla/26.2");
        Files.createDirectories(cache);
        byte[] registry = ("{\"vanilla.same\":" + sharedDefinition + '}').getBytes(StandardCharsets.UTF_8);
        Path sounds = cache.resolve("sounds.json");
        Files.write(sounds, registry);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(registry));
        Files.writeString(cache.resolve("sounds.sha1"), checksum);
        byte[] soundBytes = new byte[]{'O', 'g', 'g', 'S', 0, 2, 0, 3};
        String soundHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(soundBytes));
        byte[] indexBytes = ("{\"objects\":{\"minecraft/sounds/custom/new.ogg\":{\"hash\":\"" + soundHash +
                "\",\"size\":" + soundBytes.length + "}}}").getBytes(StandardCharsets.UTF_8);
        Files.write(cache.resolve("asset-index.json"), indexBytes);
        Files.writeString(cache.resolve("asset-index.sha1"), HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(indexBytes)));
        Path cachedSound = cache.resolve("objects").resolve(soundHash.substring(0, 2)).resolve(soundHash);
        Files.createDirectories(cachedSound.getParent());
        Files.write(cachedSound, soundBytes);

        BuildResult result = new BedrockPackCompiler(data, config(), "26.2").build(List.of(
                new ContentSource("test", ContentSource.Kind.RESOURCE_PACK, source, 1)
        ), List.of());

        assertEquals(1, result.soundDefinitions());
        assertEquals(1, result.soundFiles());
        assertEquals(1, result.vanillaFallbackSounds());
        assertTrue(result.problems().isEmpty());
        try (ZipFile zip = new ZipFile(result.outputDirectory().resolve("pack.zip").toFile())) {
            JsonObject definitions = read(zip, "sounds/sound_definitions.json").getAsJsonObject("sound_definitions");
            assertTrue(definitions.has("minecraft:custom.new"));
            assertFalse(definitions.has("minecraft:vanilla.same"));
        }
    }

    private static String cube(String texture) {
        return """
                {"textures":{"all":"%s"},"display":{"firstperson_righthand":{"rotation":[0,45,0]}},
                 "elements":[{"from":[2,1,3],"to":[14,15,13],"faces":{
                   "north":{"texture":"#all","uv":[0,0,16,16]},"south":{"texture":"#all"},
                   "east":{"texture":"#all"},"west":{"texture":"#all"},
                   "up":{"texture":"#all"},"down":{"texture":"#all"}}}]}
                """.formatted(texture);
    }

    private static void seedClientCache(Path data, Map<String, byte[]> entries) throws Exception {
        Path cache = data.resolve("cache/vanilla/26.2");
        Files.createDirectories(cache);
        Path client = cache.resolve("client.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(client))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        Files.writeString(cache.resolve("client.sha1"), HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(client))));
    }

    private static void write(Path root, String relative, String text) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static void png(Path path, Color color) throws Exception {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, color.getRGB());
        ImageIO.write(image, "PNG", path.toFile());
    }

    private static byte[] pngBytes(Color color) throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, color.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }

    private static void ogg(Path path, int marker) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[]{'O', 'g', 'g', 'S', 0, 2, 0, (byte) marker});
    }

    private static JsonObject read(ZipFile zip, String name) throws Exception {
        try (var reader = new InputStreamReader(zip.getInputStream(zip.getEntry(name)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void assertVector(JsonArray actual, double x, double y, double z) {
        assertEquals(x, actual.get(0).getAsDouble(), 1.0e-9);
        assertEquals(y, actual.get(1).getAsDouble(), 1.0e-9);
        assertEquals(z, actual.get(2).getAsDouble(), 1.0e-9);
    }

    private TwilightConfig config() {
        return new TwilightConfig(false, true, false, true, 40, 100, 10_000_000, 10_000,
                true, true, List.of(), "auto", false, false, 3);
    }
}
