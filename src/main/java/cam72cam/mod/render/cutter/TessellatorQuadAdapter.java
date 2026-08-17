package cam72cam.mod.render.cutter;

import cam72cam.mod.math.Plane;

import java.util.ArrayList;
import java.util.List;

public class TessellatorQuadAdapter
        implements PrimitiveAdapter<TessellatorQuad, TessellatorQuadTemplate> {

    @Override
    public Polygon toPolygon(TessellatorQuad quad) {
        List<ClipVertex> vertices = new ArrayList<>(4);

        for (ClipVertex vertex : quad.vertices) {
            vertices.add(vertex.copy());
        }

        return new Polygon(vertices, null);
    }

    @Override
    public List<TessellatorQuad> fromPrimitive(
            Polygon polygon,
            TessellatorQuad primitive) {

        List<TessellatorQuad> result = new ArrayList<>();

        if (polygon.getVertices().size() < 3) {
            return result;
        }

        for (Polygon quad : Polygon.convexToQuads(polygon)) {
            List<ClipVertex> vertices = quad.getVertices();

            ClipVertex[] out = new ClipVertex[] {
                    vertices.get(0).copy(),
                    vertices.get(1).copy(),
                    vertices.get(2).copy(),
                    vertices.get(3).copy()
            };

            result.add(new TessellatorQuad(
                    out,
                    primitive.hasTexture,
                    primitive.hasBrightness,
                    primitive.hasColor,
                    primitive.hasNormals
            ));
        }

        return result;
    }

    @Override
    public TessellatorQuadTemplate createTemplate(
            List<TessellatorQuad> quads,
            Plane plane) {

        if (quads.isEmpty()) {
            return null;
        }

        TessellatorQuad source = quads.get(0);

        ClipVertex[] sourceVertices = new ClipVertex[4];

        for (int i = 0; i < 4; i++) {
            sourceVertices[i] = source.vertices[i].copy();
        }

        return new TessellatorQuadTemplate(
                sourceVertices,
                source.hasTexture,
                source.hasBrightness,
                source.hasColor,
                source.hasNormals
        );
    }

    @Override
    public void prepareCap(
            Polygon polygon,
            Plane plane,
            TessellatorQuadTemplate template) {

        // Tessellator Quad 的 UV 直接使用模板进行平面插值。
        Polygon.generateUV(polygon, template);
    }

    @Override
    public List<TessellatorQuad> fromTemplate(
            Polygon polygon,
            TessellatorQuadTemplate template) {

        List<TessellatorQuad> result = new ArrayList<>();

        if (polygon.getVertices().size() < 3) {
            return result;
        }

        for (Polygon quad : Polygon.convexToQuads(polygon)) {
            List<ClipVertex> vertices = quad.getVertices();

            ClipVertex[] out = new ClipVertex[] {
                    vertices.get(0).copy(),
                    vertices.get(1).copy(),
                    vertices.get(2).copy(),
                    vertices.get(3).copy()
            };

            result.add(new TessellatorQuad(
                    out,
                    template.hasTexture,
                    template.hasBrightness,
                    template.hasColor,
                    template.hasNormals
            ));
        }

        return result;
    }
}