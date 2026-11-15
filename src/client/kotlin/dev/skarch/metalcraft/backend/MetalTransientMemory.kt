package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.systems.TransientMemory
import com.mojang.blaze3d.util.TransientBlockAllocator
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.util.Mth
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.function.Consumer
import java.util.function.LongFunction

/**
 * Short-lived memory for one submit: scratch CPU memory, staging buffers for uploads and GPU buffers for
 * data drawn this frame. On Apple Silicon every Metal buffer is CPU-visible, so "staging" and "GPU" blocks
 * are the same kind of buffer and mapped GPU memory needs no copy.
 *
 * Blocks go back to the pool a few submits after their last use, once the GPU can no longer be reading them.
 */
internal class MetalTransientMemory(private val device: MetalDevice, private val encoder: MetalCommandEncoder) : TransientMemory {
    private class Block(val handle: Long, val contents: Long, val size: Long)

    private val cpuBlocks = TransientBlockAllocator<Long>(
        BLOCK_SIZE, MAX_CPU_ALIGNMENT,
        TransientBlockAllocator.Allocator.create(LongFunction { MemoryUtil.nmemAlloc(it) }, Consumer { MemoryUtil.nmemFree(it) }),
    )
    private val stagingBlocks = TransientBlockAllocator<Block>(
        BLOCK_SIZE, MAX_GPU_ALIGNMENT,
        TransientBlockAllocator.Allocator.create(LongFunction { allocateBlock(it) }, Consumer { freeBlock(it) }),
    )
    private val gpuBlocks = TransientBlockAllocator<Block>(
        BLOCK_SIZE, MAX_GPU_ALIGNMENT,
        TransientBlockAllocator.Allocator.create(LongFunction { allocateBlock(it) }, Consumer { freeBlock(it) }),
    )
    private var submitIndex = 0L

    fun endSubmit() {
        cpuBlocks.rotate().run()
        encoder.runLater(stagingBlocks.rotate())
        encoder.runLater(gpuBlocks.rotate())
        submitIndex++
    }

    fun destroy() {
        cpuBlocks.close()
        stagingBlocks.close()
        gpuBlocks.close()
    }

    private fun allocateBlock(size: Long): Block {
        val buffer = device.createNativeBuffer(size, null)
        return Block(buffer.handle, buffer.contents, size)
    }

    private fun freeBlock(block: Block) {
        device.release(block.handle)
    }

    override fun allocateCpu(size: Long, alignment: Long, minimumAllocation: Long, elementSize: Long): ByteBuffer {
        val allocation = cpuBlocks.allocate(size, alignment, minimumAllocation, elementSize)
        return MemoryUtil.memByteBuffer(allocation.block() + allocation.offset(), allocation.size().toInt())
    }

