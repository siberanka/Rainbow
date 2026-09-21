# Changelog

All notable changes in Twilight are documented here.

## 1.0.0-pre.1 - 2026-09-21

### Server platform

- Added one server plugin for Paper, Folia, and Spigot with a Bukkit service
  API, operation events, commands, structured logs, and controlled Geyser
  reloads.
- Added deterministic provider discovery, lifecycle hooks, command
  synchronization, source fingerprints, and delayed settle checks for
  ItemsAdder, CraftEngine, Nexo, Oraxen, ModelEngine, and BetterModel.
- Added authored `contents` and `resources`, provider `data` and `cache`,
  datapack, configured source, and generated-pack fallback layers.

### Java-to-Bedrock conversion

- Added current and legacy item-definition handling, Geyser custom mappings,
  layered textures, Java cuboids, display transforms, equipment attachables,
  block states, creative metadata, and animated textures.
- Preserved Bedrock's native pose, pull, charge, cast, and line behavior for
  exact texture-only bow, crossbow, and fishing-rod recolours.
- Added runtime-selected Java state geometry for layered, transformed,
  animated, and volumetric weapons, including distinct crossbow arrow and
  rocket states.
- Added bitmap-font conversion with Unicode collision protection and layered
  custom-sound conversion with recursive references and OGG validation.
- Added hash-verified, version-matched Mojang model, texture, font, and sound
  fallback for explicitly referenced vanilla assets.

### Reliability

- Added strict publication validation, bounded resource names, safe path and
  archive handling, deterministic output, transactional deployment,
  last-known-good restoration, and bounded backups.
- Added focused source, font, and sound audits plus 28 automated tests for the
  current server compiler and deployment path.
- Added GitHub Actions and GitLab CI pipelines that build and test the same
  tagged source before publishing `Twilight.jar` and its SHA-256 checksum.
