package com.neylogin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NeyLogin extends JavaPlugin implements CommandExecutor {

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> tasks = new ConcurrentHashMap<>();
    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        getServer().getPluginManager().registerEvents(new AuthListener(this), this);
        for (String cmd : new String[]{"register", "login", "changepassword", "logout", "neylogin"}) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(this);
        }
        // Jugadores ya conectados (por /reload)
        for (Player p : Bukkit.getOnlinePlayers()) startAuth(p);
        getLogger().info("NeyLogin activado.");
    }

    @Override
    public void onDisable() {
        saveData();
        tasks.values().forEach(BukkitTask::cancel);
    }

    // ---------- Datos ----------
    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private synchronized void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("No se pudo guardar data.yml: " + e.getMessage());
        }
    }

    public boolean isRegistered(UUID id) {
        return data.contains("players." + id + ".hash");
    }

    public boolean isAuthenticated(Player p) {
        return authenticated.contains(p.getUniqueId());
    }

    // ---------- Mensajes ----------
    public String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String msg(String key, String... kv) {
        String s = getConfig().getString("messages." + key, "&c" + key);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return color(getConfig().getString("messages.prefix", "") + s);
    }

    public void send(Player p, String key, String... kv) {
        p.sendMessage(msg(key, kv));
    }

    private String ip(Player p) {
        if (p.getAddress() == null || p.getAddress().getAddress() == null) return "desconocida";
        return p.getAddress().getAddress().getHostAddress();
    }

    // ---------- Flujo de autenticación ----------
    public void startAuth(Player p) {
        final UUID id = p.getUniqueId();
        authenticated.remove(id);
        attempts.remove(id);
        cancelTask(id);

        if (isRegistered(id) && sessionValid(id, ip(p))) {
            authenticate(p, true);
            return;
        }

        if (getConfig().getBoolean("blindness", true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1, false, false, false));
        }
        final String prompt = isRegistered(id) ? "login-prompt" : "register-prompt";
        send(p, prompt);

        final int timeout = getConfig().getInt("timeout-seconds", 60);
        final int reminder = Math.max(1, getConfig().getInt("reminder-seconds", 10));
        BukkitTask t = new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                if (!p.isOnline() || isAuthenticated(p)) {
                    cancel();
                    return;
                }
                elapsed++;
                if (timeout > 0 && elapsed >= timeout) {
                    cancel();
                    p.kickPlayer(color(getConfig().getString("messages.kick-timeout", "Tiempo agotado")));
                    return;
                }
                if (elapsed % reminder == 0) send(p, isRegistered(id) ? "login-prompt" : "register-prompt");
            }
        }.runTaskTimer(this, 20L, 20L);
        tasks.put(id, t);
    }

    private boolean sessionValid(UUID id, String ip) {
        int minutes = getConfig().getInt("session-minutes", 10);
        if (minutes <= 0) return false;
        String lastIp = data.getString("players." + id + ".ip", "");
        long last = data.getLong("players." + id + ".last-login", 0L);
        return lastIp.equals(ip) && (System.currentTimeMillis() - last) <= minutes * 60_000L;
    }

    public void authenticate(Player p, boolean fromSession) {
        UUID id = p.getUniqueId();
        authenticated.add(id);
        attempts.remove(id);
        cancelTask(id);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        data.set("players." + id + ".name", p.getName());
        data.set("players." + id + ".ip", ip(p));
        data.set("players." + id + ".last-login", System.currentTimeMillis());
        saveData();
        send(p, fromSession ? "session-restored" : "login-success");
    }

    /** Guarda la hora de salida para la sesión por IP. */
    public void markLogout(Player p) {
        UUID id = p.getUniqueId();
        if (authenticated.contains(id)) {
            data.set("players." + id + ".last-login", System.currentTimeMillis());
            saveData();
        }
    }

    public void cleanup(UUID id) {
        authenticated.remove(id);
        attempts.remove(id);
        cancelTask(id);
    }

    private void cancelTask(UUID id) {
        BukkitTask t = tasks.remove(id);
        if (t != null) t.cancel();
    }

    // ---------- Comandos ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase();
        if (name.equals("neylogin")) return admin(sender, a);
        if (!(sender instanceof Player)) {
            sender.sendMessage("Solo jugadores.");
            return true;
        }
        Player p = (Player) sender;
        switch (name) {
            case "register": return register(p, a);
            case "login": return login(p, a);
            case "changepassword": return changePassword(p, a);
            case "logout":
                if (!isAuthenticated(p)) { send(p, "login-first"); return true; }
                markLogout(p);
                data.set("players." + p.getUniqueId() + ".last-login", 0L);
                saveData();
                startAuth(p);
                send(p, "logged-out");
                return true;
            default: return false;
        }
    }

    private String passwordError(String pw) {
        int min = getConfig().getInt("min-password-length", 6);
        int max = getConfig().getInt("max-password-length", 64);
        if (pw.length() < min) return msg("password-short", "min", String.valueOf(min));
        if (pw.length() > max) return msg("password-long", "max", String.valueOf(max));
        return null;
    }

    private boolean register(Player p, String[] a) {
        UUID id = p.getUniqueId();
        if (isAuthenticated(p)) { send(p, "already-logged"); return true; }
        if (isRegistered(id)) { send(p, "already-registered"); return true; }
        if (a.length != 2) { send(p, "usage-register"); return true; }
        String err = passwordError(a[0]);
        if (err != null) { p.sendMessage(err); return true; }
        if (!a[0].equals(a[1])) { send(p, "password-mismatch"); return true; }

        int maxIp = getConfig().getInt("max-accounts-per-ip", 3);
        if (maxIp > 0) {
            int count = 0;
            ConfigurationSection sec = data.getConfigurationSection("players");
            String myIp = ip(p);
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    if (myIp.equals(sec.getString(key + ".ip"))) count++;
                }
            }
            if (count >= maxIp) { send(p, "too-many-accounts"); return true; }
        }

        data.set("players." + id + ".hash", PasswordUtil.hash(a[0]));
        data.set("players." + id + ".registered", System.currentTimeMillis());
        authenticate(p, false);
        send(p, "register-success");
        return true;
    }

    private boolean login(Player p, String[] a) {
        UUID id = p.getUniqueId();
        if (isAuthenticated(p)) { send(p, "already-logged"); return true; }
        if (!isRegistered(id)) { send(p, "not-registered"); return true; }
        if (a.length != 1) { send(p, "usage-login"); return true; }

        if (PasswordUtil.verify(a[0], data.getString("players." + id + ".hash"))) {
            authenticate(p, false);
        } else {
            int n = attempts.merge(id, 1, Integer::sum);
            int max = getConfig().getInt("max-attempts", 5);
            send(p, "wrong-password", "attempt", String.valueOf(n), "max", String.valueOf(max));
            if (max > 0 && n >= max) {
                p.kickPlayer(color(getConfig().getString("messages.kick-attempts", "Demasiados intentos")));
            }
        }
        return true;
    }

    private boolean changePassword(Player p, String[] a) {
        UUID id = p.getUniqueId();
        if (!isAuthenticated(p)) { send(p, "login-first"); return true; }
        if (a.length != 2) { send(p, "usage-change"); return true; }
        if (!PasswordUtil.verify(a[0], data.getString("players." + id + ".hash"))) {
            send(p, "wrong-password", "attempt", "-", "max", "-");
            return true;
        }
        String err = passwordError(a[1]);
        if (err != null) { p.sendMessage(err); return true; }
        data.set("players." + id + ".hash", PasswordUtil.hash(a[1]));
        saveData();
        send(p, "password-changed");
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("neylogin.admin")) {
            s.sendMessage(msg("no-permission"));
            return true;
        }
        if (a.length == 0) { s.sendMessage(msg("admin-usage")); return true; }
        String sub = a[0].toLowerCase();
        if (sub.equals("reload")) {
            reloadConfig();
            loadData();
            s.sendMessage(msg("admin-reloaded"));
            return true;
        }
        if (a.length < 2) { s.sendMessage(msg("admin-usage")); return true; }
        Player online = Bukkit.getPlayerExact(a[1]);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(a[1]);
        UUID id = target.getUniqueId();

        switch (sub) {
            case "unregister":
                if (!isRegistered(id)) { s.sendMessage(msg("player-not-found")); return true; }
                data.set("players." + id, null);
                saveData();
                if (online != null) startAuth(online);
                s.sendMessage(msg("admin-unregistered", "player", a[1]));
                return true;
            case "forcelogin":
                if (online == null) { s.sendMessage(msg("player-not-found")); return true; }
                authenticate(online, false);
                s.sendMessage(msg("admin-forcelogin", "player", a[1]));
                return true;
            case "setpass":
                if (a.length < 3) { s.sendMessage(msg("admin-usage")); return true; }
                data.set("players." + id + ".hash", PasswordUtil.hash(a[2]));
                data.set("players." + id + ".name", a[1]);
                saveData();
                s.sendMessage(msg("admin-setpass", "player", a[1]));
                return true;
            default:
                s.sendMessage(msg("admin-usage"));
                return true;
        }
    }
  }
