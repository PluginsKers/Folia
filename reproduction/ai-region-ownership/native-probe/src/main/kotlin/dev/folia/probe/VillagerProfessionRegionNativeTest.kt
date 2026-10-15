package dev.folia.probe

import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.world.entity.ai.behavior.AssignProfessionFromJobSite
import net.minecraft.world.entity.ai.behavior.GoToPotentialJobSite
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.schedule.Activity
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.VillagerCareerChangeEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class VillagerProfessionRegionNativeTest(private val plugin: JavaPlugin) {
    fun run() {
        plugin.server.asyncScheduler.runNow(plugin) {
            try {
                val world = Bukkit.getWorlds().first { it.environment == World.Environment.NORMAL }
                val other = Bukkit.getWorlds().first { it.environment == World.Environment.NETHER }
                val remote = BlockPos(8195, 65, 3)
                val remoteSite = onRegion(world, remote) { reserveSite(world, remote) }.get(20, TimeUnit.SECONDS)
                val postponed = onRegion(world, BlockPos(2, 65, 2)) { deferred(world, remoteSite) }.get(20, TimeUnit.SECONDS)
                plugin.logger.info("VILLAGER_PROFESSION_REMOTE_DISTANCE_PASS")

                val foreignPos = BlockPos(3, 65, 3)
                val foreignSite = onRegion(other, foreignPos) { reserveSite(other, foreignPos) }.get(20, TimeUnit.SECONDS)
                onRegion(world, BlockPos(2, 65, 2)) { deferred(world, foreignSite).remove() }.get(20, TimeUnit.SECONDS)
                plugin.logger.info("VILLAGER_PROFESSION_DIMENSION_GUARD_PASS")

                check(postponed.teleportAsync(Location(world, remote.x + 0.5, 65.0, remote.z + 0.5)).get(20, TimeUnit.SECONDS))
                onRegion(world, remote) {
                    val handle = postponed.handle

                    check(AssignProfessionFromJobSite.create().tryStart((world as CraftWorld).handle, handle, 1L))
                    check(postponed.profession == Villager.Profession.CLERIC)
                    check(handle.brain.getMemory(MemoryModuleType.JOB_SITE).orElseThrow() == remoteSite)
                    check(!handle.brain.hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE))
                    postponed.remove()
                }.get(20, TimeUnit.SECONDS)
                plugin.logger.info("VILLAGER_PROFESSION_APPROACH_RETRY_PASS")

                onRegion(world, BlockPos(2, 65, 2)) {
                    for (z in listOf(5, 6)) {
                        val site = reserveSite(world, BlockPos(3, 65, z))
                        val villager = spawn(world, Location(world, 2.5, 65.0, 5.5))
                        val handle = villager.handle

                        handle.brain.setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, site)
                        check(AssignProfessionFromJobSite.create().tryStart((world as CraftWorld).handle, handle, 2L))
                        check(villager.profession == Villager.Profession.CLERIC)
                        check(handle.brain.getMemory(MemoryModuleType.JOB_SITE).orElseThrow() == site)
                        villager.remove()
                    }
                }.get(20, TimeUnit.SECONDS)
                plugin.logger.info("VILLAGER_PROFESSION_LOCAL_ASSIGNMENT_PASS")
                onRegion(world, BlockPos(2, 65, 2)) {
                    val site = reserveSite(world, BlockPos(3, 65, 7))
                    val villager = spawn(world, Location(world, 2.5, 65.0, 7.5))
                    val handle = villager.handle
                    var calls = 0
                    val listener = object : Listener {
                        @EventHandler
                        fun cancel(event: VillagerCareerChangeEvent) {
                            if (event.entity.uniqueId == villager.uniqueId) {
                                calls++
                                check(event.reason == VillagerCareerChangeEvent.ChangeReason.EMPLOYED)
                                event.isCancelled = true
                            }
                        }
                    }
                    Bukkit.getPluginManager().registerEvents(listener, plugin)
                    try {
                        handle.brain.setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, site)
                        check(AssignProfessionFromJobSite.create().tryStart((world as CraftWorld).handle, handle, 3L))
                        check(calls == 1 && villager.profession == Villager.Profession.NONE)
                        check(handle.brain.getMemory(MemoryModuleType.JOB_SITE).orElseThrow() == site)
                        check(!handle.brain.hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE))
                    } finally {
                        HandlerList.unregisterAll(listener)
                        villager.remove()
                    }
                }.get(20, TimeUnit.SECONDS)
                plugin.logger.info("VILLAGER_PROFESSION_CANCELLED_EVENT_PASS")
                timeout(world, BlockPos(8197, 65, 5))
                plugin.logger.info("VILLAGER_PROFESSION_REGION_NATIVE_PASS")
            } catch (failure: Throwable) {
                plugin.logger.log(Level.SEVERE, "VILLAGER_PROFESSION_REGION_NATIVE_FAIL", failure)
            } finally { plugin.server.shutdown() }
        }
    }

    private fun spawn(world: World, location: Location) = (world.spawn(location, Villager::class.java) {
        it.setAI(false)
        it.setGravity(false)
        it.isInvulnerable = true
        it.profession = Villager.Profession.NONE
    } as CraftVillager)

    private fun deferred(world: World, site: GlobalPos): CraftVillager {
        val villager = spawn(world, Location(world, 2.5, 65.0, 2.5))
        val handle = villager.handle
        val level = (world as CraftWorld).handle
        check(site.dimension() != level.dimension() || !Bukkit.isOwnedByCurrentRegion(world, site.pos().x shr 4, site.pos().z shr 4))

        handle.brain.eraseMemory(MemoryModuleType.JOB_SITE)
        handle.brain.setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, site)
        var started = false
        level.guardEntityTick({ body -> started = AssignProfessionFromJobSite.create().tryStart(level, body, 0L) }, handle)
        check(!handle.isRemoved) { "Remote profession lookup discarded the villager" }
        check(!started && villager.profession == Villager.Profession.NONE)
        check(handle.brain.getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).orElseThrow() == site)
        check(!handle.brain.hasMemoryValue(MemoryModuleType.JOB_SITE))
        return villager
    }

    private fun reserveSite(world: World, pos: BlockPos): GlobalPos {
        val block = world.getBlockAt(pos.x, pos.y, pos.z)
        block.type = Material.BREWING_STAND
        val level = (world as CraftWorld).handle
        if (level.poiManager.getType(pos).isEmpty) {
            level.poiManager.add(pos, PoiTypes.forState((block.blockData as CraftBlockData).state).orElseThrow())
        }
        check(level.poiManager.take({ true }, { _, candidate -> candidate == pos }, pos, 1).isPresent)
        return GlobalPos.of(level.dimension(), pos)
    }

    private fun timeout(world: World, remote: BlockPos) {
        val site = onRegion(world, remote) { reserveSite(world, remote) }.get(20, TimeUnit.SECONDS)
        onRegion(world, BlockPos(2, 65, 2)) {
            val villager = deferred(world, site)
            val handle = villager.handle
            val level = (world as CraftWorld).handle
            handle.brain.setActiveActivityIfPossible(Activity.IDLE)
            val approach = GoToPotentialJobSite(0.5f)
            check(approach.tryStart(level, handle, 100L))
            approach.tickOrStop(level, handle, 1301L)
            check(!handle.brain.hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE))
            check(!handle.brain.hasMemoryValue(MemoryModuleType.JOB_SITE))
            check(!handle.isRemoved)
            villager.remove()
        }.get(20, TimeUnit.SECONDS)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            val released = onRegion(world, remote) {
                (world as CraftWorld).handle.poiManager.getCountInRange({ true }, remote, 0, PoiManager.Occupancy.HAS_SPACE) == 1L
            }.get(10, TimeUnit.SECONDS)
            if (released) {
                plugin.logger.info("VILLAGER_PROFESSION_TIMEOUT_RELEASE_PASS")
                return
            }
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        error("Deferred potential job site was not released after the vanilla timeout")
    }

    private fun <T> onRegion(world: World, pos: BlockPos, action: () -> T): CompletableFuture<T> =
        world.getChunkAtAsync(pos.x shr 4, pos.z shr 4).thenCompose {
            val result = CompletableFuture<T>()
            Bukkit.getRegionScheduler().execute(plugin, world, pos.x shr 4, pos.z shr 4) {
                try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
            result
        }
}
