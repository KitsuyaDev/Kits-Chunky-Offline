package dev.kitsuya.offline.mixin;

import dev.kitsuya.offline.Hooks;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
        targets = {"net.minecraft.server.players.PlayerList", "net.minecraft.class_3324"},
        remap = false)
abstract class Players {
    @Inject(
            method = {"placeNewPlayer", "method_14570", "m_11261_"},
            at = @At("HEAD"),
            require = 0)
    private void kits$join(CallbackInfo ci) {
        Hooks.join();
    }

    @Inject(
            method = {"remove", "method_14611", "m_11286_"},
            at = @At("RETURN"),
            require = 0)
    private void kits$leave(CallbackInfo ci) {
        Hooks.leave();
    }
}
