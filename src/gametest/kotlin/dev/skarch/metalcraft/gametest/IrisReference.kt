package dev.skarch.metalcraft.gametest

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Sets up a reference run of an original shader pack on Iris (`scripts/run-tests.sh --iris <pack zip>`): the
 * same scenes as every other run, so pictures and frame rates can be compared with MetalCraft's port.
 *
 * The test game folder is emptied before every run, so the pack and Iris' settings are put in place here,
 * before any mod starts. The pack comes from `shaderpacks/_original/` in the project.
 */
class IrisReference : PreLaunchEntrypoint {
    override fun onPreLaunch() {
        val pack = System.getProperty("metalcraft.test.irisPack")?.takeIf { it.isNotBlank() } ?: return
        val packs = System.getProperty("metalcraft.shaderpacks.dir") ?: error("metalcraft.shaderpacks.dir is not set")
        val source = Path.of(packs, "_original", pack)
        check(Files.isRegularFile(source)) { "Shader pack $source does not exist" }
        val gameDir = FabricLoader.getInstance().gameDir
        Files.createDirectories(gameDir.resolve("shaderpacks"))
        Files.copy(source, gameDir.resolve("shaderpacks").resolve(pack), StandardCopyOption.REPLACE_EXISTING)
        Files.createDirectories(gameDir.resolve("config"))
        Files.writeString(gameDir.resolve("config").resolve("iris.properties"), "shaderPack=$pack\nenableShaders=true\n")
    }
}
