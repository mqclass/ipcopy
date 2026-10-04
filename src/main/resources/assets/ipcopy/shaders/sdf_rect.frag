#version 330 core

in vec2 v_LocalPos;
in vec2 v_HalfSize;
in vec4 v_Color;
in vec4 v_Radii;
in vec2 v_EdgeParams;    // x: strokeWidth, y: aaSoftness
flat in uint v_Flags;    // bit 0: shadow, bit 1: fill, bit 2: stroke, bit 3: glow
in vec4 v_ClipBounds;    // CenterX, CenterY, Width, Height
in float v_ClipRadius;
in vec4 v_ShadowParams;  // x, y: offset, z: blurRadius, w: intensity
in vec2 v_WorldPos;

out vec4 fragColor;

// Exact Euclidean Box SDF for 4 independent corner radii
float sdRoundedBox(vec2 p, vec2 b, vec4 r) {
    // Select radius: r.x = TL, r.y = TR, r.z = BR, r.w = BL
    vec2 rx = (p.x > 0.0) ? vec2(r.y, r.z) : vec2(r.x, r.w);
    float rad = (p.y > 0.0) ? rx.y : rx.x;
    vec2 q = abs(p) - b + vec2(rad);
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
}

void main() {
    // 1. Soft-scissor virtual clip calculation
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

    // 2. Base box SDF
    float d = sdRoundedBox(v_LocalPos, v_HalfSize, v_Radii);
    float aa = max(v_EdgeParams.y, max(fwidth(d), 0.001));

    vec4 finalColor = vec4(0.0);

    // 3. Drop Shadow calculation (bit 0)
    if ((v_Flags & 1u) != 0u && v_ShadowParams.z > 0.0 && v_ShadowParams.w > 0.0) {
        vec2 sPos = v_LocalPos - v_ShadowParams.xy;
        float sd = sdRoundedBox(sPos, v_HalfSize, v_Radii);
        float blur = max(v_ShadowParams.z, 0.5);
        // Gaussian shadow approximation via smoothstep
        float sAlpha = smoothstep(blur, -blur * 0.4, sd) * v_ShadowParams.w;
        finalColor = vec4(0.0, 0.0, 0.0, sAlpha);
    }

    // 4. Fill calculation (bit 1)
    if ((v_Flags & 2u) != 0u) {
        float fillAlpha = clamp(0.5 - d / aa, 0.0, 1.0);
        vec4 fillColor = vec4(v_Color.rgb, v_Color.a * fillAlpha);
        // Standard Porter-Duff source-over blending over drop shadow
        finalColor = fillColor + finalColor * (1.0 - fillColor.a);
    }

    // 5. Stroke calculation (bit 2)
    if ((v_Flags & 4u) != 0u && v_EdgeParams.x > 0.0) {
        float strokeWidth = v_EdgeParams.x;
        float strokeDist = abs(d + strokeWidth * 0.5) - strokeWidth * 0.5;
        float strokeAlpha = clamp(0.5 - strokeDist / aa, 0.0, 1.0);
        vec4 strokeColor = vec4(v_Color.rgb, v_Color.a * strokeAlpha);
        finalColor = strokeColor + finalColor * (1.0 - strokeColor.a);
    }

    // 6. Apply soft scissor attenuation
    finalColor *= clipAlpha;

    // Discard only after all SDF distance calculations if color is nearly invisible
    if (finalColor.a < 0.001) {
        discard;
    }

    fragColor = finalColor;
}
