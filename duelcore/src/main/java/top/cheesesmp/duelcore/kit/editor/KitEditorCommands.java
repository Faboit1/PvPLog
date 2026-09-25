package top.cheesesmp.duelcore.kit.editor;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.command.CommandService;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;

/** {@code /kit [edit [kit]]} and {@code /kiteditor [kit]}: the kit picker, or the editor of one kit. */
public final class KitEditorCommands {

    private final DuelCorePlugin plugin;

    public KitEditorCommands(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        commands.register(Commands.literal("kit").requires(CommandService.perm(KitEditor.PERMISSION))
            .executes(this::picker)
            .then(Commands.literal("edit").executes(this::picker).then(kitArgument()))
            .build(), "Edit where your kit items go", List.of());
        commands.register(Commands.literal("kiteditor").requires(CommandService.perm(KitEditor.PERMISSION))
            .executes(this::picker)
            .then(kitArgument())
            .build(), "Edit where your kit items go", List.of());
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> kitArgument() {
        return Commands.argument("kit", StringArgumentType.word())
            .suggests(plugin.commands().kitSuggestions())
            .executes(ctx -> {
                Player player = player(ctx);
                if (player == null) return 0;
                String id = StringArgumentType.getString(ctx, "kit");
                Kit kit = plugin.kits().get(id);
                if (kit == null || !kit.enabled()) {
                    plugin.messages().send(player, "command.unknown-kit", Messages.text("kit", id));
                    return 0;
                }
                plugin.kitEditor().open(player, kit);
                return Command.SINGLE_SUCCESS;
            });
    }

    private int picker(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return 0;
        plugin.kitEditor().openPicker(player);
        return Command.SINGLE_SUCCESS;
    }

    private static @Nullable Player player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player p) return p;
        if (ctx.getSource().getSender() instanceof Player p) return p;
        ctx.getSource().getSender().sendMessage("This command needs a player.");
        return null;
    }
}
