package com.coreboost.mixin;
import com.coreboost.client.ClientRuntime;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(RenderManager.class)
public abstract class RenderManagerMixin {
    @Inject(method="renderEntityWithPosYaw", at=@At("HEAD"), cancellable=true)
    private void coreboost$hide(Entity entity, double x, double y, double z, float yaw, float partial,
                                CallbackInfoReturnable<Boolean> callback) {
        if (entity != null && ClientRuntime.shouldCullEntity(entity.getEntityId())) callback.setReturnValue(Boolean.FALSE);
    }
}

