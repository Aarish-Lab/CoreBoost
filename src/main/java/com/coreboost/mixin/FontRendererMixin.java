package com.coreboost.mixin;

import com.coreboost.client.ClientRuntime;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.IResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontRenderer.class)
public abstract class FontRendererMixin {
    @Inject(method = "getStringWidth", at = @At("HEAD"), cancellable = true)
    private void coreboost$cachedWidth(String text, CallbackInfoReturnable<Integer> callback) {
        if (ClientRuntime.hud && text != null) {
            Integer width = ClientRuntime.cachedWidth((FontRenderer) (Object) this, text);
            if (width != null) {
                callback.setReturnValue(width);
            }
        }
    }

    @Inject(method = "getStringWidth", at = @At("RETURN"))
    private void coreboost$storeWidth(String text, CallbackInfoReturnable<Integer> callback) {
        if (ClientRuntime.hud && text != null) {
            ClientRuntime.cacheWidth((FontRenderer) (Object) this, text, callback.getReturnValue().intValue());
        }
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void coreboost$clear(IResourceManager manager, CallbackInfo callback) {
        ClientRuntime.clearWidths((FontRenderer) (Object) this);
    }
}
