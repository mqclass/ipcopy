package ru.mqclass.ipcopy.render;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Modern OpenGL 3.3 Core shader program for 2D SDF instanced rendering.
 * Supports Euclidean Box SDF, soft-scissor virtual clipping, AA borders, and Gaussian drop shadows.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class GL33SdfShader implements AutoCloseable {

    private int programId = 0;
    private int vertShaderId = 0;
    private int fragShaderId = 0;
    private int uProjMatrixLoc = -1;

    private static final String FALLBACK_VERT = """
        #version 330 core
        layout(location = 0) in vec2 a_Pos;
        layout(location = 1) in vec4 i_Bounds;
        layout(location = 2) in uint i_Color;
        layout(location = 3) in vec4 i_CornerRadii;
        layout(location = 4) in vec2 i_EdgeParams;
        layout(location = 5) in uint i_Flags;
        layout(location = 6) in vec4 i_ClipBounds;
        layout(location = 7) in float i_ClipRadius;
        layout(location = 8) in vec4 i_ShadowParams;
        uniform mat4 u_ProjMatrix;
        out vec2 v_LocalPos;
        out vec2 v_HalfSize;
        out vec4 v_Color;
        out vec4 v_Radii;
        out vec2 v_EdgeParams;
        flat out uint v_Flags;
        out vec4 v_ClipBounds;
        out float v_ClipRadius;
        out vec4 v_ShadowParams;
        out vec2 v_WorldPos;
        void main() {
            float padding = max(i_ShadowParams.z * 2.0 + length(i_ShadowParams.xy), i_EdgeParams.x + i_EdgeParams.y * 2.0 + 2.0);
            vec2 size = i_Bounds.zw + vec2(padding * 2.0);
            vec2 worldPos = i_Bounds.xy + a_Pos * size;
            v_WorldPos = worldPos;
            v_LocalPos = a_Pos * size;
            v_HalfSize = i_Bounds.zw * 0.5;
            float r = float(i_Color & 0xFFu) / 255.0;
            float g = float((i_Color >> 8u) & 0xFFu) / 255.0;
            float b = float((i_Color >> 16u) & 0xFFu) / 255.0;
            float a = float((i_Color >> 24u) & 0xFFu) / 255.0;
            v_Color = vec4(r, g, b, a);
            v_Radii = i_CornerRadii;
            v_EdgeParams = i_EdgeParams;
            v_Flags = i_Flags;
            v_ClipBounds = i_ClipBounds;
            v_ClipRadius = i_ClipRadius;
            v_ShadowParams = i_ShadowParams;
            gl_Position = u_ProjMatrix * vec4(worldPos, 0.0, 1.0);
        }
        """;

    private static final String FALLBACK_FRAG = """
        #version 330 core
        in vec2 v_LocalPos;
        in vec2 v_HalfSize;
        in vec4 v_Color;
        in vec4 v_Radii;
        in vec2 v_EdgeParams;
        flat in uint v_Flags;
        in vec4 v_ClipBounds;
        in float v_ClipRadius;
        in vec4 v_ShadowParams;
        in vec2 v_WorldPos;
        out vec4 fragColor;
        float sdRoundedBox(vec2 p, vec2 b, vec4 r) {
            vec2 rx = (p.x > 0.0) ? vec2(r.y, r.z) : vec2(r.x, r.w);
            float rad = (p.y > 0.0) ? rx.y : rx.x;
            vec2 q = abs(p) - b + vec2(rad);
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
        }
        void main() {
            float clipAlpha = 1.0;
            if (v_ClipBounds.z > 0.0 && v_ClipBounds.w > 0.0) {
                vec2 clipP = v_WorldPos - v_ClipBounds.xy;
                vec2 clipHalf = v_ClipBounds.zw * 0.5;
                float cr = max(v_ClipRadius, 0.0);
                vec2 cq = abs(clipP) - clipHalf + vec2(cr);
                float clipDist = min(max(cq.x, cq.y), 0.0) + length(max(cq, 0.0)) - cr;
                float clipFw = max(fwidth(clipDist), 0.0001);
                clipAlpha = clamp(0.5 - clipDist / clipFw, 0.0, 1.0);
            }
            float d = sdRoundedBox(v_LocalPos, v_HalfSize, v_Radii);
            float aa = max(v_EdgeParams.y, max(fwidth(d), 0.001));
            vec4 finalColor = vec4(0.0);
            if ((v_Flags & 1u) != 0u && v_ShadowParams.z > 0.0 && v_ShadowParams.w > 0.0) {
                vec2 sPos = v_LocalPos - v_ShadowParams.xy;
                float sd = sdRoundedBox(sPos, v_HalfSize, v_Radii);
                float blur = max(v_ShadowParams.z, 0.5);
                float sAlpha = smoothstep(blur, -blur * 0.4, sd) * v_ShadowParams.w;
                finalColor = vec4(0.0, 0.0, 0.0, sAlpha);
            }
            if ((v_Flags & 2u) != 0u) {
                float fillAlpha = clamp(0.5 - d / aa, 0.0, 1.0);
                vec4 fillColor = vec4(v_Color.rgb, v_Color.a * fillAlpha);
                finalColor = fillColor + finalColor * (1.0 - fillColor.a);
            }
            if ((v_Flags & 4u) != 0u && v_EdgeParams.x > 0.0) {
                float strokeWidth = v_EdgeParams.x;
                float strokeDist = abs(d + strokeWidth * 0.5) - strokeWidth * 0.5;
                float strokeAlpha = clamp(0.5 - strokeDist / aa, 0.0, 1.0);
                vec4 strokeColor = vec4(v_Color.rgb, v_Color.a * strokeAlpha);
                finalColor = strokeColor + finalColor * (1.0 - strokeColor.a);
            }
            finalColor *= clipAlpha;
            if (finalColor.a < 0.001) {
                discard;
            }
            fragColor = finalColor;
        }
        """;

    public GL33SdfShader() {
        init();
    }

    public void init() {
        if (programId != 0) return;

        String vertSource = loadShaderResource("/assets/ipcopy/shaders/sdf_rect.vert", FALLBACK_VERT);
        String fragSource = loadShaderResource("/assets/ipcopy/shaders/sdf_rect.frag", FALLBACK_FRAG);

        vertShaderId = compileShader(GL20C.GL_VERTEX_SHADER, vertSource);
        fragShaderId = compileShader(GL20C.GL_FRAGMENT_SHADER, fragSource);

        programId = GL20C.glCreateProgram();
        GL20C.glAttachShader(programId, vertShaderId);
        GL20C.glAttachShader(programId, fragShaderId);

        // Bind attribute locations explicitly
        GL20C.glBindAttribLocation(programId, 0, "a_Pos");
        GL20C.glBindAttribLocation(programId, 1, "i_Bounds");
        GL20C.glBindAttribLocation(programId, 2, "i_Color");
        GL20C.glBindAttribLocation(programId, 3, "i_CornerRadii");
        GL20C.glBindAttribLocation(programId, 4, "i_EdgeParams");
        GL20C.glBindAttribLocation(programId, 5, "i_Flags");
        GL20C.glBindAttribLocation(programId, 6, "i_ClipBounds");
        GL20C.glBindAttribLocation(programId, 7, "i_ClipRadius");
        GL20C.glBindAttribLocation(programId, 8, "i_ShadowParams");

        GL20C.glLinkProgram(programId);

        int linkStatus = GL20C.glGetProgrami(programId, GL20C.GL_LINK_STATUS);
        if (linkStatus == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(programId);
            System.err.println("[IPCopy] GL33SdfShader link failed: " + log);
            throw new IllegalStateException("Failed to link SDF shader: " + log);
        }

        uProjMatrixLoc = GL20C.glGetUniformLocation(programId, "u_ProjMatrix");
    }

    private static int compileShader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);

        int status = GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS);
        if (status == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            System.err.println("[IPCopy] Shader compile error (" + type + "): " + log);
            throw new IllegalStateException("Shader compilation failed: " + log);
        }
        return shader;
    }

    private static String loadShaderResource(String resourcePath, String fallback) {
        try (InputStream in = GL33SdfShader.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    public void bind() {
        GL20C.glUseProgram(programId);
    }

    public void unbind() {
        GL20C.glUseProgram(0);
    }

    public void setProjectionMatrix(Matrix4f matrix) {
        if (uProjMatrixLoc != -1 && matrix != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer fb = stack.mallocFloat(16);
                matrix.get(fb);
                GL20C.glUniformMatrix4fv(uProjMatrixLoc, false, fb);
            }
        }
    }

    public void setProjectionMatrix(float[] mat16) {
        if (uProjMatrixLoc != -1 && mat16 != null && mat16.length >= 16) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer fb = stack.mallocFloat(16);
                fb.put(mat16).flip();
                GL20C.glUniformMatrix4fv(uProjMatrixLoc, false, fb);
            }
        }
    }

    public int getProgramId() {
        return programId;
    }

    @Override
    public void close() {
        if (programId != 0) {
            GL20C.glUseProgram(0);
            if (vertShaderId != 0) {
                GL20C.glDetachShader(programId, vertShaderId);
                GL20C.glDeleteShader(vertShaderId);
                vertShaderId = 0;
            }
            if (fragShaderId != 0) {
                GL20C.glDetachShader(programId, fragShaderId);
                GL20C.glDeleteShader(fragShaderId);
                fragShaderId = 0;
            }
            GL20C.glDeleteProgram(programId);
            programId = 0;
        }
    }
}
