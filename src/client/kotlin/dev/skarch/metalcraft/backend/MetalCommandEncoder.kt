package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.systems.CommandEncoderBackend
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.RenderPassBackend
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.TransientMemory
import com.mojang.blaze3d.textures.GpuTexture
import dev.skarch.metalcraft.native.Mcn
import it.unimi.dsi.fastutil.longs.LongArrayList
import org.joml.Vector4fc
import org.lwjgl.system.MemoryUtil
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.ByteBuffer

/**
 * Records the game's commands into Metal command buffers. One submit is one frame (or one loading step).
 *
 * At most [MAX_SUBMITS_IN_FLIGHT] submits are allowed to be unfinished on the GPU. Objects the game closes
 * and transient memory are only released once the submit that last used them has completed.
 */
internal class MetalCommandEncoder(private val device: MetalDevice) : CommandEncoderBackend {
    private val context = device.context
    private val transientMemory = MetalTransientMemory(device, this)
    private val renderPass = MetalRenderPass(device, this)
    private var inRenderPass = false

    // One slot per submit that may still be running; a slot is emptied when its submit has completed.
    private val handlesToRelease = Array(MAX_SUBMITS_IN_FLIGHT) { LongArrayList() }
    private val actionsToRun = Array(MAX_SUBMITS_IN_FLIGHT) { ArrayList<Runnable>() }
    private val runningActions = ArrayList<Runnable>()
    private var slot = 0
    private var lastSubmitted = 0L

    private val scratch = Arena.global()
    private val colorHandles: MemorySegment = scratch.allocate(JAVA_LONG, MAX_COLOR_TARGETS.toLong())
    private val clearColors: MemorySegment = scratch.allocate(JAVA_FLOAT, MAX_COLOR_TARGETS * 4L)
    private val submitIndexOut: MemorySegment = scratch.allocate(JAVA_LONG)

    /** Releases a native object once the GPU has finished everything recorded up to now. */
    fun releaseLater(handle: Long) {
        handlesToRelease[slot].add(handle)
    }

    fun runLater(action: Runnable) {
        actionsToRun[slot].add(action)
    }

    override fun transientMemory(): TransientMemory = transientMemory

    override fun submit() {
        check(!inRenderPass) { "Cannot submit while inside a render pass" }
        transientMemory.endSubmit()
        val timing = FrameTiming.enabled
        var start = if (timing) System.nanoTime() else 0L
        Mcn.check(Mcn.submit.invokeExact(context, submitIndexOut) as Int)
        if (timing) {
            FrameTiming.add(FrameTiming.COMMIT, start)
            start = System.nanoTime()
        }
        lastSubmitted = submitIndexOut.get(JAVA_LONG, 0)
        val mustBeDone = lastSubmitted - (MAX_SUBMITS_IN_FLIGHT - 1)
        if (mustBeDone > 0 && Mcn.waitSubmit.invokeExact(context, mustBeDone, SUBMIT_TIMEOUT_NANOS) as Int == 0) {
            throw IllegalStateException("5s timeout reached when waiting for the GPU to finish submit $mustBeDone")
        }
        if (timing) {
            FrameTiming.add(FrameTiming.WAIT_GPU, start)
            FrameTiming.frameDone()
        }
        rotate()
    }

    /** Moves to the next slot and frees what was queued in it: its submit is the one just waited for. */
    private fun rotate() {
        slot = (slot + 1) % MAX_SUBMITS_IN_FLIGHT
        val handles = handlesToRelease[slot]
        for (i in 0 until handles.size) device.release(handles.getLong(i))
        handles.clear()
        val actions = actionsToRun[slot]
        if (actions.isNotEmpty()) {
            // An action may queue more work, which then belongs to the current slot's next turn.
            val pending = runningActions
            pending.addAll(actions)
            actions.clear()
            for (i in pending.indices) pending[i].run()
            pending.clear()
        }
    }

    fun destroy() {
        transientMemory.endSubmit()
        Mcn.check(Mcn.submit.invokeExact(context, submitIndexOut) as Int)
        Mcn.waitSubmit.invokeExact(context, submitIndexOut.get(JAVA_LONG, 0), SUBMIT_TIMEOUT_NANOS) as Int
        // Everything is finished on the GPU now: hand all blocks back to their pools, then free the pools.
        repeat(MAX_SUBMITS_IN_FLIGHT * 2) { rotate() }
        transientMemory.destroy()
        repeat(MAX_SUBMITS_IN_FLIGHT) { rotate() }
    }

