package dev.kitsuya.offline.mixin;

import dev.kitsuya.offline.CheckpointDirectory;
import dev.kitsuya.offline.ManagedTask;

import org.popcraft.chunky.Chunky;
import org.popcraft.chunky.GenerationTask;
import org.popcraft.chunky.platform.World;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Pseudo
@Mixin(targets = "org.popcraft.chunky.util.TaskLoader", remap = false)
abstract class TaskSave implements CheckpointDirectory {
    @Shadow @Final private Path savePath;
    @Shadow @Final private Chunky chunky;

    public Path kits$directory() {
        return savePath;
    }

    @Inject(
            method = "saveTask(Lorg/popcraft/chunky/GenerationTask;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void kits$save(GenerationTask task, CallbackInfo ci) {
        if (task instanceof ManagedTask managed) {
            managed.saveProgress(task.getCount(), task.getTotalTime());
            ci.cancel();
        }
    }

    @Inject(
            method = "saveTask(Lorg/popcraft/chunky/GenerationTask;JJ)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void kits$autosave(GenerationTask task, long count, long time, CallbackInfo ci) {
        if (task instanceof ManagedTask managed) {
            managed.saveProgress(count, time);
            ci.cancel();
        }
    }

    // Chunky cancels the saved task and the running task separately.
    @Inject(method = "cancelTask", at = @At("HEAD"), cancellable = true)
    private void kits$keepManualCheckpoint(World world, CallbackInfo ci) {
        if (chunky.getGenerationTasks().get(world.getName()) instanceof ManagedTask) ci.cancel();
    }
}
