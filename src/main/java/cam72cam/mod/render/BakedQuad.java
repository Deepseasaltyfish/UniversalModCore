package cam72cam.mod.render;

import cam72cam.mod.math.Vec3d;
import net.minecraft.util.IIcon;

public class BakedQuad {
    public final Vec3d[] pos = new Vec3d[4];
    public final float[] u = new float[4];
    public final float[] v = new float[4];
    public final int[] color = new int[4];
    public final int[] lightmap = new int[4];
    public final byte[] nx = new byte[4];
    public final byte[] ny = new byte[4];
    public final byte[] nz = new byte[4];
    public final IIcon icon;
    public final int face;

    public BakedQuad(Vec3d[] pos, float[] u, float[] v, int[] color, int[] lightmap,
                     byte[] nx, byte[] ny, byte[] nz, IIcon icon, int face) {
        System.arraycopy(pos, 0, this.pos, 0, 4);
        System.arraycopy(u, 0, this.u, 0, 4);
        System.arraycopy(v, 0, this.v, 0, 4);
        System.arraycopy(color, 0, this.color, 0, 4);
        System.arraycopy(lightmap, 0, this.lightmap, 0, 4);
        System.arraycopy(nx, 0, this.nx, 0, 4);
        System.arraycopy(ny, 0, this.ny, 0, 4);
        System.arraycopy(nz, 0, this.nz, 0, 4);
        this.icon = icon;
        this.face = face;
    }
}