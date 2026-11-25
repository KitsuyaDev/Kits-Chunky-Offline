package dev.kitsuya.offline.mixin;

import dev.kitsuya.offline.Hooks;

import org.popcraft.chunky.Chunky;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.popcraft.chunky.Chunky", remap = false)
abstract class Lifecycle {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void kits$start(CallbackInfo ci) {
        Hooks.open((Chunky) (Object) this);
    }

    @Inject(method = "disable", at = @At("HEAD"))
    private void kits$close(CallbackInfo ci) {
        Hooks.close();
    }
}
