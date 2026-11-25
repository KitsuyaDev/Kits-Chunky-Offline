package dev.kitsuya.offline;

import org.popcraft.chunky.Chunky;
import org.popcraft.chunky.Selection;
import org.popcraft.chunky.platform.Sender;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class Hooks {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("kits_chunky_offline");
    private static Chunky chunky;
    private static Settings config;
    static String version;
    private static final Map<String, Dimension> worlds = new LinkedHashMap<>();
    private static long emptySince, nextCheck;
    private static boolean occupied, watching;
    private static String issue = "Chunky is missing or has not started";
    private static int startupTicks;

    public static void log(String message) {
        LOG.info("[Kit's Chunky Offline] " + message);
    }

    public static void open(Chunky c) {
        chunky = c;
        try {
            reload();
        } catch (Exception | LinkageError e) {
            disable(e);
        }
    }

    private static void disable(Throwable e) {
        issue = e.toString();
        config = null;
        worlds.values().forEach(w -> w.pause("automation disabled"));
        log(issue);
    }

    private static void reload() throws IOException {
        try (var input = Hooks.class.getResourceAsStream("/chunky-versions.txt")) {
            if (input == null) throw new IOException("Missing release matrix");
            version =
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .lines()
                            .filter(
                                    v ->
                                            chunky.getVersion()
                                                    .isEqualTo(
                                                            new org.popcraft.chunky.util.Version(
                                                                    v)))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IOException(
                                                    "Unsupported Chunky version; see"
                                                            + " COMPATIBILITY.md"));
        }
        if (!(chunky.getTaskLoader() instanceof CheckpointDirectory)
                || !FailureAware.class.isAssignableFrom(org.popcraft.chunky.GenerationTask.class))
            throw new IOException("Required Chunky integration hooks are unavailable");
        Settings fresh = Settings.load();
        for (String id : fresh.dimensions()) {
            if (worlds.containsKey(id)) continue;
            var world =
                    chunky.getServer()
                            .getWorld(id)
                            .orElseThrow(() -> new IOException("Unknown dimension " + id));
            if (!id.equals(world.getName()))
                throw new IOException("Use canonical dimension name " + world.getName());
            worlds.put(id, new Dimension(chunky, world));
        }
        for (var entry : worlds.entrySet())
            if (!fresh.dimensions().contains(entry.getKey()))
                entry.getValue().pause("dimension disabled");
        config = fresh;
        issue = "";
        emptySince = now();
        nextCheck = 0;
        if (!config.enabled()) worlds.values().forEach(w -> w.pause("automation disabled"));
        log("Loaded settings for " + String.join(", ", fresh.dimensions()));
    }

    public static void close() {
        worlds.values()
                .forEach(
                        w -> {
                            if (w.active != null && !w.active.returned) w.active.stop(false);
                            try {
                                w.flush();
                            } catch (RuntimeException e) {
                                log("Unable to flush stage: " + e);
                            }
                        });
        worlds.clear();
        chunky = null;
        config = null;
        occupied = false;
        watching = false;
        startupTicks = 0;
    }

    private static long now() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    public static void join() {
        if (config == null) return;
        occupied = true;
        if (config.pauseOnJoin()) worlds.values().forEach(w -> w.pause("players connected"));
    }

    public static void leave() {
        emptySince = now();
        nextCheck = 0;
    }

    public static void tick() {
        if (chunky == null) {
            if (startupTicks++ == 100) log(issue);
            return;
        }
        if (config == null) return;
        try {
            long now = now();
            if (now < nextCheck && (!watching || occupied)) return;

            boolean players = !chunky.getServer().getPlayers().isEmpty();
            if (players != occupied) {
                occupied = players;
                if (players && config.pauseOnJoin())
                    worlds.values().forEach(w -> w.pause("players connected"));
                if (!players) emptySince = now;
            }
            if (now < nextCheck) return;
            nextCheck = now + 1000;
            watching = false;
            for (var w : worlds.values()) {
                if (w.fault) continue;
                try {
                    w.observe();
                    if (!config.enabled() || !config.dimensions().contains(w.world.getName()))
                        continue;
                    if (players) {
                        if (config.pauseOnJoin()) w.pause("players connected");
                        else if (w.active == null) w.reason("players connected");
                        else {
                            w.sampleDisk(now);
                            w.guardRunning(config);
                        }
                        continue;
                    }
                    if (w.active == null && now - emptySince < config.delay() * 1000L) {
                        w.reason("resume delay");
                        continue;
                    }
                    w.run(config, now);
                    watching |= w.active != null && !w.active.paused;
                } catch (Exception | LinkageError e) {
                    w.fault = true;
                    w.pause("held: " + e);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            disable(e);
        }
    }

    static Sender sender(Object source) {
        if (chunky == null) return null;
        try {
            var constructor = chunky.getServer().getConsole().getClass().getConstructors()[0];
            return (Sender) constructor.newInstance(source);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static boolean allowed(Object source) {
        Sender sender = sender(source);
        return sender != null && sender.hasPermission("kits_chunky_offline.admin");
    }

    public static int command(Object source, String action) {
        Sender sender = sender(source);
        if (sender == null || !allowed(source)) return 0;
        try {
            switch (action) {
                case "status" -> {
                    if (config == null) sender.sendMessage(issue);
                    else {
                        sender.sendMessage(
                                "Kit's Chunky Offline | enabled="
                                        + config.enabled()
                                        + " | expansion="
                                        + config.expand());
                        for (var w : worlds.values()) sender.sendMessage(w.status(config));
                    }
                }
                case "adopt" -> {
                    if (config == null) throw new IOException(issue);
                    Selection selection = chunky.getSelection().build();
                    Dimension w = worlds.get(selection.world().getName());
                    if (w == null || !config.dimensions().contains(selection.world().getName()))
                        throw new IOException("Enable the selected dimension first");
                    boolean saved =
                            chunky.getTaskLoader()
                                    .loadTask(w.world)
                                    .filter(t -> !t.isCancelled())
                                    .isPresent();
                    w.adopt(selection, saved, config);
                    sender.sendMessage("Adopted " + w.stage.shape() + " in " + w.stage.world());
                }
                case "reload" -> {
                    reload();
                    sender.sendMessage("Settings reloaded");
                }
                default -> {
                    var p = Filesafe.read(Settings.FILE);
                    switch (action) {
                        case "resume" -> p.setProperty("enabled", "true");
                        case "pause" -> p.setProperty("enabled", "false");
                        default -> throw new IllegalArgumentException("Unknown action");
                    }
                    Settings.parse(p);
                    Filesafe.write(Settings.FILE, p);
                    reload();
                    if (action.equals("resume"))
                        for (var w : worlds.values())
                            if (config.dimensions().contains(w.world.getName())
                                    && w.stage != null
                                    && w.stage.phase().equals("held")) w.retry();
                    sender.sendMessage("Applied " + action);
                }
            }
            return 1;
        } catch (Exception | LinkageError e) {
            if (action.equals("reload")) disable(e);
            sender.sendMessage("Kit's Chunky Offline | " + e.getMessage());
            return 0;
        }
    }
}
