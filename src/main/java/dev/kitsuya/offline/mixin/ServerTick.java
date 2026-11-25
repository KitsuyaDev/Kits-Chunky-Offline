package dev.kitsuya.offline.mixin;

import dev.kitsuya.offline.Hooks;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.MinecraftServer", remap = false)
abstract class ServerTick {
    @Inject(
            method = {"tickServer", "method_3748", "m_5705_"},
            at = @At("HEAD"),
            require = 0)
    private void kits$tick(CallbackInfo ci) {
        Hooks.tick();
    }
}
