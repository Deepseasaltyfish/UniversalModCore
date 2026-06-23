package cam72cam.mod.render;

import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.QuadTransformers;
import org.joml.Matrix4f;
import util.Matrix4;

import java.util.*;

/**
 * Internal class to scale an existing Baked Model
 * <p>
 * Do not use directly
 */
class BakedScaledModel implements BakedModel {
    // I know this is evil and I love it :D

    private final Matrix4 transform;
    private final BakedModel source;
    private final Map<Direction, List<BakedQuad>> quadCache = new HashMap<>();

    public BakedScaledModel(BakedModel source, Matrix4 transform) {
        this.source = source;
        this.transform = transform;
    }

    public BakedScaledModel(BakedModel source, float height) {
        this.source = source;
        transform = new Matrix4().scale(1, height, 1);
    }

    /**
     * Hack source model and adjust vertex to fit topFacing
     * */
    public BakedScaledModel(BakedModel source, float height, Vec3i basePos, Vec3d topFacing) {
        this.source = source;
        this.transform = new Matrix4().translate(basePos.x, basePos.y, basePos.z).scale(1, height, 1);

        if (topFacing == null) return;

        float[][] topVertexes = {{0, height, 0}, {1, height, 0}, {0, height, 1}, {1, height, 1}};
        float minTopY = Float.MAX_VALUE;
        float BottomY = 0;

        double centerX = 0.5, centerZ = 0.5;
        double dx = topFacing.x, dy = topFacing.y, dz = topFacing.z;
        if (Math.abs(dy) < 1e-5) return;

        double d0 = dx * centerX + dy * height + dz * centerZ;
        for (float[] v : topVertexes) {
            double y = (d0 - dx * v[0] - dz * v[2]) / dy;
            if (y < minTopY) minTopY = (float) y;
        }
        BottomY = Math.min(minTopY, BottomY);

        quadCache.clear();
        for (Direction side : Direction.values()) {
            List<BakedQuad> sideQuads = source.getQuads(null, side, RandomSource.create());
            if (sideQuads.isEmpty()) continue;

            List<BakedQuad> transformed = new ArrayList<>();
            for (BakedQuad quad : sideQuads) {
                int[] originalData = quad.getVertices();
                int[] newData = Arrays.copyOf(originalData, originalData.length);

                VertexFormat format = DefaultVertexFormat.BLOCK;
                int positionOffset = format.getOffset(VertexFormatElement.POSITION) / 4;
                int vertexStride = format.getVertexSize() / 4;

                for (int i = 0; i < 4; i++) {
                    int baseIndex = i * vertexStride + positionOffset;

                    float origX = Float.intBitsToFloat(newData[baseIndex]);
                    float origY = Float.intBitsToFloat(newData[baseIndex + 1]);
                    float origZ = Float.intBitsToFloat(newData[baseIndex + 2]);

                    float finalY;
                    if (Math.abs(origY - 1.0f) < 1e-5) {
                        double newY = (d0 - dx * origX - dz * origZ) / dy;
                        finalY = (float) newY;
                    } else if (Math.abs(origY) < 1e-5) {
                        finalY = BottomY;
                    } else {
                        finalY = origY;
                    }

                    newData[baseIndex]     = Float.floatToRawIntBits(basePos.x + origX);
                    newData[baseIndex + 1] = Float.floatToRawIntBits(basePos.y + finalY);
                    newData[baseIndex + 2] = Float.floatToRawIntBits(basePos.z + origZ);
                }

                transformed.add(new BakedQuad(
                        newData,
                        quad.getTintIndex(),
                        side,
                        quad.getSprite(),
                        quad.isShade()
                ));
            }
            quadCache.put(side, transformed);
        }
    }

    private List<BakedQuad> transformQuads(List<BakedQuad> quads, Matrix4 transform) {
        Matrix4f mat = transform.convertToMoj();
        Transformation transformation = new Transformation(mat);
        IQuadTransformer qt = QuadTransformers.applying(transformation);
        return qt.process(quads);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) {
        if (quadCache.get(side) == null) {
            List<BakedQuad> quads = source.getQuads(state, side, rand);
            quadCache.put(side, transformQuads(quads, transform));
        }
        return quadCache.get(side);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return source.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return source.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return false;
    }

    @Override
    public boolean isCustomRenderer() {
        return source.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return source.getParticleIcon();
    }

    @Override
    public ItemOverrides getOverrides() {
        return source.getOverrides();
    }

    @Override
    public ItemTransforms getTransforms() {
        return source.getTransforms();
    }
}