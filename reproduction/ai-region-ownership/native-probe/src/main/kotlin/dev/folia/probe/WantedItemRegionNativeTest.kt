package dev.folia.probe

import net.minecraft.core.BlockPos
import net.kyori.adventure.text.Component
import net.minecraft.world.entity.ai.behavior.EntityTracker
import net.minecraft.world.entity.ai.behavior.GoToWantedItem
import net.minecraft.world.entity.ai.behavior.PositionTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.item.ItemEntity
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftItem
import org.bukkit.craftbukkit.entity.CraftMob
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class WantedItemRegionNativeTest(private val plugin: JavaPlugin) {
    private data class Target(val entity: CraftItem, val handle: ItemEntity)
    private data class Memories(val wanted: ItemEntity, val look: PositionTracker, val walk: WalkTarget?)
    private val subjects = mutableListOf<Entity>()
    private val chunks = mutableSetOf<Triple<World, Int, Int>>()
    private val origin = BlockPos(3, 65, 3)
    private var failures = 0
    private var completed = 0

    fun run() {
        plugin.server.asyncScheduler.runNow(plugin) {
            try {
                val world = Bukkit.getWorlds().first { it.environment == World.Environment.NORMAL }
                val nether = Bukkit.getWorlds().first { it.environment == World.Environment.NETHER }
                val remote = onRegion(world, BlockPos(8195, 65, 3)) { item(world, BlockPos(8195, 65, 3)) }.get(30, TimeUnit.SECONDS)
                val foreign = onRegion(nether, origin) { item(nether, origin.offset(1, 0, 0)) }.get(30, TimeUnit.SECONDS)
                for (type in listOf(EntityType.ALLAY, EntityType.PIGLIN, EntityType.VILLAGER)) {
                    scenario("${type}_LOCAL") { onRegion(world, origin) { local(world, type) }.get(30, TimeUnit.SECONDS) }
                }
                scenario("VILLAGER_EXISTING_WALK") { onRegion(world, origin) {
                    val body = mob(world, EntityType.VILLAGER)
                    val target = item(world, origin.offset(1, 0, 0))
                    val before = seed(body, target, true)
                    check(!trigger(world, body))
                    unchanged(body, before)
                }.get(30, TimeUnit.SECONDS) }
                for (mode in listOf("cancel", "null", "non-item", "local-replacement", "removed-replacement")) {
                    scenario("ALLAY_EVENT_${mode.uppercase().replace('-', '_')}") {
                        onRegion(world, origin) { eventCase(world, mode, remote, foreign) }.get(30, TimeUnit.SECONDS)
                    }
                }
                scenario("ALLAY_ORIGINAL_REMOTE_OUT_OF_RANGE") { onRegion(world, origin) {
                    check(!Bukkit.isOwnedByCurrentRegion(remote.entity))
                    val body = mob(world, EntityType.ALLAY)
                    val before = seed(body, remote, true)
                    withTargetEvent(body, {}) { calls ->
                        check(!trigger(world, body))
                        check(calls() == 0)
                        unchanged(body, before)
                    }
                }.get(30, TimeUnit.SECONDS) }
                for (type in listOf(EntityType.ALLAY, EntityType.PIGLIN, EntityType.VILLAGER)) {
                    scenario("${type}_ORIGINAL_FOREIGN_WORLD") { onRegion(world, origin) {
                        check(!Bukkit.isOwnedByCurrentRegion(foreign.entity))
                        val body = mob(world, type)
                        val before = seed(body, foreign, type != EntityType.VILLAGER)
                        withTargetEvent(body, {}) { calls ->
                            check(!trigger(world, body))
                            check(calls() == 0)
                            unchanged(body, before)
                        }
                    }.get(30, TimeUnit.SECONDS) }
                }
                for (mode in listOf("remote-replacement", "foreign-replacement")) {
                    scenario("ALLAY_EVENT_${mode.uppercase().replace('-', '_')}") {
                        onRegion(world, origin) { eventCase(world, mode, remote, foreign) }.get(30, TimeUnit.SECONDS)
                    }
                }
                scenario("ALLAY_EVENT_TRANSFER_ORIGINAL") { transferCase(world).get(30, TimeUnit.SECONDS) }
                check(failures == 0) { "$failures of $completed wanted-item cases failed" }
                plugin.logger.info("WANTED_ITEM_REGION_NATIVE_PASS cases=$completed")
            } catch (failure: Throwable) {
                plugin.logger.log(Level.SEVERE, "WANTED_ITEM_REGION_NATIVE_FAIL", failure)
            } finally {
                for (subject in subjects) {
                    val done = CompletableFuture<Unit>()
                    val task = subject.scheduler.run(plugin, {
                        try { subject.remove(); done.complete(Unit) }
                        catch (failure: Throwable) { done.completeExceptionally(failure) }
                    }, { done.complete(Unit) })
                    if (task == null) done.complete(Unit)
                    try { done.get(10, TimeUnit.SECONDS) }
                    catch (failure: Throwable) { plugin.logger.log(Level.SEVERE, "WANTED_ITEM_CLEANUP_FAIL", failure) }
                }
                for ((world, x, z) in chunks) {
                    val done = CompletableFuture<Unit>()
                    Bukkit.getRegionScheduler().execute(plugin, world, x, z) {
                        world.removePluginChunkTicket(x, z, plugin)
                        done.complete(Unit)
                    }
                    try { done.get(10, TimeUnit.SECONDS) }
                    catch (failure: Throwable) { plugin.logger.log(Level.SEVERE, "WANTED_ITEM_CLEANUP_FAIL", failure) }
                }
                plugin.server.shutdown()
            }
        }
    }

    private fun local(world: World, type: EntityType) {
        val body = mob(world, type)
        val target = item(world, origin.offset(1, 0, 0))
        val before = seed(body, target, type != EntityType.VILLAGER)
        withTargetEvent(body, { event -> check(event.target === target.entity) }) { calls ->
            check(trigger(world, body))
            check(calls() == if (type == EntityType.ALLAY) 1 else 0)
            tracked(body, target)
            check(body.handle.brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM).orElseThrow() === before.wanted)
        }
    }

    private fun eventCase(world: World, mode: String, remote: Target, foreign: Target) {
        val body = mob(world, EntityType.ALLAY)
        val wanted = item(world, origin.offset(1, 0, 0))
        val before = seed(body, wanted, true)
        val replacement = when (mode) {
            "remote-replacement" -> remote
            "foreign-replacement" -> foreign
            else -> item(world, origin.offset(1, 45, 0))
        }
        if (mode == "remote-replacement" || mode == "foreign-replacement") check(!Bukkit.isOwnedByCurrentRegion(replacement.entity))
        else check(Bukkit.isOwnedByCurrentRegion(replacement.entity))
        withTargetEvent(body, { event ->
            when (mode) {
                "cancel" -> { event.target = foreign.entity; event.isCancelled = true }
                "null" -> event.target = null
                "non-item" -> event.target = body
                "removed-replacement" -> {
                    replacement.entity.remove()
                    check(Bukkit.isOwnedByCurrentRegion(replacement.entity))
                    event.target = replacement.entity
                }
                else -> event.target = replacement.entity
            }
        }) { calls ->
            val accepted = trigger(world, body)
            check(calls() == 1)
            when (mode) {
                "local-replacement", "removed-replacement" -> {
                    check(accepted)
                    check(replacement.handle.distanceToSqr(body.handle) > 32.0 * 32.0)
                    tracked(body, replacement)
                    check(body.handle.brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM).orElseThrow() === before.wanted)
                }
                else -> {
                    check(!accepted)
                    unchanged(body, before, eraseWanted = mode == "null" || mode == "non-item")
                }
            }
        }
    }

    private fun transferCase(world: World): CompletableFuture<Unit> {
        var transferred: CompletableFuture<Boolean>? = null
        return onRegion(world, origin) {
            val body = mob(world, EntityType.ALLAY)
            val wanted = item(world, origin.offset(1, 0, 0))
            val before = seed(body, wanted, true)
            withTargetEvent(body, {
                transferred = wanted.entity.teleportAsync(Location(world, 8197.5, 65.5, 3.5))
                check(!Bukkit.isOwnedByCurrentRegion(wanted.entity)) { "Real teleport did not relinquish ownership in this event" }
            }) { calls ->
                check(!trigger(world, body))
                check(calls() == 1)
                unchanged(body, before)
            }
        }.thenCompose {
            checkNotNull(transferred).thenApply { check(it); Unit }
        }
    }

    private fun trigger(world: World, body: CraftMob): Boolean {
        val level = (world as CraftWorld).handle
        val handle = body.handle
        val maxDistance = when (body.type) { EntityType.ALLAY -> 32; EntityType.PIGLIN -> 9; else -> 4 }
        val behavior = GoToWantedItem.create<net.minecraft.world.entity.Mob>(
            { actor -> if (body.type == EntityType.VILLAGER) !actor.isSleeping else true },
            if (body.type == EntityType.ALLAY) 1.75f else 1.0f,
            body.type != EntityType.VILLAGER,
            maxDistance,
        )
        check(handle.canPickUpLoot())
        check(handle.offhandItem.isEmpty)
        var result = false
        level.guardEntityTick({ actor -> result = behavior.tryStart(level, actor, 1L) }, handle)
        check(!handle.isRemoved) { "GoToWantedItem discarded ${body.type} while resolving its item target" }
        return result
    }

    private fun seed(body: CraftMob, wanted: Target, existingWalk: Boolean): Memories {
        val brain = body.handle.brain
        val look = EntityTracker(body.handle, true)
        val walk = if (existingWalk) WalkTarget(origin.offset(-1, 0, 0), 0.25f, 2) else null
        brain.eraseMemory(MemoryModuleType.ITEM_PICKUP_COOLDOWN_TICKS)
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, wanted.handle)
        brain.setMemory(MemoryModuleType.LOOK_TARGET, look)
        if (walk == null) brain.eraseMemory(MemoryModuleType.WALK_TARGET) else brain.setMemory(MemoryModuleType.WALK_TARGET, walk)
        return Memories(wanted.handle, look, walk)
    }

    private fun unchanged(body: CraftMob, before: Memories, eraseWanted: Boolean = false) {
        val brain = body.handle.brain
        check(brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM).orElse(null) === if (eraseWanted) null else before.wanted)
        check(brain.getMemory(MemoryModuleType.LOOK_TARGET).orElse(null) === before.look)
        check(brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null) === before.walk)
    }

    private fun tracked(body: CraftMob, target: Target) {
        val brain = body.handle.brain
        val walk = brain.getMemory(MemoryModuleType.WALK_TARGET).orElseThrow()
        val look = brain.getMemory(MemoryModuleType.LOOK_TARGET).orElseThrow()
        check((walk.target as EntityTracker).entity === target.handle)
        check((look as EntityTracker).entity === target.handle)
        check(walk.closeEnoughDist == 0)
        check(walk.speedModifier == if (body.type == EntityType.ALLAY) 1.75f else 1.0f)
    }

    private fun withTargetEvent(body: CraftMob, modify: (EntityTargetEvent) -> Unit, action: (() -> Int) -> Unit) {
        var calls = 0
        var listenerFailure: Throwable? = null
        val listener = object : Listener {
            @EventHandler fun target(event: EntityTargetEvent) {
                if (event.entity.uniqueId != body.uniqueId) return
                calls++
                try {
                    check(event.reason == EntityTargetEvent.TargetReason.CLOSEST_ENTITY)
                    modify(event)
                } catch (failure: Throwable) {
                    listenerFailure = failure
                }
            }
        }
        Bukkit.getPluginManager().registerEvents(listener, plugin)
        try { action { calls } } finally {
            HandlerList.unregisterAll(listener)
            listenerFailure?.let { throw it }
        }
    }

    private fun mob(world: World, type: EntityType): CraftMob = (world.spawnEntity(Location(world, 3.5, 65.5, 3.5), type) as CraftMob).also {
        subjects += it
        it.setAI(false)
        it.setGravity(false)
        it.isInvulnerable = true
        it.canPickupItems = true
        if (type == EntityType.ALLAY) it.equipment.setItemInMainHand(ItemStack(Material.STONE))
    }

    private fun item(world: World, pos: BlockPos): Target {
        val stack = ItemStack(Material.STONE).also {
            it.editMeta { meta -> meta.displayName(Component.text("wanted-item-${subjects.size}")) }
        }
        val entity = world.dropItem(Location(world, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5), stack) {
            it.velocity = Vector()
            it.setGravity(false)
            it.setWillAge(false)
            it.setCanMobPickup(false)
            it.setCanPlayerPickup(false)
        } as CraftItem
        subjects += entity
        return Target(entity, entity.handle)
    }

    private fun scenario(name: String, action: () -> Unit) {
        completed++
        try { action(); plugin.logger.info("WANTED_ITEM_CASE_PASS name=$name") }
        catch (failure: Throwable) { failures++; plugin.logger.log(Level.SEVERE, "WANTED_ITEM_CASE_FAIL name=$name", failure) }
    }

    private fun <T> onRegion(world: World, pos: BlockPos, action: () -> T): CompletableFuture<T> =
        world.getChunkAtAsync(pos.x shr 4, pos.z shr 4).thenCompose {
            val result = CompletableFuture<T>()
            Bukkit.getRegionScheduler().execute(plugin, world, pos.x shr 4, pos.z shr 4) {
                try {
                    val chunk = Triple(world, pos.x shr 4, pos.z shr 4)
                    if (chunks.add(chunk)) world.addPluginChunkTicket(chunk.second, chunk.third, plugin)
                    result.complete(action())
                } catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
            result
        }
}
