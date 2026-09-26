package top.cheesesmp.duelcore.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.profile.DuelRequests;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.ui.Icons;
import top.cheesesmp.duelcore.ui.MenuSound;
import top.cheesesmp.duelcore.ui.dialog.SettingsLayout.Entry;
import top.cheesesmp.duelcore.ui.dialog.SettingsLayout.Section;

/**
 * The settings menu (Settings hub item, /settings): a notice dialog laid out like the queue menu, with section tabs
 * (Gameplay, Visuals, Sounds, Social, Queue, see {@link SettingsLayout}) and one row per setting: its icon (gui.yml
 * {@code settings-menu.icons}), name and state, the description in its hover. Clicking a row changes it at once
 * (saved, and whatever it controls refreshed) and shows the menu again with the new state: it stays on screen
 * meanwhile (after-action NONE), like switching tabs. The duel request row cycles Everyone / Friends only / Nobody,
 * the region row lists the regions as choices, max ping has − and + steps, and the country opens a small dialog
 * with a text box (Save or Back return to the Queue tab). Every click is a text click event
 * {@code duelcore:settings/<action>} (routed here through the "settings" prefix of {@link ClickRouter}); payloads
 * are re-validated. While open, the menu is refreshed by {@link OpenDialogs} (e.g. Keep Queuing changed in the queue
 * menu). Texts: messages.yml {@code dialog.settings}.
 */
public final class SettingsDialog {

    private final DuelCorePlugin plugin;

    SettingsDialog(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    // ------------------------------------------------------------------ open

    /** Opens the menu on {@code section} (the first one when null). */
    public void open(Player player, @Nullable Section section) {
        Section shown = section == null ? Section.GAMEPLAY : section;
        OpenDialogs.Rendered first = build(player, shown);
        if (first == null) return;
        plugin.openDialogs().show(player, OpenDialogs.Kind.SETTINGS, first, p -> build(p, shown));
    }

    private OpenDialogs.@Nullable Rendered build(Player player, Section section) {
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile == null) return null;
        int width = plugin.gui().settingsWidth;
        Fingerprint fp = new Fingerprint();
        List<DialogBody> body = new ArrayList<>();
        body.add(plain(tabRow(section), width, fp));
        body.add(plain(msg().get("dialog.settings.section-body." + section.id()), width, fp));
        for (Entry e : section.entries()) body.add(plain(row(profile, section, e), width, fp));
        body.add(plain(msg().get("dialog.settings.footer"), width, fp));
        ActionButton close = plugin.dialogs().close();
        Component title = msg().get("dialog.settings.title", Messages.comp("section", msg().get("dialog.settings.sections." + section.id())));
        fp.add(title).add(close);
        Dialog dialog = Dialog.create(f -> f.empty()
            .base(DialogBase.builder(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body)
                .build())
            .type(DialogType.notice(close)));
        return new OpenDialogs.Rendered(dialog, fp.value());
    }

    private static DialogBody plain(Component text, int width, Fingerprint fp) {
        fp.add(text).add(width);
        return DialogBody.plainMessage(text, width);
    }

    // ------------------------------------------------------------------ rows

    private Component tabRow(Section selected) {
        List<Component> parts = new ArrayList<>();
        for (Section s : Section.values()) {
            Component label = msg().get(s == selected ? "dialog.settings.tab-selected" : "dialog.settings.tab",
                Messages.comp("icon", icon("section-" + s.id())), Messages.comp("name", msg().get("dialog.settings.sections." + s.id())));
            parts.add(label.hoverEvent(HoverEvent.showText(msg().get("dialog.settings.section-hover." + s.id())))
                .clickEvent(action("tab", "section", s.id())));
        }
        return Component.join(JoinConfiguration.separator(msg().get("dialog.settings.tab-separator")), parts);
    }

