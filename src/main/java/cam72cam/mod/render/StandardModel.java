package cam72cam.mod.render;

import cam72cam.mod.item.ItemStack;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.render.opengl.RenderContext;
import cam72cam.mod.render.opengl.RenderState;
import cam72cam.mod.render.opengl.Texture;
import cam72cam.mod.resource.Identifier;
import cam72cam.mod.util.With;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;
import org.lwjgl.opengl.GL11;
import util.Matrix4;

import java.util.ArrayList;
import java.util.List;

public class StandardModel {

    private final List<BakedQuad> quads = new ArrayList<>();
    private final List<RenderFunction> custom = new ArrayList<>();

    // ----- 添加方块模型（转换为 BakedQuad） ---------------------------------

    public StandardModel addItemBlock(ItemStack stack, Matrix4 transform) {
        if (stack.isEmpty()) return this;

        Block block = Block.getBlockFromItem(stack.internal.getItem());
        int meta = stack.internal.getMetadata();
        if (block == null) return this;

        if (block instanceof BlockRotatedPillar) {
            meta = 2;
        }

        Vec3d min = new Vec3d(0, 0, 0);
        Vec3d max = new Vec3d(1, 1, 1);

        for (int face = 0; face < 6; face++) {
            IIcon icon = block.getIcon(face, meta);
            if (icon == null) continue;

            Vec3d[] corners = getFaceCorners(min, max, face);
            if (corners == null) continue;

            Vec3d[] transformed = new Vec3d[4];
            for (int i = 0; i < 4; i++) {
                transformed[i] = transform.apply(corners[i]);
            }

            // 法线
            byte[] nx = new byte[4];
            byte[] ny = new byte[4];
            byte[] nz = new byte[4];
            byte[] norm = getNormalForFace(face);
            for (int i = 0; i < 4; i++) {
                nx[i] = norm[0];
                ny[i] = norm[1];
                nz[i] = norm[2];
            }

            // ---- 使用 interpolated UV ----
            float[] u = new float[4];
            float[] v = new float[4];
            for (int i = 0; i < 4; i++) {
                Vec3d p = corners[i]; // 使用变换前的角点计算局部坐标
                double lx = (p.x - min.x) / (max.x - min.x);
                double ly = (p.y - min.y) / (max.y - min.y);
                double lz = (p.z - min.z) / (max.z - min.z);

                double localU, localV;
                switch (face) {
                    case 0: // 下 (y=0)
                        localU = lx;
                        localV = 1.0 - lz;
                        break;
                    case 1: // 上 (y=1)
                        localU = lx;
                        localV = lz; // 尝试不翻转，因为顶点已反转，可能纹理方向已正确
                        break;
                    case 2: // 北 (z=0)
                        localU = lx;
                        localV = 1.0 - ly;
                        break;
                    case 3: // 南 (z=1)
                        localU = lx;
                        localV = 1.0 - ly;
                        break;
                    case 4: // 西 (x=0)
                        localU = 1.0 - lz;
                        localV = 1.0 - ly;
                        break;
                    case 5: // 东 (x=1)
                        localU = lz;
                        localV = 1.0 - ly;
                        break;
                    default:
                        localU = 0;
                        localV = 0;
                }
                localU = Math.min(1.0, Math.max(0.0, localU));
                localV = Math.min(1.0, Math.max(0.0, localV));
                u[i] = icon.getInterpolatedU((float) (localU * 16));
                v[i] = icon.getInterpolatedV((float) (localV * 16));
            }

            // ---- 对顶面、北、西反转顶点顺序和 UV（与你的原始逻辑一致） ----
            if (face == 1 || face == 2 || face == 4) {
                Vec3d[] reversedPos = new Vec3d[4];
                float[] reversedU = new float[4];
                float[] reversedV = new float[4];
                for (int i = 0; i < 4; i++) {
                    reversedPos[i] = transformed[3 - i];
                    reversedU[i] = u[3 - i];
                    reversedV[i] = v[3 - i];
                }
                transformed = reversedPos;
                u = reversedU;
                v = reversedV;
            }

            int[] color = new int[]{-1, -1, -1, -1};
            int[] lightmap = new int[]{0, 0, 0, 0};

            BakedQuad quad = new BakedQuad(
                    transformed, u, v,
                    color, lightmap,
                    nx, ny, nz,
                    icon, face
            );
            quads.add(quad);
        }

        return this;
    }

