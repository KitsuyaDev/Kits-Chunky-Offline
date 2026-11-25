package dev.kitsuya.offline;

import static java.nio.file.StandardOpenOption.*;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Properties;

public final class Filesafe {
    public static Properties read(Path path) throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(path)) {
            p.load(in);
        }
        return p;
    }

    public static void write(Path path, Properties p) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), ".kits-", ".tmp");
        try {
            try (var channel = FileChannel.open(tmp, WRITE, TRUNCATE_EXISTING)) {
                p.store(Channels.newOutputStream(channel), null);
                channel.force(true);
            }
            Files.move(
                    tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
