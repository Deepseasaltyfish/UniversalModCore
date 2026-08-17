package cam72cam.mod.render.cutter;

import cam72cam.mod.math.Vec3d;

public final class TessellatorQuadTemplate {

    public final ClipVertex[] sourceVertices;

    public final Vec3d[] sourcePos;
    public final float[] sourceU;
    public final float[] sourceV;

    public final boolean hasTexture;
    public final boolean hasBrightness;
    public final boolean hasColor;
    public final boolean hasNormals;

    public TessellatorQuadTemplate(
            ClipVertex[] sourceVertices,
            boolean hasTexture,
            boolean hasBrightness,
            boolean hasColor,
            boolean hasNormals) {

        this.sourceVertices = sourceVertices;

        this.sourcePos = new Vec3d[4];
        this.sourceU = new float[4];
        this.sourceV = new float[4];

        for (int i = 0; i < 4; i++) {
            ClipVertex vertex = sourceVertices[i];

            sourcePos[i] = vertex.pos;
            sourceU[i] = vertex.u;
            sourceV[i] = vertex.v;
        }

        this.hasTexture = hasTexture;
        this.hasBrightness = hasBrightness;
        this.hasColor = hasColor;
        this.hasNormals = hasNormals;
    }
}