    private Component row(PlayerProfile profile, Section section, Entry e) {
        return switch (e.kind()) {
            case TOGGLE -> {
                boolean on = profile.setting(Objects.requireNonNull(e.setting()));
                yield line(e, msg().get(on ? "dialog.settings.state-on" : "dialog.settings.state-off"),
                    msg().get(on ? "dialog.settings.click-off" : "dialog.settings.click-on"))
                    .clickEvent(action("toggle", "id", e.id(), "section", section.id()));
            }
            case DUEL_REQUESTS -> {
                DuelRequests who = profile.duelRequests();
                yield line(e, msg().get("dialog.settings.duel." + who.id()),
                    msg().get("dialog.settings.click-cycle", Messages.comp("next", msg().get("dialog.settings.duel." + who.next().id()))))
                    .clickEvent(action("duel", "section", section.id()));
            }
            case REGION -> line(e, regionChoices(profile, section), msg().get("dialog.settings.click-choice"));
            case MAX_PING -> line(e, pingControl(profile.maxPing(), section), msg().get("dialog.settings.click-steps"));
            case COUNTRY -> line(e, msg().get(profile.country() == null ? "dialog.settings.country-none" : "dialog.settings.country-value",
                    Messages.text("country", profile.country() == null ? "" : profile.country()))
                    .append(Component.space())
                    .append(msg().get("dialog.settings.country-change")
                        .hoverEvent(HoverEvent.showText(msg().get("dialog.settings.country-change-hover")))
                        .clickEvent(action("country"))),
                msg().get("dialog.settings.click-country"));
        };
    }

    /** "icon Name  state" with the description (and what a click does) in the hover. */
    private Component line(Entry e, Component state, Component clickHint) {
        Component name = msg().get("dialog.settings.items." + e.id() + ".name");
        Component hover = msg().get("dialog.settings.row-hover", Messages.comp("name", name),
            Messages.comp("description", msg().get("dialog.settings.items." + e.id() + ".hover")), Messages.comp("action", clickHint));
        return msg().get("dialog.settings.row", Messages.comp("icon", icon(e.id())), Messages.comp("name", name),
            Messages.comp("state", state)).hoverEvent(HoverEvent.showText(hover));
    }

    private Component regionChoices(PlayerProfile profile, Section section) {
        List<Component> parts = new ArrayList<>();
        parts.add(choice(msg().get("dialog.settings.region-none"), profile.region() == null, "", section));
        for (String r : plugin.settings().regions) parts.add(choice(Component.text(r), r.equals(profile.region()), r, section));
        return Component.join(JoinConfiguration.separator(Component.space()), parts);
    }

    private Component choice(Component name, boolean selected, String region, Section section) {
        return msg().get(selected ? "dialog.settings.choice-selected" : "dialog.settings.choice", Messages.comp("name", name))
            .hoverEvent(HoverEvent.showText(msg().get("dialog.settings.choice-hover", Messages.comp("name", name))))
            .clickEvent(action("region", "region", region, "section", section.id()));
    }

    private Component pingControl(int ping, Section section) {
        int down = SettingsLayout.stepPing(ping, -1);
        int up = SettingsLayout.stepPing(ping, 1);
        return msg().get("dialog.settings.ping-down")
            .hoverEvent(HoverEvent.showText(msg().get("dialog.settings.ping-down-hover", Messages.comp("value", ping(down)))))
            .clickEvent(action("ping", "dir", "-1", "section", section.id()))
            .append(Component.space()).append(ping(ping)).append(Component.space())
            .append(msg().get("dialog.settings.ping-up")
                .hoverEvent(HoverEvent.showText(msg().get("dialog.settings.ping-up-hover", Messages.comp("value", ping(up)))))
                .clickEvent(action("ping", "dir", "1", "section", section.id())));
    }

    private Component ping(int ms) {
        return ms <= 0 ? msg().get("dialog.settings.ping-any") : msg().get("dialog.settings.ping-value", Messages.num("ms", ms));
    }

