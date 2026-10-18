package net.gawhara.punish;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerStopSpectatingEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GawharaPunish - قايمة دايرية (GUI) لأوامر الأدمن.
 * ملحوظة: ماينكرافت مش بيسمح بواجهة دايرة شفافة حقيقية من غير مود/ريسورس باك،
 * فبنرتّب الأزرار على شكل دايرة جوه شيست GUI.
 */
public final class PunishPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** الحلقة الخارجية مرتبة مع عقارب الساعة من فوق */
    private static final int[] RING = {4, 5, 15, 25, 34, 42, 50, 49, 48, 38, 28, 19, 11, 3};
    private static final int[] INNER = {12, 13, 14, 21, 23, 30, 32, 39, 40, 41};
    private static final int HEAD_SLOT = 22;
    private static final int BOTTOM_SLOT = 31;
    private static final int CUSTOM_REASON_INDEX = RING.length - 1;

    private static final Set<String> TOOL_TYPES = Set.of("tp", "bring", "invsee", "enderchest", "unmute",
            "spectate", "spectate-view", "spectate-stop");
    private static final Pattern DURATION = Pattern.compile("(\\d+)([smhdw])");

    record Action(String id, String name, List<String> lore, Material icon, int slot, String type,
                  List<String> commands, String duration, boolean askReason, boolean needsOnline,
                  String permission) {
    }

    record Pending(UUID target, String name, String actionId) {
    }

    enum Kind { MAIN, REASON }

    static final class MenuHolder implements InventoryHolder {
        final Kind kind;
        final UUID target;
        final String targetName;
        final String actionId;
        Inventory inv;

        MenuHolder(Kind kind, UUID target, String targetName, String actionId) {
            this.kind = kind;
            this.target = target;
            this.targetName = targetName;
            this.actionId = actionId;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final Map<Integer, Action> bySlot = new HashMap<>();
    private final Map<UUID, Long> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private List<String> reasons = new ArrayList<>();
    private File mutesFile;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mutesFile = new File(getDataFolder(), "mutes.yml");
        historyFile = new File(getDataFolder(), "history.yml");
        loadSettings();
        loadMutes();
        loadHistory();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("punish") != null) {
            getCommand("punish").setExecutor(this);
            getCommand("punish").setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(spectating.keySet())) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null) {
                stopSpectate(sp, false);
            }
        }
        saveMutes();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        actions.clear();
        bySlot.clear();

        ConfigurationSection sec = c.getConfigurationSection("actions");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection a = sec.getConfigurationSection(id);
                if (a == null) {
                    continue;
                }
                Material icon = Material.matchMaterial(a.getString("icon", "PAPER"));
                if (icon == null) {
                    getLogger().warning("Unknown icon for action '" + id + "', using PAPER");
                    icon = Material.PAPER;
                }
                int slot = a.getInt("slot", -1);
                if (slot < 0 || slot > 53 || slot == HEAD_SLOT || slot == BOTTOM_SLOT) {
                    getLogger().warning("Action '" + id + "' has an invalid slot (" + slot + "), skipped");
                    continue;
                }
                Action act = new Action(
                        id,
                        a.getString("name", id),
                        a.getStringList("lore"),
                        icon,
                        slot,
                        a.getString("type", "command").toLowerCase(),
                        a.getStringList("commands"),
                        a.getString("duration", ""),
                        a.getBoolean("ask-reason", false),
                        a.getBoolean("needs-online", false),
                        a.getString("permission", ""));
                actions.put(id, act);
                bySlot.put(slot, act);
            }
        }
        reasons = new ArrayList<>(c.getStringList("reasons"));
        while (reasons.size() > CUSTOM_REASON_INDEX) {
            reasons.remove(reasons.size() - 1);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Component msg(String key, String... kv) {
        String raw = getConfig().getString("messages." + key, "&c[" + key + "]");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return LEGACY.deserialize(raw);
    }

    private Component plain(String s) {
        return LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private String stripColors(String s) {
        return PlainTextComponentSerializer.plainText().serialize(LEGACY.deserialize(s));
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(plain(name));
        if (lore != null && !lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) {
                l.add(plain(s));
            }
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack filler() {
        Material m = Material.matchMaterial(
                getConfig().getString("settings.background-material", "LIGHT_BLUE_STAINED_GLASS_PANE"));
        if (m == null) {
            m = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        }
        return item(m, " ", null);
    }

    private ItemStack headItem(UUID id, String name) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        sm.setOwningPlayer(Bukkit.getOfflinePlayer(id));
        sm.displayName(plain("&c" + name));
        List<Component> lore = new ArrayList<>();
        Player online = Bukkit.getPlayer(id);
        lore.add(plain(online != null ? "&aأونلاين" : "&cأوفلاين"));
        lore.add(plain("&7UUID: &f" + id.toString().substring(0, 8) + "..."));
        if (online != null) {
            Location l = online.getLocation();
            if (l.getWorld() != null) {
                lore.add(plain("&7العالم: &f" + l.getWorld().getName()));
            }
            lore.add(plain("&7الإحداثيات: &f" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
            lore.add(plain("&7الوضع: &f" + online.getGameMode().name()));
            lore.add(plain("&7البنج: &f" + online.getPing() + "ms"));
        } else {
            long seen = Bukkit.getOfflinePlayer(id).getLastSeen();
            if (seen > 0L) {
                lore.add(plain("&7آخر دخول: &f" + fmtTime(seen)));
            }
        }
        Long until = mutes.get(id);
        if (until != null && until > System.currentTimeMillis()) {
            lore.add(plain("&6مكتوم: " + formatDuration(until - System.currentTimeMillis())));
        }
        if (frozen.contains(id)) {
            lore.add(plain("&bمجمّد"));
        }
        List<HistoryEntry> hist = history.getOrDefault(id, List.of());
        lore.add(plain(" "));
        lore.add(plain("&7العقوبات السابقة: " + (hist.isEmpty() ? "&a0" : "&c" + hist.size())));
        int from = Math.max(0, hist.size() - 3);
        for (int i = hist.size() - 1; i >= from; i--) {
            HistoryEntry he = hist.get(i);
            lore.add(plain("&8• &7" + fmtTime(he.time()) + " &f" + he.action()));
            lore.add(plain("   &8" + he.reason()));
        }
        sm.lore(lore);
        head.setItemMeta(sm);
        return head;
    }

    private void fillShape(Inventory inv) {
        if (!getConfig().getBoolean("settings.background", true)) {
            return;
        }
        ItemStack f = filler();
        for (int s : RING) {
            inv.setItem(s, f);
        }
        for (int s : INNER) {
            inv.setItem(s, f);
        }
    }

    static long parseDuration(String s) {
        if (s == null) {
            return 0L;
        }
        long total = 0L;
        Matcher m = DURATION.matcher(s.toLowerCase());
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            switch (m.group(2).charAt(0)) {
                case 's' -> total += n * 1000L;
                case 'm' -> total += n * 60_000L;
                case 'h' -> total += n * 3_600_000L;
                case 'd' -> total += n * 86_400_000L;
                case 'w' -> total += n * 604_800_000L;
                default -> {
                }
            }
        }
        return total;
    }

    static String formatDuration(long ms) {
        long s = Math.max(1L, ms / 1000L);
        long d = s / 86400L;
        s %= 86400L;
        long h = s / 3600L;
        s %= 3600L;
        long m = s / 60L;
        s %= 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append(" يوم ");
        }
        if (h > 0) {
            sb.append(h).append(" ساعة ");
        }
        if (m > 0) {
            sb.append(m).append(" دقيقة ");
        }
        if (s > 0 && d == 0 && h == 0) {
            sb.append(s).append(" ثانية");
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- menus

    private void openMain(Player admin, UUID tid, String tname) {
        MenuHolder h = new MenuHolder(Kind.MAIN, tid, tname, null);
        String title = getConfig().getString("settings.title", "&8عقاب: &c{player}").replace("{player}", tname);
        Inventory inv = Bukkit.createInventory(h, 54, LEGACY.deserialize(title));
        h.inv = inv;

        fillShape(inv);
        for (Action a : actions.values()) {
            if (!a.permission().isEmpty() && !admin.hasPermission(a.permission())) {
                continue;
            }
            inv.setItem(a.slot(), item(a.icon(), a.name(), a.lore()));
        }
        inv.setItem(HEAD_SLOT, headItem(tid, tname));
        inv.setItem(BOTTOM_SLOT, item(Material.BARRIER, "&cإغلاق", null));
        admin.openInventory(inv);
    }

    private void openReasons(Player admin, UUID tid, String tname, Action a) {
        MenuHolder h = new MenuHolder(Kind.REASON, tid, tname, a.id());
        String title = getConfig().getString("settings.reason-title", "&8اختر السبب");
        Inventory inv = Bukkit.createInventory(h, 54, LEGACY.deserialize(title));
        h.inv = inv;

        fillShape(inv);
        for (int i = 0; i < reasons.size() && i < CUSTOM_REASON_INDEX; i++) {
            inv.setItem(RING[i], item(Material.PAPER, "&e" + reasons.get(i),
                    List.of("&7" + stripColors(a.name()))));
        }
        inv.setItem(RING[CUSTOM_REASON_INDEX],
                item(Material.WRITABLE_BOOK, "&aسبب مخصص", List.of("&7اكتب السبب بنفسك في الشات")));
        inv.setItem(HEAD_SLOT, headItem(tid, tname));
        inv.setItem(BOTTOM_SLOT, item(Material.ARROW, "&cرجوع", null));
        admin.openInventory(inv);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder h)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player admin)) {
            return;
        }
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) {
            return;
        }

        if (h.kind == Kind.MAIN) {
            if (slot == BOTTOM_SLOT) {
                Bukkit.getScheduler().runTask(this, () -> admin.closeInventory());
                return;
            }
            Action a = bySlot.get(slot);
            if (a == null) {
                return;
            }
            if (!a.permission().isEmpty() && !admin.hasPermission(a.permission())) {
                return;
            }
            if (a.needsOnline() && Bukkit.getPlayer(h.target) == null) {
                admin.sendMessage(msg("target-offline"));
                return;
            }
            if (a.askReason()) {
                Bukkit.getScheduler().runTask(this, () -> openReasons(admin, h.target, h.targetName, a));
            } else {
                Bukkit.getScheduler().runTask(this, () -> {
                    admin.closeInventory();
                    execute(admin, h.target, h.targetName, a, "");
                });
            }
            return;
        }

        // REASON menu
        if (slot == BOTTOM_SLOT) {
            Bukkit.getScheduler().runTask(this, () -> openMain(admin, h.target, h.targetName));
            return;
        }
        Action a = actions.get(h.actionId);
        if (a == null) {
            return;
        }
        int idx = -1;
        for (int i = 0; i < RING.length; i++) {
            if (RING[i] == slot) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return;
        }
        if (idx == CUSTOM_REASON_INDEX) {
            pending.put(admin.getUniqueId(), new Pending(h.target, h.targetName, a.id()));
            Bukkit.getScheduler().runTask(this, () -> {
                admin.closeInventory();
                admin.sendMessage(msg("custom-reason-prompt"));
            });
            return;
        }
        if (idx < reasons.size()) {
            String reason = reasons.get(idx);
            Bukkit.getScheduler().runTask(this, () -> {
                admin.closeInventory();
                execute(admin, h.target, h.targetName, a, reason);
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- execute

    private void execute(Player admin, UUID tid, String tname, Action a, String reason) {
        if (reason == null || reason.isBlank()) {
            reason = getConfig().getString("settings.default-reason", "مخالفة القوانين");
        }
        Player target = Bukkit.getPlayer(tid);
        boolean punitive = !TOOL_TYPES.contains(a.type());

        if (punitive) {
            if (admin.getUniqueId().equals(tid)) {
                admin.sendMessage(msg("self"));
                return;
            }
            if (target != null && target.hasPermission("punish.exempt")) {
                admin.sendMessage(msg("exempt"));
                return;
            }
        }
        if (a.needsOnline() && target == null) {
            admin.sendMessage(msg("target-offline"));
            return;
        }

        switch (a.type()) {
            case "command" -> {
                for (String raw : a.commands()) {
                    String cmd = raw
                            .replace("{player}", tname)
                            .replace("{admin}", admin.getName())
                            .replace("{reason}", reason)
                            .replace("{duration}", a.duration());
                    boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    if (!ok) {
                        admin.sendMessage(msg("cmd-failed", "cmd", cmd));
                    }
                }
            }
            case "warn" -> {
                target.showTitle(Title.title(msg("warn-title"), msg("warn-sub", "reason", reason)));
                target.sendMessage(msg("warn-chat", "reason", reason));
            }
            case "mute" -> {
                long ms = parseDuration(a.duration());
                if (ms <= 0L) {
                    admin.sendMessage(msg("bad-duration"));
                    return;
                }
                mutes.put(tid, System.currentTimeMillis() + ms);
                saveMutes();
                if (target != null) {
                    target.sendMessage(msg("mute-target", "duration", formatDuration(ms), "reason", reason));
                }
            }
            case "unmute" -> {
                if (mutes.remove(tid) == null) {
                    admin.sendMessage(msg("not-muted"));
                    return;
                }
                saveMutes();
                if (target != null) {
                    target.sendMessage(msg("unmute-target"));
                }
            }
            case "freeze" -> {
                if (frozen.remove(tid)) {
                    target.sendMessage(msg("freeze-off"));
                } else {
                    frozen.add(tid);
                    target.sendMessage(msg("freeze-on"));
                }
            }
            case "spectate" -> {
                if (!startSpectate(admin, target)) {
                    return;
                }
            }
            case "spectate-view" -> {
                if (!cycleView(admin)) {
                    return;
                }
            }
            case "spectate-stop" -> {
                if (!stopSpectate(admin, true)) {
                    return;
                }
            }
            case "tp" -> admin.teleport(target.getLocation());
            case "bring" -> target.teleport(admin.getLocation());
            case "invsee" -> admin.openInventory(target.getInventory());
            case "enderchest" -> admin.openInventory(target.getEnderChest());
            default -> {
                admin.sendMessage(LEGACY.deserialize("&cنوع غير معروف في config: " + a.type()));
                return;
            }
        }

        String cleanAction = stripColors(a.name());
        admin.sendMessage(msg("done", "action", cleanAction, "player", tname));

        if (punitive) {
            List<HistoryEntry> prevList = history.getOrDefault(tid, List.of());
            int previousCount = prevList.size();
            HistoryEntry lastEntry = prevList.isEmpty() ? null : prevList.get(prevList.size() - 1);
            recordHistory(tid, new HistoryEntry(System.currentTimeMillis(), admin.getName(), cleanAction,
                    clean(reason), a.duration() == null ? "" : a.duration()));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(admin) && p.hasPermission("punish.notify")) {
                    p.sendMessage(msg("notify", "admin", admin.getName(), "action", cleanAction,
                            "player", tname, "reason", reason));
                }
            }
            for (String raw : getConfig().getStringList("settings.after-punish-commands")) {
                String cmd = raw
                        .replace("{player}", tname)
                        .replace("{admin}", admin.getName())
                        .replace("{action}", cleanAction)
                        .replace("{reason}", reason);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
            sendDiscord(admin, tid, tname, cleanAction, reason, a.duration(),
                    "mute".equals(a.type()) ? mutes.getOrDefault(tid, 0L) : 0L,
                    target != null ? target.getLocation() : null, previousCount, lastEntry);
        }
    }

    // ---------------------------------------------------------------- discord webhook

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newHttpClient();

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String field(String name, String value, boolean inline) {
        if (value == null || value.isBlank()) {
            value = "-";
        }
        if (value.length() > 1000) {
            value = value.substring(0, 1000) + "...";
        }
        return "{\"name\":\"" + jsonEscape(name) + "\",\"value\":\"" + jsonEscape(value)
                + "\",\"inline\":" + inline + "}";
    }

    private void sendDiscord(Player admin, UUID tid, String player, String action, String reason, String duration,
                             long muteUntil, Location loc, int previousCount, HistoryEntry last) {
        ConfigurationSection d = getConfig().getConfigurationSection("discord");
        if (d == null || !d.getBoolean("enabled", false)) {
            return;
        }
        String url = d.getString("webhook-url", "");
        if (url.isBlank() || !url.startsWith("https://")) {
            getLogger().warning("discord.webhook-url غير صحيح في config.yml");
            return;
        }
        String dur = (duration == null || duration.isBlank()) ? "-" : duration;

        List<String> f = new ArrayList<>();
        f.add(field("اللاعب", player, true));
        f.add(field("الأدمن", admin.getName(), true));
        f.add(field("العقوبة", action, true));
        f.add(field("المدة", dur, true));
        if (muteUntil > 0L) {
            long sec = muteUntil / 1000L;
            f.add(field("ينتهي الميوت", "<t:" + sec + ":F> (<t:" + sec + ":R>)", true));
        }
        f.add(field("حالة اللاعب", loc != null ? "أونلاين" : "أوفلاين", true));
        if (loc != null && loc.getWorld() != null && d.getBoolean("show-location", true)) {
            f.add(field("المكان", loc.getWorld().getName() + " (" + loc.getBlockX() + ", "
                    + loc.getBlockY() + ", " + loc.getBlockZ() + ")", true));
        }
        f.add(field("عقوبات سابقة", String.valueOf(previousCount), true));
        f.add(field("السبب", reason, false));
        if (last != null) {
            f.add(field("آخر عقوبة سابقة", "<t:" + (last.time() / 1000L) + ":d> - " + last.action()
                    + " - " + last.reason() + " (بواسطة " + last.admin() + ")", false));
        }
        f.add(field("UUID", "`" + tid + "`", false));

        StringBuilder embed = new StringBuilder();
        embed.append("\"title\":\"").append(jsonEscape(d.getString("title", "تقرير عقوبة"))).append("\",");
        embed.append("\"color\":").append(d.getInt("color", 15158332)).append(',');
        embed.append("\"fields\":[").append(String.join(",", f)).append("],");
        if (d.getBoolean("show-avatar", true)) {
            embed.append("\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/").append(tid).append("/128\"},");
        }
        String server = d.getString("server-name", "");
        if (!server.isBlank()) {
            embed.append("\"footer\":{\"text\":\"").append(jsonEscape(server)).append("\"},");
        }
        embed.append("\"timestamp\":\"").append(java.time.Instant.now()).append('"');

        String json = "{\"username\":\"" + jsonEscape(d.getString("username", "GawharaPunish"))
                + "\",\"embeds\":[{" + embed + "}]}";
        java.net.http.HttpRequest req;
        try {
            req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException ex) {
            getLogger().warning("discord.webhook-url غير صحيح: " + ex.getMessage());
            return;
        }
        HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
            if (r.statusCode() / 100 != 2) {
                getLogger().warning("Discord webhook فشل: HTTP " + r.statusCode() + " " + r.body());
            }
        }).exceptionally(ex -> {
            getLogger().warning("Discord webhook فشل: " + ex.getMessage());
            return null;
        });
    }

    // ---------------------------------------------------------------- spectate / مراقبة

    enum View { FIRST, THIRD, FREE }

    static final class SpecState {
        UUID target;
        Location origin;
        GameMode mode;
        View view = View.FIRST;
        BukkitTask task;
        boolean internal;
    }

    private final Map<UUID, SpecState> spectating = new HashMap<>();

    private String viewName(View v) {
        return switch (v) {
            case FIRST -> getConfig().getString("messages.view-first", "منظور اللاعب (من عيونه)");
            case THIRD -> getConfig().getString("messages.view-third", "منظور من ورا اللاعب");
            case FREE -> getConfig().getString("messages.view-free", "كاميرا حرة");
        };
    }

    private boolean startSpectate(Player admin, Player target) {
        if (admin.getUniqueId().equals(target.getUniqueId())) {
            admin.sendMessage(msg("self"));
            return false;
        }
        SpecState st = spectating.get(admin.getUniqueId());
        if (st == null) {
            st = new SpecState();
            st.origin = admin.getLocation().clone();
            st.mode = admin.getGameMode();
            spectating.put(admin.getUniqueId(), st);
        }
        st.target = target.getUniqueId();
        applyView(admin, st);
        admin.sendMessage(msg("spectate-start", "player", target.getName(), "view", viewName(st.view)));
        sendSpecControls(admin);
        return true;
    }

    private boolean cycleView(Player admin) {
        SpecState st = spectating.get(admin.getUniqueId());
        if (st == null) {
            admin.sendMessage(msg("not-spectating"));
            return false;
        }
        View[] all = View.values();
        st.view = all[(st.view.ordinal() + 1) % all.length];
        applyView(admin, st);
        admin.sendMessage(msg("spectate-view", "view", viewName(st.view)));
        sendSpecControls(admin);
        return true;
    }

    private boolean stopSpectate(Player admin, boolean notify) {
        SpecState st = spectating.remove(admin.getUniqueId());
        if (st == null) {
            if (notify) {
                admin.sendMessage(msg("not-spectating"));
            }
            return false;
        }
        if (st.task != null) {
            st.task.cancel();
            st.task = null;
        }
        admin.setSpectatorTarget(null);
        admin.setGameMode(st.mode);
        admin.teleport(st.origin);
        if (notify) {
            admin.sendMessage(msg("spectate-stop"));
        }
        return true;
    }

    private void applyView(Player admin, SpecState st) {
        Player target = Bukkit.getPlayer(st.target);
        if (target == null) {
            admin.sendMessage(msg("target-offline"));
            stopSpectate(admin, false);
            return;
        }
        if (st.task != null) {
            st.task.cancel();
            st.task = null;
        }
        if (admin.getGameMode() != GameMode.SPECTATOR) {
            admin.setGameMode(GameMode.SPECTATOR);
        }
        st.internal = true;
        try {
            admin.setSpectatorTarget(null);
            switch (st.view) {
                case FIRST -> {
                    admin.teleport(target.getLocation());
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        Player t = Bukkit.getPlayer(st.target);
                        if (spectating.get(admin.getUniqueId()) == st && st.view == View.FIRST
                                && admin.isOnline() && t != null) {
                            st.internal = true;
                            try {
                                admin.setSpectatorTarget(t);
                            } finally {
                                st.internal = false;
                            }
                        }
                    }, 3L);
                }
                case THIRD -> st.task = Bukkit.getScheduler()
                        .runTaskTimer(this, () -> followThird(admin, st), 0L, 1L);
                case FREE -> admin.teleport(target.getLocation().add(0, 2, 0));
            }
        } finally {
            st.internal = false;
        }
    }

    private void followThird(Player admin, SpecState st) {
        Player t = Bukkit.getPlayer(st.target);
        if (t == null || !admin.isOnline() || spectating.get(admin.getUniqueId()) != st) {
            if (st.task != null) {
                st.task.cancel();
            }
            return;
        }
        Location eye = t.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = new Vector(0, 0, 1);
        } else {
            dir.normalize();
        }
        Location pos = eye.clone().subtract(dir.multiply(3.5)).add(0, 1.2, 0);
        pos.setDirection(eye.toVector().subtract(pos.toVector()));
        admin.teleport(pos);
    }

    private void sendSpecControls(Player admin) {
        Component view = LEGACY.deserialize(getConfig().getString("messages.spectate-btn-view", "&e[تغيير المنظور]"))
                .clickEvent(ClickEvent.runCommand("/punish view"));
        Component stop = LEGACY.deserialize(getConfig().getString("messages.spectate-btn-stop", "&c[خروج]"))
                .clickEvent(ClickEvent.runCommand("/punish unspectate"));
        admin.sendMessage(view.append(Component.text("  ")).append(stop));
    }

    // ---------------------------------------------------------------- history

    record HistoryEntry(long time, String admin, String action, String reason, String duration) {
    }

    private static final int MAX_HISTORY = 50;
    private final Map<UUID, List<HistoryEntry>> history = new HashMap<>();
    private File historyFile;

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private java.time.ZoneId zone() {
        try {
            return java.time.ZoneId.of(getConfig().getString("settings.timezone", "Africa/Cairo"));
        } catch (Exception ex) {
            return java.time.ZoneId.systemDefault();
        }
    }

    private String fmtTime(long ms) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(zone()).format(java.time.Instant.ofEpochMilli(ms));
    }

    private void loadHistory() {
        history.clear();
        if (!historyFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(historyFile);
        ConfigurationSection sec = y.getConfigurationSection("history");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            List<HistoryEntry> list = new ArrayList<>();
            for (String line : sec.getStringList(key)) {
                String[] p = line.split("\t", -1);
                if (p.length < 5) {
                    continue;
                }
                try {
                    list.add(new HistoryEntry(Long.parseLong(p[0]), p[1], p[2], p[3], p[4]));
                } catch (NumberFormatException ignored) {
                    // bad line
                }
            }
            if (!list.isEmpty()) {
                history.put(id, list);
            }
        }
    }

    private void saveHistory() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<HistoryEntry>> e : history.entrySet()) {
            List<String> lines = new ArrayList<>();
            for (HistoryEntry h : e.getValue()) {
                lines.add(h.time() + "\t" + clean(h.admin()) + "\t" + clean(h.action()) + "\t"
                        + clean(h.reason()) + "\t" + clean(h.duration()));
            }
            y.set("history." + e.getKey(), lines);
        }
        try {
            getDataFolder().mkdirs();
            y.save(historyFile);
        } catch (IOException ex) {
            getLogger().warning("تعذر حفظ history.yml: " + ex.getMessage());
        }
    }

    private void recordHistory(UUID id, HistoryEntry entry) {
        List<HistoryEntry> list = history.computeIfAbsent(id, k -> new ArrayList<>());
        list.add(entry);
        while (list.size() > MAX_HISTORY) {
            list.remove(0);
        }
        saveHistory();
    }

    private Map.Entry<UUID, String> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Map.entry(online.getUniqueId(), online.getName());
        }
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off == null) {
            return null;
        }
        return Map.entry(off.getUniqueId(), off.getName() != null ? off.getName() : name);
    }

    // ---------------------------------------------------------------- chat / mute / freeze

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();

        Pending pend = pending.remove(p.getUniqueId());
        if (pend != null) {
            e.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(e.message());
            if (text.equalsIgnoreCase("cancel") || text.equals("الغاء") || text.equals("إلغاء")) {
                p.sendMessage(msg("cancelled"));
                return;
            }
            Action a = actions.get(pend.actionId());
            if (a == null) {
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> execute(p, pend.target(), pend.name(), a, text));
            return;
        }

        Long until = mutes.get(p.getUniqueId());
        if (until != null) {
            long left = until - System.currentTimeMillis();
            if (left > 0L) {
                e.setCancelled(true);
                p.sendMessage(msg("muted", "time", formatDuration(left)));
            } else {
                mutes.remove(p.getUniqueId());
                saveMutes();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Long until = mutes.get(id);
        if (until == null) {
            return;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0L) {
            mutes.remove(id);
            saveMutes();
            return;
        }
        String m = e.getMessage();
        if (m.length() < 2) {
            return;
        }
        String label = m.substring(1).split(" ")[0].toLowerCase();
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (getConfig().getStringList("settings.mute-blocked-commands").contains(label)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("muted", "time", formatDuration(left)));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (frozen.isEmpty() || !frozen.contains(e.getPlayer().getUniqueId())) {
            return;
        }
        Location f = e.getFrom();
        Location t = e.getTo();
        if (t == null) {
            return;
        }
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player q = e.getPlayer();
        pending.remove(q.getUniqueId());
        // الأدمن خرج وهو بيراقب: رجّعه لمكانه ووضعه الأصلي
        if (spectating.containsKey(q.getUniqueId())) {
            stopSpectate(q, false);
        }
        // اللاعب المراقَب خرج: وقّف المراقبة عند كل اللي بيشوفوه
        for (UUID wid : new ArrayList<>(spectating.keySet())) {
            SpecState st = spectating.get(wid);
            Player w = Bukkit.getPlayer(wid);
            if (st != null && st.target.equals(q.getUniqueId()) && w != null) {
                stopSpectate(w, false);
                w.sendMessage(msg("spectate-target-left", "player", q.getName()));
            }
        }
    }

    @EventHandler
    public void onTargetWorldChange(PlayerChangedWorldEvent e) {
        UUID tid = e.getPlayer().getUniqueId();
        for (UUID wid : new ArrayList<>(spectating.keySet())) {
            SpecState st = spectating.get(wid);
            if (st == null || !st.target.equals(tid)) {
                continue;
            }
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player w = Bukkit.getPlayer(wid);
                if (w != null && spectating.get(wid) == st) {
                    applyView(w, st);
                }
            }, 5L);
        }
    }

    @EventHandler
    public void onStopSpectating(PlayerStopSpectatingEntityEvent e) {
        SpecState st = spectating.get(e.getPlayer().getUniqueId());
        if (st == null || st.internal || st.view != View.FIRST) {
            return;
        }
        // الأدمن ضغط Shift ونزل من منظور اللاعب: خليه كاميرا حرة
        st.view = View.FREE;
        e.getPlayer().sendMessage(msg("spectate-detached"));
        sendSpecControls(e.getPlayer());
    }

    // ---------------------------------------------------------------- persistence

    private void loadMutes() {
        if (!mutesFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(mutesFile);
        ConfigurationSection s = y.getConfigurationSection("mutes");
        if (s == null) {
            return;
        }
        for (String key : s.getKeys(false)) {
            try {
                mutes.put(UUID.fromString(key), s.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // bad key
            }
        }
    }

    private synchronized void saveMutes() {
        YamlConfiguration y = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> en : mutes.entrySet()) {
            if (en.getValue() > now) {
                y.set("mutes." + en.getKey(), en.getValue());
            }
        }
        try {
            y.save(mutesFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save mutes.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("punish.reload")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            loadSettings();
            sender.sendMessage(msg("reloaded"));
            return true;
        }
        if (!(sender instanceof Player admin)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!admin.hasPermission("punish.use")) {
            admin.sendMessage(msg("no-permission"));
            return true;
        }
        String sub = args.length >= 1 ? args[0].toLowerCase() : "";
        if (sub.equals("spectate") || sub.equals("spec") || sub.equals("view")
                || sub.equals("unspectate") || sub.equals("unspec")) {
            if (!admin.hasPermission("punish.spectate")) {
                admin.sendMessage(msg("no-permission"));
                return true;
            }
            if (sub.equals("view")) {
                cycleView(admin);
            } else if (sub.equals("unspectate") || sub.equals("unspec")) {
                stopSpectate(admin, true);
            } else if (args.length == 2) {
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    admin.sendMessage(msg("target-offline"));
                } else {
                    startSpectate(admin, t);
                }
            } else {
                admin.sendMessage(msg("usage"));
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            Map.Entry<UUID, String> r = resolve(args[1]);
            if (r == null) {
                admin.sendMessage(msg("not-found"));
                return true;
            }
            List<HistoryEntry> hist = history.getOrDefault(r.getKey(), List.of());
            if (hist.isEmpty()) {
                admin.sendMessage(msg("history-empty", "player", r.getValue()));
                return true;
            }
            admin.sendMessage(msg("history-header", "player", r.getValue(), "count", String.valueOf(hist.size())));
            int from = Math.max(0, hist.size() - 15);
            for (int i = hist.size() - 1; i >= from; i--) {
                HistoryEntry he = hist.get(i);
                admin.sendMessage(msg("history-line", "time", fmtTime(he.time()), "action", he.action(),
                        "reason", he.reason(), "admin", he.admin(),
                        "duration", he.duration().isBlank() ? "-" : he.duration()));
            }
            return true;
        }
        if (args.length < 1) {
            admin.sendMessage(msg("usage"));
            return true;
        }

        UUID tid;
        String tname;
        Player online = Bukkit.getPlayerExact(args[0]);
        if (online != null) {
            tid = online.getUniqueId();
            tname = online.getName();
        } else {
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (off == null) {
                admin.sendMessage(msg("not-found"));
                return true;
            }
            tid = off.getUniqueId();
            tname = off.getName() != null ? off.getName() : args[0];
        }
        openMain(admin, tid, tname);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase();
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase().startsWith(p)) {
                    out.add(pl.getName());
                }
            }
            if ("reload".startsWith(p) && sender.hasPermission("punish.reload")) {
                out.add("reload");
            }
            for (String sc : List.of("history", "spectate", "view", "unspectate")) {
                if (sc.startsWith(p)) {
                    out.add(sc);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("history")
                || args[0].equalsIgnoreCase("spectate") || args[0].equalsIgnoreCase("spec"))) {
            String p = args[1].toLowerCase();
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase().startsWith(p)) {
                    out.add(pl.getName());
                }
            }
        }
        return out;
    }
}
