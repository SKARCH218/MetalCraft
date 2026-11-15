package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import dev.skarch.metalcraft.native.Mcn
import org.lwjgl.system.MemoryUtil
import java.util.OptionalDouble
import java.util.OptionalLong

/** A buffer the native side knows by [handle]. All Metal buffers here are CPU-visible at [contents]. */
internal abstract class MetalBuffer(val handle: Long, val contents: Long, usage: Int, size: Long) : GpuBuffer(usage, size)

/** A buffer created by the game. Its native object is released a few frames after [close]. */
internal class MetalDirectBuffer(private val device: MetalDevice, handle: Long, contents: Long, usage: Int, size: Long) :
    MetalBuffer(handle, contents, usage, size) {
    private var closed = false
    private var mappings = 0

    override fun isClosed(): Boolean = closed

    override fun close() {
        if (closed) return
        closed = true
        check(mappings == 0) { "Attempt to close a mapped buffer" }
        device.encoder.releaseLater(handle)
    }

    override fun map(offset: Long, length: Long, read: Boolean, write: Boolean): GpuBufferSlice.MappedView {
        check(!closed) { "Buffer already closed" }
        require(read || write) { "At least read or write must be true" }
        check(!read || usage() and USAGE_MAP_READ != 0) { "Buffer is not readable" }
        check(!write || usage() and USAGE_MAP_WRITE != 0) { "Buffer is not writable" }
        require(offset >= 0 && length >= 0) { "Offset or length must be positive integer values" }
        require(offset + length <= size()) {
            "Cannot map more data than this buffer can hold (attempting to map $length bytes at offset $offset from ${size()} size buffer)"
        }
        require(length <= Int.MAX_VALUE) { "Mapping buffer slice larger than 2GB is not supported" }
        mappings++
        var open = true
        return GpuBufferSlice.MappedView(slice(offset, length), MemoryUtil.memByteBuffer(contents + offset, length.toInt())) {
            if (open) {
                open = false
                mappings--
            }
        }
    }
}

internal class MetalTexture(
    private val device: MetalDevice, val handle: Long, usage: Int, label: String, format: GpuFormat,
    width: Int, height: Int, depthOrLayers: Int, mipLevels: Int,
) : GpuTexture(usage, label, format, width, height, depthOrLayers, mipLevels) {
    private var closed = false

    // The texture counts as one user of itself, so it outlives its views.
    private var users = 1

    val isCube: Boolean get() = usage() and USAGE_CUBEMAP_COMPATIBLE != 0

    override fun isClosed(): Boolean = closed

    override fun close() {
        if (closed) return
        closed = true
        removeUser()
    }

    fun addUser() {
        users++
    }

    fun removeUser() {
        users--
        check(users >= 0) { "Too many views removed from texture" }
        if (closed && users == 0) device.encoder.releaseLater(handle)
    }
}

internal class MetalTextureView(private val device: MetalDevice, texture: MetalTexture, val handle: Long, baseMipLevel: Int, mipLevels: Int) :
    GpuTextureView(texture, baseMipLevel, mipLevels) {
    private var closed = false

    init {
        texture.addUser()
    }

    override fun isClosed(): Boolean = closed

    override fun close() {
        if (closed) return
        closed = true
        device.encoder.releaseLater(handle)
        (texture() as MetalTexture).removeUser()
    }
}

internal class MetalSampler(
    private val device: MetalDevice, val handle: Long, private val addressU: AddressMode, private val addressV: AddressMode,
    private val minFilter: FilterMode, private val magFilter: FilterMode, private val maxAnisotropy: Int, private val maxLod: OptionalDouble,
) : GpuSampler() {
    private var closed = false

    override fun getAddressModeU(): AddressMode = addressU
    override fun getAddressModeV(): AddressMode = addressV
    override fun getMinFilter(): FilterMode = minFilter
    override fun getMagFilter(): FilterMode = magFilter
    override fun getMaxAnisotropy(): Int = maxAnisotropy
    override fun getMaxLod(): OptionalDouble = maxLod

    override fun close() {
        if (closed) return
        closed = true
        device.encoder.releaseLater(handle)
    }
}

/**
 * Timestamps for the game's GPU profiler. Metal has no timestamp queries on the command stream, so the
 * time at which the command was encoded is recorded instead: good enough to order passes, not to time them.
 */
internal class MetalQueryPool(private val size: Int) : GpuQueryPool {
    private val values = LongArray(size)
    private val written = BooleanArray(size)

    fun write(index: Int) {
        values[index] = System.nanoTime()
        written[index] = true
    }

    override fun size(): Int = size

    override fun getValue(index: Int): OptionalLong = if (written[index]) OptionalLong.of(values[index]) else OptionalLong.empty()

    override fun getValues(index: Int, count: Int): Array<OptionalLong> = Array(count) { getValue(index + it) }

    override fun close() {}
}
