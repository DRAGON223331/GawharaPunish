package net.gawhara.punish;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

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

    private static final Set<String> TOOL_TYPES = Set.of("tp", "bring", "invsee", "enderchest", "unmute");
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
        loadSettings();
        loadMutes();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("punish") != null) {
            getCommand("punish").setExecutor(this);
            getCommand("punish").setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
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
        lore.add(plain(Bukkit.getPlayer(id) != null ? "&aأونلاين" : "&cأوفلاين"));
        Long until = mutes.get(id);
        if (until != null && until > System.currentTimeMillis()) {
            lore.add(plain("&6مكتوم: " + formatDuration(until - System.currentTimeMillis())));
        }
        if (frozen.contains(id)) {
            lore.add(plain("&bمجمّد"));
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
                Bukkit.getScheduler().runTask(this, admin::closeInventory);
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
        }
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
        pending.remove(e.getPlayer().getUniqueId());
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
        }
        return out;
    }
}
