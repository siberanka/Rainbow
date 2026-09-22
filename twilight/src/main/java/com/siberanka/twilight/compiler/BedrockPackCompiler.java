/*
 * Copyright (c) 2026 siberanka.
 * Licensed under the GNU LGPL v3.0 or later.
 */
package com.siberanka.twilight.compiler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.siberanka.twilight.config.TwilightConfig;
import com.siberanka.twilight.source.ContentSource;
import com.siberanka.twilight.source.CustomItemDescriptor;
import com.siberanka.twilight.source.ResourceIndex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BedrockPackCompiler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long ZIP_TIME = 315_532_800_000L;
    private static final int MAX_PACK_VERSION_COMPONENT = 65_535;
    private static final int MAX_BEDROCK_SAFE_IDENTIFIER_LENGTH = 36;
    private final Path dataDirectory;
    private final TwilightConfig config;
    private final String minecraftVersion;

    public BedrockPackCompiler(Path dataDirectory, TwilightConfig config) {
        this(dataDirectory, config, null);
    }

    public BedrockPackCompiler(Path dataDirectory, TwilightConfig config, String minecraftVersion) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.config = config;
        this.minecraftVersion = minecraftVersion;
    }

    public BuildResult build(List<ContentSource> sources, List<CustomItemDescriptor> liveItems) throws IOException {
        try (ResourceIndex resources = ResourceIndex.build(sources, config);
             VanillaAssetCache vanillaAssets = minecraftVersion == null ? null : new VanillaAssetCache(
                     dataDirectory, minecraftVersion, config.downloadVanillaAssets())) {
            return build(resources, liveItems, vanillaAssets);
        }
    }

    private BuildResult build(ResourceIndex resources, List<CustomItemDescriptor> liveItems,
                              VanillaAssetCache vanillaAssets) throws IOException {
        List<ItemCandidate> candidates = new ItemCandidateCollector(resources).collect(liveItems);
        JavaModelResolver resolver = new JavaModelResolver(resources, vanillaAssets);
        VanillaItemReference vanillaItems = new VanillaItemReference(resources, vanillaAssets);
        Map<String, byte[]> packFiles = new LinkedHashMap<>();
        JsonObject textureData = new JsonObject();
        JsonObject mappedItems = new JsonObject();
        List<String> problems = new ArrayList<>();
        int converted = 0, threeDimensional = 0;

        for (ItemCandidate candidate : candidates) {
            try {
                ResolvedJavaModel model = resolver.resolveAll(candidate.visualModels());
                String identifier = bedrockIdentifier(candidate);
                String safe = identifier.substring(identifier.indexOf(':') + 1);
                String texturePath = "textures/twilight/" + safe;
                String iconKey = "twilight." + safe;
                List<String> layers = flatLayers(model);
                NativeWeapon nativeWeapon = nativeWeapon(candidate, model, resolver, resources, vanillaItems);
                DynamicWeapon dynamicWeapon = nativeWeapon == null ? dynamicWeapon(candidate, resolver) : null;
                boolean customFlat = !model.isThreeDimensional() && nativeWeapon == null &&
                        requiresCustomFlat(candidate, model, vanillaItems);
                byte[] icon;
                if (dynamicWeapon != null) {
                    Set<String> stateTextures = new LinkedHashSet<>();
                    for (ResolvedJavaModel state : dynamicWeapon.states()) stateTextures.addAll(usedTextures(state));
                    TextureSet atlas = TextureSet.atlas(resources, List.copyOf(stateTextures));
                    packFiles.put(texturePath + ".png", atlas.png());
                    icon = TextureSet.png(TextureSet.layeredIcon(resources,
                            layers.isEmpty() ? List.of(usedTextures(model).iterator().next()) : layers));
                    packFiles.put("textures/twilight/" + safe + "_icon.png", icon);
                    for (int state = 0; state < dynamicWeapon.states().size(); state++) {
                        String stateSafe = safe + "_state_" + state;
                        ResolvedJavaModel stateModel = dynamicWeapon.states().get(state);
                        GeometryBounds bounds = geometryBounds(stateModel);
                        packFiles.put("models/entity/geometry." + stateSafe + ".geo.json",
                                jsonBytes(geometry(stateSafe, stateModel, atlas, bounds)));
                        packFiles.put("animations/" + stateSafe + ".animation.json",
                                jsonBytes(animations(stateSafe, stateModel.display())));
                    }
                    packFiles.put("render_controllers/" + safe + ".render_controllers.json",
                            jsonBytes(dynamicRenderController(safe, dynamicWeapon.states().size())));
                    packFiles.put("attachables/" + safe + ".json",
                            jsonBytes(dynamicWeaponAttachable(identifier, safe, texturePath, dynamicWeapon.type(),
                                    dynamicWeapon.states().size())));
                    if (dynamicWeapon.states().stream().anyMatch(ResolvedJavaModel::isThreeDimensional)) threeDimensional++;
                } else if (model.isThreeDimensional() || customFlat) {
                    Set<String> usedTextures = usedTextures(model);
                    TextureSet atlas = TextureSet.atlas(resources, List.copyOf(usedTextures));
                    GeometryBounds bounds = geometryBounds(model);
                    packFiles.put(texturePath + ".png", atlas.png());
                    icon = TextureSet.png(TextureSet.layeredIcon(resources,
                            layers.isEmpty() ? List.of(usedTextures.iterator().next()) : layers));
                    packFiles.put("textures/twilight/" + safe + "_icon.png", icon);
                    packFiles.put("models/entity/geometry." + safe + ".geo.json",
                            jsonBytes(geometry(safe, model, atlas, bounds)));
                    packFiles.put("animations/" + safe + ".animation.json",
                            jsonBytes(animations(safe, model.display())));
                    packFiles.put("attachables/" + safe + ".json",
                            jsonBytes(attachable(identifier, safe, texturePath)));
                    if (model.isThreeDimensional()) threeDimensional++;
                } else {
                    icon = TextureSet.png(TextureSet.layeredIcon(resources, layers));
                    packFiles.put(texturePath + ".png", icon);
                    if (nativeWeapon != null) {
                        List<String> framePaths = new ArrayList<>();
                        for (int frame = 0; frame < nativeWeapon.frames().size(); frame++) {
                            String framePath = texturePath + "_native_frame_" + frame;
                            packFiles.put(framePath + ".png", nativeWeapon.frames().get(frame));
                            framePaths.add(framePath);
                        }
                        packFiles.put("attachables/" + safe + ".json",
                                jsonBytes(nativeWeaponAttachable(identifier, nativeWeapon.type(), framePaths)));
                    }
                }

                JsonObject iconEntry = new JsonObject();
                iconEntry.addProperty("textures", model.isThreeDimensional() || customFlat || dynamicWeapon != null
                        ? "textures/twilight/" + safe + "_icon" : texturePath);
                textureData.add(iconKey, iconEntry);
                addMapping(mappedItems, candidate, identifier, iconKey, model.handheld());
                converted++;
            } catch (Exception failure) {
                problems.add(candidate.baseItem() + " -> " + candidate.visualModels() + ": " + failure.getMessage());
            }
        }

        BitmapFontCompiler.Result fonts;
        SoundCompiler.Result sounds;
        if (vanillaAssets == null) {
            fonts = new BitmapFontCompiler(resources, null, config.vanillaOverride()).compile(packFiles);
            sounds = new SoundCompiler(resources, config.vanillaOverride(), null).compile(packFiles);
        } else {
            fonts = new BitmapFontCompiler(resources, vanillaAssets, config.vanillaOverride()).compile(packFiles);
            sounds = new SoundCompiler(resources, config.vanillaOverride(), vanillaAssets).compile(packFiles);
        }
        problems.addAll(fonts.problems());
        problems.addAll(sounds.problems());

        if (converted == 0 && !candidates.isEmpty()) throw new IOException("No custom item could be converted; first problem: " + problems.get(0));
        if (config.strict() && !problems.isEmpty()) {
            throw new ConversionException("Strict conversion rejected " + problems.size() +
                    " content problem(s); first problem: " + problems.get(0), problems);
        }
        JsonObject itemAtlas = new JsonObject();
        itemAtlas.addProperty("resource_pack_name", "twilight");
        itemAtlas.addProperty("texture_name", "atlas.items");
        itemAtlas.add("texture_data", textureData);
        packFiles.put("textures/item_texture.json", jsonBytes(itemAtlas));
        packFiles.put("manifest.json", jsonBytes(manifest(packFiles)));
        packFiles.put("README.txt", ("Generated by Twilight. Converted " + converted + " of " + candidates.size() +
                " custom item definitions.\n").getBytes(StandardCharsets.UTF_8));

        JsonObject mappings = new JsonObject();
        mappings.addProperty("format_version", 2);
        mappings.add("items", mappedItems);

        Path staging = dataDirectory.resolve("build/.staging");
        Path current = dataDirectory.resolve("build/current");
        deleteTree(staging);
        Files.createDirectories(staging.resolve("custom_mappings"));
        Path pack = staging.resolve("pack.zip");
        writeZip(pack, packFiles);
        Files.write(staging.resolve("custom_mappings/geyser_item_mappings.json"), jsonBytes(mappings));
        JsonObject report = new JsonObject();
        report.addProperty("candidates", candidates.size());
        report.addProperty("converted", converted);
        report.addProperty("three_dimensional", threeDimensional);
        report.addProperty("glyphs", fonts.glyphs());
        report.addProperty("font_pages", fonts.pages());
        report.addProperty("vanilla_fallback_textures", fonts.vanillaFallbackTextures());
        report.addProperty("named_fonts", fonts.namedFonts());
        report.addProperty("named_font_glyphs", fonts.namedGlyphs());
        report.addProperty("sound_definitions", sounds.definitions());
        report.addProperty("sound_files", sounds.files());
        report.addProperty("vanilla_fallback_sounds", sounds.vanillaFallbackFiles());
        report.addProperty("minecraft_version", minecraftVersion == null ? "unavailable" : minecraftVersion);
        report.addProperty("vanilla_asset_download", config.downloadVanillaAssets());
        report.addProperty("skipped", candidates.size() - converted);
        report.add("problems", GSON.toJsonTree(problems));
        report.addProperty("vanilla_override", config.vanillaOverride());
        Files.write(staging.resolve("build-report.json"), jsonBytes(report));
        validate(pack, mappings, converted);
        deleteTree(current.resolveSibling("previous"));
        if (Files.exists(current)) Files.move(current, current.resolveSibling("previous"), StandardCopyOption.REPLACE_EXISTING);
        Files.move(staging, current, StandardCopyOption.REPLACE_EXISTING);
        deleteTree(current.resolveSibling("previous"));
        return new BuildResult(current, candidates.size(), converted, threeDimensional, fonts.glyphs(), fonts.pages(),
                fonts.vanillaFallbackTextures(),
                fonts.namedFonts(), fonts.namedGlyphs(),
                sounds.definitions(), sounds.files(), sounds.vanillaFallbackFiles(),
                candidates.size() - converted, List.copyOf(problems), sha256(current.resolve("pack.zip")));
    }

    private JsonObject manifest(Map<String, byte[]> contentWithoutManifest) throws IOException {
        Properties identity = new Properties();
        Path identityFile = dataDirectory.resolve("pack-identity.properties");
        if (Files.isRegularFile(identityFile)) try (InputStream input = Files.newInputStream(identityFile)) { identity.load(input); }
        String header = identity.getProperty("header", UUID.randomUUID().toString());
        String module = identity.getProperty("module", UUID.randomUUID().toString());
        identity.setProperty("header", header); identity.setProperty("module", module);
        Files.createDirectories(dataDirectory);
        try (var output = Files.newOutputStream(identityFile)) { identity.store(output, "Stable Twilight Bedrock pack identity"); }
        int contentHash = java.util.Arrays.hashCode(contentWithoutManifest.values().stream()
                .flatMapToInt(bytes -> java.util.stream.IntStream.of(java.util.Arrays.hashCode(bytes))).toArray());
        // Bedrock serializes legacy manifest version vectors through a bounded
        // protocol field. Very large positive Java integers can make the client
        // reject the complete server pack stack before entering the world.
        int build = Math.floorMod(contentHash, MAX_PACK_VERSION_COMPONENT) + 1;

        JsonObject headerJson = new JsonObject();
        headerJson.addProperty("name", "Twilight");
        headerJson.addProperty("description", "Server-generated custom content for Geyser");
        headerJson.addProperty("uuid", header);
        headerJson.add("version", intArray(1, 0, build));
        headerJson.add("min_engine_version", intArray(1, 21, 0));
        JsonObject moduleJson = new JsonObject();
        moduleJson.addProperty("type", "resources");
        moduleJson.addProperty("uuid", module);
        moduleJson.add("version", intArray(1, 0, build));
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);
        manifest.add("header", headerJson);
        JsonArray modules = new JsonArray(); modules.add(moduleJson); manifest.add("modules", modules);
        return manifest;
    }

    static List<String> flatLayers(ResolvedJavaModel model) {
        List<String> layers = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            String texture = model.textures().get("layer" + index);
            if (texture == null) break;
            layers.add(texture);
        }
        return layers;
    }

    private static Set<String> usedTextures(ResolvedJavaModel model) throws IOException {
        Set<String> textures = new LinkedHashSet<>();
        for (JsonElement element : geometryElements(model)) {
            JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
            if (faces == null) continue;
            for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                JsonObject faceJson = face.getValue().getAsJsonObject();
                if (!faceJson.has("texture")) continue;
                String reference = faceJson.get("texture").getAsString();
                if (reference.startsWith("#")) {
                    String resolved = model.textures().get(reference.substring(1));
                    if (resolved == null) throw new IOException("Missing face texture " + reference);
                    textures.add(resolved);
                } else textures.add(JavaModelResolver.qualified(reference, "minecraft"));
            }
        }
        if (textures.isEmpty()) textures.addAll(flatLayers(model));
        if (textures.isEmpty()) throw new IOException("3D model has no face textures");
        return textures;
    }

    private static NativeWeapon nativeWeapon(ItemCandidate candidate, ResolvedJavaModel model,
                                             JavaModelResolver resolver, ResourceIndex resources,
                                             VanillaItemReference vanillaItems) throws IOException {
        String type;
        int expected;
        if (candidate.baseItem().equals("minecraft:bow")) {
            type = "bow";
            expected = 4;
        } else if (candidate.baseItem().equals("minecraft:crossbow")) {
            type = "crossbow";
            expected = 6;
        } else return null;
        if (model.isThreeDimensional() || flatLayers(model).size() != 1) return null;

        List<String> stateModels = candidate.nativeStateModels().isEmpty()
                ? List.of(candidate.visualModel()) : candidate.nativeStateModels();
        int referenceState = 0;
        boolean chargedCrossbow = false;
        if (type.equals("crossbow") && stateModels.size() == 1) {
            if (hasChargeType(candidate, "arrow")) referenceState = 4;
            else if (hasChargeType(candidate, "rocket")) referenceState = 5;
            else return null;
            chargedCrossbow = true;
        }
        // Bedrock's built-in weapon geometry selects every state at runtime.
        // Reusing it is safe only when Java supplied a matching texture for
        // every state that Bedrock can select.
        if (!chargedCrossbow && stateModels.size() != expected) return null;
        List<byte[]> frames = new ArrayList<>(expected);
        for (String stateModel : stateModels) {
            if (frames.size() == expected) break;
            ResolvedJavaModel state = resolver.resolve(stateModel);
            List<String> stateLayers = flatLayers(state);
            if (state.isThreeDimensional() || stateLayers.size() != 1) return null;
            try {
                if (!vanillaItems.matchesWeaponState(type, referenceState + frames.size(), state)) return null;
            } catch (IOException unavailableReference) {
                return null;
            }
            frames.add(TextureSet.png(TextureSet.layeredIcon(resources, stateLayers)));
        }
        if (chargedCrossbow) while (frames.size() < expected) frames.add(frames.getFirst());
        return frames.size() == expected ? new NativeWeapon(type, List.copyOf(frames)) : null;
    }

    private static boolean hasChargeType(ItemCandidate candidate, String expected) {
        for (JsonElement predicateValue : candidate.predicates()) {
            if (!predicateValue.isJsonObject()) continue;
            JsonObject predicate = predicateValue.getAsJsonObject();
            if (predicate.has("property") && predicate.has("value") &&
                    predicate.get("property").getAsString().equals("charge_type") &&
                    predicate.get("value").getAsString().equals(expected)) return true;
        }
        return false;
    }

    private static DynamicWeapon dynamicWeapon(ItemCandidate candidate, JavaModelResolver resolver) throws IOException {
        if (!(candidate.baseItem().equals("minecraft:bow") || candidate.baseItem().equals("minecraft:crossbow")) ||
                candidate.nativeStateModels().size() < 4) return null;
        List<ResolvedJavaModel> states = new ArrayList<>(4);
        for (String stateModel : candidate.nativeStateModels().subList(0, 4)) {
            ResolvedJavaModel state = resolver.resolve(stateModel);
            if (!state.isThreeDimensional() && flatLayers(state).isEmpty()) return null;
            states.add(state);
        }
        String type = candidate.baseItem().equals("minecraft:bow") ? "bow" : "crossbow";
        return new DynamicWeapon(type, List.copyOf(states));
    }

    private static boolean requiresCustomFlat(ItemCandidate candidate, ResolvedJavaModel model,
                                              VanillaItemReference vanillaItems) {
        List<String> layers = flatLayers(model);
        if (layers.size() != 1) return true;
        if (candidate.baseItem().equals("minecraft:bow") || candidate.baseItem().equals("minecraft:crossbow")) {
            return true;
        }
        if (candidate.baseItem().equals("minecraft:fishing_rod")) {
            boolean cast = candidate.predicates().toString().contains("fishing_rod_cast");
            try { return !vanillaItems.matchesFishingRod(cast, model); }
            catch (IOException unavailableReference) { return true; }
        }
        return !JavaModelResolver.hasStandardFlatDisplay(model.display());
    }

    /**
     * Java's generated item model is represented as ordered, alpha-tested
     * sheets. A small depth keeps both faces stable in Bedrock while each
     * layer retains its own texture and Java display transform.
     */
    private static JsonArray geometryElements(ResolvedJavaModel model) throws IOException {
        if (model.elements() != null && !model.elements().isEmpty()) return model.elements();
        List<String> layers = flatLayers(model);
        if (layers.isEmpty()) throw new IOException("Flat item has no texture layers");
        JsonArray elements = new JsonArray();
        for (int layer = 0; layer < layers.size(); layer++) {
            double front = 7.5 + layer * 0.0125;
            JsonObject element = new JsonObject();
            element.add("from", numberArray(0, 0, front));
            element.add("to", numberArray(16, 16, front + 0.01));
            JsonObject faces = new JsonObject();
            JsonObject north = new JsonObject();
            north.add("uv", numberArray(16, 0, 0, 16));
            north.addProperty("texture", "#layer" + layer);
            faces.add("north", north);
            JsonObject south = new JsonObject();
            south.add("uv", numberArray(0, 0, 16, 16));
            south.addProperty("texture", "#layer" + layer);
            faces.add("south", south);
            element.add("faces", faces);
            elements.add(element);
        }
        return elements;
    }

    private static JsonObject geometry(String safe, ResolvedJavaModel model, TextureSet atlas,
                                       GeometryBounds bounds) throws IOException {
        JsonArray cubes = new JsonArray();
        for (JsonElement elementValue : geometryElements(model)) {
            JsonObject element = elementValue.getAsJsonObject();
            double[] from = vector(element.getAsJsonArray("from"), 0, 0, 0);
            double[] to = vector(element.getAsJsonArray("to"), 16, 16, 16);
            JsonObject cube = new JsonObject();
            cube.add("origin", numberArray(from[0] - 8, from[1], 8 - to[2]));
            cube.add("size", numberArray(to[0] - from[0], to[1] - from[1], to[2] - from[2]));
            if (element.has("rotation")) {
                JsonObject rotation = element.getAsJsonObject("rotation");
                double[] pivot = vector(rotation.getAsJsonArray("origin"), 8, 8, 8);
                double angle = rotation.has("angle") ? rotation.get("angle").getAsDouble() : 0;
                String axis = rotation.has("axis") ? rotation.get("axis").getAsString() : "y";
                cube.add("pivot", numberArray(pivot[0] - 8, pivot[1], 8 - pivot[2]));
                cube.add("rotation", switch (axis) {
                    case "x" -> numberArray(-angle, 0, 0);
                    case "z" -> numberArray(0, 0, -angle);
                    default -> numberArray(0, angle, 0);
                });
            }
            JsonObject uv = new JsonObject();
            JsonObject faces = element.getAsJsonObject("faces");
            if (faces != null) for (Map.Entry<String, JsonElement> faceEntry : faces.entrySet()) {
                JsonObject face = faceEntry.getValue().getAsJsonObject();
                if (!face.has("texture")) continue;
                String reference = face.get("texture").getAsString();
                String texture = reference.startsWith("#") ? model.textures().get(reference.substring(1))
                        : JavaModelResolver.qualified(reference, "minecraft");
                if (texture == null) throw new IOException("Unresolved face texture " + reference);
                TextureSet.Region region = atlas.region(texture);
                double[] faceUv = face.has("uv") ? vector4(face.getAsJsonArray("uv")) : new double[]{0, 0, 16, 16};
                JsonObject mapped = new JsonObject();
                mapped.add("uv", numberArray(region.x() + faceUv[0] * region.width() / 16.0,
                        region.y() + faceUv[1] * region.height() / 16.0));
                mapped.add("uv_size", numberArray((faceUv[2] - faceUv[0]) * region.width() / 16.0,
                        (faceUv[3] - faceUv[1]) * region.height() / 16.0));
                uv.add(mapFace(faceEntry.getKey()), mapped);
            }
            cube.add("uv", uv);
            cubes.add(cube);
        }
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry.twilight." + safe);
        description.addProperty("texture_width", atlas.width());
        description.addProperty("texture_height", atlas.height());
        description.addProperty("visible_bounds_width", Math.max(1,
                Math.max(bounds.maxX() - bounds.minX(), bounds.maxZ() - bounds.minZ()) / 16.0 + 1));
        description.addProperty("visible_bounds_height", Math.max(1,
                (bounds.maxY() - bounds.minY()) / 16.0 + 1));
        description.add("visible_bounds_offset", numberArray(
                (bounds.minX() + bounds.maxX()) / 32.0,
                (bounds.minY() + bounds.maxY()) / 32.0,
                (bounds.minZ() + bounds.maxZ()) / 32.0));
        JsonObject bone = new JsonObject(); bone.addProperty("name", "bone");
        bone.addProperty("binding", "q.item_slot_to_bone_name(context.item_slot)"); bone.add("pivot", numberArray(0, 8, 0)); bone.add("cubes", cubes);
        JsonArray bones = new JsonArray(); bones.add(bone);
        JsonObject geometry = new JsonObject(); geometry.add("description", description); geometry.add("bones", bones);
        JsonArray geometries = new JsonArray(); geometries.add(geometry);
        JsonObject root = new JsonObject(); root.addProperty("format_version", "1.21.0"); root.add("minecraft:geometry", geometries);
        return root;
    }

    private static JsonObject attachable(String identifier, String safe, String texturePath) {
        JsonObject description = new JsonObject(); description.addProperty("identifier", identifier);
        JsonObject materials = new JsonObject(); materials.addProperty("default", "entity_alphatest"); materials.addProperty("enchanted", "entity_alphatest_glint"); description.add("materials", materials);
        JsonObject textures = new JsonObject(); textures.addProperty("default", texturePath); textures.addProperty("enchanted", "textures/misc/enchanted_item_glint"); description.add("textures", textures);
        JsonObject geometries = new JsonObject(); geometries.addProperty("default", "geometry.twilight." + safe); description.add("geometry", geometries);
        JsonObject animationNames = new JsonObject();
        for (String view : List.of("first_person_right", "first_person_left", "third_person_right", "third_person_left", "head"))
            animationNames.addProperty(view, "animation.twilight." + safe + "." + view);
        description.add("animations", animationNames);
        JsonArray animate = new JsonArray();
        animate.add(condition("first_person_right", "context.is_first_person == 1.0 && context.item_slot == 'main_hand'"));
        animate.add(condition("first_person_left", "context.is_first_person == 1.0 && context.item_slot == 'off_hand'"));
        animate.add(condition("third_person_right", "context.is_first_person == 0.0 && context.item_slot == 'main_hand'"));
        animate.add(condition("third_person_left", "context.is_first_person == 0.0 && context.item_slot == 'off_hand'"));
        animate.add(condition("head", "context.is_first_person == 0.0 && context.item_slot == 'head'"));
        JsonObject scripts = new JsonObject(); scripts.add("animate", animate); description.add("scripts", scripts);
        JsonArray controllers = new JsonArray(); controllers.add("controller.render.item_default"); description.add("render_controllers", controllers);
        JsonObject attachable = new JsonObject(); attachable.add("description", description);
        JsonObject root = new JsonObject(); root.addProperty("format_version", "1.21.0"); root.add("minecraft:attachable", attachable); return root;
    }

    private static JsonObject nativeWeaponAttachable(String identifier, String type, List<String> framePaths) {
        List<String> textureKeys = type.equals("bow")
                ? List.of("default", "bow_pulling_0", "bow_pulling_1", "bow_pulling_2")
                : List.of("default", "crossbow_pulling_0", "crossbow_pulling_1", "crossbow_pulling_2",
                "crossbow_arrow", "crossbow_rocket");
        List<String> geometries = type.equals("bow")
                ? List.of("geometry.bow_standby", "geometry.bow_pulling_0", "geometry.bow_pulling_1", "geometry.bow_pulling_2")
                : List.of("geometry.crossbow_standby", "geometry.crossbow_pulling_0", "geometry.crossbow_pulling_1",
                "geometry.crossbow_pulling_2", "geometry.crossbow_arrow", "geometry.crossbow_rocket");
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest");
        materials.addProperty("enchanted", "entity_alphatest_glint");
        description.add("materials", materials);
        JsonObject textures = new JsonObject();
        for (int index = 0; index < textureKeys.size(); index++) textures.addProperty(textureKeys.get(index), framePaths.get(index));
        textures.addProperty("enchanted", "textures/misc/enchanted_item_glint");
        description.add("textures", textures);
        JsonObject geometry = new JsonObject();
        for (int index = 0; index < textureKeys.size(); index++) geometry.addProperty(textureKeys.get(index), geometries.get(index));
        description.add("geometry", geometry);
        JsonObject animations = new JsonObject();
        animations.addProperty("wield", "animation." + type + ".wield");
        animations.addProperty("wield_first_person_pull", "animation." + type + ".wield_first_person_pull");
        description.add("animations", animations);
        JsonObject scripts = new JsonObject();
        JsonArray preAnimation = new JsonArray();
        preAnimation.add("variable.charge_amount = math.clamp((query.main_hand_item_max_duration - " +
                "(query.main_hand_item_use_duration - query.frame_alpha + 1.0)) / 10.0, 0.0, 1.0f);");
        scripts.add("pre_animation", preAnimation);
        JsonArray animate = new JsonArray();
        animate.add("wield");
        animate.add(condition("wield_first_person_pull",
                "query.main_hand_item_use_duration > 0.0f && c.is_first_person"));
        scripts.add("animate", animate);
        description.add("scripts", scripts);
        JsonArray controllers = new JsonArray();
        controllers.add("controller.render." + type);
        description.add("render_controllers", controllers);
        JsonObject attachable = new JsonObject(); attachable.add("description", description);
        JsonObject root = new JsonObject(); root.addProperty("format_version", "1.21.0");
        root.add("minecraft:attachable", attachable);
        return root;
    }

    private static JsonObject dynamicWeaponAttachable(String identifier, String safe, String texturePath,
                                                      String type, int stateCount) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest");
        materials.addProperty("enchanted", "entity_alphatest_glint");
        description.add("materials", materials);
        JsonObject textures = new JsonObject();
        textures.addProperty("default", texturePath);
        textures.addProperty("enchanted", "textures/misc/enchanted_item_glint");
        description.add("textures", textures);
        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", "geometry.twilight." + safe + "_state_0");
        for (int state = 0; state < stateCount; state++)
            geometry.addProperty("state_" + state, "geometry.twilight." + safe + "_state_" + state);
        description.add("geometry", geometry);
        JsonObject animationNames = new JsonObject();
        List<String> views = List.of("first_person_right", "first_person_left", "third_person_right", "third_person_left", "head");
        for (int state = 0; state < stateCount; state++) for (String view : views)
            animationNames.addProperty("state_" + state + '_' + view,
                    "animation.twilight." + safe + "_state_" + state + '.' + view);
        description.add("animations", animationNames);

        JsonObject scripts = new JsonObject();
        JsonArray preAnimation = new JsonArray();
        preAnimation.add("v.twilight_model_index = " + dynamicStateExpression(type) + ";");
        scripts.add("pre_animation", preAnimation);
        JsonArray animate = new JsonArray();
        for (int state = 0; state < stateCount; state++) {
            String selected = "v.twilight_model_index == " + state;
            animate.add(condition("state_" + state + "_first_person_right",
                    selected + " && context.is_first_person == 1.0 && context.item_slot == 'main_hand'"));
            animate.add(condition("state_" + state + "_first_person_left",
                    selected + " && context.is_first_person == 1.0 && context.item_slot == 'off_hand'"));
            animate.add(condition("state_" + state + "_third_person_right",
                    selected + " && context.is_first_person == 0.0 && context.item_slot == 'main_hand'"));
            animate.add(condition("state_" + state + "_third_person_left",
                    selected + " && context.is_first_person == 0.0 && context.item_slot == 'off_hand'"));
            animate.add(condition("state_" + state + "_head",
                    selected + " && context.is_first_person == 0.0 && context.item_slot == 'head'"));
        }
        scripts.add("animate", animate);
        description.add("scripts", scripts);
        JsonArray controllers = new JsonArray();
        controllers.add("controller.render.twilight." + safe + "_runtime");
        description.add("render_controllers", controllers);
        JsonObject attachable = new JsonObject(); attachable.add("description", description);
        JsonObject root = new JsonObject(); root.addProperty("format_version", "1.21.0");
        root.add("minecraft:attachable", attachable);
        return root;
    }

    private static JsonObject dynamicRenderController(String safe, int stateCount) {
        JsonArray geometries = new JsonArray();
        for (int state = 0; state < stateCount; state++) geometries.add("Geometry.state_" + state);
        JsonObject geometryArrays = new JsonObject();
        geometryArrays.add("Array.twilight_geometries", geometries);
        JsonObject arrays = new JsonObject(); arrays.add("geometries", geometryArrays);
        JsonObject controller = new JsonObject();
        controller.add("arrays", arrays);
        controller.addProperty("geometry", "Array.twilight_geometries[v.twilight_model_index]");
        JsonArray materials = new JsonArray();
        JsonObject material = new JsonObject();
        material.addProperty("*", "variable.is_enchanted ? Material.enchanted : Material.default");
        materials.add(material);
        controller.add("materials", materials);
        JsonArray textures = new JsonArray(); textures.add("Texture.default"); textures.add("Texture.enchanted");
        controller.add("textures", textures);
        JsonObject controllers = new JsonObject();
        controllers.add("controller.render.twilight." + safe + "_runtime", controller);
        JsonObject root = new JsonObject(); root.addProperty("format_version", "1.8.0");
        root.add("render_controllers", controllers);
        return root;
    }

    private static String dynamicStateExpression(String type) {
        String progress = type.equals("crossbow")
                ? "math.clamp(1.0 - q.item_remaining_use_duration(c.item_slot, 1.0), 0.0, 1.0)"
                : "math.clamp(q.item_in_use_duration, 0.0, 1.0)";
        String secondThreshold = type.equals("crossbow") ? "0.58" : "0.65";
        String finalThreshold = type.equals("crossbow") ? "1.0" : "0.9";
        return "(q.is_using_item && q.item_remaining_use_duration(c.item_slot) > 0.0) ? (" + progress +
                " >= " + finalThreshold + " ? 3 : (" + progress + " >= " + secondThreshold + " ? 2 : 1)) : 0";
    }

    private static JsonObject animations(String safe, JsonObject display) {
        JsonObject definitions = new JsonObject();
        JsonObject firstRight = transform(display, "firstperson_righthand", null);
        JsonObject firstLeft = transform(display, "firstperson_lefthand", firstRight);
        JsonObject thirdRight = transform(display, "thirdperson_righthand", null);
        JsonObject thirdLeft = transform(display, "thirdperson_lefthand", thirdRight);
        definitions.add("animation.twilight." + safe + ".first_person_right", animation(mapFirst(firstRight)));
        definitions.add("animation.twilight." + safe + ".first_person_left", animation(mapFirst(
                mirror(firstLeft, display.has("firstperson_lefthand")))));
        definitions.add("animation.twilight." + safe + ".third_person_right", animation(mapThird(thirdRight)));
        definitions.add("animation.twilight." + safe + ".third_person_left", animation(mapThird(
                mirror(thirdLeft, display.has("thirdperson_lefthand")))));
        definitions.add("animation.twilight." + safe + ".head", animation(mapHead(transform(display, "head", null))));
        JsonObject root = new JsonObject(); root.addProperty("format_version", "1.8.0"); root.add("animations", definitions); return root;
    }

    private static JsonObject animation(MappedTransform mapped) {
        JsonObject bone = new JsonObject(); bone.add("position", numberArray(mapped.position)); bone.add("rotation", numberArray(mapped.rotation)); bone.add("scale", numberArray(mapped.scale));
        JsonObject bones = new JsonObject(); bones.add("bone", bone);
        JsonObject animation = new JsonObject(); animation.addProperty("loop", true); animation.add("bones", bones); return animation;
    }

    private static MappedTransform mapFirst(JsonObject transform) {
        double[] t = vector(transform.getAsJsonArray("translation"), 0, 0, 0), r = vector(transform.getAsJsonArray("rotation"), 0, 0, 0), s = vector(transform.getAsJsonArray("scale"), 1, 1, 1);
        Quaternion q = Quaternion.axis(-90, 1, 0, 0).mul(Quaternion.axis(r[0], 0, 0, 1)).mul(Quaternion.axis(r[1], 1, 0, 0)).mul(Quaternion.axis(-r[2], 0, 1, 0));
        return new MappedTransform(new double[]{-t[1], 12.5 + t[2], t[0]}, q.eulerXYZ(), s);
    }

    private static MappedTransform mapThird(JsonObject transform) {
        double[] t = vector(transform.getAsJsonArray("translation"), 0, 0, 0), r = vector(transform.getAsJsonArray("rotation"), 0, 0, 0), s = vector(transform.getAsJsonArray("scale"), 1, 1, 1);
        Quaternion q = Quaternion.axis(90, 1, 0, 0).mul(Quaternion.axis(-r[0], 1, 0, 0)).mul(Quaternion.axis(-r[1], 0, 0, 1)).mul(Quaternion.axis(-r[2], 0, 1, 0));
        return new MappedTransform(new double[]{-t[0], 12.5 + t[2], -t[1]}, q.eulerXYZ(), s);
    }

    private static MappedTransform mapHead(JsonObject transform) {
        double[] t = vector(transform.getAsJsonArray("translation"), 0, 0, 0), r = vector(transform.getAsJsonArray("rotation"), 0, 0, 0), s = vector(transform.getAsJsonArray("scale"), 1, 1, 1);
        return new MappedTransform(new double[]{-t[0] * .655, 20 + t[1] * .655, t[2] * .655}, new double[]{-r[0], -r[1], r[2]}, scale(s, .655));
    }

    private static JsonObject transform(JsonObject display, String name, JsonObject fallback) {
        JsonObject result = fallback == null ? new JsonObject() : fallback.deepCopy();
        if (display.has(name)) result = display.getAsJsonObject(name).deepCopy();
        if (!result.has("translation")) result.add("translation", numberArray(0, 0, 0));
        if (!result.has("rotation")) result.add("rotation", numberArray(0, 0, 0));
        if (!result.has("scale")) result.add("scale", numberArray(1, 1, 1));
        return result;
    }

    private static JsonObject mirror(JsonObject input, boolean explicit) {
        if (explicit) return input;
        JsonObject result = input.deepCopy();
        double[] r = vector(result.getAsJsonArray("rotation"), 0, 0, 0), t = vector(result.getAsJsonArray("translation"), 0, 0, 0);
        result.add("rotation", numberArray(r[0], -r[1], -r[2])); result.add("translation", numberArray(-t[0], t[1], t[2])); return result;
    }

    private static GeometryBounds geometryBounds(ResolvedJavaModel model) throws IOException {
        GeometryBounds bounds = GeometryBounds.empty();
        for (JsonElement elementValue : geometryElements(model)) {
            JsonObject element = elementValue.getAsJsonObject();
            double[] from = vector(element.getAsJsonArray("from"), 0, 0, 0);
            double[] to = vector(element.getAsJsonArray("to"), 16, 16, 16);
            JsonObject rotation = element.has("rotation") ? element.getAsJsonObject("rotation") : null;
            double[] pivot = rotation == null ? new double[]{8, 8, 8}
                    : vector(rotation.getAsJsonArray("origin"), 8, 8, 8);
            double angle = rotation != null && rotation.has("angle")
                    ? Math.toRadians(rotation.get("angle").getAsDouble()) : 0;
            String axis = rotation != null && rotation.has("axis")
                    ? rotation.get("axis").getAsString() : "y";
            double cosine = Math.cos(angle), sine = Math.sin(angle);
            for (int mask = 0; mask < 8; mask++) {
                double x = ((mask & 1) == 0 ? from[0] : to[0]) - pivot[0];
                double y = ((mask & 2) == 0 ? from[1] : to[1]) - pivot[1];
                double z = ((mask & 4) == 0 ? from[2] : to[2]) - pivot[2];
                double rotatedX = x, rotatedY = y, rotatedZ = z;
                switch (axis) {
                    case "x" -> {
                        rotatedY = y * cosine - z * sine;
                        rotatedZ = y * sine + z * cosine;
                    }
                    case "z" -> {
                        rotatedX = x * cosine - y * sine;
                        rotatedY = x * sine + y * cosine;
                    }
                    case "y" -> {
                        rotatedX = x * cosine + z * sine;
                        rotatedZ = -x * sine + z * cosine;
                    }
                    default -> throw new IOException("Unsupported element rotation axis " + axis);
                }
                bounds = bounds.include(rotatedX + pivot[0] - 8,
                        rotatedY + pivot[1], 8 - (rotatedZ + pivot[2]));
            }
        }
        if (!bounds.finite()) throw new IOException("3D model geometry bounds must be finite");
        return bounds;
    }

    private static void addMapping(JsonObject mappedItems, ItemCandidate candidate, String identifier, String iconKey,
                                   boolean displayHandheld) {
        JsonArray definitions = mappedItems.has(candidate.baseItem()) ? mappedItems.getAsJsonArray(candidate.baseItem()) : new JsonArray();
        JsonObject mapping = new JsonObject();
        if (candidate.customModelData().isPresent()) { mapping.addProperty("type", "legacy"); mapping.addProperty("custom_model_data", candidate.customModelData().getAsInt()); }
        else { mapping.addProperty("type", "definition"); mapping.addProperty("model", candidate.mappingModel()); }
        mapping.addProperty("bedrock_identifier", identifier);
        if (!candidate.predicates().isEmpty()) {
            mapping.add("predicate", candidate.predicates().size() == 1
                    ? candidate.predicates().get(0).deepCopy() : candidate.predicates().deepCopy());
            if (candidate.predicates().size() > 1) mapping.addProperty("predicate_strategy", "and");
        }
        if (candidate.priority() != 0) mapping.addProperty("priority", candidate.priority());
        JsonObject options = new JsonObject();
        options.addProperty("icon", iconKey);
        if (displayHandheld) options.addProperty("display_handheld", true);
        mapping.add("bedrock_options", options);
        definitions.add(mapping); mappedItems.add(candidate.baseItem(), definitions);
    }

    private static String bedrockIdentifier(ItemCandidate candidate) {
        String raw = candidate.visualModel().toLowerCase(Locale.ROOT).replace(':', '_').replaceAll("[^a-z0-9._-]", "_");
        // Keep every emitted resource-pack path below Geyser's 80-character
        // portability boundary, including dynamic `_state_N.animation.json`
        // suffixes. The stable hash remains the collision boundary when the
        // readable model portion has to be shortened.
        int readableLength = MAX_BEDROCK_SAFE_IDENTIFIER_LENGTH - 13; // '_' plus the 12-character hash
        if (raw.length() > readableLength) raw = raw.substring(raw.length() - readableLength);
        return "twilight:" + raw + "_" + shortHash(candidate.baseItem() + '|' + candidate.mappingModel() + '|' +
                candidate.visualModels() + '|' + candidate.customModelData() + '|' + candidate.predicates());
    }

    private static String shortHash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 6); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String mapFace(String face) { return switch (face) { case "north" -> "south"; case "south" -> "north"; default -> face; }; }
    private static JsonObject condition(String animation, String expression) { JsonObject value = new JsonObject(); value.addProperty(animation, expression); return value; }
    private static double[] scale(double[] value, double factor) { return new double[]{value[0] * factor, value[1] * factor, value[2] * factor}; }
    private static double[] vector(JsonArray value, double x, double y, double z) { if (value == null || value.size() < 3) return new double[]{x,y,z}; return new double[]{value.get(0).getAsDouble(),value.get(1).getAsDouble(),value.get(2).getAsDouble()}; }
    private static double[] vector4(JsonArray value) throws IOException { if (value == null || value.size() < 4) throw new IOException("Face UV must contain four numbers"); return new double[]{value.get(0).getAsDouble(),value.get(1).getAsDouble(),value.get(2).getAsDouble(),value.get(3).getAsDouble()}; }
    private static JsonArray numberArray(double... values) { JsonArray array = new JsonArray(); for (double value : values) array.add(value); return array; }
    private static JsonArray intArray(int... values) { JsonArray array = new JsonArray(); for (int value : values) array.add(value); return array; }
    static byte[] jsonBytes(JsonElement element) { return GSON.toJson(element).getBytes(StandardCharsets.UTF_8); }

    private static void validate(Path pack, JsonObject mappings, int expected) throws IOException {
        if (!Files.isRegularFile(pack) || Files.size(pack) == 0) throw new IOException("Generated pack is empty");
        int definitions = 0; for (JsonElement array : mappings.getAsJsonObject("items").asMap().values()) definitions += array.getAsJsonArray().size();
        if (definitions != expected) throw new IOException("Mapping count mismatch: expected " + expected + ", got " + definitions);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(pack.toFile())) {
            if (zip.getEntry("manifest.json") == null || zip.getEntry("textures/item_texture.json") == null) throw new IOException("Generated pack lacks required files");
            var entries = zip.entries(); while (entries.hasMoreElements()) { ZipEntry e = entries.nextElement(); if (e.getName().endsWith(".json")) try (InputStream in = zip.getInputStream(e)) { JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)); } }
        }
    }

    private static void writeZip(Path path, Map<String, byte[]> files) throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> file : files.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                ZipEntry entry = new ZipEntry(file.getKey()); entry.setTime(ZIP_TIME); zip.putNextEntry(entry); zip.write(file.getValue()); zip.closeEntry();
            }
        }
    }

    private static String sha256(Path path) throws IOException { try { MessageDigest d=MessageDigest.getInstance("SHA-256"); try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536]; for(int n;(n=in.read(b))>=0;)if(n>0)d.update(b,0,n);} return HexFormat.of().formatHex(d.digest()).toUpperCase(Locale.ROOT);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    private static void deleteTree(Path path) throws IOException { if(!Files.exists(path))return; try(var stream=Files.walk(path)){for(Path entry:stream.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(entry))throw new IOException("Symbolic build path rejected: "+entry); Files.deleteIfExists(entry);}} }

    private record MappedTransform(double[] position, double[] rotation, double[] scale) {}
    private record NativeWeapon(String type, List<byte[]> frames) {}
    private record DynamicWeapon(String type, List<ResolvedJavaModel> states) {}
    private record GeometryBounds(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        static GeometryBounds empty() {
            return new GeometryBounds(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        }

        GeometryBounds include(double x, double y, double z) {
            return new GeometryBounds(Math.min(minX, x), Math.min(minY, y), Math.min(minZ, z),
                    Math.max(maxX, x), Math.max(maxY, y), Math.max(maxZ, z));
        }

        boolean finite() {
            return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                    && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ);
        }

    }
    private record Quaternion(double x,double y,double z,double w) {
        static Quaternion axis(double degrees,double ax,double ay,double az){double h=Math.toRadians(degrees)/2,s=Math.sin(h);return new Quaternion(ax*s,ay*s,az*s,Math.cos(h));}
        Quaternion mul(Quaternion b){return new Quaternion(w*b.x+x*b.w+y*b.z-z*b.y,w*b.y-x*b.z+y*b.w+z*b.x,w*b.z+x*b.y-y*b.x+z*b.w,w*b.w-x*b.x-y*b.y-z*b.z);}
        double[] eulerXYZ(){double xx=x*x,yy=y*y,zz=z*z; double m00=1-2*(yy+zz),m01=2*(x*y-z*w),m10=2*(x*y+z*w),m11=1-2*(xx+zz),m20=2*(x*z-y*w),m21=2*(y*z+x*w),m22=1-2*(xx+yy); double sy=Math.max(-1,Math.min(1,-m20)), yv=Math.asin(sy),xv,zv; if(Math.abs(Math.cos(yv))>1e-7){xv=Math.atan2(m21,m22);zv=Math.atan2(m10,m00);}else{xv=Math.atan2(-m01,m11);zv=0;} return new double[]{Math.toDegrees(xv),Math.toDegrees(yv),Math.toDegrees(zv)};}
    }
}
