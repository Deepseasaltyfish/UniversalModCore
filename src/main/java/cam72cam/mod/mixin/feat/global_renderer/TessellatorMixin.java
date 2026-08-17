package cam72cam.mod.mixin.feat.global_renderer;

import cam72cam.mod.render.cutter.TessellatorCapture;
import net.minecraft.client.renderer.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Tessellator.class)
public abstract class TessellatorMixin {

    @Shadow private double textureU;
    @Shadow private double textureV;

    @Shadow private int brightness;
    @Shadow private int color;
    @Shadow private int normal;

    @Shadow private boolean hasTexture;
    @Shadow private boolean hasBrightness;
    @Shadow private boolean hasColor;
    @Shadow private boolean hasNormals;

    @Shadow private double xOffset;
    @Shadow private double yOffset;
    @Shadow private double zOffset;

    @Inject(
            method = "addVertex(DDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cutter$captureVertex(
            double x,
            double y,
            double z,
            CallbackInfo ci) {

        if (!TessellatorCapture.isCapturing()) {
            return;
        }

        int c = hasColor ? color : -1;
        int l = hasBrightness ? brightness : 0;

        byte nx = 0;
        byte ny = 0;
        byte nz = 0;

        if (hasNormals) {
            nx = (byte) (normal & 0xFF);
            ny = (byte) ((normal >>> 8) & 0xFF);
            nz = (byte) ((normal >>> 16) & 0xFF);
        }

        float u = hasTexture
                ? (float) textureU
                : 0.0F;

        float v = hasTexture
                ? (float) textureV
                : 0.0F;

        TessellatorCapture.captureVertex(
                x,
                y,
                z,
                u,
                v,
                c,
                l,
                nx,
                ny,
                nz,
                hasTexture,
                hasBrightness,
                hasColor,
                hasNormals
        );

        // 捕获时禁止原版 Tessellator 写入原始顶点
        ci.cancel();
    }
}