    // ---- render passes ----------------------------------------------------------------------------------

    override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPassBackend {
        check(!inRenderPass) { "A render pass is already open" }
        val colors = descriptor.colorAttachments()
        require(colors.size <= MAX_COLOR_TARGETS) { "Too many colour attachments: ${colors.size}" }
        val area = checkNotNull(descriptor.renderArea) { "Render pass has no render area" }

        var width = 0
        var height = 0
        var clearMask = 0
        var firstColor: MetalTextureView? = null
        for (i in colors.indices) {
            val attachment = colors[i]
            if (attachment == null) {
                colorHandles.setAtIndex(JAVA_LONG, i.toLong(), 0L)
                continue
            }
            val view = attachment.textureView() as MetalTextureView
            if (firstColor == null) firstColor = view
            colorHandles.setAtIndex(JAVA_LONG, i.toLong(), view.handle)
            width = view.getWidth(0)
            height = view.getHeight(0)
            val clear = attachment.clearValue().orElse(null)
            if (clear != null) {
                clearMask = clearMask or (1 shl i)
                clearColors.setAtIndex(JAVA_FLOAT, i * 4L, clear.x())
                clearColors.setAtIndex(JAVA_FLOAT, i * 4L + 1, clear.y())
                clearColors.setAtIndex(JAVA_FLOAT, i * 4L + 2, clear.z())
                clearColors.setAtIndex(JAVA_FLOAT, i * 4L + 3, clear.w())
            }
        }
        val depth = descriptor.depthAttachment()
        val depthView = depth?.textureView() as MetalTextureView?
        if (depthView != null && width == 0) {
            width = depthView.getWidth(0)
            height = depthView.getHeight(0)
        }
        val clearDepth = depth != null && depth.clearValue().isPresent
        val depthValue = if (clearDepth) depth!!.clearValue().asDouble else 0.0

        // Metal clears whole attachments. A pass limited to part of its target clears that part by drawing.
        val partial = area.x() != 0 || area.y() != 0 || area.width() != width || area.height() != height
        val clearByDrawing = partial && (clearMask != 0 || clearDepth)
        if (clearByDrawing) {
            check(colors.size == 1 && firstColor != null) { "A partial clear needs exactly one colour attachment" }
        }

        Mcn.check(
            Mcn.passBegin.invokeExact(
                context, colors.size, colorHandles, if (clearByDrawing) 0 else clearMask, clearColors,
                depthView?.handle ?: 0L, if (clearDepth && !clearByDrawing) 1 else 0, depthValue, MemorySegment.NULL,
            ) as Int,
        )
        inRenderPass = true
        val depthFormat = if (depthView != null) MetalFormats.code(depthView.texture().format) else MetalFormats.NO_FORMAT
        if (clearByDrawing) {
            // Without a clear value the area keeps its contents, so only clear what was asked for.
            val clearsColor = clearMask != 0
            if (clearsColor && (clearDepth || depthView == null)) {
                Mcn.check(
                    Mcn.passClearRect.invokeExact(
                        context, MetalFormats.code(firstColor!!.texture().format), depthFormat,
                        clearColors.getAtIndex(JAVA_FLOAT, 0), clearColors.getAtIndex(JAVA_FLOAT, 1),
                        clearColors.getAtIndex(JAVA_FLOAT, 2), clearColors.getAtIndex(JAVA_FLOAT, 3),
                        depthValue.toFloat(), area.x(), area.y(), area.width(), area.height(),
                    ) as Int,
                )
            } else {
                throw UnsupportedOperationException("Partial clear of only colour or only depth is not supported by the Metal backend yet")
            }
        }
        renderPass.begin(area.x(), area.y(), area.width(), area.height(), depthFormat)
        return renderPass
    }

    override fun submitRenderPass() {
        check(inRenderPass) { "Cannot submit a renderpass if one hasn't been started!" }
        Mcn.passEnd.invokeExact(context) as Int
        renderPass.end()
        inRenderPass = false
    }

    // ---- clears -----------------------------------------------------------------------------------------

