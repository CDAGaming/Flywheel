package com.jozufozu.flywheel.api.context;

import com.jozufozu.flywheel.backend.gl.shader.GlProgram;
import com.jozufozu.flywheel.core.source.FileResolution;

public record ContextShader(GlProgram.Factory factory, FileResolution vertexShader, FileResolution fragmentShader) {

}
