package dev.kitsuya.offline;

import org.popcraft.chunky.Selection;
import org.popcraft.chunky.shape.ShapeType;

import java.util.*;

record Stage(
        UUID id,
        String previous,
        String phase,
        String world,
        String shape,
        String pattern,
        int rateLimit,
        double x,
        double z,
        double rx,
        double rz,
        double last) {
    Stage {
        Objects.requireNonNull(id);
        if (!previous.isEmpty() && !previous.matches("saved-[a-f0-9]{64}"))
            UUID.fromString(previous);
        if (!Set.of("planned", "active", "done", "held").contains(phase)
                || (!ShapeType.all().contains(shape) && !"oval".equals(shape))
                || !Set.of("region", "ring", "spiral", "concentric", "strip", "loop")
                        .contains(pattern)
                || rateLimit < 0
                || !finite(x, z, rx, rz, last)
                || rx <= 0
                || rz <= 0
                || last < 0
                || last > Math.max(rx, rz)
                || (phase.equals("done") && last != Math.max(rx, rz))
                || Math.abs(x) > 29999984
                || Math.abs(z) > 29999984
                || Math.max(rx, rz) > 29999984)
            throw new IllegalArgumentException("Invalid stored generation stage");
    }

    static boolean finite(double... values) {
        for (double v : values) if (!Double.isFinite(v)) return false;
        return true;
    }

    static Stage initial(String world, double radius) {
        return new Stage(
                UUID.randomUUID(),
                "",
                "planned",
                world,
                "square",
                "region",
                0,
                0,
                0,
                radius,
                radius,
                0);
    }

    private static int selectionRate(Selection s) {
        try {
            return (int) Selection.class.getMethod("rateLimit").invoke(s);
        } catch (NoSuchMethodException e) {
            return 0;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read Chunky rate limit", e);
        }
    }

    Selection build(Selection.Builder builder) {
        if (rateLimit > 0) {
            try {
                Selection.Builder.class
                        .getMethod("rateLimit", int.class)
                        .invoke(builder, rateLimit);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Chunky cannot restore the saved rate limit", e);
            }
        }
        return builder.shape(shape)
                .center(x, z)
                .radiusX(rx)
                .radiusZ(rz)
                .pattern(org.popcraft.chunky.util.Parameter.of(pattern))
                .build();
    }

    static Stage adopt(Selection s) {
        return new Stage(
                UUID.randomUUID(),
                "",
                "planned",
                s.world().getName(),
                s.shape(),
                "region",
                selectionRate(s),
                s.centerX(),
                s.centerZ(),
                s.radiusX(),
                s.radiusZ(),
                0);
    }

    Stage phase(String next) {
        return new Stage(
                id,
                previous,
                next,
                world,
                shape,
                pattern,
                rateLimit,
                x,
                z,
                rx,
                rz,
                next.equals("done") ? Math.max(rx, rz) : last);
    }

    Stage retry() {
        return new Stage(
                UUID.randomUUID(),
                id.toString(),
                "planned",
                world,
                shape,
                pattern,
                rateLimit,
                x,
                z,
                rx,
                rz,
                last);
    }

    Stage fromCheckpoint(Properties nativeTask, String iterator) {
        return new Stage(
                id,
                fingerprint(nativeTask),
                "planned",
                world,
                shape,
                iterator,
                Integer.parseInt(nativeTask.getProperty("rate-limit", "0")),
                x,
                z,
                rx,
                rz,
                last);
    }

    static String fingerprint(Properties properties) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
                digest.update(
                        (key + "=" + properties.getProperty(key) + "\n")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return "saved-" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    Stage grow(double step) {
        return new Stage(
                UUID.randomUUID(),
                id.toString(),
                "planned",
                world,
                shape,
                pattern,
                rateLimit,
                x,
                z,
                rx + step,
                rz + step,
                last);
    }

    Properties encode() {
        Properties p = new Properties();
        p.putAll(
                Map.of(
                        "id",
                        id.toString(),
                        "previous",
                        previous,
                        "phase",
                        phase,
                        "world",
                        world,
                        "shape",
                        shape,
                        "x",
                        "" + x,
                        "z",
                        "" + z,
                        "rx",
                        "" + rx,
                        "rz",
                        "" + rz,
                        "last",
                        "" + last));
        p.setProperty("pattern", pattern);
        p.setProperty("rateLimit", Integer.toString(rateLimit));
        return p;
    }

    static Stage decode(Properties p) {
        return new Stage(
                UUID.fromString(p.getProperty("id")),
                p.getProperty("previous"),
                p.getProperty("phase"),
                p.getProperty("world"),
                p.getProperty("shape"),
                p.getProperty("pattern", "region"),
                Integer.parseInt(p.getProperty("rateLimit", "0")),
                number(p, "x"),
                number(p, "z"),
                number(p, "rx"),
                number(p, "rz"),
                number(p, "last"));
    }

    static double number(Properties p, String k) {
        return Double.parseDouble(p.getProperty(k));
    }
}