    override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Vector4fc) {
        clear(colorTexture as MetalTexture, clearColor, null, 0.0)
    }

    override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc, depthTexture: GpuTexture, clearDepth: Double) {
        clear(colorTexture as MetalTexture, clearColor, depthTexture as MetalTexture, clearDepth)
    }

    override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) {
        clear(null, null, depthTexture as MetalTexture, clearDepth)
    }

    /** Clears every mip level. Levels both textures have are cleared together. */
    private fun clear(color: MetalTexture?, clearColor: Vector4fc?, depth: MetalTexture?, clearDepth: Double) {
        val levels = maxOf(color?.mipLevels ?: 0, depth?.mipLevels ?: 0)
        val sameSize = color == null || depth == null || (color.getWidth(0) == depth.getWidth(0) && color.getHeight(0) == depth.getHeight(0))
        for (level in 0 until levels) {
            val colorHandle = if (color != null && level < color.mipLevels) color.handle else 0L
            val depthHandle = if (depth != null && level < depth.mipLevels) depth.handle else 0L
            if (sameSize) {
                clearLevel(colorHandle, clearColor, depthHandle, clearDepth, level)
            } else {
                if (colorHandle != 0L) clearLevel(colorHandle, clearColor, 0L, 0.0, level)
                if (depthHandle != 0L) clearLevel(0L, null, depthHandle, clearDepth, level)
            }
        }
    }

    private fun clearLevel(color: Long, clearColor: Vector4fc?, depth: Long, clearDepth: Double, level: Int) {
        Mcn.check(
            Mcn.clearTextures.invokeExact(
                context, color, clearColor?.x() ?: 0f, clearColor?.y() ?: 0f, clearColor?.z() ?: 0f, clearColor?.w() ?: 0f,
                depth, clearDepth, level,
            ) as Int,
        )
    }

    override fun clearColorAndDepthTextures(
        colorTexture: GpuTexture, clearColor: Vector4fc, depthTexture: GpuTexture, clearDepth: Double,
        regionX: Int, regionY: Int, regionWidth: Int, regionHeight: Int,
    ) {
        check(!inRenderPass) { "Cannot clear while inside a render pass" }
        val color = colorTexture as MetalTexture
        val depth = depthTexture as MetalTexture
        colorHandles.setAtIndex(JAVA_LONG, 0, color.handle)
        Mcn.check(Mcn.passBegin.invokeExact(context, 1, colorHandles, 0, clearColors, depth.handle, 0, 0.0, MemorySegment.NULL) as Int)
        val cleared = Mcn.passClearRect.invokeExact(
            context, MetalFormats.code(color.format), MetalFormats.code(depth.format),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), clearDepth.toFloat(),
            regionX, regionY, regionWidth, regionHeight,
        ) as Int
        Mcn.passEnd.invokeExact(context) as Int
        Mcn.check(cleared)
    }

    // ---- copies -----------------------------------------------------------------------------------------

    override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) {
        // The write has to happen in command order (draws recorded earlier must still see the old bytes),
        // so it goes through a staging copy instead of straight into the buffer's memory.
        val target = destination.buffer() as MetalBuffer
        val offset = destination.offset()
        val size = data.remaining().toLong()
        if (size == 0L) return
        if (offset and 3L == 0L && size and 3L == 0L) {
            val staging = transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC)
            Mcn.check(Mcn.copyBuffer.invokeExact(context, (staging.buffer() as MetalBuffer).handle, staging.offset(), target.handle, offset, size) as Int)
            return
        }
        // Metal copies whole 4-byte words. Widen the copy and keep the neighbouring bytes as they are now.
        val start = offset and 3L.inv()
        val end = (offset + size + 3L) and 3L.inv()
        val staging = transientMemory.allocateStaging(end - start, 4L, GpuBuffer.USAGE_COPY_SRC)
        val scratch = MemoryUtil.memAddress(staging.data())
        MemoryUtil.memCopy(target.contents + start, scratch, end - start)
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), scratch + (offset - start), size)
        Mcn.check(
            Mcn.copyBuffer.invokeExact(context, (staging.slice().buffer() as MetalBuffer).handle, staging.slice().offset(), target.handle, start, end - start) as Int,
        )
    }

    override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) {
        if (source.offset() or target.offset() or source.length() and 3L != 0L) {
            throw UnsupportedOperationException("Metal copies buffers in 4-byte words: offsets and length must be multiples of 4")
        }
        Mcn.check(
            Mcn.copyBuffer.invokeExact(
                context, (source.buffer() as MetalBuffer).handle, source.offset(),
                (target.buffer() as MetalBuffer).handle, target.offset(), source.length(),
            ) as Int,
        )
    }

    override fun writeToTexture(
        destination: GpuTexture, source: ByteBuffer, mipLevel: Int, depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int,
    ) {
        val staging = transientMemory.uploadStaging(source, TEXEL_ALIGNMENT, GpuBuffer.USAGE_COPY_SRC)
        Mcn.check(
            Mcn.copyBufferToTexture.invokeExact(
                context, (staging.buffer() as MetalBuffer).handle, staging.offset(), width * destination.format.blockSize(),
                width, height, (destination as MetalTexture).handle, depthOrLayer, mipLevel, destX, destY,
            ) as Int,
        )
    }

    override fun copyBufferToTexture(
        source: GpuBufferSlice, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, destination: GpuTexture,
        destinationX: Int, destinationY: Int, copyWidth: Int, copyHeight: Int, mipLevel: Int, arrayLayer: Int,
    ) {
        val texelSize = destination.format.blockSize()
        val skippedBytes = (sourceX + sourceY.toLong() * sourceWidth) * texelSize
        Mcn.check(
            Mcn.copyBufferToTexture.invokeExact(
                context, (source.buffer() as MetalBuffer).handle, source.offset() + skippedBytes, sourceWidth * texelSize,
                copyWidth, copyHeight, (destination as MetalTexture).handle, arrayLayer, mipLevel, destinationX, destinationY,
            ) as Int,
        )
    }

    override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel))
    }

    override fun copyTextureToBuffer(
        source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int, x: Int, y: Int, width: Int, height: Int,
    ) {
        Mcn.check(
            Mcn.copyTextureToBuffer.invokeExact(
                context, (source as MetalTexture).handle, mipLevel, x, y, width, height,
                (destination as MetalBuffer).handle, offset, width * readbackTexelSize(source),
            ) as Int,
        )
        // The data is in the buffer once the GPU has run this submit.
        runLater(callback)
    }

    override fun copyTextureToTexture(
        source: GpuTexture, destination: GpuTexture, mipLevel: Int, destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int,
    ) {
        Mcn.check(
            Mcn.copyTexture.invokeExact(
                context, (source as MetalTexture).handle, (destination as MetalTexture).handle, mipLevel,
                sourceX, sourceY, width, height, destX, destY,
            ) as Int,
        )
    }

    /** Bytes per texel when a texture is copied to a buffer. Depth/stencil textures give up their depth only. */
    private fun readbackTexelSize(texture: GpuTexture): Int {
        val format = texture.format
        return if (format.hasDepthAspect() && format.hasStencilAspect()) 4 else format.blockSize()
    }

    // ---- synchronisation --------------------------------------------------------------------------------

    override fun createFence(): GpuFence = object : GpuFence {
        private val submitIndex = lastSubmitted + 1
        private var completed = false

        override fun awaitCompletion(timeoutNanos: Long): Boolean {
            if (completed) return true
            if (submitIndex > lastSubmitted) {
                check(timeoutNanos == 0L) { "Cannot wait on a fence for the current submit" }
                return false
            }
            completed = Mcn.waitSubmit.invokeExact(context, submitIndex, timeoutNanos) as Int != 0
            return completed
        }

        override fun close() {
            completed = true
        }
    }

    override fun writeTimestamp(pool: GpuQueryPool, index: Int) {
        (pool as MetalQueryPool).write(index)
    }

    companion object {
        /** Frames the CPU may get ahead of the GPU (CLAUDE.md section 5). */
        const val MAX_SUBMITS_IN_FLIGHT = 3
        const val MAX_COLOR_TARGETS = 8
        private const val SUBMIT_TIMEOUT_NANOS = 5_000_000_000L

        /** Metal wants texture upload sources to start on a texel boundary; 16 covers every format. */
        private const val TEXEL_ALIGNMENT = 16L
    }
}
