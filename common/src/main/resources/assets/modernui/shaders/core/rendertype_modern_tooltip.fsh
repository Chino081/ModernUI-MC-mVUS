#version 150

layout(std140) uniform ModernTooltip {
    mat4 u_LocalMat;
    vec4 u_PushData0; // x,y = size, z = radius, w = thickness
    vec3 u_PushData1; // x = shadowAlpha, y = shadowSpread, z = backgroundAlpha
    vec4 u_PushData2; // background gradient / rainbow start
    vec4 u_PushData3; // background gradient / rainbow end
    vec4 u_PushData4; // gradient corner
    vec4 u_PushData5; // gradient corner
};

#define u_Size u_PushData0.xy
#define u_Radius u_PushData0.z
#define u_Thickness u_PushData0.w
#define u_ShadowAlpha u_PushData1.x
#define u_ShadowSpread u_PushData1.y
#define u_BackgroundAlpha u_PushData1.z
#define u_RainbowOffset u_PushData2.w

in vec2 f_Position;
out vec4 fragColor;

// 1D noise for subtle shadow variation
float noise1(float seed1, float seed2) {
    return fract(seed1 + 12.34567 * fract(100.*(abs(seed1*0.91)+seed2+94.68)*fract((abs(seed2*0.41)+45.46)*fract((abs(seed2)+757.21)*fract(seed1*0.0171)))));
}

// Simple dithering to avoid banding
vec4 dither(vec4 color) {
    vec2 A = gl_FragCoord.xy;
    vec2 B = floor(A);
    float U = fract(B.x*0.5 + B.y*B.y*0.75);
    vec2 C = A*0.5;
    vec2 D = floor(C);
    float V = fract(D.x*0.5 + D.y*D.y*0.75);
    vec2 E = C*0.5;
    vec2 F = floor(E);
    float W = fract(F.x*0.5 + F.y*F.y*0.75);
    float dithering = ((W*0.25 + V)*0.25 + U) - (63.0/128.0);
    return vec4(clamp(color.rgb + dithering*(1.0/255.0),0.0,1.0), color.a);
}

void main() {
    vec2 pos = f_Position;
    vec2 d = abs(pos) - u_Size + u_Radius;
    float dis = length(max(d,0.0)) + min(max(d.x,d.y),0.0) - u_Radius;

    vec4 border;

    // 彩虹/渐变边框
    if (u_RainbowOffset == 0.0) {
        vec2 t = clamp(0.5*pos/(u_Size+u_Thickness)+0.5, 0.0, 1.0);
        vec3 q11 = pow(u_PushData2.rgb, vec3(2.2));
        vec3 q21 = pow(u_PushData3.rgb, vec3(2.2));
        vec3 q12 = pow(u_PushData4.rgb, vec3(2.2));
        vec3 q22 = pow(u_PushData5.rgb, vec3(2.2));
        vec3 col = mix(mix(q11,q21,t.x), mix(q12,q22,t.x), t.y);
        border = vec4(pow(col, vec3(1.0/2.2)),1.0);
    } else {
        float t = atan(-pos.y, -pos.x) * 0.1591549430918;
        float hue = mod(t+u_RainbowOffset,1.0);
        const vec4 K = vec4(1,2./3.,1./3.,3);
        vec3 rgb = clamp(abs(fract(hue+K.xyz)*6.-K.w)-K.x,0.,1.);
        border = vec4(rgb*vec3(0.9,0.85,0.9),1.0);
    }

    // 阴影
    float shadow = pow(1.0-(u_ShadowSpread*clamp(dis-u_Thickness,0.0,1.0/u_ShadowSpread)),3.0);
    shadow = u_ShadowAlpha * (shadow + (noise1(gl_FragCoord.x,gl_FragCoord.y)-1.0)*0.05);

    // 边框 alpha
    float dstA = shadow * u_BackgroundAlpha;
    float f = abs(dis)-u_Thickness;
    float afwidth = fwidth(f);
    float srcA = border.a * (1.0 - clamp(f/afwidth + 0.5, 0.0, 1.0));
    float alpha = max(srcA + (1.0-srcA)*dstA, 0.001); // 保证 alpha 不为 0
    vec3 color = border.rgb * srcA / alpha;

    fragColor = dither(vec4(color, alpha));
}
