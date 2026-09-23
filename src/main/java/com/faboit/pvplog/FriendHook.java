package com.faboit.pvplog;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Optional FriendSystem integration. Talks to FriendSystem's API through the service
 * manager at runtime, so PvPLog has no build-time dependency on it and works fine
 * when it isn't installed.
 */
final class FriendHook {

    private static final String API_CLASS = "com.faboit.friendsystem.api.FriendSystemAPI";

    private final PvPLogPlugin plugin;
    private volatile Class<?> apiClass;
    private volatile MethodHandle areFriends;
    private volatile boolean warned;

    FriendHook(PvPLogPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isAvailable() {
        return api() != null;
    }

    /** Whether the two players are friends. Safe from any thread (FriendSystem queries are in-memory). */
    boolean areFriends(Player a, Player b) {
        Object api = api();
        if (api == null) return false;
        try {
            return (boolean) areFriends.invoke(api, a.getUniqueId(), b.getUniqueId());
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("FriendSystem friend check failed: " + t);
            }
            return false;
        }
    }

    /** Current FriendSystem API instance, or null. Looked up each call so reloads never leave a stale instance. */
    private Object api() {
        Class<?> cls = apiClass;
        if (cls != null) {
            Object provider = provider(cls);
            if (provider != null) return provider;
            apiClass = null; // FriendSystem disabled or reloaded with a new class loader
            areFriends = null;
        }
        for (Class<?> known : Bukkit.getServicesManager().getKnownServices()) {
            if (!known.getName().equals(API_CLASS)) continue;
            Object provider = provider(known);
            if (provider == null) continue;
            try {
                areFriends = MethodHandles.publicLookup().findVirtual(known, "areFriends",
                        MethodType.methodType(boolean.class, UUID.class, UUID.class));
                apiClass = known;
                return provider;
            } catch (ReflectiveOperationException e) {
                if (!warned) {
                    warned = true;
                    plugin.getLogger().warning("Unsupported FriendSystem version: " + e);
                }
            }
        }
        return null;
    }

    private static Object provider(Class<?> cls) {
        RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(cls);
        if (rsp == null || !rsp.getPlugin().isEnabled()) return null;
        return rsp.getProvider();
    }
}
