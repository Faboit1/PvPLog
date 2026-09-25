package top.cheesesmp.duelcore.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * All player-facing text, from messages.yml, in MiniMessage. There is no global prefix. Theme colours are exposed
 * as style tags: {@code <accent> <text> <muted> <good> <bad>}, so the palette changes in one place.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> templates = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final Logger logger;
    private final TagResolver theme;

    public Messages(YamlConfiguration yml, Logger logger) {
        this.logger = logger;
        ConfigurationSection themeSection = yml.getConfigurationSection("theme");
        this.theme = TagResolver.resolver(
            style("accent", themeSection, "#f2c14e"),
            style("text", themeSection, "#e6e6e6"),
            style("muted", themeSection, "#8b9099"),
            style("good", themeSection, "#7fd99a"),
            style("bad", themeSection, "#ff7a7a"));
        flatten(yml, "");
    }

    private static TagResolver style(String name, ConfigurationSection section, String def) {
        String hex = section == null ? def : section.getString(name, def);
        TextColor color = TextColor.fromHexString(hex);
        return Placeholder.styling(name, color == null ? TextColor.fromHexString(def) : color);
    }

    private void flatten(ConfigurationSection section, String prefix) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (path.equals("theme")) continue;
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                flatten(child, path);
            } else if (value instanceof List<?> list) {
                templates.put(path, String.join("<newline>", list.stream().map(String::valueOf).toList()));
            } else if (value != null) {
                templates.put(path, String.valueOf(value));
            }
        }
    }

    public TagResolver theme() {
        return theme;
    }

    public boolean has(String key) {
        return templates.containsKey(key);
    }

    public String raw(String key) {
        String template = templates.get(key);
        if (template == null) {
            if (warned.add(key)) logger.warning("Missing message '" + key + "' in messages.yml");
            return "<bad>" + key;
        }
        return template;
    }

    /** Renders a message. Italic is always forced off so text looks the same in item names and lore. */
    public Component get(String key, TagResolver... resolvers) {
        return parse(raw(key), resolvers);
    }

    public Component parse(String miniMessage, TagResolver... resolvers) {
        TagResolver all = resolvers.length == 0 ? theme : TagResolver.resolver(theme, TagResolver.resolver(resolvers));
        return MM.deserialize(miniMessage, all).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Sends a chat message unless the template is empty (empty = disabled). */
    public void send(Audience audience, String key, TagResolver... resolvers) {
        String template = raw(key);
        if (template.isBlank()) return;
        audience.sendMessage(parse(template, resolvers));
    }

    public void actionBar(Audience audience, String key, TagResolver... resolvers) {
        String template = raw(key);
        if (template.isBlank()) return;
        audience.sendActionBar(parse(template, resolvers));
    }

    public static TagResolver text(String key, String value) {
        return Placeholder.unparsed(key, value);
    }

    public static TagResolver comp(String key, Component value) {
        return Placeholder.component(key, value);
    }

    public static TagResolver num(String key, Number value) {
        return Placeholder.unparsed(key, value instanceof Double || value instanceof Float
            ? String.format(java.util.Locale.ROOT, "%.1f", value.doubleValue())
            : String.valueOf(value));
    }
}