    override fun allocateStaging(size: Long, alignment: Long, usage: Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice.MappedView =
        mapped(stagingBlocks.allocate(size, alignment, minimumAllocation, elementSize), usage)

    override fun allocateGpu(size: Long, alignment: Long, usage: Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice {
        val allocation = gpuBlocks.allocate(size, alignment, minimumAllocation, elementSize)
        return GpuBufferSlice(transientBuffer(allocation.block(), usage), allocation.offset(), allocation.size())
    }

    override fun allocateGpuMapped(size: Long, alignment: Long, usage: Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice.MappedView =
        mapped(gpuBlocks.allocate(size, alignment, minimumAllocation, elementSize), usage)

    private fun mapped(allocation: TransientBlockAllocator.Allocation<Block>, usage: Int): GpuBufferSlice.MappedView {
        val block = allocation.block()
        val data = MemoryUtil.memByteBuffer(block.contents + allocation.offset(), allocation.size().toInt())
        return GpuBufferSlice.MappedView(GpuBufferSlice(transientBuffer(block, usage), allocation.offset(), allocation.size()), data, NO_OP)
    }

    private fun transientBuffer(block: Block, usage: Int) = TransientBuffer(block.handle, block.contents, usage, block.size, submitIndex)

    override fun uploadStaging(data: List<ByteBuffer>, alignment: Long, usage: Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice =
        upload(data, alignment, usage, minimumAllocation, elementSize, true)

    override fun uploadGpu(data: List<ByteBuffer>, alignment: Long, usage: Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice =
        upload(data, alignment, usage, minimumAllocation, elementSize, false)

    /** Copies the buffers one after another, each starting at a multiple of [alignment]. */
    private fun upload(
        data: List<ByteBuffer>, alignment: Long, usage: Int, minimumAllocation: Long, elementSize: Long, staging: Boolean,
    ): GpuBufferSlice {
        var totalSize = 0L
        for (buffer in data) totalSize = Mth.roundToward(totalSize + buffer.remaining(), alignment)
        val view = if (staging) {
            allocateStaging(totalSize, alignment, usage, minimumAllocation, elementSize)
        } else {
            allocateGpuMapped(totalSize, alignment, usage, minimumAllocation, elementSize)
        }
        val target = MemoryUtil.memAddress(view.data())
        val capacity = view.slice().length()
        var offset = 0L
        for (buffer in data) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), target + offset, minOf(capacity - offset, buffer.remaining().toLong()))
            offset = Mth.roundToward(offset + buffer.remaining(), alignment)
            if (offset >= capacity) break
        }
        return view.slice()
    }

    override fun multiUploadStaging(data: List<ByteBuffer>, alignment: Long, usage: Int): List<GpuBufferSlice> =
        multiUpload(data, alignment, usage, true)

    override fun multiUploadGpu(data: List<ByteBuffer>, alignment: Long, usage: Int): List<GpuBufferSlice> =
        multiUpload(data, alignment, usage, false)

    /** Uploads each buffer on its own, largest first, filling the current block before starting another. */
    private fun multiUpload(data: List<ByteBuffer>, alignment: Long, usage: Int, staging: Boolean): List<GpuBufferSlice> {
        val uploaded = arrayOfNulls<GpuBufferSlice>(data.size)
        val allocator = if (staging) stagingBlocks else gpuBlocks
        val order = IntArrayList(data.size)
        for (i in data.indices) order.add(i)
        order.sort { a, b -> data[a].remaining().compareTo(data[b].remaining()) }
        while (!order.isEmpty) {
            var position = order.size - 1
            while (position >= 0 && !allocator.canAllocateInCurrentBlock(data[order.getInt(position)].remaining().toLong(), alignment)) position--
            // Nothing fits the current block any more: take the largest one, which opens a new block.
            val index = if (position >= 0) order.removeInt(position) else order.popInt()
            val source = data[index]
            val view = if (staging) {
                allocateStaging(source.remaining().toLong(), alignment, usage)
            } else {
                allocateGpuMapped(source.remaining().toLong(), alignment, usage)
            }
            MemoryUtil.memCopy(source, view.data())
            uploaded[index] = view.slice()
        }
        @Suppress("UNCHECKED_CAST")
        return (uploaded as Array<GpuBufferSlice>).asList()
    }

    /** A view of a transient block. It dies with the submit it was allocated in. */
    private inner class TransientBuffer(handle: Long, contents: Long, usage: Int, size: Long, private val allocatedIn: Long) :
        MetalBuffer(handle, contents, usage, size) {
        private var closed = false

        override fun isClosed(): Boolean {
            if (!closed) closed = allocatedIn < submitIndex
            return closed
        }

        override fun close() {
            closed = true
        }

        override fun map(offset: Long, length: Long, read: Boolean, write: Boolean): GpuBufferSlice.MappedView =
            throw IllegalStateException("Cannot map transient buffer")

        override fun slice(offset: Long, length: Long): GpuBufferSlice = throw IllegalStateException("Cannot slice transient buffer")

        override fun slice(): GpuBufferSlice = throw IllegalStateException("Cannot slice transient buffer")
    }

    private companion object {
        const val BLOCK_SIZE = 524288L
        const val MAX_CPU_ALIGNMENT = 16L
        const val MAX_GPU_ALIGNMENT = 1L shl 62
        val NO_OP = Runnable { }
    }
}
