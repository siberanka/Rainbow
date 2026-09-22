/*
 * Copyright (c) 2026 siberanka.
 * Licensed under the GNU LGPL v3.0 or later.
 */
package com.siberanka.twilight.compiler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.siberanka.twilight.source.ResourceIndex;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts Java bitmap-font graphs into Bedrock BMP glyph pages without contextual collisions. */
final class BitmapFontCompiler {
    private static final int BEDROCK_CELL_SIZE = 16;
    private final ResourceIndex resources;
    private final VanillaAssetCache vanillaAssets;
    private final boolean vanillaOverride;
    private final Map<String, ProblemBucket> problemGroups = new LinkedHashMap<>();
    private final Map<Integer, Glyph> glyphs = new LinkedHashMap<>();
    private final Set<String> nonBmpGlyphs = new HashSet<>();
    private final Set<String> unsupportedBaselineProviders = new HashSet<>();
    private final Set<String> vanillaFallbackTextures = new HashSet<>();
    private int namedFonts;
    private int namedGlyphs;

    BitmapFontCompiler(ResourceIndex resources, VanillaAssetCache vanillaAssets) {
        this(resources, vanillaAssets, false);
    }

    BitmapFontCompiler(ResourceIndex resources, VanillaAssetCache vanillaAssets, boolean vanillaOverride) {
        this.resources = resources;
        this.vanillaAssets = vanillaAssets;
        this.vanillaOverride = vanillaOverride;
    }

