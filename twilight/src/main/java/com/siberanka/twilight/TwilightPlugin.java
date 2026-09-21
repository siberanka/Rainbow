/*
 * Copyright (c) 2026 siberanka.
 * Licensed under the GNU LGPL v3.0 or later.
 */
package com.siberanka.twilight;

import com.siberanka.twilight.config.TwilightConfig;
import com.siberanka.twilight.compiler.BedrockPackCompiler;
import com.siberanka.twilight.compiler.BuildResult;
import com.siberanka.twilight.compiler.ConversionException;
import com.siberanka.twilight.api.TwilightApi;
import com.siberanka.twilight.api.event.TwilightBuildCompleteEvent;
import com.siberanka.twilight.api.event.TwilightDeployCompleteEvent;
import com.siberanka.twilight.api.event.TwilightOperationFailedEvent;
import com.siberanka.twilight.api.event.TwilightScanCompleteEvent;
import com.siberanka.twilight.deploy.DeploymentResult;
import com.siberanka.twilight.deploy.GeyserDeploymentService;
import com.siberanka.twilight.report.ContentReportWriter;
import com.siberanka.twilight.source.ContentInspector;
import com.siberanka.twilight.source.ContentReport;
import com.siberanka.twilight.source.ContentSource;
import com.siberanka.twilight.source.BukkitItemCollector;
import com.siberanka.twilight.source.CustomItemDescriptor;
import com.siberanka.twilight.source.SourceDiscovery;
import com.siberanka.twilight.source.SourceFingerprint;
import com.siberanka.twilight.source.WorldLayout;
import com.siberanka.twilight.logging.OperationLog;
import com.siberanka.twilight.integration.ProviderHookManager;
import com.siberanka.twilight.integration.ProviderCommandClassifier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class TwilightPlugin extends JavaPlugin {
    private final AtomicBoolean operationRunning = new AtomicBoolean();
    private final AtomicReference<ContentReport> lastReport = new AtomicReference<>();
    private final AtomicReference<String> lastSuccessfulInputFingerprint = new AtomicReference<>();
    private final AtomicBoolean automaticBuildPending = new AtomicBoolean();
    private ExecutorService worker;
    private ServerScheduler scheduler;
    private Path serverRoot;
    private TwilightConfig config;
    private GeyserDeploymentService deployment;
    private ProviderHookManager providerHooks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        serverRoot = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        scheduler = new ServerScheduler(this);
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Twilight-Worker");
            thread.setDaemon(true);
            return thread;
        });
        loadServices();

        TwilightCommand command = new TwilightCommand(this);
        var registered = getCommand("twilight");
        if (registered == null) throw new IllegalStateException("Twilight command is missing from plugin.yml");
        registered.setExecutor(command);
        registered.setTabCompleter(command);
        getServer().getServicesManager().register(TwilightApi.class, new Api(), this, ServicePriority.Normal);
        providerHooks = new ProviderHookManager(this, reason -> scheduleProviderBuild(reason, config.startupDelayTicks()));
        getServer().getPluginManager().registerEvents(new AutomationListener(), this);
        for (org.bukkit.plugin.Plugin installed : getServer().getPluginManager().getPlugins()) providerHooks.register(installed);

        getLogger().info("Twilight server-side content compiler enabled. Vanilla overrides: " + config.vanillaOverride());
        if (config.autoBuildOnStartup()) {
            scheduleBuild("startup", config.startupDelayTicks());
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (worker != null) worker.shutdownNow();
    }

    private void loadServices() {
        reloadConfig();
        config = TwilightConfig.read(getConfig(), serverRoot);
        deployment = new GeyserDeploymentService(serverRoot, getDataFolder().toPath(), config);
    }

    void scan(CommandSender sender, boolean buildRequested) {
        scan(sender, buildRequested, false);
    }

    private void scan(CommandSender sender, boolean buildRequested, boolean skipUnchanged) {
        if (!operationRunning.compareAndSet(false, true)) {
            send(sender, "Another Twilight operation is already running.");
            return;
        }
        String operation = buildRequested ? "convert" : "scan";
        OperationLog operationLog;
        try {
            operationLog = OperationLog.create(getDataFolder().toPath(), operation);
        } catch (Exception failure) {
            operationRunning.set(false);
            send(sender, "Could not create operation log: " + rootMessage(failure));
            return;
        }
        Set<Path> runtimeWorlds = Bukkit.getWorlds().stream().map(World::getWorldFolder).map(java.io.File::toPath)
                .collect(Collectors.toUnmodifiableSet());
        BukkitItemCollector.CollectionResult runtimeCollection = BukkitItemCollector.collect();
        List<CustomItemDescriptor> liveItems = runtimeCollection.items();
        String minecraftVersion = Bukkit.getMinecraftVersion();
        operationLog.info("runtime-items", liveItems.size());
        for (String issue : runtimeCollection.issues()) operationLog.warn("provider-api", issue);
        operationLog.info("runtime-worlds", runtimeWorlds);
        send(sender, (buildRequested ? "Build" : "Scan") + " started off the server thread.");
        CompletableFuture.supplyAsync(() -> {
            try {
                Set<Path> worlds = WorldLayout.discover(serverRoot, runtimeWorlds);
                operationLog.info("world-discovery", worlds);
                List<ContentSource> sources = new SourceDiscovery(serverRoot, config).discover(worlds);
                operationLog.info("source-discovery", sources);
                String inputFingerprint = SourceFingerprint.compute(sources, liveItems, config);
                operationLog.info("input-fingerprint", inputFingerprint);
                ContentReport report = new ContentInspector(config).inspect(sources);
                operationLog.info("content-report", report);
                ContentReportWriter.write(getDataFolder().toPath().resolve("reports/content-report.json"), report);
                lastReport.set(report);
                boolean unchanged = buildRequested && skipUnchanged &&
                        inputFingerprint.equals(lastSuccessfulInputFingerprint.get());
                if (unchanged) operationLog.info("build-skip", "normalized inputs are unchanged");
                BuildResult build = buildRequested && !unchanged
                        ? new BedrockPackCompiler(getDataFolder().toPath(), config, minecraftVersion).build(sources, liveItems)
                        : null;
                if (build != null) operationLog.info("build-result", build);
                DeploymentResult deployed = build != null && config.deployAfterBuild()
                        ? deployment.deploy(build.outputDirectory()) : null;
                if (deployed != null) {
                    operationLog.info("deploy-result", deployed);
                    reloadGeyser(operationLog);
                }
                return new ScanOutcome(report, build, deployed, inputFingerprint, unchanged);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, worker).whenComplete((outcome, failure) -> {
            operationRunning.set(false);
            if (failure != null) {
                Throwable root = rootCause(failure);
                if (root instanceof ConversionException conversion) {
                    for (String problem : conversion.problems()) operationLog.warn("conversion-problem", problem);
                }
                operationLog.failure("operation-failed", root);
                getLogger().log(Level.SEVERE, "Twilight content scan failed", failure);
                send(sender, "Twilight " + operation + " failed: " + rootMessage(failure) + ". Log: " + operationLog.path());
                scheduler.execute(() -> Bukkit.getPluginManager().callEvent(
                        new TwilightOperationFailedEvent(operation, operationLog.path(), root)));
            } else {
                ContentReport report = outcome.report();
                operationLog.info("operation-complete", "success");
                send(sender, "Scan passed: " + report.sources() + " sources, " + report.entries() + " files, " +
                        report.itemDefinitions() + " current items, " + report.legacyItemModels() + " legacy item roots, " +
                        report.bbmodels() + " bbmodels, " + report.biomeDefinitions() + " custom biomes.");
                if (outcome.build() != null) {
                    BuildResult build = outcome.build();
                    lastSuccessfulInputFingerprint.set(outcome.inputFingerprint());
                    send(sender, "Build passed: " + build.converted() + "/" + build.candidates() + " custom items, " +
                            build.threeDimensional() + " volumetric models, " + build.glyphs() + " glyphs/" +
                            build.fontPages() + " font pages, " + build.vanillaFallbackTextures() + " vanilla texture fallbacks, " +
                            build.namedFonts() + " named fonts/" + build.namedGlyphs() + " globally safe glyphs, " +
                            build.soundDefinitions() + " sound events/" + build.soundFiles() + " OGG files/" +
                            build.vanillaFallbackSounds() + " vanilla sound fallbacks, " +
                            build.problems().size() + " isolated problems, SHA-256 " + build.packSha256() + '.');
                }
                if (outcome.unchanged()) send(sender, "Automatic build skipped: normalized provider inputs are unchanged.");
                if (outcome.deployment() != null) send(sender, outcome.deployment().message());
                scheduler.execute(() -> {
                    Bukkit.getPluginManager().callEvent(new TwilightScanCompleteEvent(report));
                    if (outcome.build() != null) Bukkit.getPluginManager().callEvent(new TwilightBuildCompleteEvent(outcome.build()));
                    if (outcome.deployment() != null) Bukkit.getPluginManager().callEvent(new TwilightDeployCompleteEvent(outcome.deployment()));
                });
            }
            try { operationLog.close(); } catch (Exception closeFailure) { getLogger().log(Level.WARNING, "Could not close operation log", closeFailure); }
        });
    }

    void deploy(CommandSender sender) {
        runExclusive(sender, "Geyser deployment", log -> {
            DeploymentResult result = deployment.deploy(getDataFolder().toPath().resolve("build/current"));
            reloadGeyser(log);
            return result;
        });
    }

    void rollback(CommandSender sender, int index) {
        runExclusive(sender, "Geyser rollback", log -> {
            DeploymentResult result = deployment.rollback(index);
            reloadGeyser(log);
            return result;
        });
    }

    private void runExclusive(CommandSender sender, String label, Operation operation) {
        if (!operationRunning.compareAndSet(false, true)) {
            send(sender, "Another Twilight operation is already running.");
            return;
        }
        OperationLog operationLog;
        try { operationLog = OperationLog.create(getDataFolder().toPath(), label); }
        catch (Exception failure) {
            operationRunning.set(false);
            send(sender, "Could not create operation log: " + rootMessage(failure));
            return;
        }
        send(sender, label + " started.");
        CompletableFuture.supplyAsync(() -> {
            try { return operation.run(operationLog); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }, worker).whenComplete((result, failure) -> {
            operationRunning.set(false);
            if (failure != null) {
                Throwable root = rootCause(failure);
                operationLog.failure("operation-failed", root);
                getLogger().log(Level.SEVERE, label + " failed", failure);
                send(sender, label + " failed: " + rootMessage(failure) + ". Log: " + operationLog.path());
                scheduler.execute(() -> Bukkit.getPluginManager().callEvent(
                        new TwilightOperationFailedEvent(label, operationLog.path(), root)));
            } else {
                operationLog.info("operation-complete", result);
                send(sender, result.message());
                if (label.toLowerCase(java.util.Locale.ROOT).contains("deploy")) {
                    scheduler.execute(() -> Bukkit.getPluginManager().callEvent(new TwilightDeployCompleteEvent(result)));
                }
            }
            try { operationLog.close(); } catch (Exception closeFailure) { getLogger().log(Level.WARNING, "Could not close operation log", closeFailure); }
        });
    }

    void reloadTwilight(CommandSender sender) {
        if (operationRunning.get()) {
            send(sender, "Wait for the active Twilight operation before reloading.");
            return;
        }
        try (OperationLog log = OperationLog.create(getDataFolder().toPath(), "reload")) {
            loadServices();
            log.info("configuration", "reloaded; vanilla-override=" + config.vanillaOverride());
            send(sender, "Twilight configuration reloaded. Vanilla overrides: " + config.vanillaOverride());
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Twilight configuration reload failed", failure);
            send(sender, "Configuration reload failed: " + rootMessage(failure));
        }
    }

    void sendStatus(CommandSender sender) {
        ContentReport report = lastReport.get();
        send(sender, "Twilight " + getPluginMeta().getVersion() + " | operation=" +
                (operationRunning.get() ? "running" : "idle") + " | vanilla-override=" + config.vanillaOverride());
        if (report == null) send(sender, "No scan has completed since startup.");
        else send(sender, "Last scan " + report.scannedAt() + ": " + report.sources() + " sources, " +
                report.itemDefinitions() + " current items, " + report.bbmodels() + " bbmodels, " +
                report.biomeDefinitions() + " custom biomes.");
        String fingerprint = lastSuccessfulInputFingerprint.get();
        if (fingerprint != null) send(sender, "Last successful input fingerprint: " + fingerprint.substring(0, 16));
        try {
            send(sender, "Geyser=" + deployment.resolveGeyserDirectory() + " | snapshots=" + deployment.snapshots().size() + "/" + config.backupsToKeep());
        } catch (Exception unavailable) {
            send(sender, "Geyser unavailable: " + unavailable.getMessage());
        }
    }

    void send(CommandSender sender, String message) {
        scheduler.execute(() -> sender.sendMessage("§5[Twilight] §f" + message));
    }

    private void reloadGeyser(OperationLog operationLog) throws Exception {
        if (!config.reloadAfterDeploy()) {
            operationLog.info("geyser-reload", "disabled by configuration");
            return;
        }
        operationLog.info("geyser-reload", "dispatching console command: geyser reload");
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        scheduler.execute(() -> {
            try {
                result.complete(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "geyser reload"));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        boolean accepted = result.get(15, TimeUnit.SECONDS);
        if (!accepted) throw new IllegalStateException("Geyser rejected the reload command after deployment");
        operationLog.info("geyser-reload", "command accepted");
    }

    private void scheduleProviderBuild(String reason, long delayTicks) {
        if (!config.syncProviderChanges()) return;
        scheduleBuild(reason, delayTicks);
    }

    private void scheduleBuild(String reason, long delayTicks) {
        if (!automaticBuildPending.compareAndSet(false, true)) return;
        getLogger().info("Scheduled automatic Twilight conversion after " + reason);
        scheduler.delayed(() -> {
            if (operationRunning.get()) {
                automaticBuildPending.set(false);
                scheduleBuild(reason + " (deferred)", config.startupDelayTicks());
                return;
            }
            automaticBuildPending.set(false);
            scan(Bukkit.getConsoleSender(), true, true);
        }, delayTicks);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = rootCause(throwable);
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    @FunctionalInterface
    private interface Operation { DeploymentResult run(OperationLog log) throws Exception; }

    private record ScanOutcome(ContentReport report, BuildResult build, DeploymentResult deployment,
                               String inputFingerprint, boolean unchanged) {}

    private final class AutomationListener implements Listener {
        @EventHandler
        public void onServerLoaded(ServerLoadEvent event) {
            if (config.autoBuildOnStartup()) scheduleBuild("server loaded", config.startupDelayTicks());
        }

        @EventHandler
        public void onPluginEnabled(PluginEnableEvent event) {
            int hooks = providerHooks.register(event.getPlugin());
            if (hooks > 0) scheduleProviderBuild(event.getPlugin().getName() + " enabled", config.startupDelayTicks());
        }

        @EventHandler(ignoreCancelled = true)
        public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
            if (event.getPlayer().hasPermission("twilight.admin")) {
                observeProviderCommand(event.getMessage(), event.getPlayer().getName());
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onServerCommand(ServerCommandEvent event) {
            observeProviderCommand(event.getCommand(), "console");
        }

        private void observeProviderCommand(String command, String actor) {
            ProviderCommandClassifier.classify(command).ifPresent(mutation -> {
                org.bukkit.plugin.Plugin provider = getServer().getPluginManager().getPlugin(mutation.provider());
                if (provider == null || !provider.isEnabled()) return;
                getLogger().info("Observed content-changing provider command " + mutation + " from " + actor);
                String reason = "provider command " + mutation;
                long delay = config.providerCommandDelayTicks();
                scheduleProviderBuild(reason, delay);
                scheduler.delayed(() -> scheduleProviderBuild(reason + " settle check", 1L), delay * 4L);
            });
        }
    }

    private final class Api implements TwilightApi {
        @Override public boolean isOperationRunning() { return operationRunning.get(); }
        @Override public boolean requestScan() { return request(false); }
        @Override public boolean requestConvert() { return request(true); }
        @Override public boolean requestDeploy() {
            if (operationRunning.get()) return false;
            scheduler.execute(() -> deploy(Bukkit.getConsoleSender()));
            return true;
        }
        private boolean request(boolean build) {
            if (operationRunning.get()) return false;
            scheduler.execute(() -> scan(Bukkit.getConsoleSender(), build));
            return true;
        }
        @Override public java.util.Optional<ContentReport> lastContentReport() { return java.util.Optional.ofNullable(lastReport.get()); }
        @Override public Path dataDirectory() { return getDataFolder().toPath().toAbsolutePath().normalize(); }
    }
}
