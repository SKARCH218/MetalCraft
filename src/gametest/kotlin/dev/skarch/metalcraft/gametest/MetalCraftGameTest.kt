package dev.skarch.metalcraft.gametest

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl
import net.minecraft.client.CloudStatus
import net.minecraft.client.InactivityFpsLimit
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.presets.WorldPresets
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.function.Function
import java.util.function.Supplier
import kotlin.math.abs

/**
 * The automated test harness described in CLAUDE.md section 6-1.
 *
 * One run visits a fixed list of scenes, saves a screenshot of each and measures frame times, then the
 * gametest runner closes the game. System properties:
 * - `metalcraft.backend`: renderer the run must use (`opengl`, `vulkan` or `metal`). The run fails if the
 *   game ends up on a different one.
 * - `metalcraft.test.outputDir`: where screenshots and `metrics.json` go.
 * - `metalcraft.test.benchmark=true`: 10 s warm-up and 30 s measurement per scene instead of 2 s and 5 s.
 * - `metalcraft.test.scenes`: comma-separated scene names to run. Empty runs everything.
 * - `metalcraft.test.worldCache`: folder that keeps the generated normal world between runs.
 */
class MetalCraftGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        Harness(context).run()
    }
}

private class Harness(private val context: ClientGameTestContext) {
    private val requestedBackend: String? = System.getProperty("metalcraft.backend")?.trim()?.lowercase()
    private val benchmark = System.getProperty("metalcraft.test.benchmark").toBoolean()
    private val shaderPack: String? = System.getProperty("metalcraft.shaderpack")?.takeIf { it.isNotBlank() && it != "off" }
    private val sceneFilter: Set<String> = System.getProperty("metalcraft.test.scenes").orEmpty()
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val warmupSeconds = if (benchmark) 10.0 else 2.0
    private val measureSeconds = if (benchmark) 30.0 else 5.0

