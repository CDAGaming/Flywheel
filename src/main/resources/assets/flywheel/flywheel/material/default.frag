#use "flywheel:api/fragment.glsl"
#use "flywheel:util/fog.glsl"
#use "flywheel:uniform/fog.glsl"

void flw_materialFragment() {
}

vec4 flw_fogFilter(vec4 color) {
    return linear_fog(color, flw_distance, flw_fogRange.x, flw_fogRange.y, flw_fogColor);
}