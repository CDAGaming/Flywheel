package com.jozufozu.flywheel.backend.engine.indirect;

import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43.glDispatchCompute;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

import com.jozufozu.flywheel.api.event.RenderStage;
import com.jozufozu.flywheel.api.instancer.InstancedPart;
import com.jozufozu.flywheel.api.struct.StructType;
import com.jozufozu.flywheel.api.vertex.VertexType;
import com.jozufozu.flywheel.backend.compile.FlwCompiler;
import com.jozufozu.flywheel.backend.engine.UniformBuffer;
import com.jozufozu.flywheel.gl.shader.GlProgram;
import com.jozufozu.flywheel.lib.context.Contexts;
import com.jozufozu.flywheel.lib.pipeline.Pipelines;
import com.jozufozu.flywheel.lib.util.QuadConverter;

public class IndirectCullingGroup<T extends InstancedPart> {

	private static final int BARRIER_BITS = GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT;

	final GlProgram compute;
	final GlProgram draw;
	private final VertexType vertexType;
	private final long objectStride;

	final IndirectBuffers buffers;

	final IndirectMeshPool meshPool;
	private final int elementBuffer;

	int vertexArray;

	final IndirectDrawSet<T> drawSet = new IndirectDrawSet<>();

	private boolean hasCulledThisFrame;
	private boolean needsMemoryBarrier;
	private int instanceCountThisFrame;

	IndirectCullingGroup(StructType<T> structType, VertexType vertexType) {
		this.vertexType = vertexType;

		objectStride = structType.getLayout()
				.getStride();
		buffers = new IndirectBuffers(objectStride);
		buffers.createBuffers();
		buffers.createObjectStorage(128);
		buffers.createDrawStorage(2);

		meshPool = new IndirectMeshPool(vertexType, 1024);

		vertexArray = glCreateVertexArrays();

		elementBuffer = QuadConverter.getInstance()
				.quads2Tris(2048).glBuffer;
		setupVertexArray();

		compute = FlwCompiler.INSTANCE.getCullingProgram(structType);
		draw = FlwCompiler.INSTANCE.getPipelineProgram(vertexType, structType, Contexts.WORLD, Pipelines.INDIRECT);
	}

	private void setupVertexArray() {
		glVertexArrayElementBuffer(vertexArray, elementBuffer);

		var meshLayout = vertexType.getLayout();
		var meshAttribs = meshLayout.getAttributeCount();

		var attributes = meshLayout.attributes();

		long offset = 0;
		for (int i = 0; i < meshAttribs; i++) {
			var attribute = attributes.get(i);
			glEnableVertexArrayAttrib(vertexArray, i);
			glVertexArrayVertexBuffer(vertexArray, i, meshPool.vbo, offset, meshLayout.getStride());
			attribute.format(vertexArray, i);
			offset += attribute.getByteWidth();
		}
	}

	void beginFrame() {
		hasCulledThisFrame = false;
		needsMemoryBarrier = true;
		instanceCountThisFrame = calculateTotalInstanceCountAndPrepareBatches();
	}

	void submit(RenderStage stage) {
		if (drawSet.isEmpty()) {
			return;
		}

		if (instanceCountThisFrame == 0) {
			return;
		}

		cull();
		dispatchDraw(stage);
	}

	private void cull() {
		if (hasCulledThisFrame) {
			return;
		}

		buffers.updateCounts(instanceCountThisFrame, drawSet.size());
		meshPool.uploadAll();
		uploadInstanceData();
		uploadIndirectCommands();

		UniformBuffer.syncAndBind(compute);
		buffers.bindForCompute();

		var groupCount = (instanceCountThisFrame + 31) >> 5; // ceil(instanceCount / 32)
		glDispatchCompute(groupCount, 1, 1);
		hasCulledThisFrame = true;
	}

	private void dispatchDraw(RenderStage stage) {
		if (!drawSet.contains(stage)) {
			return;
		}

		UniformBuffer.syncAndBind(draw);
		glBindVertexArray(vertexArray);
		buffers.bindForDraw();

		memoryBarrier();

		drawSet.submit(stage);
		glBindVertexArray(0);
	}

	private void memoryBarrier() {
		if (needsMemoryBarrier) {
			glMemoryBarrier(BARRIER_BITS);
			needsMemoryBarrier = false;
		}
	}

	private void uploadInstanceData() {
		long objectPtr = buffers.objectPtr;
		long batchIDPtr = buffers.batchPtr;

		for (int i = 0, batchesSize = drawSet.indirectDraws.size(); i < batchesSize; i++) {
			var batch = drawSet.indirectDraws.get(i);
			var instanceCount = batch.instancer()
					.getInstanceCount();
			batch.writeObjects(objectPtr, batchIDPtr, i);

			objectPtr += instanceCount * objectStride;
			batchIDPtr += instanceCount * IndirectBuffers.INT_SIZE;
		}

		buffers.flushObjects(objectPtr - buffers.objectPtr);
		buffers.flushBatchIDs(batchIDPtr - buffers.batchPtr);
	}

	private void uploadIndirectCommands() {
		long writePtr = buffers.drawPtr;
		for (var batch : drawSet.indirectDraws) {
			batch.writeIndirectCommand(writePtr);
			writePtr += IndirectBuffers.DRAW_COMMAND_STRIDE;
		}
		buffers.flushDrawCommands(writePtr - buffers.drawPtr);
	}

	private int calculateTotalInstanceCountAndPrepareBatches() {
		int baseInstance = 0;
		for (var batch : drawSet.indirectDraws) {
			batch.prepare(baseInstance);
			baseInstance += batch.instancer().instanceCount;
		}
		return baseInstance;
	}

	public void delete() {
		glDeleteVertexArrays(vertexArray);
		buffers.delete();
		meshPool.delete();
	}

}