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
    @Inject(method="getStringWidth", at=@At("HEAD"), cancellable=true)
    private void coreboost$cachedWidth(String text, CallbackInfoReturnable<Integer> callback) {
        if (ClientRuntime.hud && text != null) {
            Integer width = ClientRuntime.cachedWidth((FontRenderer)(Object)this, text);
            if (width != null) callback.setReturnValue(width);
        }
    }
    @Inject(method="getStringWidth", at=@At("RETURN"))
    private void coreboost$storeWidth(String text, CallbackInfoReturnable<Integer> callback) {
        if (ClientRuntime.hud && text != null)
            ClientRuntime.cacheWidth((FontRenderer)(Object)this, text, callback.getReturnValue().intValue());
    }
    @Inject(method="onResourceManagerReload", at=@At("HEAD"))
    private void coreboost$clear(IResourceManager manager, CallbackInfo callback) {
        ClientRuntime.clearWidths((FontRenderer)(Object)this);
    }
}
"""
}

def generate(force=False):
    project = OUT / NAME
    archive = OUT / (NAME + ".zip")
    if project.exists():
        if not force:
            raise FileExistsError("{} exists; use --force to replace it.".format(project))
        if project.resolve().parent != OUT.resolve() or project.name != NAME:
            raise RuntimeError("Refusing to delete a path outside the output directory.")
        shutil.rmtree(str(project))
    project.mkdir(parents=True)
    for relative, content in FILES.items():
        path = project / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content.replace("$", "$"), encoding="utf-8")
    with zipfile.ZipFile(str(archive), "w", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(project.rglob("*")):
            if path.is_file():
                output.write(str(path), str(path.relative_to(OUT)))
    return project, archive

def main():
    parser = argparse.ArgumentParser(description="Generate CoreBoost Forge 1.8.9 project and ZIP.")
    parser.add_argument("--force", action="store_true", help="Replace an existing generated project folder.")
    args = parser.parse_args()
    project, archive = generate(args.force)
    print("Created project: {}".format(project))
    print("Created ZIP: {}".format(archive))

if __name__ == "__main__":
    main()

