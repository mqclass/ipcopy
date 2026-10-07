package ru.mqclass.ipcopy.render;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * High-performance 2D SDF Instanced Batch Renderer built on Direct Off-Heap Native Memory
 * and OpenGL 3.3 Core.
 *
 * Implements hardware-instanced batch rendering of anti-aliased rounded rectangles,
 * soft-scissor virtual clipping, borders, and Gaussian drop shadows with zero allocations in the render loop.
 * Fully isolated OpenGL state stack guarantees 100% compatibility with Sodium, Iris, and Canvas.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class SdfRenderer2D implements AutoCloseable {

    private static final SdfRenderer2D INSTANCE = new SdfRenderer2D();

    public static final int MAX_INSTANCES = 4096;
    public static final int INSTANCE_STRIDE = 84; // 84 bytes per instance

    // Flags bitmask
    public static final int FLAG_SHADOW = 1;
    public static final int FLAG_FILL   = 2;
    public static final int FLAG_STROKE = 4;
    public static final int FLAG_GLOW   = 8;

    private final GL33SdfShader shader;
    private ByteBuffer instanceBuffer;
    private int instanceCount = 0;

    private int vao = 0;
    private int quadVbo = 0;
    private int quadEbo = 0;
    private int instanceVbo = 0;
    private boolean initialized = false;

    // Active virtual scissor clip
    private float clipCenterX = 0.0f;
    private float clipCenterY = 0.0f;
    private float clipWidth = 0.0f;
    private float clipHeight = 0.0f;
    private float clipRadius = 0.0f;

    // Saved OpenGL state stack for Sodium / Iris interop
    private int savedProgram;
    private int savedVao;
    private int savedArrayBuffer;
    private int savedElementBuffer;
    private boolean savedBlend;
    private int savedBlendSrcRgb;
    private int savedBlendDstRgb;
    private boolean savedDepthTest;
    private boolean savedScissorTest;

    private SdfRenderer2D() {
        this.shader = new GL33SdfShader();
    }

    public static SdfRenderer2D getInstance() {
        return INSTANCE;
    }

    public void init() {
        if (initialized) return;

        shader.init();

        // 1. Allocate native off-heap direct memory (344,064 bytes)
        this.instanceBuffer = ByteBuffer.allocateDirect(MAX_INSTANCES * INSTANCE_STRIDE).order(ByteOrder.nativeOrder());

        // 2. Generate VAO and Mesh Buffers
        vao = GL30C.glGenVertexArrays();
        GL30C.glBindVertexArray(vao);

        // Quad VBO: centered unit square [-0.5, 0.5]
        quadVbo = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, quadVbo);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer quadVertices = stack.floats(
                -0.5f, -0.5f,
                 0.5f, -0.5f,
                 0.5f,  0.5f,
                -0.5f,  0.5f
            );
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, quadVertices, GL15C.GL_STATIC_DRAW);
        }
        GL20C.glEnableVertexAttribArray(0);
        GL20C.glVertexAttribPointer(0, 2, GL11C.GL_FLOAT, false, 8, 0L);
        GL33C.glVertexAttribDivisor(0, 0);

        // Quad EBO: two triangles
        quadEbo = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, quadEbo);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ShortBuffer quadIndices = stack.shorts(
                (short) 0, (short) 1, (short) 2,
                (short) 2, (short) 3, (short) 0
            );
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, quadIndices, GL15C.GL_STATIC_DRAW);
        }

        // Instance VBO: Dynamic streaming buffer
        instanceVbo = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceVbo);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, (long) MAX_INSTANCES * INSTANCE_STRIDE, GL15C.GL_DYNAMIC_DRAW);

        // Setup Packed 84-byte Instance Attributes (divisor = 1)
        // 1: vec4 i_Bounds (offset 0)
        GL20C.glEnableVertexAttribArray(1);
        GL20C.glVertexAttribPointer(1, 4, GL11C.GL_FLOAT, false, INSTANCE_STRIDE, 0L);
        GL33C.glVertexAttribDivisor(1, 1);

        // 2: uint i_Color (offset 16)
        GL20C.glEnableVertexAttribArray(2);
        GL30C.glVertexAttribIPointer(2, 1, GL11C.GL_UNSIGNED_INT, INSTANCE_STRIDE, 16L);
        GL33C.glVertexAttribDivisor(2, 1);

        // 3: vec4 i_CornerRadii (offset 20)
        GL20C.glEnableVertexAttribArray(3);
        GL20C.glVertexAttribPointer(3, 4, GL11C.GL_FLOAT, false, INSTANCE_STRIDE, 20L);
        GL33C.glVertexAttribDivisor(3, 1);

        // 4: vec2 i_EdgeParams (offset 36)
        GL20C.glEnableVertexAttribArray(4);
        GL20C.glVertexAttribPointer(4, 2, GL11C.GL_FLOAT, false, INSTANCE_STRIDE, 36L);
        GL33C.glVertexAttribDivisor(4, 1);

        // 5: uint i_Flags (offset 44)
        GL20C.glEnableVertexAttribArray(5);
        GL30C.glVertexAttribIPointer(5, 1, GL11C.GL_UNSIGNED_INT, INSTANCE_STRIDE, 44L);
        GL33C.glVertexAttribDivisor(5, 1);

        // 6: vec4 i_ClipBounds (offset 48)
        GL20C.glEnableVertexAttribArray(6);
        GL20C.glVertexAttribPointer(6, 4, GL11C.GL_FLOAT, false, INSTANCE_STRIDE, 48L);
        GL33C.glVertexAttribDivisor(6, 1);

        // 7: float i_ClipRadius (offset 64)
        GL20C.glEnableVertexAttribArray(7);
        GL20C.glVertexAttribPointer(7, 1, GL11C.GL_FLOAT, false, INSTANCE_STRIDE, 64L);
        GL33C.glVertexAttribDivisor(7, 1);

        // 8: vec4 i_ShadowParams (offset 68)
        GL20C.glEnableVertexAttribArray(8);
        GL20C.glVertexAttribPointer(8, 4, GL11C.GL_FLOAT, false, INSTANCE_STRIDE, 68L);
        GL33C.glVertexAttribDivisor(8, 1);

        GL30C.glBindVertexArray(0);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);

        initialized = true;
    }

    /**
     * Begins rendering with full OpenGL state isolation.
     */
    public void begin(Matrix4f projMatrix) {
        if (!initialized) init();

        // 1. Snapshot pipeline states for Sodium / Iris compatibility
        savedProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        savedVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        savedArrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        savedElementBuffer = GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        savedBlend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        savedBlendSrcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        savedBlendDstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        savedDepthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        savedScissorTest = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);

        // 2. Configure 2D alpha blending state
        if (!savedBlend) GL11C.glEnable(GL11C.GL_BLEND);
        GL14C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
        if (savedDepthTest) GL11C.glDisable(GL11C.GL_DEPTH_TEST);

        // 3. Bind shader and upload projection
        shader.bind();
        shader.setProjectionMatrix(projMatrix);

        if (this.instanceBuffer != null) {
            this.instanceBuffer.clear();
        }
        this.instanceCount = 0;
    }

    /**
     * Sets virtual soft scissor clipping rectangle.
     */
    public void setClip(float x, float y, float width, float height, float radius) {
        this.clipCenterX = x + width * 0.5f;
        this.clipCenterY = y + height * 0.5f;
        this.clipWidth = width;
        this.clipHeight = height;
        this.clipRadius = radius;
    }

    /**
     * Clears active virtual scissor clipping.
     */
    public void clearClip() {
        this.clipWidth = 0.0f;
        this.clipHeight = 0.0f;
    }

    /**
     * Direct zero-allocation draw call writing into off-heap direct buffer.
     */
    public void drawInstance(
        float x, float y, float width, float height,
        int packedRgba8,
        float rTL, float rTR, float rBR, float rBL,
        float strokeWidth, float aaSoftness,
        int flags,
        float shadowOffsetX, float shadowOffsetY, float shadowBlur, float shadowIntensity
    ) {
        if (instanceBuffer == null) {
            return;
        }
        if (instanceCount >= MAX_INSTANCES) {
            flush();
        }

        // CPU Corner Radius Normalization (W3C CSS specification)
        float sumTop = rTL + rTR;
        float sumBottom = rBL + rBR;
        float sumLeft = rTL + rBL;
        float sumRight = rTR + rBR;
        float f = 1.0f;
        if (sumTop > width && sumTop > 0.0f) f = Math.min(f, width / sumTop);
        if (sumBottom > width && sumBottom > 0.0f) f = Math.min(f, width / sumBottom);
        if (sumLeft > height && sumLeft > 0.0f) f = Math.min(f, height / sumLeft);
        if (sumRight > height && sumRight > 0.0f) f = Math.min(f, height / sumRight);

        rTL *= f;
        rTR *= f;
        rBR *= f;
        rBL *= f;

        float centerX = x + width * 0.5f;
        float centerY = y + height * 0.5f;

        int base = instanceCount * INSTANCE_STRIDE;
        if (base + INSTANCE_STRIDE > instanceBuffer.limit()) {
            instanceBuffer.limit(instanceBuffer.capacity());
        }
        if (base + INSTANCE_STRIDE > instanceBuffer.capacity()) {
            return;
        }

        // Offset 0: i_Bounds (16 bytes)
        instanceBuffer.putFloat(base + 0, centerX);
        instanceBuffer.putFloat(base + 4, centerY);
        instanceBuffer.putFloat(base + 8, width);
        instanceBuffer.putFloat(base + 12, height);

        // Offset 16: i_Color (4 bytes)
        instanceBuffer.putInt(base + 16, packedRgba8);

        // Offset 20: i_CornerRadii (16 bytes)
        instanceBuffer.putFloat(base + 20, rTL);
        instanceBuffer.putFloat(base + 24, rTR);
        instanceBuffer.putFloat(base + 28, rBR);
        instanceBuffer.putFloat(base + 32, rBL);

        // Offset 36: i_EdgeParams (8 bytes)
        instanceBuffer.putFloat(base + 36, strokeWidth);
        instanceBuffer.putFloat(base + 40, Math.max(aaSoftness, 0.75f));

        // Offset 44: i_Flags (4 bytes)
        instanceBuffer.putInt(base + 44, flags);

        // Offset 48: i_ClipBounds (16 bytes)
        instanceBuffer.putFloat(base + 48, clipCenterX);
        instanceBuffer.putFloat(base + 52, clipCenterY);
        instanceBuffer.putFloat(base + 56, clipWidth);
        instanceBuffer.putFloat(base + 60, clipHeight);

        // Offset 64: i_ClipRadius (4 bytes)
        instanceBuffer.putFloat(base + 64, clipRadius);

        // Offset 68: i_ShadowParams (16 bytes)
        instanceBuffer.putFloat(base + 68, shadowOffsetX);
        instanceBuffer.putFloat(base + 72, shadowOffsetY);
        instanceBuffer.putFloat(base + 76, shadowBlur);
        instanceBuffer.putFloat(base + 80, shadowIntensity);

        instanceCount++;
    }

    /**
     * High-level helper: Solid rounded rectangle with fill.
     */
    public void drawRoundedRect(float x, float y, float w, float h, float radius, int argbColor) {
        int rgba = argbToRgba(argbColor);
        drawInstance(x, y, w, h, rgba, radius, radius, radius, radius, 0.0f, 1.0f, FLAG_FILL, 0, 0, 0, 0);
    }

    /**
     * High-level helper: Rounded rectangle with distinct corner radii.
     */
    public void drawRoundedRectVarying(float x, float y, float w, float h, float rTL, float rTR, float rBR, float rBL, int argbColor) {
        int rgba = argbToRgba(argbColor);
        drawInstance(x, y, w, h, rgba, rTL, rTR, rBR, rBL, 0.0f, 1.0f, FLAG_FILL, 0, 0, 0, 0);
    }

    /**
     * High-level helper: Rounded rectangle with border stroke.
     */
    public void drawRoundedRectWithStroke(float x, float y, float w, float h, float radius, int fillColor, float strokeWidth, int strokeColor) {
        drawRoundedRect(x, y, w, h, radius, fillColor);
        int strokeRgba = argbToRgba(strokeColor);
        drawInstance(x, y, w, h, strokeRgba, radius, radius, radius, radius, strokeWidth, 1.0f, FLAG_STROKE, 0, 0, 0, 0);
    }

    /**
     * High-level helper: Modern Glassmorphic card with Gaussian drop shadow and subtle border.
     */
    public void drawGlassCard(
        float x, float y, float w, float h,
        float radius,
        int bgArgb,
        int borderArgb,
        float shadowBlur,
        float shadowOffsetY
    ) {
        int bgRgba = argbToRgba(bgArgb);

        // 1. Draw Drop Shadow + Background fill
        drawInstance(
            x, y, w, h,
            bgRgba,
            radius, radius, radius, radius,
            0.0f, 1.0f,
            FLAG_FILL | FLAG_SHADOW,
            0.0f, shadowOffsetY, shadowBlur, 0.65f
        );

        // 2. Draw Subtle Border stroke
        if (borderArgb != 0) {
            int borderRgba = argbToRgba(borderArgb);
            drawInstance(
                x, y, w, h,
                borderRgba,
                radius, radius, radius, radius,
                1.0f, 1.0f,
                FLAG_STROKE,
                0, 0, 0, 0
            );
        }
    }

    /**
     * Flushes buffered instances to GPU via glBufferSubData and issues glDrawElementsInstanced.
     */
    public void flush() {
        if (instanceCount == 0 || instanceBuffer == null) return;

        try {
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceVbo);

            instanceBuffer.position(0);
            instanceBuffer.limit(instanceCount * INSTANCE_STRIDE);
            GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0, instanceBuffer);

            // Instanced draw call: 6 vertices per quad, instanceCount instances
            GL33C.glDrawElementsInstanced(GL11C.GL_TRIANGLES, 6, GL11C.GL_UNSIGNED_SHORT, 0L, instanceCount);
        } finally {
            instanceBuffer.clear();
            GL30C.glBindVertexArray(0);
            instanceCount = 0;
        }
    }

    /**
     * Ends batch, flushes instances, and cleanly restores original OpenGL state.
     */
    public void end() {
        try {
            flush();
        } finally {
            shader.unbind();

            // Restore pipeline state for Sodium / Iris compatibility
            if (savedDepthTest) GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            if (!savedBlend) GL11C.glDisable(GL11C.GL_BLEND);
            GL14C.glBlendFunc(savedBlendSrcRgb, savedBlendDstRgb);
            if (savedScissorTest) GL11C.glEnable(GL11C.GL_SCISSOR_TEST); else GL11C.glDisable(GL11C.GL_SCISSOR_TEST);

            GL30C.glBindVertexArray(savedVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, savedArrayBuffer);
            GL20C.glUseProgram(savedProgram);
        }
    }

    private static int argbToRgba(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r) | (g << 8) | (b << 16) | (a << 24);
    }

    @Override
    public void close() {
        if (initialized) {
            if (vao != 0) {
                GL30C.glDeleteVertexArrays(vao);
                vao = 0;
            }
            if (quadVbo != 0) {
                GL15C.glDeleteBuffers(quadVbo);
                quadVbo = 0;
            }
            if (quadEbo != 0) {
                GL15C.glDeleteBuffers(quadEbo);
                quadEbo = 0;
            }
            if (instanceVbo != 0) {
                GL15C.glDeleteBuffers(instanceVbo);
                instanceVbo = 0;
            }
            instanceBuffer = null;
            shader.close();
            initialized = false;
        }
    }
}
