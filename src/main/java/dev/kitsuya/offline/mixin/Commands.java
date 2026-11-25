package dev.kitsuya.offline.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.kitsuya.offline.Hooks;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
        targets = {"net.minecraft.commands.Commands", "net.minecraft.class_2170"},
        remap = false)
abstract class Commands {
    @Inject(method = "<init>", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void kits$register(CallbackInfo ci) {
        try {
            for (var f : getClass().getDeclaredFields()) {
                if (f.getType() != CommandDispatcher.class) continue;
                f.setAccessible(true);
                var dispatcher = (CommandDispatcher<Object>) f.get(this);
                var root =
                        LiteralArgumentBuilder.<Object>literal("kitsoffline")
                                .requires(Hooks::allowed)
                                .executes(ctx -> Hooks.command(ctx.getSource(), "status"));
                for (String name : new String[] {"status", "pause", "resume", "adopt", "reload"})
                    root.then(
                            LiteralArgumentBuilder.<Object>literal(name)
                                    .executes(ctx -> Hooks.command(ctx.getSource(), name)));
                dispatcher.register(root);
                return;
            }
        } catch (ReflectiveOperationException e) {
            Hooks.log("Cannot register administrator commands: " + e);
        }
    }
}
