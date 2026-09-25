package top.cheesesmp.duelcore.debug;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;

/**
 * What client a player uses: the Minecraft version (from ViaVersion when it is installed, looked up by reflection so
 * it stays optional) and the client brand ("vanilla", "fabric", "lunarclient:…").
 */
public final class ClientInfo {

    /** A client's version: the protocol number (-1 when unknown) and its name ("1.21.11"). */
    public record Version(int protocol, String name) {
    }

    private ClientInfo() {
    }

    /** The client's protocol version as ViaVersion reports it (-1 without ViaVersion). */
    public static int protocol(Player p) {
        Version v = viaVersion(p.getUniqueId());
        return v == null ? -1 : v.protocol();
    }

    /** The client's version; without ViaVersion every client speaks the server's version. */
    public static Version version(Player p) {
        Version v = viaVersion(p.getUniqueId());
        return v != null ? v : new Version(-1, Bukkit.getMinecraftVersion());
    }

    private static @Nullable Version viaVersion(UUID uuid) {
        try {
            // methods of the public API types (the implementation classes may not be accessible)
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Class<?> viaApi = Class.forName("com.viaversion.viaversion.api.ViaAPI");
            Class<?> protocolVersion = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            Object api = via.getMethod("getAPI").invoke(null);
            Object version = viaApi.getMethod("getPlayerProtocolVersion", UUID.class).invoke(api, uuid);
            if (version == null) return null;
            int protocol = (int) protocolVersion.getMethod("getVersion").invoke(version);
            String name = String.valueOf(protocolVersion.getMethod("getName").invoke(version));
            return new Version(protocol, name);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** "client 1.21.11 (protocol 774), brand vanilla". */
    public static String describe(Player p) {
        Version v = version(p);
        String brand = p.getClientBrandName();
        return "client " + v.name() + " (protocol " + (v.protocol() < 0 ? "of the server" : String.valueOf(v.protocol()))
            + "), brand " + (brand == null || brand.isBlank() ? "unknown" : brand);
    }

    /**
     * Logs "[join] name client … (protocol …), brand …". The brand arrives in a plugin message that may come after
     * the join, so without one it is logged once about two seconds later instead.
     */
    public static void logJoin(DuelCorePlugin plugin, Player player) {
        if (player.getClientBrandName() != null) {
            plugin.getLogger().info("[join] " + player.getName() + " " + describe(player));
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.getLogger().info("[join] " + player.getName() + " " + describe(player));
        }, 40L);
    }
}