    Result compile(Map<String, byte[]> packFiles) throws IOException {
        List<String> definitions = resources.paths().stream().map(BitmapFontCompiler::fontIdentifier)
                .filter(java.util.Objects::nonNull).sorted().toList();
        if (definitions.isEmpty()) return new Result(0, 0, 0, 0, 0, List.of());

        Set<String> defaultClosure = new HashSet<>();
        if (definitions.contains("minecraft:default")) {
            Map<Integer, Glyph> defaultGlyphs = new LinkedHashMap<>();
            readFont("minecraft:default", new HashSet<>(), defaultClosure, defaultGlyphs);
            mergeDefaultFont(defaultGlyphs);
        }
        List<String> namedDefinitions = definitions.stream()
                .filter(identifier -> !identifier.equals("minecraft:default"))
                .filter(identifier -> !defaultClosure.contains(identifier)).toList();
        namedFonts = namedDefinitions.size();
        for (String identifier : namedDefinitions) {
            Map<Integer, Glyph> contextual = new LinkedHashMap<>();
            readFont(identifier, new HashSet<>(), new HashSet<>(), contextual);
            mergeNamedFont(identifier, contextual);
        }
        Map<Integer, List<Glyph>> pages = new LinkedHashMap<>();
        glyphs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                pages.computeIfAbsent(entry.getKey() >>> 8, ignored -> new ArrayList<>()).add(entry.getValue()));
        for (Map.Entry<Integer, List<Glyph>> page : pages.entrySet()) {
            packFiles.put("font/glyph_%02X.png".formatted(page.getKey()), TextureSet.png(compose(page.getValue())));
        }
        List<String> problems = new ArrayList<>(problemGroups.entrySet().stream()
                .map(entry -> entry.getValue().format(entry.getKey())).toList());
        if (!nonBmpGlyphs.isEmpty()) problems.add(nonBmpGlyphs.size() +
                " supplementary-plane glyph(s) cannot be represented by Bedrock BMP pages");
        if (!unsupportedBaselineProviders.isEmpty()) problems.add(unsupportedBaselineProviders.size() +
                " Java-only negative/out-of-range baseline provider(s) were skipped");
        return new Result(glyphs.size(), pages.size(), vanillaFallbackTextures.size(), namedFonts, namedGlyphs,
                List.copyOf(problems));
    }

    private void readFont(String identifier, Set<String> active, Set<String> closure,
                          Map<Integer, Glyph> target) {
        String normalized = identifier(identifier, "minecraft");
        if (!active.add(normalized)) return;
        closure.add(normalized);
        try {
            var definition = resources.find(fontPath(normalized));
            if (definition.isEmpty()) {
                if (!normalized.startsWith("minecraft:")) problem("missing referenced fonts", normalized);
                return;
            }
            JsonElement parsed = JsonParser.parseString(definition.get().readUtf8());
            if (!parsed.isJsonObject()) {
                problem("font roots are not objects", normalized);
                return;
            }
            JsonElement providersElement = parsed.getAsJsonObject().get("providers");
            if (providersElement == null || !providersElement.isJsonArray()) return;
            var providers = providersElement.getAsJsonArray();
            for (int index = providers.size() - 1; index >= 0; index--) {
                JsonElement element = providers.get(index);
                if (!element.isJsonObject()) {
                    problem("font definitions contain non-object providers", normalized);
                    continue;
                }
                JsonObject provider = element.getAsJsonObject();
                String type = string(provider, "type");
                type = type.substring(type.indexOf(':') + 1);
                if (type.equals("bitmap")) {
                    try { readBitmap(normalized, provider, target); }
                    catch (IOException failure) {
                        problem("bitmap providers could not be decoded", normalized + '|' + provider,
                                normalized + " -> " + message(failure));
                    }
                }
                else if (type.equals("reference")) {
                    String reference = string(provider, "id");
                    if (!reference.isBlank()) readFont(reference, active, closure, target);
                }
                // Space and built-in providers retain Bedrock's native metrics.
            }
        } catch (IOException | RuntimeException failure) {
            problem("font definitions could not be parsed", normalized + " -> " + message(failure));
        } finally {
            active.remove(normalized);
        }
    }

    private void readBitmap(String font, JsonObject provider, Map<Integer, Glyph> target) throws IOException {
        String providerKey = font + '|' + provider;
        String file = string(provider, "file");
        JsonElement chars = provider.get("chars");
        if (file.isBlank() || chars == null || !chars.isJsonArray()) {
            problem("bitmap providers have invalid file/chars fields", providerKey, font);
            return;
        }
        String textureIdentifier = identifier(file, "minecraft");
        String texturePath = texturePath(textureIdentifier);
        List<ResourceIndex.Asset> textureAssets = resources.findAll(texturePath);
        BufferedImage image = null;
        IOException lastFailure = null;
        for (ResourceIndex.Asset textureAsset : textureAssets) {
            try {
                image = ImageIO.read(new ByteArrayInputStream(textureAsset.readBytes()));
                if (image != null) break;
                lastFailure = new IOException("not a valid PNG from " + textureAsset.source().provider());
            } catch (IOException failure) {
                lastFailure = new IOException(message(failure) + " from " + textureAsset.source().provider(), failure);
            }
        }
        if (image == null && textureIdentifier.startsWith("minecraft:") && vanillaAssets != null) {
            try {
                var vanilla = vanillaAssets.readTexture(texturePath);
                if (vanilla.isPresent()) {
                    image = ImageIO.read(new ByteArrayInputStream(vanilla.get()));
                    if (image == null) throw new IOException("cached vanilla entry is not a PNG");
                    vanillaFallbackTextures.add(texturePath);
                }
            } catch (IOException failure) {
                problem("verified vanilla texture cache could not resolve references", providerKey,
                        textureIdentifier + " -> " + message(failure));
            }
        }
        if (image == null && textureAssets.isEmpty()) {
            problem("bitmap textures are missing", providerKey, font + " -> " + textureIdentifier);
            return;
        }
        if (image == null && lastFailure != null) {
            throw new IOException(textureIdentifier + ": " + message(lastFailure), lastFailure);
        }
        if (image == null) {
            problem("bitmap textures are not valid PNG files", providerKey, textureIdentifier);
            return;
        }
        List<String> rows = new ArrayList<>();
        for (JsonElement row : chars.getAsJsonArray()) rows.add(row.getAsString());
        if (rows.isEmpty()) return;
        int columns = rows.getFirst().codePointCount(0, rows.getFirst().length());
        if (columns < 1 || rows.stream().anyMatch(row -> row.codePointCount(0, row.length()) != columns)) {
            problem("bitmap providers have inconsistent chars row widths", providerKey, font);
            return;
        }
        if (image.getWidth() % columns != 0 || image.getHeight() % rows.size() != 0) {
            problem("bitmap textures are not divisible by their glyph grids", providerKey, textureIdentifier);
            return;
        }
        int declaredHeight = integer(provider, "height", 8);
        int declaredAscent = integer(provider, "ascent", declaredHeight);
        if (declaredHeight < 1 || declaredAscent < 0 || declaredAscent > declaredHeight) {
            unsupportedBaselineProviders.add(providerKey);
            return;
        }
        int cellWidth = image.getWidth() / columns;
        int cellHeight = image.getHeight() / rows.size();
        for (int row = 0; row < rows.size(); row++) {
            int[] codePoints = rows.get(row).codePoints().toArray();
            for (int column = 0; column < codePoints.length; column++) {
                int codePoint = codePoints[column];
                if (codePoint == 0) continue;
                if (!isBmp(codePoint)) {
                    nonBmpGlyphs.add(font + " -> U+%X".formatted(codePoint));
                    continue;
                }
                target.put(codePoint, new Glyph(codePoint, image, column * cellWidth, row * cellHeight,
                        cellWidth, cellHeight, declaredHeight, declaredAscent));
            }
        }
    }

    private void mergeNamedFont(String font, Map<Integer, Glyph> contextual) {
        for (Map.Entry<Integer, Glyph> entry : contextual.entrySet()) {
            int codePoint = entry.getKey();
            Glyph candidate = entry.getValue();
            Glyph existing = glyphs.get(codePoint);
            if (existing != null && sameGlyph(existing, candidate)) continue;
            if (!isBmpPrivateUse(codePoint)) {
                problem("named font glyphs require contextual Bedrock remapping",
                        font + " -> U+%04X".formatted(codePoint));
                continue;
            }
            if (existing != null) {
                problem("named font glyphs conflict on Bedrock's global codepoint map",
                        font + " -> U+%04X".formatted(codePoint));
                continue;
            }
            glyphs.put(codePoint, candidate);
            namedGlyphs++;
        }
    }

    private void mergeDefaultFont(Map<Integer, Glyph> defaults) {
        for (Map.Entry<Integer, Glyph> entry : defaults.entrySet()) {
            if (!vanillaOverride && !isBmpPrivateUse(entry.getKey())) {
                problem("default font glyphs require vanilla-override",
                        "minecraft:default -> U+%04X".formatted(entry.getKey()));
                continue;
            }
            glyphs.put(entry.getKey(), entry.getValue());
        }
    }

    private static boolean sameGlyph(Glyph left, Glyph right) {
        if (left.width() != right.width() || left.height() != right.height() ||
                left.declaredHeight() != right.declaredHeight() || left.declaredAscent() != right.declaredAscent()) {
            return false;
        }
        for (int y = 0; y < left.height(); y++) {
            for (int x = 0; x < left.width(); x++) {
                if (left.image().getRGB(left.x() + x, left.y() + y) !=
                        right.image().getRGB(right.x() + x, right.y() + y)) return false;
            }
        }
        return true;
    }

    private static BufferedImage compose(List<Glyph> glyphs) {
        // Bedrock derives glyph metrics from the page cell size. Keeping every
        // page on the same 16px grid prevents a code point's apparent chat
        // height from changing merely because it shares (or does not share) a
        // page with a larger GUI glyph.
        int cellSize = BEDROCK_CELL_SIZE;
        BufferedImage page = new BufferedImage(cellSize * 16, cellSize * 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = page.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (Glyph glyph : glyphs) {
                double displayWidth = Math.max(1.0, glyph.width() * (glyph.declaredHeight() / (double) glyph.height()));
                double scale = Math.min(1.0, cellSize / Math.max(displayWidth, glyph.declaredHeight()));
                int width = Math.max(1, (int) Math.round(displayWidth * scale));
                int height = Math.max(1, (int) Math.round(glyph.declaredHeight() * scale));
                int slot = glyph.codePoint() & 0xFF;
                int x = (slot & 15) * cellSize + Math.max(0, (cellSize - width) / 2);
                int y = (slot >>> 4) * cellSize + Math.max(0, cellSize - height);
                graphics.drawImage(glyph.image(), x, y, x + width, y + height,
                        glyph.x(), glyph.y(), glyph.x() + glyph.width(), glyph.y() + glyph.height(), null);
            }
        } finally {
            graphics.dispose();
        }
        return page;
    }

    private static String fontPath(String identifier) {
        String[] split = identifier(identifier, "minecraft").split(":", 2);
        return "assets/" + split[0] + "/font/" + split[1] + ".json";
    }

    private static String fontIdentifier(String path) {
        if (!path.startsWith("assets/") || !path.endsWith(".json")) return null;
        int namespaceEnd = path.indexOf('/', "assets/".length());
        if (namespaceEnd < 0 || !path.startsWith("font/", namespaceEnd + 1)) return null;
        String namespace = path.substring("assets/".length(), namespaceEnd);
        String value = path.substring(namespaceEnd + "/font/".length(), path.length() - ".json".length());
        return namespace.isBlank() || value.isBlank() ? null : namespace + ':' + value;
    }

    private static String texturePath(String identifier) {
        String[] split = identifier.split(":", 2);
        String path = split[1];
        if (!path.endsWith(".png")) path += ".png";
        return "assets/" + split[0] + "/textures/" + path;
    }

    private static String identifier(String value, String defaultNamespace) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains(":") ? normalized : defaultNamespace + ':' + normalized;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try { return object.has(key) ? object.get(key).getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean isBmp(int codePoint) {
        return codePoint >= 0 && codePoint <= Character.MAX_VALUE &&
                (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE);
    }

    private static boolean isBmpPrivateUse(int codePoint) {
        return codePoint >= 0xE000 && codePoint <= 0xF8FF;
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private void problem(String category, String example) {
        problem(category, example, example);
    }

    private void problem(String category, String key, String example) {
        problemGroups.computeIfAbsent(category, ignored -> new ProblemBucket()).add(key, example);
    }

    private static final class ProblemBucket {
        private int count;
        private final Set<String> seen = new HashSet<>();
        private final List<String> examples = new ArrayList<>();

        void add(String key, String example) {
            if (!seen.add(key)) return;
            count++;
            if (examples.size() < 5 && !examples.contains(example)) examples.add(example);
        }

        String format(String category) {
            return count + " " + category + (examples.isEmpty() ? "" : "; examples: " + String.join(", ", examples));
        }
    }

    record Result(int glyphs, int pages, int vanillaFallbackTextures, int namedFonts, int namedGlyphs,
                  List<String> problems) {}
    private record Glyph(int codePoint, BufferedImage image, int x, int y, int width, int height,
                         int declaredHeight, int declaredAscent) {}
}