    public StandardModel addColorBlock(Color color, Matrix4 transform) {
        ItemStack stack = new ItemStack(new net.minecraft.item.ItemStack(Blocks.stained_hardened_clay, 1, color.internal));
        return addItemBlock(stack, transform);
    }

    public StandardModel addSnow(int layers, Matrix4 transform) {
        Matrix4 scaled = transform.copy();
        float height = Math.max(1, Math.min(8, layers)) / 8.0f;
        scaled.scale(1, height, 1);
        ItemStack stack = new ItemStack(new net.minecraft.item.ItemStack(Blocks.snow_layer));
        return addItemBlock(stack, scaled);
    }

    // ----- 自定义渲染（保留原有 addItem 功能） ---------------------------------

    public StandardModel addItem(ItemStack stack, Matrix4 apply) {
        if (stack.isEmpty()) return this;
        custom.add((state, pt) -> {
            state = state.clone().texture(Texture.wrap(new Identifier(TextureMap.locationBlocksTexture)));
            state.model_view().multiply(apply);

            try (With ctx = RenderContext.apply(state)) {
                IItemRenderer ir = MinecraftForgeClient.getItemRenderer(stack.internal, IItemRenderer.ItemRenderType.ENTITY);
                if (ir != null) {
                    ir.renderItem(IItemRenderer.ItemRenderType.ENTITY, stack.internal);
                } else {
                    Block block = Block.getBlockFromItem(stack.internal.getItem());
                    if (block != null) {
                        RenderBlocks rb = new RenderBlocks();
                        rb.renderBlockAsItem(block, stack.internal.getMetadata(), 1.0f);
                    }
                }
            }
        });
        return this;
    }

    public StandardModel addCustom(RenderFunction fn) {
        this.custom.add(fn);
        return this;
    }

    // ----- 辅助方法 ------------------------------------------------

    private Vec3d[] getFaceCorners(Vec3d min, Vec3d max, int face) {
        double x0 = min.x, y0 = min.y, z0 = min.z;
        double x1 = max.x, y1 = max.y, z1 = max.z;
        Vec3d[] corners = new Vec3d[4];
        switch (face) {
            case 0: // 下
                corners[0] = new Vec3d(x0, y0, z0);
                corners[1] = new Vec3d(x1, y0, z0);
                corners[2] = new Vec3d(x1, y0, z1);
                corners[3] = new Vec3d(x0, y0, z1);
                break;
            case 1: // 上
                corners[0] = new Vec3d(x0, y1, z0);
                corners[1] = new Vec3d(x1, y1, z0);
                corners[2] = new Vec3d(x1, y1, z1);
                corners[3] = new Vec3d(x0, y1, z1);
                break;
            case 2: // 北
                corners[0] = new Vec3d(x0, y0, z0);
                corners[1] = new Vec3d(x1, y0, z0);
                corners[2] = new Vec3d(x1, y1, z0);
                corners[3] = new Vec3d(x0, y1, z0);
                break;
            case 3: // 南
                corners[0] = new Vec3d(x0, y0, z1);
                corners[1] = new Vec3d(x1, y0, z1);
                corners[2] = new Vec3d(x1, y1, z1);
                corners[3] = new Vec3d(x0, y1, z1);
                break;
            case 4: // 西
                corners[0] = new Vec3d(x0, y0, z0);
                corners[1] = new Vec3d(x0, y1, z0);
                corners[2] = new Vec3d(x0, y1, z1);
                corners[3] = new Vec3d(x0, y0, z1);
                break;
            case 5: // 东
                corners[0] = new Vec3d(x1, y0, z0);
                corners[1] = new Vec3d(x1, y1, z0);
                corners[2] = new Vec3d(x1, y1, z1);
                corners[3] = new Vec3d(x1, y0, z1);
                break;
            default: return null;
        }
        return corners;
    }

