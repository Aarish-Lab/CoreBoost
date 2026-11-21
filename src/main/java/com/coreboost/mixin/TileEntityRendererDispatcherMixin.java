package com.coreboost.mixin;
import com.coreboost.client.ClientRuntime;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(TileEntityRendererDispatcher.class)
public abstract class TileEntityRendererDispatcherMixin {
    @Inject(method="renderTileEntity", at=@At("HEAD"), cancellable=true)
    private void coreboost$hideTile(TileEntity tile, float partial, int destroyStage, CallbackInfo callback) {
        if (tile != null && tile.getPos() != null && ClientRuntime.shouldCullTile(tile.getPos().toLong())) callback.cancel();
    }
}

