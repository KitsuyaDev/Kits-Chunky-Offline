package dev.kitsuya.offline;

import java.io.*;
import java.nio.file.*;
import java.util.*;

record Settings(
        boolean enabled,
        boolean expand,
        int initial,
        int increment,
        int maximum,
        int delay,
        boolean pauseOnJoin,
        boolean border,
        long minimumBytes,
        List<String> dimensions,
        boolean verbose) {
    static final Path FILE = Path.of("config/kits_chunky_offline.properties");

    static Settings load() throws IOException {
        if (Files.notExists(FILE)) {
            Files.createDirectories(FILE.getParent());
            try (var in = Settings.class.getResourceAsStream("/kits_chunky_offline.properties")) {
                Files.copy(Objects.requireNonNull(in), FILE);
            }
        }
        return parse(Filesafe.read(FILE));
    }

    static Settings parse(Properties p) {
        Set<String> keys =
                Set.of(
                        "enabled",
                        "expand",
                        "initialRadius",
                        "increment",
                        "maximumRadius",
                        "resumeDelaySeconds",
                        "pauseOnJoin",
                        "respectWorldBorder",
                        "minimumDiskGB",
                        "dimensions",
                        "logging");
        if (!keys.equals(p.stringPropertyNames()))
            throw new IllegalArgumentException(
                    "Missing or unknown configuration key: " + p.stringPropertyNames());
        List<String> worlds =
                Arrays.stream(p.getProperty("dimensions").split(",", -1))
                        .map(String::trim)
                        .toList();
        if (worlds.stream()
                        .anyMatch(
                                w ->
                                        !w.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                                                || w.contains("..")
                                                || w.contains(":/")
                                                || w.endsWith("/")
                                                || w.contains("//"))
                || new HashSet<>(worlds).size() != worlds.size())
            throw new IllegalArgumentException("Invalid or duplicate dimension");
        String log = p.getProperty("logging");
        if (!Set.of("normal", "verbose").contains(log))
            throw new IllegalArgumentException("logging must be normal or verbose");
        return new Settings(
                bool(p, "enabled"),
                bool(p, "expand"),
                num(p, "initialRadius", 1, 29999984),
                num(p, "increment", 1, 29999984),
                num(p, "maximumRadius", 0, 29999984),
                num(p, "resumeDelaySeconds", 0, 604800),
                bool(p, "pauseOnJoin"),
                bool(p, "respectWorldBorder"),
                num(p, "minimumDiskGB", 0, Integer.MAX_VALUE) * 1_000_000_000L,
                worlds,
                log.equals("verbose"));
    }

    static boolean bool(Properties p, String k) {
        return switch (p.getProperty(k, "")) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(k + " must be true or false");
        };
    }

    static int num(Properties p, String k, int min, int max) {
        int n;
        try {
            n = Integer.parseInt(p.getProperty(k));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid " + k);
        }
        if (n < min || n > max)
            throw new IllegalArgumentException(k + " outside " + min + ".." + max);
        return n;
    }
}
