package cam72cam.mod.render.cutter;

public final class TessellatorQuad {

    public final ClipVertex[] vertices;

    public final boolean hasTexture;
    public final boolean hasBrightness;
    public final boolean hasColor;
    public final boolean hasNormals;

    public TessellatorQuad(
            ClipVertex[] vertices,
            boolean hasTexture,
            boolean hasBrightness,
            boolean hasColor,
            boolean hasNormals) {

        if (vertices == null || vertices.length != 4) {
            throw new IllegalArgumentException(
                    "TessellatorQuad requires exactly 4 vertices"
            );
        }

        this.vertices = vertices;

        this.hasTexture = hasTexture;
        this.hasBrightness = hasBrightness;
        this.hasColor = hasColor;
        this.hasNormals = hasNormals;
    }
}