package com.siberanka.twilight.source;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

public final class BukkitItemCollector {
    private static final int MAX_REPORTED_PROVIDER_ISSUES = 64;
    private static final List<ProviderProbe> PROVIDERS = List.of(
            new ProviderProbe("ItemsAdder", "dev.lone.itemsadder.api.CustomStack",
                    List.of("getNamespacedIdsInRegistry"), List.of("getInstance")),
            new ProviderProbe("CraftEngine", "net.momirealms.craftengine.bukkit.api.CraftEngineItems",
                    List.of("loadedItems"), List.of("byId")),
            new ProviderProbe("Oraxen", "io.th0rgal.oraxen.api.OraxenItems",
                    List.of("getItemNames"), List.of("getItemById")),
            new ProviderProbe("Nexo", "com.nexomc.nexo.api.NexoItems",
                    List.of("itemNames", "getItemNames"), List.of("itemFromId", "getItemById"))
    );

    private BukkitItemCollector() {}

    public static CollectionResult collect() {
        Map<String, CustomItemDescriptor> descriptors = new LinkedHashMap<>();
        Set<String> issues = new LinkedHashSet<>();
        var recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) add("recipe", recipes.next().getResult(), descriptors);
        Bukkit.getOnlinePlayers().forEach(player -> {
            for (ItemStack stack : player.getInventory().getContents()) add("inventory", stack, descriptors);
        });
        for (ProviderProbe probe : PROVIDERS) probe.collect(descriptors, issues);
        return new CollectionResult(List.copyOf(descriptors.values()), List.copyOf(issues));
    }

    private static void add(String provider, Object possibleStack, Map<String, CustomItemDescriptor> output) {
        ItemStack stack = unwrapStack(possibleStack);
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return;
        ItemMeta meta = stack.getItemMeta();
        Optional<String> model = itemModel(meta);
        OptionalInt customModelData = meta.hasCustomModelData()
                ? OptionalInt.of(meta.getCustomModelData()) : OptionalInt.empty();
        if (model.isEmpty() && customModelData.isEmpty()) return;
        String base = stack.getType().getKey().toString();
        String display = legacyDisplayName(meta, base);
        String key = base + '|' + model.orElse("") + '|' + (customModelData.isPresent() ? customModelData.getAsInt() : "");
        output.putIfAbsent(key, new CustomItemDescriptor(provider, base, model, customModelData, display));
    }

    private static Optional<String> itemModel(ItemMeta meta) {
        try {
            Method method = meta.getClass().getMethod("getItemModel");
            Object value = method.invoke(meta);
            if (value instanceof NamespacedKey key) return Optional.of(key.toString());
            if (value != null) return Optional.of(value.toString());
        } catch (ReflectiveOperationException ignored) {
            // Older servers expose only legacy custom model data.
        }
        return Optional.empty();
    }

    @SuppressWarnings("deprecation") // Kept as the cross-platform Spigot/Paper string representation.
    private static String legacyDisplayName(ItemMeta meta, String fallback) {
        return meta.hasDisplayName() ? meta.getDisplayName() : fallback;
    }

    private static ItemStack unwrapStack(Object value) {
        if (value instanceof ItemStack stack) return stack;
        if (value == null) return null;
        for (String methodName : List.of("getItemStack", "build", "buildBukkitItem")) {
            try {
                Method method = value.getClass().getMethod(methodName);
                Object result = method.invoke(value);
                if (result instanceof ItemStack stack) return stack;
            } catch (ReflectiveOperationException ignored) {
                // Try the next stable provider shape.
            }
        }
        return null;
    }

    private static void issue(Set<String> issues, String message) {
        if (issues.size() < MAX_REPORTED_PROVIDER_ISSUES) {
            issues.add(message);
        } else if (issues.size() == MAX_REPORTED_PROVIDER_ISSUES) {
            issues.add("Additional provider API issues were suppressed after " + MAX_REPORTED_PROVIDER_ISSUES + " entries.");
        }
    }

    private static String describe(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public record CollectionResult(List<CustomItemDescriptor> items, List<String> issues) {
        public CollectionResult {
            items = List.copyOf(items);
            issues = List.copyOf(issues);
        }
    }

    private record ProviderProbe(String name, String className, List<String> registryMethods, List<String> itemMethods) {
        void collect(Map<String, CustomItemDescriptor> output, Set<String> issues) {
            Plugin provider = Bukkit.getPluginManager().getPlugin(name);
            if (provider == null || !provider.isEnabled()) return;
            try {
                Class<?> api = Class.forName(className, false, provider.getClass().getClassLoader());
                Method registry = findStaticMethod(api, registryMethods);
                if (registry == null) {
                    issue(issues, name + " API mismatch: no supported item registry method on " + className);
                    return;
                }
                Object registryValue = registry.invoke(null);
                List<?> ids = ProviderRegistryEntries.ids(registryValue).orElse(null);
                if (ids == null) {
                    issue(issues, name + " API mismatch: " + registry.getName() +
                            " did not return a map, collection, iterable, iterator, stream, or array");
                    return;
                }
                Method itemMethod = findStaticMethod(api, itemMethods, String.class);
                if (itemMethod == null) {
                    issue(issues, name + " API mismatch: no supported item lookup method on " + className);
                    return;
                }
                for (Object id : ids) {
                    try {
                        Object providerItem = itemMethod.invoke(null, String.valueOf(id));
                        ItemStack stack = unwrapStack(providerItem);
                        if (providerItem != null && stack == null) {
                            issue(issues, name + " item " + id + " returned unsupported type " + providerItem.getClass().getName());
                        } else {
                            add(name, stack, output);
                        }
                    } catch (ReflectiveOperationException | LinkageError failure) {
                        issue(issues, name + " item " + id + " could not be read: " + describe(failure));
                    }
                }
            } catch (ClassNotFoundException failure) {
                issue(issues, name + " is enabled but its public API class is unavailable: " + className);
            } catch (ReflectiveOperationException | LinkageError failure) {
                issue(issues, name + " public API probe failed: " + describe(failure));
            }
        }

        private static Method findStaticMethod(Class<?> api, List<String> candidates, Class<?>... arguments) {
            for (String candidate : candidates) {
                try {
                    Method method = api.getMethod(candidate, arguments);
                    if (Modifier.isStatic(method.getModifiers())) return method;
                } catch (NoSuchMethodException ignored) { }
            }
            return null;
        }
    }
}
