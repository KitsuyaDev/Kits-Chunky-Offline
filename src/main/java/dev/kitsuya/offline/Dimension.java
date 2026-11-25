package dev.kitsuya.offline;

import org.popcraft.chunky.*;
import org.popcraft.chunky.platform.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class Dimension {
    final Chunky chunky;
    final World world;
    final Path directory, journal, checkpoint, chunkyCheckpoint;
    Stage stage;
    ManagedTask active;
    String reason = "waiting";
    boolean fault;
    private CompletableFuture<Long> disk;
    private CompletableFuture<Properties> reading;
    private CompletableFuture<Void> writing;
    private long nextRead;
    private long sampledAt, available = -1;

    Dimension(Chunky c, World w) throws IOException {
        chunky = c;
        world = w;
        directory =
                w.getDirectory("")
                        .orElseThrow(
                                () -> new IOException("No dimension directory for " + w.getName()));
        journal = directory.resolve("kits_chunky_offline/stage.properties");
        checkpoint = journal.resolveSibling("task.properties");
        chunkyCheckpoint =
                ((CheckpointDirectory) c.getTaskLoader())
                        .kits$directory()
                        .resolve(w.getName().replace(':', '/') + ".properties");
        if (!Files.notExists(journal)) {
            stage = Stage.decode(Filesafe.read(journal));
            if (!w.getName().equals(stage.world()))
                throw new IOException("Journal belongs to another dimension");
        }

        if (Files.notExists(checkpoint) && Files.exists(chunkyCheckpoint)) {
            Properties old = Filesafe.read(chunkyCheckpoint);
            String owner = old.getProperty("kits-stage", "");
            if (stage == null && !owner.isEmpty())
                throw new IOException("Restore the missing Kit's stage journal for " + w.getName());
            if (stage != null
                    && (stage.id().toString().equals(owner)
                            || (!owner.isEmpty() && stage.previous().equals(owner))
                            || stage.previous().equals(Stage.fingerprint(old))))
                Filesafe.write(checkpoint, old);
        }
    }

    void save(Stage next) throws IOException {
        Filesafe.write(journal, next.encode());
        stage = next;
    }

    private void persist(Stage next) {
        stage = next;
        writing =
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                Filesafe.write(journal, next.encode());
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
    }

    void flush() {
        if (writing != null) writing.join();
    }

    void reason(String value) {
        if (!value.equals(reason)) {
            reason = value;
            Hooks.log(world.getName() + " — " + value);
        }
    }

    void pause(String why) {
        if (active != null && !active.returned && !active.paused) active.stop(false);
        reason(why);
    }

    void observe() throws IOException {
        if (active == null) return;
        if (active.failure != null || active.abandoned) {
            persist(stage.phase("held"));
            reason(
                    "held after cancellation or generation failure"
                            + (active.failure == null ? "" : ": " + active.failure));
            active = null;
            return;
        }
        if (!active.returned) return;
        if (active.paused) {
            active = null;
            return;
        }
        if (active.complete()) {
            sampledAt = 0;
            available = -1;
            disk = null;
            persist(stage.phase("done"));
            reason("completed radius " + stage.last());
            active = null;
        }
    }

    void sampleDisk(long now) {
        if (disk != null && disk.isDone()) {
            available = disk.join();
            disk = null;
        }
        if (disk == null && (sampledAt == 0 || now - sampledAt >= 30_000)) {
            sampledAt = now;
            disk =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    long free = Files.getFileStore(directory).getUsableSpace();
                                    Path region = directory.resolve("region");
                                    if (Files.exists(region))
                                        free =
                                                Math.min(
                                                        free,
                                                        Files.getFileStore(region)
                                                                .getUsableSpace());
                                    return free;
                                } catch (IOException | RuntimeException e) {
                                    return -1L;
                                }
                            });
        }
    }

    void guardRunning(Settings cfg) {
        if (available < cfg.minimumBytes() || !(room(stage, cfg) >= 0))
            pause("storage or boundary limit");
    }

    double room(Stage s, Settings config) {
        double limit = Math.min(29999984, chunky.getServer().getMaxWorldSize());
        double left = -limit, right = limit, top = -limit, bottom = limit;
        if (config.border()) {
            Border border = world.getWorldBorder();
            double bx = border.getCenter().getX(), bz = border.getCenter().getZ();
            left = Math.max(left, bx - border.getRadiusX());
            right = Math.min(right, bx + border.getRadiusX());
            top = Math.max(top, bz - border.getRadiusZ());
            bottom = Math.min(bottom, bz + border.getRadiusZ());
        }
        double xRoom = axisRadius(s.x(), left, right);
        double zRoom = axisRadius(s.z(), top, bottom);
        boolean twoAxes = Set.of("rectangle", "ellipse", "oval").contains(s.shape());
        double extra = Math.min(xRoom - s.rx(), zRoom - (twoAxes ? s.rz() : s.rx()));
        extra = Math.min(extra, chunky.getLimit() - Math.max(s.rx(), s.rz()));
        if (config.maximum() > 0)
            extra = Math.min(extra, config.maximum() - Math.max(s.rx(), s.rz()));
        return extra;
    }

    // Chunky rounds the centre to a chunk and rounds each radius up to whole chunks.
    static double axisRadius(double centre, double lower, double upper) {
        double start = ((int) centre >> 4) * 16.0;
        return 16.0 * Math.floor(Math.min(start - lower, upper - (start + 16.0)) / 16.0);
    }

    Properties nativeTask() throws IOException {
        return Files.notExists(checkpoint) ? new Properties() : Filesafe.read(checkpoint);
    }

    void run(Settings cfg, long now) throws IOException {
        if (fault) return;
        if (writing != null) {
            if (!writing.isDone()) return;
            writing.join();
            writing = null;
        }
        sampleDisk(now);
        if (active == null && disk != null) {
            if (cfg.verbose()) reason("waiting for disk measurement");
            return;
        }
        if (available < cfg.minimumBytes()) {
            pause(available < 0 ? "waiting for disk measurement" : "low disk space");
            return;
        }
        if (stage != null && (!Double.isFinite(room(stage, cfg)) || room(stage, cfg) < 0)) {
            pause("selection exceeds border or radius limit");
            return;
        }
        if (active != null) {
            active.checkpointLegacy(now);
            return;
        }
        String name = world.getName();
        if (chunky.getGenerationTasks().containsKey(name)
                || chunky.getTrimTasks().containsKey(name)) {
            reason("dimension has another Chunky task");
            return;
        }
        if (stage != null && stage.phase().equals("held")) {
            reason("held for administrator review");
            return;
        }
        if (stage != null
                && stage.phase().equals("done")
                && (!cfg.expand() || room(stage, cfg) <= 0)) {
            reason(cfg.expand() ? "radius or world boundary reached" : "expansion disabled");
            return;
        }
        if (reading == null) {
            if (now < nextRead) return;
            reading =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return nativeTask();
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
            return;
        }
        if (!reading.isDone()) return;
        Properties nativeTask = reading.join();
        reading = null;
        nextRead = now + 1000;
        String owner = nativeTask.getProperty("kits-stage", "");
        if (stage == null) {
            if (!nativeTask.isEmpty()) {
                reason("managed checkpoint has no stage journal; restore stage.properties");
                return;
            }
            Stage first = Stage.initial(name, cfg.initial());
            double reduction = Math.min(0, room(first, cfg));
            if (!(cfg.initial() + reduction >= 1)) {
                reason("no space inside the configured boundary");
                return;
            }
            persist(Stage.initial(name, cfg.initial() + reduction));
            return;
        }
        if (stage.phase().equals("done")) {
            if (!owner.equals(stage.id().toString())) {
                reason("completed checkpoint replaced or missing");
                return;
            }
            persist(stage.grow(Math.min(cfg.increment(), room(stage, cfg))));
            return;
        }
        boolean same = owner.equals(stage.id().toString());
        boolean adopting =
                stage.phase().equals("planned")
                        && stage.previous().startsWith("saved-")
                        && Stage.fingerprint(nativeTask).equals(stage.previous());
        boolean newStage =
                stage.phase().equals("planned")
                        && (nativeTask.isEmpty() && stage.previous().isEmpty()
                                || !owner.isEmpty() && owner.equals(stage.previous()));
        if (!same && !newStage && !adopting) {
            reason("checkpoint missing, cancelled or replaced; automation held");
            return;
        }
        long count = 0, time = 0;
        if (adopting) {
            count = Long.parseLong(nativeTask.getProperty("chunks"));
            time = Long.parseLong(nativeTask.getProperty("time"));
        }
        if (same) {
            if (!matches(nativeTask)) {
                reason("checkpoint selection mismatch");
                return;
            }
            String outcome = nativeTask.getProperty("kits-outcome", "");
            if (!Set.of("working", "paused", "draining").contains(outcome)) {
                persist(stage.phase("held"));
                reason("checkpoint records cancellation or failure");
                return;
            }
            if (!outcome.equals("draining")
                    && Hooks.version.equals(nativeTask.getProperty("kits-version"))) {
                count = Long.parseLong(nativeTask.getProperty("chunks"));
                time = Long.parseLong(nativeTask.getProperty("time"));
            }
        }
        Selection selection = stage.build(Selection.builder(chunky, world));
        var iterator =
                org.popcraft.chunky.iterator.ChunkIteratorFactory.getChunkIterator(selection);
        if (count < 0 || time < 0 || count > iterator.total())
            throw new IOException("Invalid Chunky cursor");
        if (count > 0 && !iterator.name().equals(nativeTask.getProperty("pattern")))
            throw new IOException("Saved cursor uses another iteration pattern");
        // A checkpoint at the end is not itself a completion receipt. Verify the stage again.
        if (count == iterator.total()) count = 0;
        ManagedTask task =
                new ManagedTask(
                        chunky, selection, count, time, stage, journal, checkpoint, nativeTask);
        if (chunky.getGenerationTasks().putIfAbsent(name, task) != null) return;
        try {
            stage = stage.phase("active");
            if (cfg.verbose())
                Hooks.log(name + " stage " + stage.id() + " starting at iterator count " + count);
            active = task;
            chunky.getScheduler().runTask(task);
            reason("generating radius " + stage.rx() + " × " + stage.rz());
        } catch (RuntimeException | LinkageError e) {
            chunky.getGenerationTasks().remove(name, task);
            active = null;
            throw e;
        }
    }

    void adopt(Selection selection, boolean saved, Settings cfg) throws IOException {
        if (stage != null
                || active != null
                || chunky.getGenerationTasks().containsKey(world.getName()))
            throw new IOException("A stage or running task already exists in this dimension");
        Properties p =
                Files.notExists(chunkyCheckpoint)
                        ? new Properties()
                        : Filesafe.read(chunkyCheckpoint);
        if (!nativeTask().isEmpty())
            throw new IOException("Restore the existing managed stage journal first");
        Stage next;
        if (saved) {
            var task =
                    chunky.getTaskLoader()
                            .loadTask(world)
                            .orElseThrow(() -> new IOException("No saved Chunky task"));
            if (p.containsKey("kits-stage")
                    || task.isCancelled()
                    || !"false".equals(p.getProperty("cancelled")))
                throw new IOException("Select a paused, unmanaged Chunky task");
            next =
                    Stage.adopt(task.getSelection())
                            .fromCheckpoint(p, task.getChunkIterator().name());
            if (task.getCount() < 0
                    || task.getCount() > task.getChunkIterator().total()
                    || task.getTotalTime() < 0) throw new IOException("Invalid saved cursor");
        } else {
            next = Stage.adopt(selection);
        }
        if (!(room(next, cfg) >= 0))
            throw new IOException("Selection exceeds configured boundaries");
        save(next);
        if (saved) Filesafe.write(checkpoint, p);
        reading = null;
    }

    void retry() throws IOException {
        if (stage == null
                || !stage.phase().equals("held")
                || active != null
                || chunky.getGenerationTasks().containsKey(world.getName())
                || !stage.id().toString().equals(nativeTask().getProperty("kits-stage")))
            throw new IOException("Retry requires a held stage and its matching checkpoint");
        flush();
        save(stage.retry());
        fault = false;
    }

    private boolean matches(Properties p) {
        return world.getName().equals(p.getProperty("world"))
                && stage.shape().equals(p.getProperty("shape"))
                && stage.x() == Stage.number(p, "center-x")
                && stage.z() == Stage.number(p, "center-z")
                && stage.rx() == Stage.number(p, "radius")
                && stage.rz() == Stage.number(p, "radius-z");
    }

    String status(Settings cfg) {
        if (stage == null) return world.getName() + " " + reason;
        double step = Math.min(cfg.increment(), room(stage, cfg));
        return world.getName()
                + " "
                + reason
                + "; current="
                + stage.rx()
                + "x"
                + stage.rz()
                + "; last="
                + stage.last()
                + "; next="
                + (cfg.expand() && step > 0
                        ? (stage.rx() + step) + "x" + (stage.rz() + step)
                        : "none")
                + "; phase="
                + stage.phase();
    }
}
