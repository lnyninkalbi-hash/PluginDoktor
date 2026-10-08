package com.example.plugindoktor;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PluginDoktor extends JavaPlugin implements CommandExecutor {

    private final Map<String, Integer> hataSayisi = new ConcurrentHashMap<>();
    private final Map<String, String> sonHata = new ConcurrentHashMap<>();
    private Handler logHandler;

    private static final Pattern P_YUKLENEMEDI = Pattern.compile("Could not load '([^']+)'");
    private static final Pattern P_ENABLE = Pattern.compile("Error occurred while enabling (\\S+)");
    private static final Pattern P_EVENT = Pattern.compile("Could not pass event (\\S+) to (\\S+)");

    @Override
    public void onLoad() {
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                try {
                    boolean ciddi = r.getLevel().intValue() >= Level.SEVERE.intValue()
                            || r.getThrown() != null;
                    if (!ciddi) return;
                    String plugin = pluginBul(r);
                    if (plugin == null || plugin.equals(getName())) return;
                    hataSayisi.merge(plugin, 1, Integer::sum);
                    String msg = r.getThrown() != null
                            ? r.getThrown().getClass().getSimpleName() + ": " + r.getThrown().getMessage()
                            : String.valueOf(r.getMessage());
                    sonHata.put(plugin, msg);
                } catch (Throwable ignored) {
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Bukkit.getLogger().addHandler(logHandler);
    }

    @Override
    public void onEnable() {
        Objects.requireNonNull(getCommand("plugindoktor")).setExecutor(this);
        getLogger().info("PluginDoktor aktif. Komut: /plugindoktor [tum|temizle]");
    }

    @Override
    public void onDisable() {
        if (logHandler != null) Bukkit.getLogger().removeHandler(logHandler);
    }

    private String pluginBul(LogRecord r) {
        String ln = r.getLoggerName();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (ln != null && ln.equalsIgnoreCase(p.getName())) return p.getName();
        }
        Throwable t = r.getThrown();
        int derinlik = 0;
        while (t != null && derinlik++ < 6) {
            for (StackTraceElement el : t.getStackTrace()) {
                for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                    String main = p.getDescription().getMain();
                    int i = main.lastIndexOf('.');
                    String paket = i > 0 ? main.substring(0, i) : main;
                    if (!paket.isEmpty() && el.getClassName().startsWith(paket)) return p.getName();
                }
            }
            t = t.getCause();
        }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("plugindoktor.admin")) {
            s.sendMessage("§cBu komutu kullanma yetkin yok.");
            return true;
        }

        if (a.length > 0 && a[0].equalsIgnoreCase("temizle")) {
            hataSayisi.clear();
            sonHata.clear();
            s.sendMessage("§aHata sayaclari sifirlandi.");
            return true;
        }
        boolean tumunu = a.length > 0 && a[0].equalsIgnoreCase("tum");

        s.sendMessage("§e===== PluginDoktor Taramasi =====");
        sunucuDurumu(s);

        Map<String, Integer> logSayac = new HashMap<>();
        Set<String> logYuklenemeyen = new LinkedHashSet<>();
        List<String> logSatirlar = logTara(logSayac, logYuklenemeyen);

        Map<String, Integer> gorevler = new HashMap<>();
        try {
            for (BukkitTask t : Bukkit.getScheduler().getPendingTasks()) {
                gorevler.merge(t.getOwner().getName(), 1, Integer::sum);
            }
        } catch (Throwable ignored) {
        }

        int sorun = 0, saglam = 0;
        Set<String> yuklu = new HashSet<>();

        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            yuklu.add(p.getName().toLowerCase());
            String yol = dosyaYolu(p);
            List<String> notlar = new ArrayList<>();

            if (!p.isEnabled()) notlar.add("KAPALI / baslatilamadi");

            for (String dep : p.getDescription().getDepend()) {
                Plugin d = Bukkit.getPluginManager().getPlugin(dep);
                if (d == null) notlar.add("Eksik bagimlilik: " + dep);
                else if (!d.isEnabled()) notlar.add("Bagimlilik kapali: " + dep);
            }
            for (String dep : p.getDescription().getSoftDepend()) {
                if (Bukkit.getPluginManager().getPlugin(dep) == null)
                    notlar.add("Uyari: istege bagli plugin yok: " + dep);
            }

            if (p instanceof JavaPlugin) {
                JavaPlugin jp = (JavaPlugin) p;
                for (String cmd : p.getDescription().getCommands().keySet()) {
                    if (jp.getCommand(cmd) == null)
                        notlar.add("Komut kaydedilmemis: /" + cmd);
                }
            }

            if (p.getDescription().getAPIVersion() == null)
                notlar.add("Uyari: eski API (api-version yok), uyumsuz olabilir");

            int hs = Math.max(hataSayisi.getOrDefault(p.getName(), 0),
                    logSayac.getOrDefault(p.getName(), 0));
            if (hs > 0) {
                String son = sonHata.get(p.getName());
                notlar.add("Hata sayisi: " + hs + (son != null ? " | Son: " + son : " (latest.log'dan)"));
            }

            boolean kritik = notlar.stream().anyMatch(n -> !n.startsWith("Uyari"));

            if (kritik) {
                sorun++;
                s.sendMessage("§c✘ " + p.getName() + " v" + p.getDescription().getVersion() + " §7(CALISMIYOR / SORUNLU)");
            } else {
                saglam++;
                if (tumunu || !notlar.isEmpty())
                    s.sendMessage("§a✔ " + p.getName() + " v" + p.getDescription().getVersion() + " §7(calisiyor)");
            }
            if (kritik || tumunu || !notlar.isEmpty()) {
                s.sendMessage("   §7Dosya: §f" + yol);
                if (tumunu) {
                    int dinleyici = 0;
                    try { dinleyici = HandlerList.getRegisteredListeners(p).size(); } catch (Throwable ignored) {}
                    s.sendMessage("   §8Dinleyici: " + dinleyici + " | Bekleyen gorev: "
                            + gorevler.getOrDefault(p.getName(), 0));
                }
                for (String n : notlar) s.sendMessage("   §6- " + n);
            }
        }

        File klasor = getDataFolder().getParentFile();
        File[] jarlar = klasor.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
        if (jarlar != null) {
            for (File jar : jarlar) {
                String ad = jarPluginAdi(jar);
                if (ad == null) {
                    sorun++;
                    s.sendMessage("§c✘ Bozuk jar (plugin.yml okunamadi)");
                    s.sendMessage("   §7Dosya: §f" + jar.getAbsolutePath());
                } else if (!yuklu.contains(ad.toLowerCase())) {
                    sorun++;
                    s.sendMessage("§c✘ " + ad + " YUKLENEMEDI");
                    s.sendMessage("   §7Dosya: §f" + jar.getAbsolutePath());
                }
            }
        }

        if (!logYuklenemeyen.isEmpty()) {
            s.sendMessage("§e--- Acilista yuklenemeyen dosyalar (log) ---");
            for (String y : logYuklenemeyen) s.sendMessage("§c• " + y);
        }
        if (!logSatirlar.isEmpty()) {
            s.sendMessage("§e--- latest.log son acilis hatalari ---");
            for (String h : logSatirlar) s.sendMessage("§7• " + h);
        }

        s.sendMessage("§7Saglam plugin: §a" + saglam
                + " §7| Sorunlu: " + (sorun == 0 ? "§a0" : "§c" + sorun));
        if (!tumunu) s.sendMessage("§8/plugindoktor tum = hepsini listele | /plugindoktor temizle = sayaclari sifirla");
        return true;
    }

    private void sunucuDurumu(CommandSender s) {
        try {
            double[] tps = Bukkit.getTPS();
            double t = Math.min(20.0, tps[0]);
            String renk = t >= 18 ? "§a" : (t >= 15 ? "§e" : "§c");
            s.sendMessage("§7TPS: " + renk + String.format("%.1f", t) + " §7(20 = ideal)");
        } catch (Throwable e) {
            s.sendMessage("§7TPS: okunamadi");
        }
        Runtime rt = Runtime.getRuntime();
        long kul = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long max = rt.maxMemory() / 1048576L;
        s.sendMessage("§7RAM: §f" + kul + " MB / " + max + " MB §7| Plugin: §f"
                + Bukkit.getPluginManager().getPlugins().length
                + " §7| Oyuncu: §f" + Bukkit.getOnlinePlayers().size());
    }

    private List<String> logTara(Map<String, Integer> sayac, Set<String> yuklenemeyen) {
        List<String> sonuc = new ArrayList<>();
        try {
            File log = new File("logs/latest.log");
            if (!log.exists()) return sonuc;
            for (String satir : Files.readAllLines(log.toPath(), StandardCharsets.ISO_8859_1)) {
                Matcher m1 = P_YUKLENEMEDI.matcher(satir);
                if (m1.find()) yuklenemeyen.add(m1.group(1));
                Matcher m2 = P_ENABLE.matcher(satir);
                if (m2.find()) sayac.merge(m2.group(1), 1, Integer::sum);
                Matcher m3 = P_EVENT.matcher(satir);
                if (m3.find()) sayac.merge(m3.group(2), 1, Integer::sum);

                if (satir.contains("Could not load") || satir.contains("Error occurred while enabling")
                        || satir.contains("Unknown dependency") || satir.contains("Ambiguous plugin name")
                        || satir.contains("Could not pass event")) {
                    sonuc.add(satir.length() > 180 ? satir.substring(0, 180) + "..." : satir);
                }
            }
        } catch (Exception ignored) {
        }
        int n = sonuc.size();
        return n > 6 ? sonuc.subList(n - 6, n) : sonuc;
    }

    private String dosyaYolu(Plugin p) {
        try {
            return new File(p.getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (Exception e) {
            return "bulunamadi";
        }
    }

    private String jarPluginAdi(File jar) {
        try (JarFile jf = new JarFile(jar)) {
            JarEntry e = jf.getJarEntry("plugin.yml");
            if (e == null) e = jf.getJarEntry("paper-plugin.yml");
            if (e == null) return null;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(jf.getInputStream(e), StandardCharsets.UTF_8));
            return y.getString("name");
        } catch (Exception ex) {
            return null;
        }
    }
}
