package dev.kitsuya.offline;

import org.popcraft.chunky.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ManagedTask extends GenerationTask {
    public final UUID stage;
    private final Stage plan;
    private final Path journal, checkpoint;
    private final Properties expected;
    private final Object checkpointLock = new Object();
    private static final boolean NEEDS_AUTOSAVE =
            Arrays.stream(org.popcraft.chunky.util.TaskLoader.class.getMethods())
                    .noneMatch(
                            method ->
                                    method.getName().equals("saveTask")
                                            && method.getParameterCount() == 3);
    private volatile Properties firstCheckpoint;
    private long lastSample, priorCount, priorTime;
    private java.util.concurrent.CompletableFuture<Void> autosave;
    public volatile boolean paused, abandoned, returned;
    public volatile Throwable failure;

    ManagedTask(
            Chunky chunky,
            Selection selection,
            long count,
            long time,
            Stage plan,
            Path journal,
            Path checkpoint,
            Properties expected) {
        super(chunky, selection, count, time, false);
        this.stage = plan.id();
        this.plan = plan;
        this.journal = journal;
        this.checkpoint = checkpoint;
        this.expected = expected;
        priorCount = count;
        priorTime = time;
    }

    @Override
    public void stop(boolean cancel) {
        paused = true;
        super.stop(cancel || abandoned);
        if (cancel && !abandoned) {
            abandoned = true;
            synchronized (checkpointLock) {
                try {
                    Filesafe.write(journal, plan.phase("held").encode());
                } catch (IOException e) {
                    failure = e;
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            if (abandoned) return;
            Properties current =
                    Files.notExists(checkpoint) ? new Properties() : Filesafe.read(checkpoint);
            if (!current.equals(expected))
                throw new IOException("Chunky checkpoint changed before dispatch");
            getChunky().getTaskLoader().saveTask(this);
            if (failure != null
                    || !stage.toString()
                            .equals(Filesafe.read(checkpoint).getProperty("kits-stage")))
                throw new IOException("Unable to claim the Chunky checkpoint", failure);
            synchronized (checkpointLock) {
                if (!abandoned && !plan.phase().equals("active"))
                    Filesafe.write(journal, plan.phase("active").encode());
            }
            super.run();
        } catch (IOException | RuntimeException | LinkageError error) {
            failure = error;
            stop(true);
        } finally {
            returned = true;
            getChunky().getGenerationTasks().remove(getSelection().world().getName(), this);
        }
    }

    public void saveProgress(long count, long elapsed) {
        synchronized (checkpointLock) {
            Properties p = new Properties();
            p.setProperty("world", plan.world());
            p.setProperty("shape", plan.shape());
            p.setProperty("center-x", Double.toString(plan.x()));
            p.setProperty("center-z", Double.toString(plan.z()));
            p.setProperty("radius", Double.toString(plan.rx()));
            p.setProperty("radius-z", Double.toString(plan.rz()));
            p.setProperty("chunks", Long.toString(count));
            p.setProperty("time", Long.toString(elapsed));
            p.setProperty("pattern", getChunkIterator().name());
            p.setProperty("kits-stage", stage.toString());
            p.setProperty("kits-version", Hooks.version);
            p.setProperty("kits-outcome", outcome());
            try {
                Filesafe.write(checkpoint, p);
                if (firstCheckpoint == null) firstCheckpoint = p;
            } catch (IOException e) {
                failure = e;
                stop(false);
            }
        }
    }

    void checkpointLegacy(long now) {
        if (!NEEDS_AUTOSAVE || paused || returned || firstCheckpoint == null) return;
        if (lastSample == 0) {
            lastSample = now;
            return;
        }
        if (now - lastSample < 60_000 || (autosave != null && !autosave.isDone())) return;
        lastSample = now;
        Properties p = new Properties();
        p.putAll(firstCheckpoint);
        p.setProperty("chunks", Long.toString(priorCount));
        p.setProperty("time", Long.toString(priorTime));
        priorCount = getCount();
        priorTime = getTotalTime();
        autosave =
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> {
                            synchronized (checkpointLock) {
                                if (paused || returned || isCancelled()) return;
                                try {
                                    Filesafe.write(checkpoint, p);
                                } catch (IOException e) {
                                    failure = e;
                                    stop(false);
                                }
                            }
                        });
    }

    synchronized boolean complete() {
        return returned
                && !paused
                && failure == null
                && getProgress().isComplete()
                && getCount() == getChunkIterator().total();
    }

    public String outcome() {
        if (failure != null || abandoned) return "held";
        if (paused) return "paused";
        return isCancelled() ? "draining" : "working";
    }
}
