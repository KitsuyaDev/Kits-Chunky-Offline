package dev.kitsuya.offline.mixin;

import dev.kitsuya.offline.ManagedTask;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

import java.util.function.BiConsumer;

@Pseudo
@Mixin(targets = "org.popcraft.chunky.GenerationTask", remap = false)
abstract class TaskFailures implements dev.kitsuya.offline.FailureAware {
    @Redirect(
            method = "run",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 0)
    private Object kits$removeFinished(java.util.Map<?, ?> tasks, Object key) {
        if ((Object) this instanceof ManagedTask owned)
            return tasks.remove(key, owned) ? owned : null;
        return tasks.remove(key);
    }

    @ModifyArg(
            method = "run",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ljava/util/concurrent/CompletableFuture;whenComplete(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletableFuture;"),
            index = 0)
    private BiConsumer<Object, Throwable> kits$observe(BiConsumer<Object, Throwable> callback) {
        if (!((Object) this instanceof ManagedTask task)) return callback;
        return (result, error) -> {
            if (error != null) {
                task.failure = error;
                task.stop(true);
            }
            callback.accept(result, error);
        };
    }
}
