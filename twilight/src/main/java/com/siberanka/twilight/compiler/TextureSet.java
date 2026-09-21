package com.siberanka.twilight.compiler;

import com.siberanka.twilight.source.ResourceIndex;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TextureSet {
    private final BufferedImage image;
    private final Map<String, Region> regions;

    private TextureSet(BufferedImage image, Map<String, Region> regions) {
        this.image = image;
        this.regions = regions;
    }

    static TextureSet atlas(ResourceIndex resources, List<String> identifiers) throws IOException {
        Map<String, BufferedImage> images = new LinkedHashMap<>();
        for (String identifier : identifiers) {
            if (images.containsKey(identifier)) continue;
            ResourceIndex.Asset asset = resources.find(JavaModelResolver.texturePath(identifier))
                    .orElseThrow(() -> new IOException("Missing texture " + identifier));
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(asset.readBytes()));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) throw new IOException("Invalid PNG " + identifier);
            images.put(identifier, image);
        }
        if (images.isEmpty()) throw new IOException("Model has no resolved textures");
        int width = images.values().stream().mapToInt(BufferedImage::getWidth).sum();
        int height = images.values().stream().mapToInt(BufferedImage::getHeight).max().orElseThrow();
        BufferedImage atlas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        Map<String, Region> regions = new LinkedHashMap<>();
        try {
            graphics.setComposite(AlphaComposite.Src);
            int x = 0;
            for (Map.Entry<String, BufferedImage> entry : images.entrySet()) {
                BufferedImage source = entry.getValue();
                graphics.drawImage(source, x, 0, null);
                regions.put(entry.getKey(), new Region(x, 0, source.getWidth(), source.getHeight()));
                x += source.getWidth();
            }
        } finally { graphics.dispose(); }
        return new TextureSet(atlas, Map.copyOf(regions));
    }

    static BufferedImage layeredIcon(ResourceIndex resources, List<String> identifiers) throws IOException {
        List<BufferedImage> layers = new ArrayList<>();
        int width = 0, height = 0;
        for (String identifier : identifiers) {
            ResourceIndex.Asset asset = resources.find(JavaModelResolver.texturePath(identifier))
                    .orElseThrow(() -> new IOException("Missing texture " + identifier));
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(asset.readBytes()));
            if (original == null) throw new IOException("Invalid PNG " + identifier);
            int frameHeight = original.getHeight() > original.getWidth() && original.getHeight() % original.getWidth() == 0
                    ? original.getWidth() : original.getHeight();
            BufferedImage frame = original.getSubimage(0, 0, original.getWidth(), frameHeight);
            layers.add(frame);
            width = Math.max(width, frame.getWidth());
            height = Math.max(height, frame.getHeight());
        }
        if (layers.isEmpty()) throw new IOException("Flat item has no texture layers");
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (BufferedImage layer : layers) graphics.drawImage(layer, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return result;
    }

    byte[] png() throws IOException { return png(image); }
    int width() { return image.getWidth(); }
    int height() { return image.getHeight(); }
    Region region(String identifier) throws IOException {
        Region region = regions.get(identifier);
        if (region == null) throw new IOException("Texture is outside atlas: " + identifier);
        return region;
    }

    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", output)) throw new IOException("PNG encoder is unavailable");
        return output.toByteArray();
    }

    record Region(int x, int y, int width, int height) {}
}
