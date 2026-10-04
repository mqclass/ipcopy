#version 330 core

// Quad mesh vertex in range [-0.5, 0.5]
layout(location = 0) in vec2 a_Pos;

// Packed 84-byte instance attributes (divisor = 1)
layout(location = 1) in vec4 i_Bounds;        // CenterX, CenterY, Width, Height
layout(location = 2) in uint i_Color;         // Packed RGBA8
layout(location = 3) in vec4 i_CornerRadii;   // TL, TR, BR, BL
layout(location = 4) in vec2 i_EdgeParams;    // x: strokeWidth, y: aaSoftness
layout(location = 5) in uint i_Flags;         // bit 0: shadow, bit 1: fill, bit 2: stroke, bit 3: glow
layout(location = 6) in vec4 i_ClipBounds;    // CenterX, CenterY, Width, Height
layout(location = 7) in float i_ClipRadius;   // Scissor corner radius
layout(location = 8) in vec4 i_ShadowParams;  // x, y: offset, z: blurRadius, w: intensity

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

    // Unpack color (RGBA8)
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
