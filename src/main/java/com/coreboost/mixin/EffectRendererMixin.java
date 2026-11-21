package com.coreboost.mixin;
import com.coreboost.client.ClientRuntime;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(EffectRenderer.class)
public abstract class EffectRendererMixin {
    @Inject(method="addEffect", at=@At("HEAD"), cancellable=true)
    private void coreboost$limit(EntityFX effect, CallbackInfo callback) {
        if (!ClientRuntime.allowParticle(effect)) callback.cancel();
    }
}