    private val worldCacheDir: Path? = System.getProperty("metalcraft.test.worldCache")
        ?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath() }

    private lateinit var outputDir: Path
    private val sceneResults = JsonArray()
    private val report = JsonObject()
    private val benchmarkTable = StringBuilder(
        "| Scene | Avg FPS | 1% low FPS | Avg frame (ms) | p99 frame (ms) |\n|---|---:|---:|---:|---:|\n",
    )

    fun run() {
        val unknown = sceneFilter - ALL_SCENES
        require(unknown.isEmpty()) { "Unknown scene names in metalcraft.test.scenes: $unknown. Known: $ALL_SCENES" }

        outputDir = resolveOutputDir()
        Files.createDirectories(outputDir)
        LOGGER.info("Test output directory: {}", outputDir)

        var completed = false
        try {
            applyFixedOptions()
            describeEnvironment()
            if (NORMAL_WORLD_SCENES.any(::selected)) runNormalWorld()
            if (FLAT_WORLD_SCENES.any(::selected)) runFlatWorld()
            completed = true
        } finally {
            report.addProperty("completed", completed)
            report.add("scenes", sceneResults)
            Files.writeString(outputDir.resolve("metrics.json"), GSON.toJson(report))
        }
        // The run script appends this table to docs/benchmarks.md.
        if (benchmark) Files.writeString(outputDir.resolve("benchmark.md"), benchmarkTable.toString())
        LOGGER.info("Test run finished: {} scenes written to {}", sceneResults.size(), outputDir)
    }

    /** Presses MetalCraft's "next shader pack" key and waits until its on-screen message has faded. */
    private fun pressShaderPackKey() {
        context.input.pressKey(
            Function<Options, KeyMapping> { options -> options.keyMappings.first { it.name == "key.metalcraft.cycle_shaderpack" } },
        )
        context.waitTicks(100)
    }

    private fun selected(scene: String) = sceneFilter.isEmpty() || scene in sceneFilter

    private fun resolveOutputDir(): Path {
        System.getProperty("metalcraft.test.outputDir")?.takeIf { it.isNotBlank() }?.let { return Path.of(it).toAbsolutePath() }
        val gameDir = context.onClient { it.gameDirectory.toPath() }
        return gameDir.resolve("test-output").resolve("manual").resolve(requestedBackend ?: "default").toAbsolutePath()
    }

    // ---- reproducibility ------------------------------------------------------------------------------------

    private fun applyFixedOptions() {
        context.onClient { client ->
            val options = client.options
            options.renderDistance().set(RENDER_DISTANCE)
            options.enableVsync().set(false)
            options.framerateLimit().set(UNLIMITED_FRAMERATE)
            // The default throttles to 30 FPS after a minute without input, which a benchmark run always hits.
            options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED)
            options.cloudStatus().set(CloudStatus.FANCY)
            options.guiScale().set(GUI_SCALE)
            // The vignette eases towards its target by 1% per rendered frame, so its strength depends on the FPS.
            options.vignette().set(false)
            options.pauseOnLostFocus = false
        }
        val width = context.onClient { it.gameRenderer.mainRenderTarget().width }
        val height = context.onClient { it.gameRenderer.mainRenderTarget().height }
        if (width != WIDTH || height != HEIGHT) {
            LOGGER.warn("Render target is {}x{}, resizing to {}x{}", width, height, WIDTH, HEIGHT)
            context.input.resizeWindow(WIDTH, HEIGHT)
        }
        context.waitTicks(5)
    }

    private fun describeEnvironment() {
        val info = context.onClient { RenderSystem.getDevice().deviceInfo }
        val actualBackend = info.backendName()
        if (requestedBackend != null && !actualBackend.equals(requestedBackend, ignoreCase = true)) {
            throw AssertionError("Requested backend '$requestedBackend' but the game is running on '$actualBackend'")
        }
        report.addProperty("backend", actualBackend.lowercase())
        report.addProperty("requestedBackend", requestedBackend)
        report.addProperty("device", info.name())
        report.addProperty("driver", info.driverInfo())
        report.addProperty("mode", if (benchmark) "benchmark" else "quick")
        report.addProperty("warmupSeconds", warmupSeconds)
        report.addProperty("measureSeconds", measureSeconds)
        report.addProperty("width", context.onClient { it.gameRenderer.mainRenderTarget().width })
        report.addProperty("height", context.onClient { it.gameRenderer.mainRenderTarget().height })
        report.addProperty("renderDistance", context.onClient { it.options.effectiveRenderDistance })
        report.addProperty("vsync", context.onClient { it.options.enableVsync().get() })
        report.addProperty("graphicsPreset", context.onClient { it.options.graphicsPreset().get().toString() })
        report.addProperty("normalWorldSeed", NORMAL_WORLD_SEED)
    }

    // ---- worlds ---------------------------------------------------------------------------------------------

    private fun runNormalWorld() {
        val cached = worldCacheDir?.resolve(NORMAL_WORLD_CACHE)
        val fromCache = cached != null && Files.isDirectory(cached)
        report.addProperty("normalWorldFromCache", fromCache)
        val world = if (fromCache) {
            val save = context.onClient { it.levelSource.baseDir }.resolve(NORMAL_WORLD_CACHE)
            copyTree(cached!!, save)
            LOGGER.info("Opening the cached normal world from {}", cached)
            // The public gametest API can only reopen a world it created in the same session.
            TestWorldSaveImpl(context, save).open()
        } else {
            context.worldBuilder().adjustSettings { creator ->
                // "Consistent settings" already fix the seed, freeze time and weather and disable structures and
                // mob spawning; only the flat preset has to be swapped for real terrain.
                val presets = creator.settings.worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET)
                creator.setWorldType(WorldCreationUiState.WorldTypeEntry(presets.getOrThrow(WorldPresets.NORMAL)))
                creator.setSeed(NORMAL_WORLD_SEED)
                creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)
                creator.setAllowCommands(true)
            }.create()
        }
        val saveDirectory = world.worldSave.saveDirectory
        world.use {
            quietCommands(world.server)
            world.server.runCommand("weather clear")

            val plains = world.server.onServer { server ->
                val level = server.overworld()
                val found = level.findClosestBiome3d({ it.`is`(Biomes.PLAINS) }, BlockPos(0, 64, 0), 6400, 32, 64)
                    ?: throw AssertionError("No plains biome near the origin of seed $NORMAL_WORLD_SEED")
                val x = found.first.x
                val z = found.first.z
                BlockPos(x, surfaceY(level, x, z), z)
            }

            // The torches for the night scene go in before any screenshot. They stay in the saved world, so
            // placing them later would make the first run's daytime shot differ from every later run.
            world.server.onServer { server ->
                val level = server.overworld()
                for (ahead in 3..19 step 4) {
                    for (side in -6..6 step 4) {
                        val x = plains.x + ahead
                        val z = plains.z + side
                        val ground = BlockPos(x, surfaceY(level, x, z), z)
                        level.setBlockAndUpdate(ground, Blocks.TORCH.defaultBlockState())
                    }
                }
            }

            if (selected("plains_day")) {
                world.server.runCommand("time set 2000")
                // Facing east so the morning sun, clouds and terrain all land in the frame.
                val view = Viewpoint(plains.x + 0.5, plains.y + 4.0, plains.z + 0.5, -90f, 5f)
                worldScene("plains_day", world, OVERWORLD, view)
            }

            if (selected("ocean")) {
                world.server.runCommand("time set 6000")
                val ocean = world.server.onServer { server ->
                    server.overworld().findClosestBiome3d({ it.`is`(Biomes.OCEAN) || it.`is`(Biomes.DEEP_OCEAN) }, plains, 6400, 32, 64)?.first
                        ?: throw AssertionError("No ocean biome near $plains")
                }
                val view = Viewpoint(ocean.x + 0.5, SEA_LEVEL + 4.0, ocean.z + 0.5, 0f, 25f)
                worldScene("ocean", world, OVERWORLD, view)
            }

            if (selected("night_torches")) {
                world.server.runCommand("time set 18000")
                val view = Viewpoint(plains.x + 0.5, plains.y + 2.0, plains.z + 0.5, -90f, 15f)
                worldScene("night_torches", world, OVERWORLD, view)
            }

            if (selected("cave")) {
                world.server.runCommand("time set 6000")
                val view = world.server.onServer { server ->
                    val level = server.overworld()
                    val found = WorldProbe.findCavern(level, plains.x, plains.z, 96, -48, 32)
                        ?: throw AssertionError("No cave found within 96 blocks of $plains")
                    WorldProbe.placeTorchesAhead(level, found, intArrayOf(4, 10))
                    found
                }
                worldScene("cave", world, OVERWORLD, view)
            }

            if (selected("nether")) {
                val view = world.server.onServer { server ->
                    val level = server.getLevel(Level.NETHER) ?: throw AssertionError("The Nether is not loaded")
                    WorldProbe.findCavern(level, 0, 0, 48, 34, 100)
                        ?: throw AssertionError("No open space found in the Nether near the origin")
                }
                worldScene("nether", world, NETHER, view)
            }
        }
        // Terrain generation is not repeatable where trees overlap, even with a fixed seed. Keeping the world
        // generated by the first complete run gives every later run exactly the same blocks.
        if (!fromCache && cached != null && NORMAL_WORLD_SCENES.all(::selected)) {
            try {
                copyTree(saveDirectory, cached)
                LOGGER.info("Saved the normal world to {} for later runs", cached)
            } catch (e: java.io.IOException) {
                // The scenes are already captured; only the next run pays by generating the world again.
                LOGGER.warn("Could not save the normal world to {}", cached, e)
            }
        }
    }

    private fun copyTree(from: Path, to: Path) {
        val staging = to.resolveSibling(to.fileName.toString() + ".tmp")
        staging.toFile().deleteRecursively()
        to.toFile().deleteRecursively()
        Files.walk(from).use { paths ->
            for (source in paths) {
                // The lock file belongs to the game instance that had the world open.
                if (source.fileName.toString() == "session.lock") continue
                val target = staging.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
        Files.createDirectories(to.parent)
        Files.move(staging, to)
    }

    private fun runFlatWorld() {
        val builder = context.worldBuilder().adjustSettings { creator ->
            creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)
            creator.setAllowCommands(true)
        }
        builder.create().use { world ->
            quietCommands(world.server)
            world.server.runCommand("weather clear")
            world.server.runCommand("time set 6000")
            val ground = world.server.onServer { surfaceY(it.overworld(), 0, 0) }
            val view = Viewpoint(0.5, ground + 3.0, 0.5, 0f, 20f)

            teleport(world, OVERWORLD, view)
            waitForWorldReady()
            removeWildlife(world, OVERWORLD)
            // Mobs are summoned only now, a fixed number of ticks before the screenshot, so their age-driven
            // idle animations are in the same pose on every run.
            summonMobs(world.server, ground)
            world.connection.waitForClientboundPackets()
            context.waitTicks(SETTLE_TICKS)
            if (selected("entities")) capture("entities", OVERWORLD)

            for (item in arrayOf("diamond_sword", "oak_planks 64", "torch 32", "golden_apple 5", "water_bucket")) {
                world.server.runCommand("give @a minecraft:$item")
            }
            world.connection.waitForClientboundPackets()

            if (selected("gui_inventory")) {
                context.input.pressKey(Function<Options, KeyMapping> { it.keyInventory })
                context.waitForScreen(CreativeModeInventoryScreen::class.java)
                capture("gui_inventory", OVERWORLD)
                closeScreen()
            }
            // The renderer has to survive the window changing size and every texture and shader being
            // reloaded. The view turns away from the mobs, whose poses depend on how long the reload took.
            if (selected("after_reload")) {
                context.input.resizeWindow(1280, 800)
                context.waitTicks(10)
                context.input.resizeWindow(WIDTH, HEIGHT)
                context.waitTicks(10)
                val reload = context.onClient { it.reloadResourcePacks() }
                context.waitFor({ client -> reload.isDone && client.gui.overlay() == null }, CHUNK_TIMEOUT_TICKS)
                teleport(world, OVERWORLD, Viewpoint(0.5, ground + 3.0, 0.5, 180f, 20f))
                waitForWorldReady()
                context.waitTicks(SETTLE_TICKS)
                capture("after_reload", OVERWORLD, benchmarkable = false)
                // With a shader pack: switch it off and on again with its key. "pack_off" must look like the
                // game without a pack, "pack_on_again" like "after_reload".
                if (shaderPack != null) {
                    pressShaderPackKey()
                    capture("pack_off", OVERWORLD, benchmarkable = false)
                    pressShaderPackKey()
                    capture("pack_on_again", OVERWORLD, benchmarkable = false)
                }
                teleport(world, OVERWORLD, view)
                waitForWorldReady()
            }
            if (selected("gui_chat")) {
                world.server.runCommand("say MetalCraft automated test harness")
                world.server.runCommand("tellraw @a {text:\"The quick brown fox jumps over the lazy dog\",color:\"gold\"}")
                world.connection.waitForClientboundPackets()
                context.input.pressKey(Function<Options, KeyMapping> { it.keyChat })
                context.waitForScreen(ChatScreen::class.java)
                context.input.typeChars("Hello from MetalCraft")
                capture("gui_chat", OVERWORLD, benchmarkable = false)
                closeScreen()
            }

            // Item drops spin from a random starting angle and smoke is random too, so these two scenes are
            // looked at by eye instead of being compared pixel by pixel.
            if (selected("entities_fx")) {
                summonItems(world.server, ground)
                world.connection.waitForClientboundPackets()
                context.waitTicks(SETTLE_TICKS)
                capture("entities_fx", OVERWORLD, pixelCompare = false)
            }
            if (selected("gui_f3")) {
                context.onClient { it.debugEntries.setOverlayVisible(true) }
                capture("gui_f3", OVERWORLD, benchmarkable = false, pixelCompare = false)
                context.onClient { it.debugEntries.setOverlayVisible(false) }
            }
        }
    }

    /** 60 motionless mobs behind a row of campfires and torches (animated textures and a steady smoke source). */
    private fun summonMobs(server: TestServerContext, ground: Int) {
        val mobs = arrayOf("cow", "sheep", "pig", "mooshroom", "villager", "iron_golem", "polar_bear", "wolf", "cat", "fox", "llama", "goat")
        for (row in 0 until 5) {
            for ((column, mob) in mobs.withIndex()) {
                val x = (column - (mobs.size - 1) / 2.0) * 3.0
                val z = 8.0 + row * 3.5
                server.runCommand(
                    "summon minecraft:$mob ${fmt(x)} $ground ${fmt(z)} {NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[180f,0f]}",
                )
            }
        }
        for (x in intArrayOf(-9, -3, 3, 9)) server.runCommand("setblock $x $ground 6 minecraft:campfire")
        for (x in intArrayOf(-6, 0, 6)) server.runCommand("setblock $x $ground 6 minecraft:torch")
    }

    private fun summonItems(server: TestServerContext, ground: Int) {
        val items = arrayOf("diamond", "emerald", "gold_ingot", "iron_ingot", "apple", "bread", "redstone", "ender_pearl", "book", "bone", "feather", "stick")
        for ((index, item) in items.withIndex()) {
            val x = (index - (items.size - 1) / 2.0) * 1.5
            server.runCommand(
                "summon minecraft:item ${fmt(x)} $ground 5.0 {Item:{id:\"minecraft:$item\",count:1},PickupDelay:32767,Age:-32768}",
            )
        }
    }

    // ---- scenes ---------------------------------------------------------------------------------------------

    private fun worldScene(name: String, world: TestSingleplayerContext, dimension: String, view: Viewpoint) {
        LOGGER.info("Scene {}: moving to {} {} {} in {}", name, fmt(view.x), fmt(view.y), fmt(view.z), dimension)
        teleport(world, dimension, view)
        waitForWorldReady()
        context.waitForScreen(null)
        removeWildlife(world, dimension)
        context.waitTicks(SETTLE_TICKS)
        // Entities load a little after their chunk, so a second sweep catches the stragglers.
        removeWildlife(world, dimension)
        // A mesh built while its neighbours were still arriving can keep stale light values for good. Rebuilding
        // everything once the world is complete gives the same shading on every run.
        context.onClient { it.levelExtractor.allChanged() }
        context.waitTicks(2)
        waitForWorldReady()
        capture(name, dimension)
    }

    /** Command feedback would otherwise show up as chat lines in every screenshot. */
    private fun quietCommands(server: TestServerContext) {
        server.runCommand("gamerule send_command_feedback false")
        server.runCommand("gamerule log_admin_commands false")
    }

    /** First free block above the terrain. Loads the chunk first: an unloaded column reports the world bottom. */
    private fun surfaceY(level: ServerLevel, x: Int, z: Int): Int {
        level.getChunk(x shr 4, z shr 4)
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
    }

    /**
     * Waits until every chunk the server sends at this render distance has arrived and the chunk mesh queue has
     * stayed empty for a while. The gametest API's own wait expects a full square of chunks, but the server
     * skips the corners, so above render distance 3 it never finishes.
     */
    private fun waitForWorldReady() {
        var stableTicks = 0
        context.waitFor({ client ->
            stableTicks = if (isWorldReady(client)) stableTicks + 1 else 0
            stableTicks >= STABLE_TICKS
        }, CHUNK_TIMEOUT_TICKS)
    }

    private fun isWorldReady(client: Minecraft): Boolean {
        val level = client.level ?: return false
        val player = client.player ?: return false
        val centerX = player.chunkPosition().x()
        val centerZ = player.chunkPosition().z()
        val distance = client.options.effectiveRenderDistance
        for (dz in -distance..distance) {
            for (dx in -distance..distance) {
                // Same shape as ChunkTrackingView.isWithinDistance on the server.
                val ax = maxOf(0, abs(dx) - 1)
                val az = maxOf(0, abs(dz) - 1)
                if (ax * ax + az * az >= distance * distance) continue
                if (level.chunkSource.getChunk(centerX + dx, centerZ + dz, false) == null) return false
            }
        }
        return !level.chunkSource.lightEngine.hasLightWork() && client.levelRenderer.hasRenderedAllSections()
    }

    /**
     * Animals and fish created together with the terrain wander around, so no two runs would match. Dropping
     * them into the void removes them without death animations or loot.
     */
    private fun removeWildlife(world: TestSingleplayerContext, dimension: String) {
        world.server.runCommand("execute in $dimension run tp @e[type=!minecraft:player] 0 -500 0")
        world.connection.waitForClientboundPackets()
    }

    private fun teleport(world: TestSingleplayerContext, dimension: String, view: Viewpoint) {
        world.server.runCommand(
            "execute in $dimension run tp @a ${fmt(view.x)} ${fmt(view.y)} ${fmt(view.z)} ${fmt(view.yaw.toDouble())} ${fmt(view.pitch.toDouble())}",
        )
        world.server.onServer { server ->
            for (player in server.playerList.players) {
                player.abilities.flying = true
                player.onUpdateAbilities()
            }
        }
        world.connection.waitForClientboundPackets()
        context.waitTicks(2)
    }

    /**
     * Screenshot first, frame times second. With [pixelCompare] the frame is made repeatable just before the
     * shot: animated textures are rewound to their first frame, particles and toasts are removed.
     */
    private fun capture(name: String, dimension: String, benchmarkable: Boolean = true, pixelCompare: Boolean = true) {
        context.waitTicks(5)
        val file = try {
            if (pixelCompare) {
                context.onClient { Determinism.holdAnimations = true }
                // The next texture tick redraws every animated sprite at frame zero.
                context.waitTicks(2)
                context.onClient { client ->
                    client.particleEngine.clearParticles()
                    client.gui.toastManager().clear()
                }
            }
            context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(outputDir))
        } finally {
            context.onClient { Determinism.holdAnimations = false }
        }

        val warmup = if (benchmarkable) warmupSeconds else QUICK_WARMUP_SECONDS
        val measure = if (benchmarkable) measureSeconds else QUICK_MEASURE_SECONDS
        waitSeconds(warmup)
        context.onClient { FrameRecorder.start() }
        waitSeconds(measure)
        val stats = context.onClient { FrameRecorder.stop() }

        val scene = JsonObject()
        scene.addProperty("name", name)
        scene.addProperty("screenshot", file.fileName.toString())
        scene.addProperty("pixelCompare", pixelCompare)
        scene.addProperty("dimension", dimension)
        context.onClient { client ->
            val player = client.player ?: return@onClient
            scene.addProperty("x", round2(player.x))
            scene.addProperty("y", round2(player.y))
            scene.addProperty("z", round2(player.z))
            scene.addProperty("yaw", round2(player.yRot.toDouble()))
            scene.addProperty("pitch", round2(player.xRot.toDouble()))
        }
        scene.addProperty("measureSeconds", measure)
        scene.addProperty("frames", stats.frames)
        scene.addProperty("avgFps", round2(stats.averageFps))
        scene.addProperty("low1PercentFps", round2(stats.onePercentLowFps))
        scene.addProperty("avgFrameTimeMs", round3(stats.averageFrameTimeMs))
        scene.addProperty("p99FrameTimeMs", round3(stats.p99FrameTimeMs))
        scene.addProperty("maxFrameTimeMs", round3(stats.maxFrameTimeMs))
        sceneResults.add(scene)
        if (benchmarkable) {
            benchmarkTable.append(
                "| $name | ${fmt(stats.averageFps)} | ${fmt(stats.onePercentLowFps)} | " +
                    "${fmt(stats.averageFrameTimeMs)} | ${fmt(stats.p99FrameTimeMs)} |\n",
            )
        }
        LOGGER.info(
            "Scene {}: {} FPS avg, {} FPS 1% low, {} ms avg, {} ms p99",
            name, round2(stats.averageFps), round2(stats.onePercentLowFps), round3(stats.averageFrameTimeMs), round3(stats.p99FrameTimeMs),
        )
    }

    private fun closeScreen() {
        context.setScreen(Supplier<Screen?> { null })
        context.waitTicks(2)
    }

    /** Lets the game run freely for [seconds] of wall-clock time. Frames are not limited to one per tick. */
    private fun waitSeconds(seconds: Double) {
        val end = System.nanoTime() + (seconds * 1e9).toLong()
        while (System.nanoTime() < end) context.waitTick()
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger("MetalCraft/Test")
        val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().create()

        const val WIDTH = 1920
        const val HEIGHT = 1200
        const val RENDER_DISTANCE = 12
        const val GUI_SCALE = 3
        const val UNLIMITED_FRAMERATE = 260
        const val NORMAL_WORLD_SEED = "2626"
        const val NORMAL_WORLD_CACHE = "metalcraft-normal"
        const val SEA_LEVEL = 63
        const val CHUNK_TIMEOUT_TICKS = 20 * 600
        const val SETTLE_TICKS = 60
        const val STABLE_TICKS = 20
        const val QUICK_WARMUP_SECONDS = 2.0
        const val QUICK_MEASURE_SECONDS = 5.0
        const val OVERWORLD = "minecraft:overworld"
        const val NETHER = "minecraft:the_nether"

        val NORMAL_WORLD_SCENES = listOf("plains_day", "ocean", "night_torches", "cave", "nether")
        val FLAT_WORLD_SCENES = listOf("entities", "gui_inventory", "after_reload", "gui_chat", "entities_fx", "gui_f3")
        val ALL_SCENES = (NORMAL_WORLD_SCENES + FLAT_WORLD_SCENES).toSet()

        fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
        fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
        fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0
    }
}

private inline fun <T> ClientGameTestContext.onClient(crossinline block: (Minecraft) -> T): T =
    computeOnClient<T, RuntimeException> { block(it) }

private inline fun <T> TestServerContext.onServer(crossinline block: (MinecraftServer) -> T): T =
    computeOnServer<T, RuntimeException> { block(it) }
