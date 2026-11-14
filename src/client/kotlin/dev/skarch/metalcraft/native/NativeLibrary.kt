package dev.skarch.metalcraft.native

import dev.skarch.metalcraft.MetalCraft
import net.fabricmc.loader.api.FabricLoader
import java.io.FileNotFoundException
import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup
import java.nio.file.Files

internal object NativeLibrary {
    private const val FILE_NAME = "libMetalCraftNative.dylib"
    private const val RESOURCE_PATH = "natives/metalcraft/macos-arm64/$FILE_NAME"

    /** Extracts the bundled dylib into a private temp directory and opens it for the lifetime of the JVM. */
    fun load(): SymbolLookup {
        val os = System.getProperty("os.name")
        val arch = System.getProperty("os.arch")
        if (!os.startsWith("Mac") || arch != "aarch64") {
            throw UnsupportedOperationException("MetalCraft needs macOS on Apple Silicon (found $os / $arch)")
        }

        val container = FabricLoader.getInstance().getModContainer(MetalCraft.MOD_ID)
            .orElseThrow { IllegalStateException("Mod container '${MetalCraft.MOD_ID}' not found") }
        val resource = container.findPath(RESOURCE_PATH)
            .orElseThrow { FileNotFoundException("$RESOURCE_PATH is missing from the mod") }

        // The file stays on disk while the game runs so debuggers and crash reports can symbolicate it.
        val directory = Files.createTempDirectory("metalcraft-native")
        val library = directory.resolve(FILE_NAME)
        directory.toFile().deleteOnExit()
        library.toFile().deleteOnExit()
        Files.copy(resource, library)

        return SymbolLookup.libraryLookup(library, Arena.global())
    }
}
