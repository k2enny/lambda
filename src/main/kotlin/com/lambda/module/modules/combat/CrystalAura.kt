/*
 * Copyright 2026 Lambda
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.lambda.module.modules.combat

import com.lambda.config.ConfigEditor.hide
import com.lambda.config.ConfigEditor.hideAllExcept
import com.lambda.config.Tab
import com.lambda.config.automation.AutomationConfig.Companion.setDefaultAutomationConfig
import com.lambda.config.blocks.TargetingSettings
import com.lambda.config.withEdits
import com.lambda.context.SafeContext
import com.lambda.event.events.EntityEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.managers.hotbar.HotbarRequest
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.interaction.managers.rotating.Rotation.Companion.rotationTo
import com.lambda.interaction.managers.rotating.RotationManager
import com.lambda.interaction.material.StackSelection.Companion.selectStack
import com.lambda.interaction.handlers.ContainerHandler.transfer
import com.lambda.interaction.material.container.containers.HotbarContainer
import com.lambda.interaction.material.container.containers.OffHandContainer
import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import com.lambda.threading.runSafe
import com.lambda.threading.runSafeAutomated
import com.lambda.threading.runSafeGameScheduled
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.PacketUtils.sendPacket
import com.lambda.util.Timer
import com.lambda.util.collections.LimitedDecayQueue
import com.lambda.util.combat.CombatUtils.crystalDamage
import com.lambda.util.extension.fullHealth
import com.lambda.util.math.MathUtils.ceilToInt
import com.lambda.util.math.MathUtils.roundToStep
import com.lambda.util.math.distSq
import com.lambda.util.math.flooredBlockPos
import com.lambda.util.math.getHitVec
import com.lambda.util.math.minus
import com.lambda.util.math.plus
import com.lambda.util.player.RotationUtils.getVisibleSurfaces
import com.lambda.util.player.SlotUtils.hotbarStacks
import com.lambda.util.world.fastEntitySearch
import net.minecraft.block.Blocks
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.EndCrystalEntity
import net.minecraft.item.Items
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.fixedRateTimer
import kotlin.time.Duration.Companion.milliseconds

@Suppress("unused")
object CrystalAura : Module(
    name = "CrystalAura",
    description = "Automatically attacks entities with crystals",
    tag = ModuleTag.COMBAT,
) {
    private const val GENERAL_TAB = "General"
    private const val PLACEMENT_TAB = "Placement"
    private const val EXPLODING_TAB = "Exploding"
    private const val PREDICTION_TAB = "Prediction"
    private const val TARGETING_TAB = "Targeting"

    @Tab(GENERAL_TAB) private val rotate by setting("Rotate", true)
    @Tab(GENERAL_TAB) private val updateMode by setting("Update Mode", UpdateMode.Async)
    @Tab(GENERAL_TAB) private val updateDelaySetting by setting("Update Delay", 25L, 5L..200L, 5L, unit = " ms") { updateMode == UpdateMode.Async }
    @Tab(GENERAL_TAB) private val maxUpdatesPerFrame by setting("Max Updates Per Frame", 5, 1..20, 1) { updateMode == UpdateMode.Async }
    @Tab(GENERAL_TAB) private val debug by setting("Debug", false)

    @Tab(PLACEMENT_TAB) private val placeRange by setting("Place Range", 4.6, 1.0..7.0, 0.1, "Range to place crystals", " blocks")
    @Tab(PLACEMENT_TAB) private val placeDelay by setting("Place Delay", 50L, 0L..1000L, 1L, "Delay between placement attempts", " ms")
    @Tab(PLACEMENT_TAB) private val swap by setting("Swap", true, "Swaps to crystals")
    @Tab(PLACEMENT_TAB) private val swapHand by setting("Swap Hand", Hand.MAIN_HAND, "Which hand to swap the crystal to") { swap }
    @Tab(PLACEMENT_TAB) private val priorityMode by setting("Crystal Priority", Priority.Damage)
    @Tab(PLACEMENT_TAB) private val minDamageAdvantage by setting("Min Damage Advantage", 4.0, 1.0..10.0, 0.5) { priorityMode == Priority.Advantage }
    @Tab(PLACEMENT_TAB) private val minTargetDamage by setting("Min Target Damage", 8.0, 0.0..20.0, 0.5, "Minimum target damage to use crystals")
    @Tab(PLACEMENT_TAB) private val maxSelfDamage by setting("Max Self Damage", 8.0, 0.0..36.0, 0.5, "Maximum self damage to use crystals")
    @Tab(PLACEMENT_TAB) private val minPlaceHealth by setting("Min Place Health", 5.0, 0.0..36.0, 0.5, "Minimum player health to place crystals")
    @Tab(PLACEMENT_TAB) private val preventDeath by setting("Prevent Death", true, "Prevent death by crystal")
    @Tab(PLACEMENT_TAB) private val oldPlace by setting("1.12 Placement", false)

    @Tab(EXPLODING_TAB) private val explodeRange by setting("Explode Range", 3.0, 1.0..7.0, 0.1, "Range to explode crystals", " blocks")
    @Tab(EXPLODING_TAB) private val explodeDelay by setting("Explode Delay", 10L, 0L..1000L, 1L, "Delay between explosion attempts", " ms")

    @Tab(PREDICTION_TAB) private val prediction by setting("Prediction", PredictionMode.None)
    @Tab(PREDICTION_TAB) private val packetPredictions by setting("Packet Predictions", 1, 0..20, 1) { prediction.onPacket }
    @Tab(PREDICTION_TAB) private val placePostPause by setting("Place Post Pause", true) { prediction.onPacket }
    @Tab(PREDICTION_TAB) private val placePredictions by setting("Place Predictions", 4, 1..20, 1) { prediction.onPlace }
    @Tab(PREDICTION_TAB) private val packetLifetime by setting("Packet Lifetime", 500L, 50L..1000L) { prediction.onPlace }

    @Tab(PREDICTION_TAB) private val targetingSettings by configBlock(TargetingSettings.CombatSettings(this, 10.0))

    private val blueprint = mutableMapOf<BlockPos, Opportunity>()
    private var activeOpportunity: Opportunity? = null
    private var currentTarget: LivingEntity? = null

    private val damage = mutableListOf<Opportunity>()
    private val actionMap = mutableMapOf<ActionType, MutableList<Opportunity>>()
    private var actionType = ActionType.Normal

    private val pendingAsyncUpdates = AtomicInteger()
    private var lastAsyncUpdate = 0L
    private var updatesThisFrame = 0

    private val placeTimer = Timer()
    private val explodeTimer = Timer()

    private val predictionTimer = Timer()
    private var lastEntityId = 0

    private val decay = LimitedDecayQueue<Int>(10000, 3000L)

    private val collidingOffsets = mutableListOf<BlockPos>().apply {
        for (x in -1..1) {
            for (z in -1..1) {
                for (y in 0..1) {
                    if (x != 0 || y != 0 || z != 0) add(BlockPos(x, y, z))
                }
            }
        }
    }

	init {
		setDefaultAutomationConfig()
            .withEdits {
                hideAllExcept(::buildConfig, ::rotationConfig, ::hotbarConfig, ::inventoryConfig)
                buildConfig.apply {
                    hide(
                        ::pathing, ::collectDrops, ::spleefEntities,
                        ::maxPendingActions, ::actionTimeout, ::maxBuildDependencies, ::breakBlocks, ::interactBlocks, ::placeBlocks
                    )
                }
            }

        // Async ticking
        fixedRateTimer(
            name = "Crystal Aura Thread",
            daemon = true,
            initialDelay = 0L,
            period = 1L
        ) {
            if (isDisabled || updateMode != UpdateMode.Async) return@fixedRateTimer

            val now = System.currentTimeMillis()
            if (now - lastAsyncUpdate < updateDelaySetting) return@fixedRateTimer

            lastAsyncUpdate = now
            pendingAsyncUpdates.updateAndGet {
                (it + 1).coerceAtMost(maxUpdatesPerFrame)
            }
        }

        fixedRateTimer(
            name = "CA Counter",
            daemon = true,
            initialDelay = 0L,
            period = 1000L
        ) {
            if (isDisabled || !debug) return@fixedRateTimer

            runSafeGameScheduled {
                info((decay.size.toDouble() * 0.3333).roundToStep(0.1).toString())
            }
        }

        // Ticking with alignment
        listen<TickEvent.Input.Post> {
            when (updateMode) {
                UpdateMode.Async -> runPendingAsyncTicks()
                UpdateMode.Ticked -> {
                    pendingAsyncUpdates.set(0)
                    tick()
                }
            }
        }

        listen<TickEvent.Render.Post> {
            updatesThisFrame = 0
        }

        // Update last received entity spawn
        listen<EntityEvent.Spawn>(alwaysListen = true) { event ->
            lastEntityId = event.entity.id
            predictionTimer.reset()
        }

        // Prediction
        listen<EntityEvent.Spawn> { event ->
            val crystal = event.entity as? EndCrystalEntity ?: return@listen
            val pos = crystal.baseBlockPos

            // Update crystal
            val opportunity = blueprint[pos] ?: return@listen
            opportunity.crystal = crystal

            // Run packet prediction
            if (!prediction.isActive || activeOpportunity != opportunity) return@listen

            explodeInternal(lastEntityId)

            if (!prediction.onPacket) return@listen

            repeat(packetPredictions) {
                placeInternal(opportunity, swapHand)
                explodeInternal(++lastEntityId)
            }

            if (placePostPause) placeTimer.reset()
        }

        listen<EntityEvent.Removal> { event ->
            val crystal = event.entity as? EndCrystalEntity ?: return@listen
            val pos = crystal.baseBlockPos

            // Invalidate crystal entity
            val opportunity = blueprint[pos] ?: return@listen
            opportunity.crystal = null
            decay += crystal.id
        }

        onEnable {
            currentTarget = null
            pendingAsyncUpdates.set(0)
            updatesThisFrame = 0
            lastAsyncUpdate = 0L
            resetBlueprint()
        }
    }

    private fun SafeContext.runPendingAsyncTicks() {
        while (updatesThisFrame < maxUpdatesPerFrame) {
            val pending = pendingAsyncUpdates.get()
            if (pending <= 0) return
            if (!pendingAsyncUpdates.compareAndSet(pending, pending - 1)) continue

            updatesThisFrame++
            tick()
        }
    }

    private fun SafeContext.tick() {
        // Update the target
        currentTarget = targetingSettings.target<LivingEntity>()

        // Update the blueprint
        currentTarget?.let {
            updateBlueprint(it)
        } ?: resetBlueprint()

        // Choosing and running the best opportunity
        activeOpportunity?.let {
            tickInteraction(it)
        }
    }

    private fun tickInteraction(best: Opportunity) {
        if (!best.blocked) {
            if (best.canExplode) best.explode()
            if (best.canPlace) best.place()
            return
        }

        val mutableBlockPos = BlockPos.Mutable()

        // Break crystals nearby if the best crystal placement is blocked by other crystals
        collidingOffsets.mapNotNull {
            mutableBlockPos.set(
                best.blockPos.x + it.x,
                best.blockPos.y + it.y,
                best.blockPos.z + it.z
            )

            blueprint[mutableBlockPos]
        }.filter { it.hasCrystal && it.canExplode }.maxByOrNull { it.priority }?.explode()

        if (best.canPlace) best.place()
    }

    private fun SafeContext.placeInternal(opportunity: Opportunity, hand: Hand) {
        interaction.syncSelectedSlot()
        val hitResult = BlockHitResult(opportunity.crystalPosition, opportunity.side, opportunity.blockPos, false)
        interaction.sendSequencedPacket(world) { sequence ->
            PlayerInteractBlockC2SPacket(
                hand, hitResult, sequence
            )
        }

        player.swingHand(hand)
    }

    private fun SafeContext.explodeInternal(id: Int) {
        connection.sendPacket {
            PlayerInteractEntityC2SPacket(
                id, player.isSneaking, PlayerInteractEntityC2SPacket.ATTACK
            )
        }

        player.swingHand(Hand.MAIN_HAND)
    }

    private fun SafeContext.inPlaceRange(pos: BlockPos) =
        player.eyePos distSq pos.crystalPosition <= placeRange * placeRange

    private fun SafeContext.inExplodeRange(crystal: EndCrystalEntity) =
        player.eyePos distSq crystal.pos <= explodeRange * explodeRange

    private fun SafeContext.updateBlueprint(target: LivingEntity) {
        resetBlueprint()

        fun info(
            pos: BlockPos,
            target: LivingEntity,
            canPlace: Boolean,
            canExplode: Boolean,
            blocked: Boolean,
            crystal: EndCrystalEntity? = null
        ): Opportunity? {
            if (!canPlace && !canExplode) return null

            val crystalPos = pos.crystalPosition

            val targetDamage = crystalDamage(crystalPos, target)
            if (targetDamage < minTargetDamage) return null

            val selfDamage = crystalDamage(crystalPos, player)
            if (selfDamage > maxSelfDamage ||
                player.fullHealth - selfDamage <= minPlaceHealth ||
                (preventDeath && player.fullHealth - selfDamage <= 0)
            ) return null

            if (priorityMode == Priority.Advantage && priorityMode.factor(
                    targetDamage,
                    selfDamage
                ) < minDamageAdvantage
            ) return null

            return Opportunity(
                pos.toImmutable(),
                targetDamage,
                selfDamage,
                canPlace,
                canExplode,
                blocked,
                crystal
            )
        }

        // Extra checks for placement, because you may explode but not place in special cases(crystal in the air)
        fun placeInfo(
            pos: BlockPos,
            target: LivingEntity
        ): Opportunity? {
            // Check if crystals could be placed on the base block
            val state = blockState(pos)
            val isOfBlock = state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK)
            if (!isOfBlock) return null

            // Check if the block above is air and other conditions for valid crystal placement
            val above = pos.up()
            if (!world.isAir(above)) return null
            if (oldPlace && !world.isAir(above.up())) return null

            // Exclude blocks blocked by entities
            val crystalBox = pos.crystalBox

            val entitiesNearby = fastEntitySearch<Entity>(3.5, pos)
            val crystals = entitiesNearby.filterIsInstance<EndCrystalEntity>()
            val otherEntities = entitiesNearby - crystals + player

            if (otherEntities.any {
                    it.boundingBox.intersects(crystalBox)
                }) return null

            // Placement collision checks
            val baseCrystal = crystals.firstOrNull {
                it.baseBlockPos == pos
            }

            val crystalPlaceBox = pos.crystalPlaceHitBox
            val blocked = baseCrystal == null && crystals.any {
                it.boundingBox.intersects(crystalPlaceBox)
            }
            val canExplode = baseCrystal?.let { inExplodeRange(it) } ?: false
            val canPlace = inPlaceRange(pos) && (baseCrystal == null || canExplode)

            return info(
                pos,
                target,
                canPlace,
                canExplode,
                blocked,
                baseCrystal
            )
        }

        // Iterate through existing crystals
        val crystalBase = BlockPos.Mutable()
        fastEntitySearch<EndCrystalEntity>(explodeRange + 1.0).forEach { crystal ->
            if (!inExplodeRange(crystal)) return@forEach

            crystalBase.set(crystal.x, crystal.y - 0.5, crystal.z)
            damage += info(
                crystalBase,
                target,
                canPlace = false,
                canExplode = true,
                blocked = false,
                crystal = crystal
            ) ?: return@forEach
        }

        // Iterate through possible place positions and calculate damage information for each
        val placeRangeInt = placeRange.ceilToInt() + 1
        BlockPos.iterateOutwards(player.blockPos.up(), placeRangeInt, placeRangeInt, placeRangeInt).forEach { pos ->
            if (!inPlaceRange(pos)) return@forEach

            damage += placeInfo(pos, target) ?: return@forEach
        }

        // Map opportunities
        damage.forEach {
            blueprint[it.blockPos] = it
        }

        // Associate by actions
        blueprint.values.forEach { opportunity ->
            actionMap.getOrPut(opportunity.actionType, ::mutableListOf) += opportunity

            if (opportunity.actionType.priority > actionType.priority) {
                actionType = opportunity.actionType
            }
        }

        // Select best action
        activeOpportunity = actionMap[actionType]?.maxByOrNull {
            it.priority
        }
    }

    private fun resetBlueprint() {
        blueprint.clear()
        damage.clear()
        actionMap.clear()
        actionType = ActionType.Normal
        activeOpportunity = null
    }

    private fun SafeContext.ensureCrystalInHand(hand: Hand): Boolean {
        if (player.getStackInHand(hand).item == Items.END_CRYSTAL) return true
        if (!swap) return false

        val selection = selectStack { isItem(Items.END_CRYSTAL) }

        return when (hand) {
            Hand.MAIN_HAND -> runSafeAutomated {
                var crystalSlot = player.hotbarStacks.indexOfFirst { selection.filterStack(it) }
                if (crystalSlot < 0) {
                    if (!selection.transfer(HotbarContainer)) return@runSafeAutomated false
                    crystalSlot = player.hotbarStacks.indexOfFirst { selection.filterStack(it) }
                }

                crystalSlot in 0..8 &&
                    HotbarRequest(
                        crystalSlot,
                        this@CrystalAura
                    ).submit(queueIfMismatchedStage = false).done
            }
            Hand.OFF_HAND -> runSafeAutomated {
                player.offHandStack.item == Items.END_CRYSTAL || selection.transfer(OffHandContainer)
            }
        }
    }

    /**
     * Represents the damage information resulting from placing an end crystal on a given [blockPos]
     * and causing an explosion that targets current target entity.
     *
     * @property blockPos The position of the base block where the crystal is placed.
     * @property target The amount of damage inflicted on the target.
     * @property self The amount of damage inflicted on the player.
     * @property canPlace Whether a crystal can be placed on [blockPos].
     * @property canExplode Whether the crystal on [blockPos] can be exploded.
     * @property blocked Whether the placement on [blockPos] is blocked by other crystals.
     * @property crystal A crystal that is placed on [blockPos].
     */
    private class Opportunity(
        val blockPos: BlockPos,
        val target: Double,
        val self: Double,
        val canPlace: Boolean,
        val canExplode: Boolean,
        var blocked: Boolean,
        var crystal: EndCrystalEntity? // ToDo: packet-based update wisely
    ) {
        var actionType = ActionType.Normal
        val priority = priorityMode.factor(target, self)
        val hasCrystal get() = crystal != null

        val crystalPosition by lazy {
            blockPos.crystalPosition
        }

        val side by lazy {
            runSafe {
                val visibleSides = Box(blockPos).getVisibleSurfaces(player.eyePos)
                if (visibleSides.contains(Direction.UP)) Direction.UP else visibleSides.minByOrNull {
                    blockPos.getHitVec(it) distSq player.eyePos
                }
            } ?: Direction.UP
        }

        val placeRotation by lazy {
            runSafe {
                var vec = blockPos.getHitVec(side)

                // look at the top part of the side
                if (side.axis != Direction.Axis.Y) vec += Vec3d(0.0, 0.45, 0.0)

                player.eyePos.rotationTo(vec)
            } ?: RotationManager.activeRotation
        }

        /**
         * Places the crystal on [blockPos]
         */
        fun place() = runSafe {
            if (!canPlace) return@runSafe

            if (rotate && !rotationRequest { rotation(placeRotation) }.submit().done)
                return@runSafe

            if (!ensureCrystalInHand(swapHand)) return@runSafe

            placeTimer.runSafeIfPassed(placeDelay.milliseconds) {
                placeInternal(this@Opportunity, swapHand)

                if (prediction.onPlace)
                    predictionTimer.runIfNotPassed(packetLifetime.milliseconds, false) {
                        val last = lastEntityId

                        repeat(placePredictions) {
                            explodeInternal(++lastEntityId)
                        }

                        lastEntityId = last + 1
                        crystal = null
                    }
            }
        }

        /**
         * Explodes a crystal that is on [blockPos]
         * @return Whether the delay passed, null if the interaction failed or no crystal found
         */
        fun explode() {
            if (!canExplode) return
            val crystal = crystal ?: return

            if (rotate && !rotationRequest { rotation(placeRotation) }.submit().done) return

            explodeTimer.runSafeIfPassed(explodeDelay.milliseconds) {
                explodeInternal(crystal.id)
            }
        }
    }

    private val EndCrystalEntity.baseBlockPos get() =
        (pos - Vec3d(0.0, 0.5, 0.0)).flooredBlockPos

    private val BlockPos.crystalPosition get() =
        this.getHitVec(Direction.UP)

    private val BlockPos.crystalPlaceHitBox get() =
        crystalPosition.let { base ->
            Box(
                base - Vec3d(1.0, 0.0, 1.0),
                base + Vec3d(1.0, 2.0, 1.0),
            )
        }

    private val BlockPos.crystalBox get() =
        crystalPosition.let { base ->
            Box(
                base - Vec3d(0.5, 0.0, 0.5),
                base + Vec3d(0.5, 2.0, 0.5),
            )
        }

    private enum class UpdateMode {
        Async,
        Ticked
    }

    @Suppress("Unused")
    private enum class PredictionMode(val onPacket: Boolean, val onPlace: Boolean) {
        // Prediction disable
        None(false, false),

        // Predict on packet receive
        Packet(true, false),

        // Predict on place
        Deferred(false, true),

        // Predict on both timings
        Mixed(true, true);

        val isActive = onPacket || onPlace
    }

    private enum class Priority(val factor: (targetDamage: Double, selfDamage: Double) -> Double) {
        Damage({ target, _ ->
            target
        }),
        Advantage({ target, self ->
            target - self
        })
    }

    // ToDo: implement actions
    @Suppress("Unused")
    private enum class ActionType(val priority: Int) {
        Normal(0),
        ForcePlace(1),
        SlowBreak(2)
    }
}
