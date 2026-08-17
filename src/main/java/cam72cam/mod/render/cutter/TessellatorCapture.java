package cam72cam.mod.render.cutter;

import cam72cam.mod.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TessellatorCapture {

    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();

    private TessellatorCapture() {
    }

    public static void begin() {
        CURRENT.set(new Capture());
    }

    public static List<TessellatorQuad> end() {
        Capture capture = CURRENT.get();
        CURRENT.remove();

        if (capture == null) {
            return Collections.emptyList();
        }

        return capture.buildQuads();
    }

    public static boolean isCapturing() {
        return CURRENT.get() != null;
    }

    public static void captureVertex(
            double x,
            double y,
            double z,
            double u,
            double v,
            int color,
            int light,
            byte nx,
            byte ny,
            byte nz,
            boolean hasTexture,
            boolean hasBrightness,
            boolean hasColor,
            boolean hasNormals) {

        Capture capture = CURRENT.get();
        if (capture == null) {
            return;
        }

        capture.addVertex(
                new ClipVertex(
                        new Vec3d(x, y, z),
                        (float) u,
                        (float) v,
                        color,
                        light,
                        nx,
                        ny,
                        nz
                ),
                hasTexture,
                hasBrightness,
                hasColor,
                hasNormals
        );
    }

    private static class Capture {

        private final List<TessellatorQuad> quads = new ArrayList<>();

        private final List<ClipVertex> vertices =
                new ArrayList<>(4);

        private boolean hasTexture;
        private boolean hasBrightness;
        private boolean hasColor;
        private boolean hasNormals;

        private void addVertex(
                ClipVertex vertex,
                boolean hasTexture,
                boolean hasBrightness,
                boolean hasColor,
                boolean hasNormals) {

            if (vertices.isEmpty()) {
                this.hasTexture = hasTexture;
                this.hasBrightness = hasBrightness;
                this.hasColor = hasColor;
                this.hasNormals = hasNormals;
            }

            vertices.add(vertex);

            if (vertices.size() == 4) {
                quads.add(
                        new TessellatorQuad(
                                vertices.toArray(new ClipVertex[4]),
                                this.hasTexture,
                                this.hasBrightness,
                                this.hasColor,
                                this.hasNormals
                        )
                );

                vertices.clear();
            }
        }

        private List<TessellatorQuad> buildQuads() {
            return quads;
        }
    }
}