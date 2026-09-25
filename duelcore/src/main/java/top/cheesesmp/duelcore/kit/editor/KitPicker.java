package top.cheesesmp.duelcore.kit.editor;

import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.ui.Icons;
import top.cheesesmp.duelcore.ui.dialog.DialogService;
import top.cheesesmp.duelcore.ui.dialog.OpenDialogs;

/**
 * The kit picker: every enabled kit, grouped by the queue menu's categories (with its tab icons and names), each
 * with its sprite and a marker when the player saved a layout of their own. Clicking a kit sends
 * {@code duelcore:kiteditor/open {kit:"<id>"}}.
 */
public final class KitPicker {

    private final DuelCorePlugin plugin;
    private final KitEditor editor;

    KitPicker(DuelCorePlugin plugin, KitEditor editor) {
        this.plugin = plugin;
        this.editor = editor;
    }

    /** Shows the picker (after loading the player's layouts, so the markers are right). */
    public void open(Player player) {
        String refusal = editor.refusal(player);
        if (refusal != null) {
            plugin.messages().send(player, refusal);
            return;
        }
        KitLayouts layouts = editor.layouts();
        if (!layouts.loaded(player.getUniqueId())) {
            long ticket = plugin.openDialogs().awaitNext(player); // an open dialog stays until the picker is loaded
            layouts.load(player).thenRun(() -> {
                if (player.isOnline() && layouts.loaded(player.getUniqueId())) {
                    plugin.openDialogs().continueAwait(player, ticket, () -> show(player));
                } else if (player.isOnline()) {
                    plugin.messages().send(player, "kit-editor.not-ready");
                    plugin.openDialogs().abandon(player, ticket);
                }
            });
            return;
        }
        show(player);
    }

    private void show(Player player) {
        // a match was found while the layouts loaded; or the editor is open (a dialog would hide it client-side
        // while the server still has it open)
        if (editor.refusal(player) != null || editor.editing(player.getUniqueId())) {
            plugin.openDialogs().abandon(player);
            return;
        }
        Messages msg = plugin.messages();
        KitEditorStyle style = plugin.gui().kitEditor;
        UUID uuid = player.getUniqueId();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(msg.get("kit-editor.picker.header"), style.pickerWidth()));
        boolean any = false;
        for (Kit.Category category : Kit.Category.values()) {
            List<Kit> kits = plugin.kits().enabled(category);
            if (kits.isEmpty()) continue;
            any = true;
            body.add(DialogBody.plainMessage(msg.get("kit-editor.picker.category",
                Messages.comp("icon", categoryIcon(player, category)),
                Messages.comp("name", msg.get("dialog.queue.tabs." + category.id()))), style.pickerWidth()));
            for (int i = 0; i < kits.size(); i += style.pickerColumns()) {
                List<Component> row = new ArrayList<>();
                for (Kit kit : kits.subList(i, Math.min(kits.size(), i + style.pickerColumns()))) row.add(chip(uuid, kit));
                body.add(DialogBody.plainMessage(Component.join(JoinConfiguration.separator(msg.get("kit-editor.picker.separator")), row),
                    style.pickerWidth()));
            }
        }
        body.add(DialogBody.plainMessage(msg.get(any ? "kit-editor.picker.footer" : "kit-editor.picker.no-kits"), style.pickerWidth()));
        plugin.openDialogs().show(player, OpenDialogs.Kind.KIT_PICKER, DialogService.dialog(msg.get("kit-editor.picker.title"),
            body, List.of(), DialogType.notice(plugin.dialogs().close())));
    }

    private Component categoryIcon(Player player, Kit.Category category) {
        String spec = plugin.gui().queueTabIcons.getOrDefault(category.id(), "");
        if (spec.equalsIgnoreCase("head")) return Icons.head(player.getUniqueId(), player.getName());
        if (spec.isBlank()) return Component.empty();
        return Icons.parse(spec);
    }

    private Component chip(UUID uuid, Kit kit) {
        Messages msg = plugin.messages();
        boolean custom = editor.layouts().custom(uuid, kit);
        return msg.get(custom ? "kit-editor.picker.kit-custom" : "kit-editor.picker.kit",
                Messages.comp("kit_icon", kit.sprite()), Messages.comp("kit", kit.displayName()))
            .hoverEvent(HoverEvent.showText(msg.get(custom ? "kit-editor.picker.kit-hover-custom" : "kit-editor.picker.kit-hover",
                Messages.comp("kit", kit.displayName()), Messages.text("description", kit.description()))))
            .clickEvent(editClick(kit));
    }

    /**
     * The click that opens a kit's editor: {@code duelcore:kiteditor/open {kit:"<id>"}} (kit ids are [a-z0-9_]). The
     * picker's kits and the queue menu's ✎ use it.
     */
    public static ClickEvent<?> editClick(Kit kit) {
        return ClickEvent.custom(Key.key(DialogService.NS, "kiteditor/open"),
            BinaryTagHolder.binaryTagHolder("{kit:\"" + kit.id() + "\"}"));
    }
}
