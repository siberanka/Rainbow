# Twilight 1.0.0-pre.2

Twilight is now a server-side Java-to-Bedrock custom-content compiler for
Geyser. This pre-release produces one `Twilight.jar` for Paper, Folia, and
Spigot servers; players do not install a client mod.

## Highlights

- Discovers authored and generated assets from ItemsAdder, CraftEngine, Nexo,
  Oraxen, ModelEngine, BetterModel, datapacks, and configured resource packs.
- Uses provider APIs and lifecycle events when available, with deterministic
  `contents`, `resources`, `data`, `cache`, and generated-pack fallbacks.
- Preserves Bedrock's native bow, crossbow, and fishing-rod behavior for exact
  texture-only recolours. Layered, transformed, animated, and volumetric Java
  items retain their model states, display transforms, and runtime animation.
- Selects handheld presentation from each resolved Java model's parent chain
  and preserves authored hand translation, rotation, and scale without
  implicit fitting, fixing custom axes that appeared in a guitar-like pose.
- Keeps chat emoji at a stable Bedrock height with fixed 16-pixel Unicode
  cells and independent bottom alignment, even beside oversized GUI glyphs.
- Converts supported Java models, bitmap fonts, and custom sounds into bounded,
  validated Bedrock resources and Geyser custom mappings.
- Builds and deploys packs transactionally, retains last-known-good snapshots,
  and rejects unsafe paths, malformed output, missing assets, and hash failures.

## Requirements

- Java 21 or newer
- Paper, Folia, or Spigot 1.21.4 or newer
- Geyser with custom content enabled

Install `Twilight.jar` in the server's `plugins` directory. Because this is a
pre-release, review the generated reports before enabling automatic deployment
on a production server.

`Twilight.jar.sha256` contains the checksum of the CI-built release artifact.
