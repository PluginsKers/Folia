package dev.folia.probe

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.JumpOnBed
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Bed
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Villager
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class VillagerJumpBedNativeTest(private val plugin: JavaPlugin) {
    private val fields = listOf("targetBed", "remainingTimeToReachBed", "remainingJumps", "remainingCooldownUntilNextJump")
        .associateWith { JumpOnBed::class.java.getDeclaredField(it).apply { isAccessible = true } }
    private val subjects = mutableListOf<Entity>()
    private val bed = BlockPos(3, 65, 3)

    fun run() {
        plugin.server.asyncScheduler.runNow(plugin) {
            try {
                val world = Bukkit.getWorlds().first { it.environment == World.Environment.NORMAL }
                val nether = Bukkit.getWorlds().first { it.environment == World.Environment.NETHER }
                onRegion(world, bed) {
                    placeBed(world, bed)
                    localJump(world)
                    walkingTimeout(world)
                    nonBedAndAdult(world)
                }.get(30, TimeUnit.SECONDS)
                dimensionTransfer(world, nether, true)
                dimensionTransfer(world, nether, false)
                onRegion(world, bed) {
                    unloadedBed(world, true)
                    unloadedBed(world, false)
                }.get(30, TimeUnit.SECONDS)
                remoteTransfer(world)
                plugin.logger.info("VILLAGER_JUMP_BED_NATIVE_PASS")
            } catch (failure: Throwable) {
                plugin.logger.log(Level.SEVERE, "VILLAGER_JUMP_BED_NATIVE_FAIL", failure)
            } finally {
                for (subject in subjects) {
                    val done = CompletableFuture<Unit>()
                    val task = subject.scheduler.run(plugin, {
                        try { subject.remove(); done.complete(Unit) }
                        catch (failure: Throwable) { done.completeExceptionally(failure) }
                    }, { done.complete(Unit) })
                    if (task == null) done.complete(Unit)
                    try { done.get(10, TimeUnit.SECONDS) }
                    catch (failure: Throwable) { plugin.logger.log(Level.SEVERE, "VILLAGER_JUMP_BED_CLEANUP_FAIL", failure) }
                }
                plugin.server.shutdown()
            }
        }
    }

    private fun localJump(world: World) {
        val subject = spawn(world, bed)
        val handle = subject.handle
        val behavior = start(world, subject, bed)
        val initialJumps = fields.getValue("remainingJumps").getInt(behavior)
        check(initialJumps in 3..6)
        tick(world, subject, behavior, 1L)
        check(behavior.status == Behavior.Status.RUNNING)
        check(fields.getValue("remainingJumps").getInt(behavior) == initialJumps - 1)
        handle.jumpControl.tick()
        check(handle.isJumping) { "The real JumpControl did not request a jump" }
        handle.setOnGround(true)
        handle.aiStep()
        check(handle.deltaMovement.y > 0.0) { "The villager did not receive upward jump velocity" }
        handle.setPos(bed.x + 0.5, bed.y + 0.5625, bed.z + 0.5)
        for (time in 2L..50L) {
            if (behavior.status == Behavior.Status.STOPPED) break
            tick(world, subject, behavior, time)
        }
        stopped(behavior)
        subject.remove()
        plugin.logger.info("VILLAGER_JUMP_BED_LOCAL_JUMP_AND_FINISH_PASS")
    }

    private fun walkingTimeout(world: World) {
        val subject = spawn(world, bed.offset(3, 0, 0))
        val behavior = start(world, subject, bed)
        repeat(100) { tick(world, subject, behavior, 20_000L + it) }
        check(behavior.status == Behavior.Status.RUNNING)
        check(fields.getValue("remainingTimeToReachBed").getInt(behavior) == 0)
        tick(world, subject, behavior, 20_100L)
        stopped(behavior)
        subject.remove()
        plugin.logger.info("VILLAGER_JUMP_BED_WALK_TIMEOUT_PASS")
    }

    private fun nonBedAndAdult(world: World) {
        val subject = spawn(world, bed)
        val nonBed = bed.offset(4, 0, 0)
        world.getBlockAt(nonBed.x, nonBed.y, nonBed.z).setType(Material.STONE, false)
        val behavior = start(world, subject, nonBed)
        tick(world, subject, behavior, 1L)
        stopped(behavior)
        subject.setAdult()
        subject.handle.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        subject.handle.brain.setMemory(MemoryModuleType.NEAREST_BED, bed)
        check(!JumpOnBed(0.5f).tryStart((world as CraftWorld).handle, subject.handle, 0L))
        subject.remove()
        plugin.logger.info("VILLAGER_JUMP_BED_NON_BED_AND_ADULT_PASS")
    }

    private fun dimensionTransfer(world: World, destination: World, localBed: Boolean) {
        onRegion(destination, bed) {
            placeBed(destination, bed)
            if (!localBed) destination.getBlockAt(bed.x, bed.y, bed.z).setType(Material.AIR, false)
        }.get(30, TimeUnit.SECONDS)
        val (subject, behavior) = onRegion(world, bed) {
            val subject = spawn(world, bed)
            subject to start(world, subject, bed)
        }.get(30, TimeUnit.SECONDS)
        check(subject.teleportAsync(location(destination, bed)).get(30, TimeUnit.SECONDS))
        onRegion(destination, bed) {
            check(subject.world == destination)
            check(fields.getValue("targetBed").get(behavior) == bed)
            tick(destination, subject, behavior, 1L)
            if (localBed) {
                check(behavior.status == Behavior.Status.RUNNING)
                subject.handle.jumpControl.tick()
                check(subject.handle.isJumping)
                behavior.doStop((destination as CraftWorld).handle, subject.handle, 2L)
            }
            stopped(behavior)
            subject.remove()
        }.get(30, TimeUnit.SECONDS)
        plugin.logger.info("VILLAGER_JUMP_BED_DIMENSION_${if (localBed) "LOCAL_BED" else "NO_BED"}_PASS")
    }

    private fun unloadedBed(world: World, mounted: Boolean) {
        val remote = BlockPos(16387, 65, 3)
        check(!world.isChunkLoaded(remote.x shr 4, remote.z shr 4))
        check(!Bukkit.isOwnedByCurrentRegion(world, remote.x shr 4, remote.z shr 4))
        val subject = spawn(world, bed.offset(3, 0, 2))
        val boat = if (mounted) (world.spawnEntity(subject.location, EntityType.OAK_BOAT) as CraftEntity).also {
            subjects += it
            it.setGravity(false)
            it.isInvulnerable = true
            check(it.addPassenger(subject))
        } else null
        val behavior = start(world, subject, remote)
        val brain = subject.handle.brain
        val walkTarget = brain.getMemory(MemoryModuleType.WALK_TARGET).orElseThrow()
        if (boat != null) {
            val level = (world as CraftWorld).handle
            level.guardEntityTick({ behavior.tickOrStop(level, subject.handle, 1L) }, boat.handle)
            check(!boat.handle.isRemoved) { "JumpOnBed discarded the boat while ticking its passenger" }
            check(!subject.handle.isRemoved)
            check(subject.vehicle == boat && boat.passengers.single() == subject)
        } else {
            tick(world, subject, behavior, 1L)
        }
        stopped(behavior)
        check(brain.getMemory(MemoryModuleType.NEAREST_BED).orElseThrow() == remote)
        check(brain.getMemory(MemoryModuleType.WALK_TARGET).orElseThrow() === walkTarget)
        check(!world.isChunkLoaded(remote.x shr 4, remote.z shr 4))
        subject.remove()
        boat?.remove()
        plugin.logger.info("VILLAGER_JUMP_BED_UNLOADED_${if (mounted) "BOAT" else "MEMORY"}_STOP_PASS")
    }

    private fun remoteTransfer(world: World) {
        val destination = BlockPos(8195, 65, 3)
        onRegion(world, destination) { placeBed(world, destination) }.get(30, TimeUnit.SECONDS)
        val (subject, behavior) = onRegion(world, bed) {
            val subject = spawn(world, bed)
            subject to start(world, subject, bed)
        }.get(30, TimeUnit.SECONDS)
        check(subject.teleportAsync(location(world, destination)).get(30, TimeUnit.SECONDS))
        onRegion(world, destination) {
            check(!Bukkit.isOwnedByCurrentRegion(world, bed.x shr 4, bed.z shr 4))
            check(fields.getValue("targetBed").get(behavior) == bed)
            tick(world, subject, behavior, 1L)
            stopped(behavior)
            subject.handle.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            val restarted = start(world, subject, destination)
            tick(world, subject, restarted, 2L)
            subject.handle.jumpControl.tick()
            check(subject.handle.isJumping) { "The surviving villager could not jump on its new local bed" }
            restarted.doStop((world as CraftWorld).handle, subject.handle, 3L)
            stopped(restarted)
            subject.remove()
        }.get(30, TimeUnit.SECONDS)
        plugin.logger.info("VILLAGER_JUMP_BED_REMOTE_STOP_AND_RESTART_PASS")
    }

    private fun start(world: World, subject: CraftVillager, target: BlockPos): JumpOnBed {
        val behavior = JumpOnBed(0.5f)
        subject.handle.brain.setMemory(MemoryModuleType.NEAREST_BED, target)
        check(behavior.tryStart((world as CraftWorld).handle, subject.handle, 0L))
        check(behavior.status == Behavior.Status.RUNNING)
        check(fields.getValue("targetBed").get(behavior) == target)
        check(subject.handle.brain.getMemory(MemoryModuleType.WALK_TARGET).orElseThrow().target.currentBlockPosition() == target)
        return behavior
    }

    private fun tick(world: World, subject: CraftVillager, behavior: JumpOnBed, timestamp: Long) {
        val level = (world as CraftWorld).handle
        val handle = subject.handle
        level.guardEntityTick({ body -> behavior.tickOrStop(level, body, timestamp) }, handle)
        check(!handle.isRemoved) { "JumpOnBed discarded the villager while checking its cached bed" }
    }

    private fun stopped(behavior: JumpOnBed) {
        check(behavior.status == Behavior.Status.STOPPED)
        check(fields.getValue("targetBed").get(behavior) == null)
        for (name in fields.keys - "targetBed") check(fields.getValue(name).getInt(behavior) == 0)
    }

    private fun spawn(world: World, pos: BlockPos): CraftVillager = (world.spawn(location(world, pos), Villager::class.java) {
        it.setAI(false)
        it.setGravity(false)
        it.setBaby()
        it.isInvulnerable = true
    } as CraftVillager).also { subjects += it }

    private fun location(world: World, pos: BlockPos) = Location(world, pos.x + 0.5, pos.y + 0.5625, pos.z + 0.5)

    private fun placeBed(world: World, pos: BlockPos) {
        for ((at, part) in listOf(pos to Bed.Part.FOOT, pos.north() to Bed.Part.HEAD)) {
            world.getBlockAt(at.x, at.y - 1, at.z).setType(Material.STONE, false)
            world.getBlockAt(at.x, at.y + 1, at.z).setType(Material.AIR, false)
            world.getBlockAt(at.x, at.y, at.z).setBlockData((Bukkit.createBlockData(Material.RED_BED) as Bed).apply {
                facing = BlockFace.NORTH
                this.part = part
            }, false)
        }
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