    /** The gui.yml icon of a row or tab ("section-<id>"), nothing when not set. */
    private Component icon(String id) {
        String spec = plugin.gui().settingsIcons.getOrDefault(id, "");
        return spec.isBlank() ? Component.empty() : Icons.parse(spec);
    }

    private static ClickEvent<?> action(String action, String... kv) {
        Map<String, String> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) payload.put(kv[i], kv[i + 1]);
        return ClickEvent.custom(Key.key(DialogService.NS, "settings/" + action),
            BinaryTagHolder.binaryTagHolder(DialogService.snbt(payload)));
    }

    // ------------------------------------------------------------------ country

    private void country(Player player, PlayerProfile profile) {
        ActionButton save = plugin.dialogs().button(msg().get("dialog.settings.country-save"), null, 150, "settings/country-save", Map.of());
        ActionButton back = plugin.dialogs().button(msg().get("dialog.settings.back"), null, 150, "settings/back", Map.of());
        Dialog d = DialogService.dialog(msg().get("dialog.settings.country-title"),
            List.of(plugin.dialogs().text(msg().get("dialog.settings.country-body"))),
            List.of(DialogInput.text("country", msg().get("dialog.settings.country-label")).width(200)
                .initial(profile.country() == null ? "" : profile.country()).maxLength(2).build()),
            DialogType.confirmation(save, back));
        plugin.openDialogs().show(player, OpenDialogs.Kind.SETTINGS, d);
    }

    // ------------------------------------------------------------------ clicks

    /** Every {@code duelcore:settings/*} click. {@code data} is untrusted client input. */
    public void click(Player player, String action, Map<String, String> data, @Nullable DialogResponseView view) {
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile == null) return;
        Section section = Section.parse(data.get("section"));
        switch (action) {
            case "settings/open", "settings/tab" -> open(player, section);
            case "settings/toggle" -> {
                Entry e = SettingsLayout.find(data.get("id"));
                if (e == null || e.kind() != SettingsLayout.Kind.TOGGLE || e.setting() == null) return;
                boolean on = !profile.setting(e.setting());
                profile.setting(e.setting(), on);
                changed(player, profile, on);
                open(player, section);
            }
            case "settings/duel" -> {
                profile.duelRequests(profile.duelRequests().next());
                changed(player, profile, profile.duelRequests() != DuelRequests.NOBODY);
                open(player, section);
            }
            case "settings/region" -> {
                String region = data.getOrDefault("region", "").toUpperCase(Locale.ROOT);
                profile.region(plugin.settings().regions.contains(region) ? region : null);
                changed(player, profile, true);
                open(player, section);
            }
            case "settings/ping" -> {
                int dir = "-1".equals(data.get("dir")) ? -1 : 1;
                profile.maxPing(SettingsLayout.stepPing(profile.maxPing(), dir));
                changed(player, profile, dir > 0);
                open(player, section);
            }
            case "settings/country" -> country(player, profile);
            case "settings/country-save" -> {
                String raw = view == null ? null : view.getText("country");
                if (raw != null) {
                    String cc = raw.trim().toUpperCase(Locale.ROOT);
                    profile.country(cc.matches("[A-Z]{2}") ? cc : null);
                    changed(player, profile, true);
                }
                open(player, Section.QUEUE);
            }
            case "settings/back" -> open(player, Section.QUEUE);
            default -> {
            }
        }
    }

    /**
     * Saves the settings and refreshes everything a setting controls right away (sidebar, hub visibility, queue
     * music, the hotbar hint, the searching bar), with the toggle on / off menu sound ({@code up}: on, more).
     */
    private void changed(Player player, PlayerProfile profile, boolean up) {
        plugin.profiles().saveSettings(profile);
        plugin.sidebar().refresh(player);
        plugin.visibility().refresh(player);
        plugin.queueMusic().refresh(player);
        plugin.hints().refresh(player);
        if (!profile.setting(Setting.SEARCHING_BAR)) plugin.queue().searching().stop(player.getUniqueId());
        plugin.menuSounds().play(player, MenuSound.toggle(up));
    }
}
