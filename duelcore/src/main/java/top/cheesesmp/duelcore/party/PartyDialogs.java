package top.cheesesmp.duelcore.party;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.party.PartyService.Mode;
import top.cheesesmp.duelcore.party.PartyService.Outcome;
import top.cheesesmp.duelcore.party.PartyService.Result;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.ui.Icons;
import top.cheesesmp.duelcore.ui.dialog.DialogService;

/**
 * The party menus. Every button and clickable line is a {@code duelcore:party/<action>} custom click with a flat SNBT
 * payload, routed here through the "party" prefix of the click router; payloads are untrusted and every action is
 * checked again by {@link PartyService}. The dialogs stay open after a click (after-action "none") until the server
 * shows the next one, so buttons that can't be used (struck through, the reason as tooltip) simply do nothing, and
 * Close is a click as well. Errors are shown as a line at the top of the re-opened dialog.
 */
public final class PartyDialogs {

    private final DuelCorePlugin plugin;
    private final PartyService parties;

    PartyDialogs(DuelCorePlugin plugin, PartyService parties) {
        this.plugin = plugin;
        this.parties = parties;
    }

    private Messages msg() {
        return plugin.messages();
    }

    // ------------------------------------------------------------------ building blocks

    private static String snbt(Map<String, String> payload) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : payload.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append(":\"")
                .append(e.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private static Map<String, String> payload(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return map;
    }

    private static ClickEvent click(String action, Map<String, String> payload) {
        return ClickEvent.custom(Key.key(DialogService.NS, action), BinaryTagHolder.binaryTagHolder(snbt(payload)));
    }

    private ActionButton button(Component label, @Nullable Component tooltip, int width, @Nullable String action,
                                Map<String, String> payload) {
        ActionButton.Builder b = ActionButton.builder(label).width(Math.clamp(width, 1, 1024));
        if (tooltip != null) b.tooltip(tooltip);
        if (action != null) {
            b.action(DialogAction.customClick(Key.key(DialogService.NS, action),
                payload.isEmpty() ? null : BinaryTagHolder.binaryTagHolder(snbt(payload))));
        }
        return b.build();
    }

    /** A button that can't be used now: struck through, the reason as tooltip, no action. */
    private ActionButton disabled(Component label, String whyKey, int width) {
        return button(msg().get("party.dialog.disabled", Messages.comp("label", label)), msg().get(whyKey), width, null, Map.of());
    }

    private ActionButton close() {
        return button(msg().get("party.dialog.close"), null, 120, "party/close", Map.of());
    }

    private ActionButton back() {
        return button(msg().get("party.dialog.back"), null, 120, "party/menu", Map.of());
    }

    private static Dialog dialog(Component title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
        return Dialog.create(f -> f.empty()
            .base(DialogBase.builder(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body)
                .inputs(inputs)
                .build())
            .type(type));
    }

    private DialogBody text(Component content) {
        return DialogBody.plainMessage(content, plugin.gui().partyWidth);
    }

    private static Component lines(List<Component> lines) {
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private List<DialogBody> body(@Nullable Component notice, Component... parts) {
        List<DialogBody> body = new ArrayList<>();
        if (notice != null && !notice.equals(Component.empty())) body.add(text(notice));
        for (Component part : parts) body.add(text(part));
        return body;
    }

    /** The message of a failed action (messages.yml party.result.*). */
    Component error(Outcome outcome) {
        return msg().get("party.result." + outcome.result().name().toLowerCase(Locale.ROOT),
            Messages.text("player", outcome.subject()));
    }

    private Component status(UUID uuid) {
        return msg().get(switch (parties.status(uuid)) {
            case ONLINE -> "party.status.online";
            case OFFLINE -> "party.status.offline";
            case IN_MATCH -> "party.status.in-match";
        });
    }

    private Component lock(Party party) {
        return party.hasPassword() ? msg().get("party.dialog.lock") : Component.empty();
    }

    private Component head(Party.@Nullable Member member) {
        return member == null ? Component.empty() : Icons.head(member.uuid(), member.name());
    }

    private static String leaderName(Party party) {
        Party.Member leader = party.leaderMember();
        return leader == null ? "?" : leader.name();
    }

    // ------------------------------------------------------------------ entry

    /** The party menu, or the create/join screen for players without a party (hub item, /party). */
    public void open(Player player) {
        if (!parties.loaded()) {
            msg().send(player, "party.result.not_loaded");
            return;
        }
        if (parties.party(player.getUniqueId()) == null) openNone(player, null);
        else openMenu(player, 0, null);
    }

    // ------------------------------------------------------------------ not in a party

    public void openNone(Player player, @Nullable Component notice) {
        GuiConfig gui = plugin.gui();
        List<Component> intro = new ArrayList<>(List.of(msg().get("party.dialog.none"), msg().get("party.dialog.none-hint")));
        List<Component> invites = new ArrayList<>();
        List<PartyService.Invite> pending = parties.invites(player.getUniqueId());
        for (int i = pending.size() - 1; i >= 0 && invites.size() < gui.partyListLimit; i--) {
            PartyService.Invite invite = pending.get(i);
            Party party = parties.byId(invite.partyId());
            if (party == null) continue;
            Map<String, String> data = payload("party", party.id());
            invites.add(msg().get("party.dialog.invite-line", Messages.comp("head", head(party.leaderMember())),
                Messages.text("leader", leaderName(party)), Messages.text("inviter", invite.inviterName()),
                Messages.num("size", party.size()), Messages.num("max", parties.maxSize()),
                Messages.comp("accept", msg().get("party.dialog.accept-link").clickEvent(click("party/accept", data))),
                Messages.comp("deny", msg().get("party.dialog.deny-link").clickEvent(click("party/deny", data)))));
        }
        List<Party> listed = new ArrayList<>();
        for (Party p : parties.parties()) {
            if (p.open() && p.size() < parties.maxSize() && parties.onlineCount(p) > 0) listed.add(p);
        }
        listed.sort(Comparator.comparingInt(parties::onlineCount).reversed().thenComparing(PartyDialogs::leaderName,
            String.CASE_INSENSITIVE_ORDER));
        List<Component> open = new ArrayList<>();
        for (Party p : listed.subList(0, Math.min(listed.size(), gui.partyListLimit))) {
            open.add(msg().get("party.dialog.open-line", Messages.comp("head", head(p.leaderMember())),
                Messages.text("leader", leaderName(p)), Messages.num("size", p.size()), Messages.num("max", parties.maxSize()),
                Messages.num("online", parties.onlineCount(p)), Messages.comp("lock", lock(p)),
                Messages.comp("join", msg().get("party.dialog.join-link").clickEvent(click("party/join-open", payload("party", p.id()))))));
        }
        List<DialogBody> body = body(notice, lines(intro));
        if (!invites.isEmpty()) {
            invites.addFirst(msg().get("party.dialog.invites-title"));
            body.add(text(lines(invites)));
        }
        if (!open.isEmpty()) {
            open.addFirst(msg().get("party.dialog.open-title"));
            body.add(text(lines(open)));
        }
        List<DialogInput> inputs = List.of(DialogInput.text("password", msg().get("party.dialog.password-input"))
            .width(Math.min(gui.partyWidth, 300)).maxLength(PartyPasswords.MAX_LENGTH).build());
        int w = gui.partyButtonWidth * 3 / 2;
        List<ActionButton> buttons = List.of(
            button(msg().get("party.dialog.create"), msg().get("party.dialog.create-tooltip"), w, "party/create", Map.of()),
            button(msg().get("party.dialog.join"), msg().get("party.dialog.join-tooltip"), w, "party/join-menu", Map.of()));
        player.showDialog(dialog(msg().get("party.dialog.title"), body, inputs,
            DialogType.multiAction(buttons).columns(2).exitAction(close()).build()));
    }

    public void openJoin(Player player, String leader, @Nullable Component notice) {
        int width = Math.min(plugin.gui().partyWidth, 300);
        List<DialogInput> inputs = List.of(
            DialogInput.text("leader", msg().get("party.dialog.leader-input")).width(width).initial(leader).maxLength(16).build(),
            DialogInput.text("password", msg().get("party.dialog.password-optional")).width(width)
                .maxLength(PartyPasswords.MAX_LENGTH).build());
        int w = plugin.gui().partyButtonWidth * 3 / 2;
        player.showDialog(dialog(msg().get("party.dialog.join-title"), body(notice, msg().get("party.dialog.join-body")), inputs,
            DialogType.confirmation(button(msg().get("party.dialog.join-confirm"), null, w, "party/join", Map.of()),
                button(msg().get("party.dialog.back"), null, w, "party/menu", Map.of()))));
    }

    // ------------------------------------------------------------------ party menu

    public void openMenu(Player player, int page, @Nullable Component notice) {
        UUID uuid = player.getUniqueId();
        Party party = parties.party(uuid);
        if (party == null) {
            openNone(player, notice);
            return;
        }
        GuiConfig gui = plugin.gui();
        boolean leader = party.isLeader(uuid);
        List<Party.Member> members = party.leaderFirst();
        int per = gui.partyMembersPerPage;
        int pages = Math.max(1, (members.size() + per - 1) / per);
        int shown = Math.clamp(page, 0, pages - 1);
        List<Component> lines = new ArrayList<>();
        lines.add(msg().get("party.dialog.header", Messages.num("size", party.size()), Messages.num("max", parties.maxSize()),
            Messages.num("online", parties.onlineCount(party)),
            Messages.comp("privacy", msg().get(party.open() ? "party.dialog.privacy-open" : "party.dialog.privacy-private")),
            Messages.comp("lock", lock(party))));
        for (Party.Member m : members.subList(shown * per, Math.min(members.size(), (shown + 1) * per))) {
            boolean self = m.uuid().equals(uuid);
            Component line = msg().get("party.dialog.member",
                Messages.comp("star", party.isLeader(m.uuid()) ? msg().get("party.dialog.leader-star") : Component.empty()),
                Messages.comp("head", head(m)), Messages.text("player", m.name()),
                Messages.comp("you", self ? msg().get("party.dialog.member-you") : Component.empty()),
                Messages.comp("status", status(m.uuid())));
            if (leader && !self) {
                line = line.clickEvent(click("party/member", payload("id", m.uuid().toString())))
                    .hoverEvent(HoverEvent.showText(msg().get("party.dialog.member-hover", Messages.text("player", m.name()))));
            }
            lines.add(line);
        }
        if (pages > 1) lines.add(msg().get("party.dialog.page", Messages.num("page", shown + 1), Messages.num("pages", pages)));
        List<Component> incoming = new ArrayList<>();
        if (leader) {
            for (PartyService.Challenge c : parties.challenges(party.id())) {
                Kit kit = plugin.kits().get(c.kit());
                if (kit == null) continue;
                Map<String, String> data = payload("party", c.from(), "from", "menu");
                incoming.add(msg().get("party.dialog.challenge-line", Messages.text("leader", c.fromLeader()),
                    Messages.comp("kit", kit.displayName()), Messages.comp("kit_icon", kit.sprite()),
                    Messages.comp("accept", msg().get("party.dialog.accept-link").clickEvent(click("party/duel-accept", data))),
                    Messages.comp("deny", msg().get("party.dialog.deny-link").clickEvent(click("party/duel-deny", data)))));
            }
        }
        List<DialogBody> body = body(notice, lines(lines));
        if (!incoming.isEmpty()) {
            incoming.addFirst(msg().get("party.dialog.challenges-title"));
            body.add(text(lines(incoming)));
        }

        int w = gui.partyButtonWidth;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(shown > 0
            ? button(msg().get("party.dialog.prev"), msg().get("party.dialog.prev-tooltip"), w, "party/page",
                payload("page", String.valueOf(shown - 1)))
            : button(msg().get("party.dialog.prev-none"), null, w, null, Map.of()));
        Component invite = msg().get("party.dialog.invite");
        buttons.add(!leader ? disabled(invite, "party.dialog.why-leader", w)
            : party.size() >= parties.maxSize() ? disabled(invite, "party.dialog.why-full", w)
            : button(invite, msg().get("party.dialog.invite-tooltip"), w, "party/invite-menu", Map.of()));
        buttons.add(shown < pages - 1
            ? button(msg().get("party.dialog.next"), msg().get("party.dialog.next-tooltip"), w, "party/page",
                payload("page", String.valueOf(shown + 1)))
            : button(msg().get("party.dialog.next-none"), null, w, null, Map.of()));
        Party.Member self = party.member(uuid);
        boolean chat = self != null && self.chat();
        buttons.add(button(msg().get(chat ? "party.dialog.chat-on" : "party.dialog.chat-off"),
            msg().get("party.dialog.chat-tooltip"), w, "party/chat", payload("page", String.valueOf(shown))));
        buttons.add(modeButton(party, player, Mode.FFA, w));
        buttons.add(modeButton(party, player, Mode.SPLIT, w));
        buttons.add(modeButton(party, player, Mode.PVP, w));
        Component privacy = msg().get("party.dialog.privacy");
        buttons.add(leader ? button(privacy, msg().get("party.dialog.privacy-tooltip"), w, "party/privacy", Map.of())
            : disabled(privacy, "party.dialog.why-leader", w));
        buttons.add(leader
            ? button(msg().get("party.dialog.disband"), msg().get("party.dialog.disband-tooltip"), w, "party/disband", Map.of())
            : button(msg().get("party.dialog.leave"), msg().get("party.dialog.leave-tooltip"), w, "party/leave", Map.of()));
        player.showDialog(dialog(msg().get("party.dialog.title"), body, List.of(),
            DialogType.multiAction(buttons).columns(3).exitAction(close()).build()));
    }

    private ActionButton modeButton(Party party, Player player, Mode mode, int width) {
        Component label = msg().get("party.dialog.mode-" + mode.id());
        Result why = parties.matchBlock(party, player, mode);
        if (why != null) {
            return disabled(label, switch (why) {
                case NOT_LEADER -> "party.dialog.why-leader";
                case BUSY -> "party.dialog.why-match";
                case NO_OPPONENTS -> "party.dialog.why-parties";
                default -> "party.dialog.why-two";
            }, width);
        }
        return mode == Mode.PVP
            ? button(label, msg().get("party.dialog.mode-pvp-tooltip"), width, "party/pvp", Map.of())
            : button(label, msg().get("party.dialog.mode-" + mode.id() + "-tooltip"), width, "party/mode", payload("mode", mode.id()));
    }

    public void openMember(Player viewer, @Nullable UUID target) {
        Party party = parties.party(viewer.getUniqueId());
        Party.Member member = party == null || target == null ? null : party.member(target);
        if (party == null || member == null || !party.isLeader(viewer.getUniqueId()) || target.equals(viewer.getUniqueId())) {
            open(viewer);
            return;
        }
        Component info = msg().get("party.dialog.member-body", Messages.comp("head", head(member)),
            Messages.text("player", member.name()), Messages.comp("status", status(member.uuid())),
            Messages.text("ago", ago(member.joinedAt())));
        int w = plugin.gui().partyButtonWidth * 3 / 2;
        Map<String, String> data = payload("id", member.uuid().toString());
        Component promote = msg().get("party.dialog.promote");
        List<ActionButton> buttons = List.of(
            PartyService.online(member.uuid()) ? button(promote, msg().get("party.dialog.promote-tooltip"), w, "party/promote", data)
                : disabled(promote, "party.dialog.why-offline", w),
            button(msg().get("party.dialog.kick"), msg().get("party.dialog.kick-tooltip"), w, "party/kick", data));
        viewer.showDialog(dialog(msg().get("party.dialog.member-title", Messages.text("player", member.name())),
            body(null, info), List.of(), DialogType.multiAction(buttons).columns(2).exitAction(back()).build()));
    }

    private static String ago(long at) {
        long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }

    // ------------------------------------------------------------------ invite

    public void openInvite(Player player, @Nullable Component notice) {
        Party party = parties.party(player.getUniqueId());
        if (party != null && !party.isLeader(player.getUniqueId())) {
            openMenu(player, 0, error(Outcome.of(Result.NOT_LEADER)));
            return;
        }
        GuiConfig gui = plugin.gui();
        List<Player> candidates = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player) || parties.party(other.getUniqueId()) != null) continue;
            PlayerProfile profile = plugin.profiles().get(other);
            if (profile == null || (!profile.setting(Setting.PARTY_INVITES) && !parties.friends(player, other))) continue;
            candidates.add(other);
        }
        // the inviter's online friends first, then by name
        candidates.sort(Comparator.comparing((Player p) -> !parties.friends(player, p))
            .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int w = gui.partyButtonWidth * 3 / 2;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(msg().get("party.dialog.invite-send"), msg().get("party.dialog.invite-send-tooltip"), w,
            "party/invite", Map.of()));
        buttons.add(button(msg().get("party.dialog.invite-refresh"), null, w, "party/invite-menu", Map.of()));
        for (Player p : candidates.subList(0, Math.min(candidates.size(), gui.partyListLimit))) {
            buttons.add(button(msg().get("party.dialog.invite-player", Messages.comp("head", Icons.head(p.getUniqueId(), p.getName())),
                Messages.text("player", p.getName())), null, w, "party/invite", payload("target", p.getUniqueId().toString())));
        }
        Component intro = msg().get(candidates.isEmpty() ? "party.dialog.invite-none" : "party.dialog.invite-body");
        List<DialogInput> inputs = List.of(DialogInput.text("name", msg().get("party.dialog.invite-input"))
            .width(Math.min(gui.partyWidth, 300)).maxLength(16).build());
        player.showDialog(dialog(msg().get("party.dialog.invite-title"), body(notice, intro), inputs,
            DialogType.multiAction(buttons).columns(2).exitAction(back()).build()));
    }

    // ------------------------------------------------------------------ privacy

    public void openPrivacy(Player player, @Nullable Component notice) {
        Party party = parties.party(player.getUniqueId());
        if (party == null || !party.isLeader(player.getUniqueId())) {
            openMenu(player, 0, error(Outcome.of(party == null ? Result.NOT_IN_PARTY : Result.NOT_LEADER)));
            return;
        }
        int width = Math.min(plugin.gui().partyWidth, 300);
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.bool("open", msg().get("party.dialog.open-input")).initial(party.open()).build());
        inputs.add(DialogInput.text("password", msg().get("party.dialog.password-new")).width(width)
            .maxLength(PartyPasswords.MAX_LENGTH).build());
        if (party.hasPassword()) {
            inputs.add(DialogInput.bool("remove_password", msg().get("party.dialog.password-remove")).initial(false).build());
        }
        Component info = msg().get("party.dialog.privacy-body", Messages.comp("state",
            msg().get(party.hasPassword() ? "party.dialog.password-state-set" : "party.dialog.password-state-none")));
        int w = plugin.gui().partyButtonWidth * 3 / 2;
        player.showDialog(dialog(msg().get("party.dialog.privacy-title"), body(notice, info), inputs,
            DialogType.confirmation(button(msg().get("party.dialog.save"), null, w, "party/privacy-save", Map.of()),
                button(msg().get("party.dialog.back"), null, w, "party/menu", Map.of()))));
    }

    // ------------------------------------------------------------------ party matches

    /** Kit picker of a party match; {@code target} is the challenged party (Party vs Party). */
    public void openKits(Player player, Mode mode, @Nullable String target) {
        Party party = parties.party(player.getUniqueId());
        if (party == null || !party.isLeader(player.getUniqueId())) {
            openMenu(player, 0, error(Outcome.of(party == null ? Result.NOT_IN_PARTY : Result.NOT_LEADER)));
            return;
        }
        Party targetParty = parties.byId(target);
        if (mode == Mode.PVP && targetParty == null) {
            openPvp(player, error(Outcome.of(Result.NO_PARTY)));
            return;
        }
        GuiConfig gui = plugin.gui();
        List<ActionButton> buttons = new ArrayList<>();
        for (Kit kit : plugin.kits().enabled()) {
            buttons.add(button(msg().get("party.dialog.kit-button", Messages.comp("kit_icon", kit.sprite()),
                    Messages.comp("kit", kit.displayName())), Component.text(kit.description()), gui.kitButtonWidth,
                "party/start", payload("mode", mode.id(), "kit", kit.id(), "target", target == null ? "" : target)));
        }
        Component info = msg().get("party.dialog.kit-body-" + mode.id(), Messages.num("online", parties.onlineCount(party)),
            Messages.text("leader", targetParty == null ? "" : leaderName(targetParty)), Messages.num("seconds", parties.inviteSeconds()));
        Component title = msg().get("party.dialog.kit-title", Messages.comp("mode", msg().get("party.dialog.mode-" + mode.id())));
        player.showDialog(dialog(title, body(null, info), List.of(), buttons.isEmpty() ? DialogType.notice(back())
            : DialogType.multiAction(buttons).columns(gui.kitColumns).exitAction(back()).build()));
    }

    /** Parties that can be challenged (their leader is online). */
    public void openPvp(Player player, @Nullable Component notice) {
        Party own = parties.party(player.getUniqueId());
        if (own == null || !own.isLeader(player.getUniqueId())) {
            openMenu(player, 0, error(Outcome.of(own == null ? Result.NOT_IN_PARTY : Result.NOT_LEADER)));
            return;
        }
        List<Party> targets = parties.challengeable(own);
        targets.sort(Comparator.comparingInt(parties::onlineCount).reversed().thenComparing(PartyDialogs::leaderName,
            String.CASE_INSENSITIVE_ORDER));
        int w = plugin.gui().partyButtonWidth * 3 / 2;
        List<ActionButton> buttons = new ArrayList<>();
        for (Party p : targets.subList(0, Math.min(targets.size(), plugin.gui().partyListLimit))) {
            buttons.add(button(msg().get("party.dialog.pvp-party", Messages.comp("head", head(p.leaderMember())),
                    Messages.text("leader", leaderName(p)), Messages.num("online", parties.onlineCount(p)),
                    Messages.num("size", p.size())), null, w, "party/pvp-pick", payload("party", p.id())));
        }
        Component title = msg().get("party.dialog.pvp-title");
        if (buttons.isEmpty()) {
            player.showDialog(dialog(title, body(notice, msg().get("party.dialog.pvp-none")), List.of(), DialogType.notice(back())));
            return;
        }
        player.showDialog(dialog(title, body(notice, msg().get("party.dialog.pvp-body")), List.of(),
            DialogType.multiAction(buttons).columns(2).exitAction(back()).build()));
    }

    /** Shown to a party leader who was challenged (also sent as clickable chat). */
    public void openChallenge(Player leader, PartyService.Challenge challenge) {
        Kit kit = plugin.kits().get(challenge.kit());
        Party from = parties.byId(challenge.from());
        if (kit == null || from == null) return;
        Component info = msg().get("party.dialog.challenge-body", Messages.text("leader", challenge.fromLeader()),
            Messages.num("count", parties.onlineCount(from)), Messages.comp("kit", kit.displayName()),
            Messages.comp("kit_icon", kit.sprite()), Messages.num("seconds", parties.inviteSeconds()));
        Map<String, String> data = payload("party", from.id());
        int w = plugin.gui().partyButtonWidth * 3 / 2;
        // Escape / Close only closes it (the chat message can still accept); Deny tells the other leader
        List<ActionButton> buttons = List.of(button(msg().get("party.dialog.accept"), null, w, "party/duel-accept", data),
            button(msg().get("party.dialog.deny"), null, w, "party/duel-deny", data));
        leader.showDialog(dialog(msg().get("party.dialog.challenge-title"), body(null, info), List.of(),
            DialogType.multiAction(buttons).columns(2).exitAction(close()).build()));
    }

    public void openDisband(Player player) {
        int w = plugin.gui().partyButtonWidth * 3 / 2;
        player.showDialog(dialog(msg().get("party.dialog.disband-title"), body(null, msg().get("party.dialog.disband-body")),
            List.of(), DialogType.confirmation(
                button(msg().get("party.dialog.disband-confirm"), null, w, "party/disband-confirm", Map.of()),
                button(msg().get("party.dialog.back"), null, w, "party/menu", Map.of()))));
    }

    // ------------------------------------------------------------------ clicks

    /** Every {@code duelcore:party/*} click (dialog buttons, clickable dialog lines and chat). */
    void click(Player player, String action, Map<String, String> data, @Nullable DialogResponseView view) {
        if (!player.hasPermission(PartyService.PERMISSION)) {
            msg().send(player, "command.no-permission");
            return;
        }
        boolean fromChat = "chat".equals(data.get("from"));
        switch (action) {
            case "party/close" -> player.closeDialog();
            case "party/menu" -> open(player);
            case "party/page" -> openMenu(player, number(data.get("page")), null);
            case "party/create" -> {
                String password = input(view, "password");
                parties.create(player, password.isEmpty() ? null : password).thenAccept(o -> {
                    if (!player.isOnline()) return;
                    if (o.ok()) openMenu(player, 0, null);
                    else openNone(player, error(o));
                });
            }
            case "party/join-menu" -> openJoin(player, "", null);
            case "party/join" -> {
                String leader = input(view, "leader");
                String password = input(view, "password");
                if (leader.isEmpty()) {
                    openJoin(player, "", msg().get("party.dialog.leader-missing"));
                    return;
                }
                afterJoin(player, leader, parties.join(player, leader, password.isEmpty() ? null : password));
            }
            case "party/join-open" -> {
                Party party = parties.byId(data.get("party"));
                if (party == null) {
                    openNone(player, error(Outcome.of(Result.NO_PARTY)));
                } else if (party.hasPassword() && parties.invites(player.getUniqueId()).stream().noneMatch(i -> i.partyId().equals(party.id()))) {
                    openJoin(player, leaderName(party), msg().get("party.result.needs_password"));
                } else {
                    afterJoin(player, leaderName(party), parties.join(player, party, null));
                }
            }
            case "party/accept" -> {
                Outcome o = parties.accept(player, data.get("party"), null);
                if (o.ok()) {
                    if (!fromChat || plugin.matches().match(player.getUniqueId()) == null) openMenu(player, 0, null);
                } else if (fromChat) {
                    feedback(player, o);
                } else {
                    openNone(player, error(o));
                }
            }
            case "party/deny" -> {
                Outcome o = parties.deny(player, data.get("party"), null);
                if (fromChat) {
                    if (!o.ok()) feedback(player, o);
                } else {
                    openNone(player, null);
                }
            }
            case "party/invite-menu" -> openInvite(player, null);
            case "party/invite" -> {
                String name = input(view, "name");
                boolean picked = data.containsKey("target");
                Player target = picked ? player(data.get("target")) : name.isEmpty() ? null : Bukkit.getPlayerExact(name);
                if (target == null) {
                    // a picked player who just went offline simply drops out of the refreshed list
                    openInvite(player, picked ? null : name.isEmpty() ? msg().get("party.dialog.name-missing")
                        : error(Outcome.of(Result.OFFLINE, name)));
                    return;
                }
                Outcome o = parties.invite(player, target);
                openInvite(player, o.ok() ? msg().get("party.dialog.invited", Messages.text("player", target.getName())) : error(o));
            }
            case "party/chat" -> {
                Outcome o = parties.toggleChat(player);
                openMenu(player, number(data.get("page")), o.ok() ? null : error(o));
            }
            case "party/member" -> openMember(player, uuid(data.get("id")));
            case "party/kick" -> {
                Outcome o = parties.kick(player, uuid(data.get("id")));
                openMenu(player, 0, o.ok() ? null : error(o));
            }
            case "party/promote" -> {
                Outcome o = parties.promote(player, uuid(data.get("id")));
                openMenu(player, 0, o.ok() ? null : error(o));
            }
            case "party/leave" -> {
                Outcome o = parties.leave(player);
                openNone(player, o.ok() ? null : error(o));
            }
            case "party/disband" -> openDisband(player);
            case "party/disband-confirm" -> {
                Outcome o = parties.disband(player);
                if (o.ok()) openNone(player, null);
                else openMenu(player, 0, error(o));
            }
            case "party/privacy" -> openPrivacy(player, null);
            case "party/privacy-save" -> savePrivacy(player, view);
            case "party/mode" -> {
                Mode mode = Mode.parse(data.get("mode"));
                if (mode == null || mode == Mode.PVP) open(player);
                else openKits(player, mode, null);
            }
            case "party/pvp" -> openPvp(player, null);
            case "party/pvp-pick" -> openKits(player, Mode.PVP, data.get("party"));
            case "party/start" -> start(player, data);
            case "party/duel-accept" -> {
                // from chat, the party menu ("menu") or the challenge dialog; a started match closes every dialog
                Outcome o = parties.acceptChallenge(player, data.get("party"));
                if (o.ok()) return;
                if ("menu".equals(data.get("from"))) {
                    openMenu(player, 0, error(o));
                } else {
                    feedback(player, o);
                    if (!fromChat) player.closeDialog();
                }
            }
            case "party/duel-deny" -> {
                Outcome o = parties.denyChallenge(player, data.get("party"));
                if ("menu".equals(data.get("from"))) {
                    openMenu(player, 0, o.ok() ? null : error(o));
                } else {
                    if (!o.ok()) feedback(player, o);
                    if (!fromChat) player.closeDialog();
                }
            }
            default -> {
            }
        }
    }

    private void afterJoin(Player player, String leader, CompletableFuture<Outcome> result) {
        result.thenAccept(o -> {
            if (!player.isOnline()) return;
            if (o.ok()) openMenu(player, 0, null);
            else openJoin(player, leader, error(o));
        });
    }

    private void savePrivacy(Player player, @Nullable DialogResponseView view) {
        if (view == null) return;
        Boolean open = view.getBoolean("open");
        String password = input(view, "password");
        Boolean remove = view.getBoolean("remove_password");
        if (!password.isEmpty() && !PartyPasswords.valid(password)) {
            openPrivacy(player, error(Outcome.of(Result.BAD_PASSWORD)));
            return;
        }
        if (open != null) {
            Outcome o = parties.setOpen(player, open);
            if (!o.ok()) {
                openMenu(player, 0, error(o));
                return;
            }
        }
        CompletableFuture<Outcome> change = !password.isEmpty() ? parties.setPassword(player, password)
            : Boolean.TRUE.equals(remove) ? parties.setPassword(player, null)
            : CompletableFuture.completedFuture(Outcome.OK);
        change.thenAccept(o -> {
            if (!player.isOnline()) return;
            if (o.ok()) openMenu(player, 0, null);
            else openPrivacy(player, error(o));
        });
    }

    private void start(Player player, Map<String, String> data) {
        Mode mode = Mode.parse(data.get("mode"));
        Kit kit = plugin.kits().get(data.getOrDefault("kit", ""));
        if (mode == null) {
            open(player);
            return;
        }
        if (kit == null || !kit.enabled()) {
            openMenu(player, 0, error(Outcome.of(Result.KIT_DISABLED)));
            return;
        }
        Outcome o = switch (mode) {
            case FFA -> parties.startFfa(player, kit);
            case SPLIT -> parties.startSplit(player, kit);
            case PVP -> parties.challenge(player, data.get("target"), kit);
        };
        if (!o.ok()) openMenu(player, 0, error(o));
        else if (mode == Mode.PVP) openMenu(player, 0, msg().get("party.dialog.challenge-sent"));
        // FFA and Party Duel: the match closed everyone's dialogs
    }

    private void feedback(Player player, Outcome outcome) {
        msg().send(player, "party.result." + outcome.result().name().toLowerCase(Locale.ROOT),
            Messages.text("player", outcome.subject()));
    }

    private static String input(@Nullable DialogResponseView view, String key) {
        return view == null ? "" : Objects.requireNonNullElse(view.getText(key), "").strip();
    }

    private static int number(@Nullable String raw) {
        try {
            return raw == null ? 0 : Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static @Nullable UUID uuid(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable Player player(@Nullable String raw) {
        UUID id = uuid(raw);
        return id == null ? null : Bukkit.getPlayer(id);
    }
}