    private byte[] getNormalForFace(int face) {
        switch (face) {
            case 0: return new byte[]{0, -127, 0};
            case 1: return new byte[]{0, 127, 0};
            case 2: return new byte[]{0, 0, -127};
            case 3: return new byte[]{0, 0, 127};
            case 4: return new byte[]{-127, 0, 0};
            case 5: return new byte[]{127, 0, 0};
            default: return new byte[]{0, 0, 0};
        }
    }

    // ----- 渲染 ----------------------------------------------------

    public void render(RenderState state) {
        render(state, 0);
    }

    public void render(RenderState state, float partialTicks) {
        renderCustom(state, partialTicks);

        if (!quads.isEmpty()) {
            try (With ctx = RenderContext.apply(state.clone().texture(Texture.wrap(new Identifier(TextureMap.locationBlocksTexture))))) {
                Tessellator tessellator = Tessellator.instance;
                tessellator.startDrawingQuads();

                for (BakedQuad quad : quads) {
                    for (int i = 0; i < 4; i++) {
                        tessellator.addVertex(quad.pos[i].x, quad.pos[i].y, quad.pos[i].z);
                        tessellator.setTextureUV(quad.u[i], quad.v[i]);
                        if (quad.color[i] != -1) {
                            int c = quad.color[i];
                            tessellator.setColorRGBA((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >> 24) & 0xFF);
                        } else {
                            tessellator.setColorRGBA(255, 255, 255, 255);
                        }
                        tessellator.setNormal(quad.nx[i] / 127.0f, quad.ny[i] / 127.0f, quad.nz[i] / 127.0f);
                        tessellator.setBrightness(0x00F000F0); // 物品渲染使用最大亮度
                    }
                }
                tessellator.draw();
            }
        }
    }

    public void renderQuads(IBlockAccess world, int x, int y, int z) {
        if (quads.isEmpty()) return;

        Tessellator tessellator = Tessellator.instance;
        // 获取块光照和天空光照
        int light = world.getLightBrightnessForSkyBlocks(x, y, z, 0);
        tessellator.setColorRGBA(255, 255, 255, 255);

        for (BakedQuad quad : quads) {
            for (int i = 0; i < 4; i++) {
                tessellator.addVertex(quad.pos[i].x + x, quad.pos[i].y + y, quad.pos[i].z + z);
                tessellator.setTextureUV(quad.u[i], quad.v[i]);
                if (quad.color[i] != -1) {
                    int c = quad.color[i];
                    tessellator.setColorRGBA((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >> 24) & 0xFF);
                } else {
                    tessellator.setColorRGBA(255, 255, 255, 255);
                }
                tessellator.setNormal(quad.nx[i] / 127.0f, quad.ny[i] / 127.0f, quad.nz[i] / 127.0f);
                tessellator.setBrightness(light);
            }
        }
    }

    public void renderCustom(RenderState state) {
        renderCustom(state, 0);
    }

    public void renderCustom(RenderState state, float partialTicks) {
        custom.forEach(cons -> cons.render(state.clone(), partialTicks));
    }

    public boolean hasCustom() {
        return !custom.isEmpty();
    }

    // ----- 辅助获取/设置 ------------------------------------------------

    public List<BakedQuad> getQuads() {
        return quads;
    }

    public void clearQuads() {
        quads.clear();
    }

    public void addQuads(List<BakedQuad> additional) {
        quads.addAll(additional);
    }
}