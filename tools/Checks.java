package dev.kitsuya.offline;

import java.nio.file.Path;
import java.util.Properties;

public class Checks {
    private static void check(boolean result) {
        if (!result) throw new AssertionError();
    }

    public static void main(String[] args) throws Exception {
        Properties defaults = Filesafe.read(Path.of("src/main/resources/kits_chunky_offline.properties"));
        Settings settings = Settings.parse(defaults);
        check(settings.enabled() && settings.expand() && settings.pauseOnJoin() && settings.border());
        check(settings.initial() == 5000 && settings.increment() == 1000 && settings.minimumBytes() == 10_000_000_000L);
        for (String[] pair : new String[][]{{"increment", "0"}, {"enabled", "yes"}, {"initialRadius", "30000000"},
                {"resumeDelaySeconds", "-1"}, {"dimensions", "minecraft:../world"}, {"logging", "invalid"}}) {
            Properties invalid = new Properties();
            invalid.putAll(defaults);
            invalid.setProperty(pair[0], pair[1]);
            try {Settings.parse(invalid); throw new AssertionError(pair[0]);} catch (IllegalArgumentException expected) {}
        }

        check(Dimension.axisRadius(0, -48, 48) == 32);
        check(Dimension.axisRadius(0, -48, 64) == 48);
        check(Dimension.axisRadius(-17, -64, 64) == 32);
        check(Dimension.axisRadius(0, 1, 64) < 0);
        check(Dimension.axisRadius(29_999_983, -29_999_984, 29_999_984) == 0);
        Stage first = Stage.initial("minecraft:overworld", 5000);
        Stage complete = first.phase("done");
        Stage next = complete.grow(1000);
        check(!first.id().equals(next.id()) && next.previous().equals(first.id().toString()));
        check(next.rx() == 6000 && next.last() == 5000 && next.phase().equals("planned"));
        check(Stage.decode(next.encode()).equals(next));
        Stage retry = next.phase("held").retry();
        check(retry.rx() == next.rx() && retry.last() == next.last() && !retry.id().equals(next.id()));
        Properties broken = next.encode();
        broken.setProperty("rx", "NaN");
        try {Stage.decode(broken); throw new AssertionError("NaN radius");} catch (IllegalArgumentException expected) {}
        System.out.println("Passed configuration, chunk-boundary and stage journal checks.");
    }
}
