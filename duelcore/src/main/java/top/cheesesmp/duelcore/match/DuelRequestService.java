package top.cheesesmp.duelcore.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/** Unranked challenges: /duel &lt;player&gt; &lt;kit&gt;, accept or deny within 60 seconds. */
public final class DuelRequestService implements Listener, Runnable {

    public enum Result { SENT, SELF, OFFLINE, DISABLED_BY_TARGET, BUSY, TARGET_BUSY, ALREADY_SENT, NO_REQUEST, ACCEPTED }

    public record Request(UUID from, String fromName, UUID to, String kit, long expires) {
    }

    private static final long LIFETIME = 60_000;

    private final DuelCorePlugin plugin;
    /** Keyed by target. */
    private final Map<UUID, List<Request>> incoming = new HashMap<>();

    public DuelRequestService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    public Result send(Player from, Player to, Kit kit) {
        if (from.equals(to)) return Result.SELF;
        if (plugin.matches().match(from.getUniqueId()) != null) return Result.BUSY;
        if (plugin.matches().match(to.getUniqueId()) != null) return Result.TARGET_BUSY;
        PlayerProfile target = plugin.profiles().get(to);
        if (target != null && !target.setting(Setting.DUEL_REQUESTS)) return Result.DISABLED_BY_TARGET;
        List<Request> list = incoming.computeIfAbsent(to.getUniqueId(), k -> new ArrayList<>());
        for (Request r : list) if (r.from().equals(from.getUniqueId())) return Result.ALREADY_SENT;
        list.add(new Request(from.getUniqueId(), from.getName(), to.getUniqueId(), kit.id(), System.currentTimeMillis() + LIFETIME));
        String payload = "{from:\"" + from.getUniqueId() + "\"}";
        Component accept = plugin.messages().get("duel.accept-button")
            .clickEvent(ClickEvent.custom(Key.key("duelcore", "duel/accept"), BinaryTagHolder.binaryTagHolder(payload)));
        Component deny = plugin.messages().get("duel.deny-button")
            .clickEvent(ClickEvent.custom(Key.key("duelcore", "duel/deny"), BinaryTagHolder.binaryTagHolder(payload)));
        plugin.messages().send(to, "duel.received", Messages.text("player", from.getName()),
            Messages.comp("kit", kit.displayName()), Messages.comp("kit_icon", kit.sprite()),
            Messages.comp("accept", accept), Messages.comp("deny", deny));
        plugin.messages().send(from, "duel.sent", Messages.text("player", to.getName()), Messages.comp("kit", kit.displayName()),
            Messages.comp("kit_icon", kit.sprite()));
        return Result.SENT;
    }

    public Result accept(Player to, UUID from) {
        Request r = take(to.getUniqueId(), from);
        if (r == null) return Result.NO_REQUEST;
        Player challenger = Bukkit.getPlayer(from);
        Kit kit = plugin.kits().get(r.kit());
        if (challenger == null || kit == null) return Result.OFFLINE;
        if (plugin.matches().match(from) != null) return Result.TARGET_BUSY;
        if (plugin.matches().match(to.getUniqueId()) != null) return Result.BUSY;
        plugin.matches().create(List.of(List.of(challenger), List.of(to)), kit, false, Match.Origin.DUEL);
        return Result.ACCEPTED;
    }

    public Result deny(Player to, UUID from) {
        Request r = take(to.getUniqueId(), from);
        if (r == null) return Result.NO_REQUEST;
        Player challenger = Bukkit.getPlayer(from);
        if (challenger != null) plugin.messages().send(challenger, "duel.denied", Messages.text("player", to.getName()));
        return Result.SENT;
    }

    /** Latest request from a named player, or the newest request if name is null. */
    public @Nullable Request find(UUID to, @Nullable String fromName) {
        List<Request> list = incoming.get(to);
        if (list == null || list.isEmpty()) return null;
        if (fromName == null) return list.getLast();
        for (Request r : list) if (r.fromName().equalsIgnoreCase(fromName)) return r;
        return null;
    }

    private @Nullable Request take(UUID to, UUID from) {
        List<Request> list = incoming.get(to);
        if (list == null) return null;
        for (Iterator<Request> it = list.iterator(); it.hasNext(); ) {
            Request r = it.next();
            if (r.from().equals(from)) {
                it.remove();
                if (list.isEmpty()) incoming.remove(to);
                return r.expires() < System.currentTimeMillis() ? null : r;
            }
        }
        return null;
    }

    /** Drops every request from or to this player. */
    public void clear(UUID uuid) {
        incoming.remove(uuid);
        for (Iterator<List<Request>> it = incoming.values().iterator(); it.hasNext(); ) {
            List<Request> list = it.next();
            list.removeIf(r -> r.from().equals(uuid));
            if (list.isEmpty()) it.remove();
        }
    }

    public int pending() {
        int n = 0;
        for (List<Request> l : incoming.values()) n += l.size();
        return n;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    /** Expires old requests (runs every second). */
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, List<Request>>> it = incoming.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, List<Request>> e = it.next();
            e.getValue().removeIf(r -> {
                if (r.expires() >= now) return false;
                Player from = Bukkit.getPlayer(r.from());
                if (from != null) plugin.messages().send(from, "duel.expired");
                return true;
            });
            if (e.getValue().isEmpty()) it.remove();
        }
    }
}
