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

package com.lambda.module.modules.combat.autodisconnect

import com.lambda.Lambda
import com.lambda.config.Group
import com.lambda.config.Tab
import com.lambda.context.SafeContext
import com.lambda.event.events.PlayerEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.events.WorldEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.handlers.FriendHandler
import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import com.lambda.sound.SoundHandler.playSound
import com.lambda.util.CommunicationUtils
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.prefix
import com.lambda.util.FormattingUtils.format
import com.lambda.util.NamedEnum
import com.lambda.util.TickTimer
import com.lambda.util.combat.CombatUtils.crystalDamage
import com.lambda.util.combat.CombatUtils.hasDeadlyCrystal
import com.lambda.util.combat.DamageUtils.isFallDeadly
import com.lambda.util.extension.fullHealth
import com.lambda.util.extension.getBlockState
import com.lambda.util.extension.tickDeltaF
import com.lambda.util.player.PlayerUtils.isIn2b2tQueue
import com.lambda.util.player.SlotUtils.allStacks
import com.lambda.util.player.SlotUtils.armorSlots
import com.lambda.util.text.buildText
import com.lambda.util.text.color
import com.lambda.util.text.highlighted
import com.lambda.util.text.literal
import com.lambda.util.text.text
import com.lambda.util.world.WorldUtils.isLoaded
import com.lambda.util.world.fastEntitySearch
import net.minecraft.client.network.CookieStorage
import net.minecraft.client.network.ServerAddress
import net.minecraft.client.network.ServerInfo
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.entity.decoration.EndCrystalEntity
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.mob.CreeperEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.item.Items
import net.minecraft.network.message.LastSeenMessageList
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.world.GameMode
import java.awt.Color
import java.time.Instant
import java.util.*

@Suppress("unused")
object AutoDisconnect : Module(
    name = "AutoDisconnect",
    description = "Automatically disconnects when in danger or on low health",
    tag = ModuleTag.COMBAT,
    modulePriority = -100
) {
    private const val INVALID_HOTBAR_SLOT = 42
    private const val IMPOSSIBLE_CHAT_TIMESTAMP = -1L

    private const val MAX_CRYSTAL_DAMAGE_RANGE = 12.0
    private const val CRYSTAL_TRIGGER_RANGE = 6.0
    // Ticks to wait after (re)joining before re-arming, so triggers don't re-arm against a
    // world whose entities haven't synced in yet (there's no clean "entities loaded" signal).
    private const val JOIN_GRACE_TICKS = 20

    private const val TRIGGERS_TAB = "Triggers"
    private const val GENERAL_TAB = "General"

    private const val PACKET_DISCONNECT_GROUP = "Packet Disconnect Methods"
    private const val DAMAGE_TRIGGER_GROUP = "Damage Triggers"
    private const val HEALTH_GROUP = "Health Settings"
    private const val Y_LEVEL_GROUP = "Y Level Settings"
    private const val FALLS_GROUP = "Falls Settings"
    private const val CRYSTALS_GROUP = "Crystals Settings"
    private const val CREEPERS_GROUP = "Creepers Settings"
    private const val TOTEM_GROUP = "Totem Settings"
    private const val PLAYERS_GROUP = "Players Settings"
    private const val ARMOR_GROUP = "Armor Settings"
    private const val ENTITY_GROUP = "Entity Settings"
    private const val COORDINATE_DISCONNECT_GROUP = "Coordinate Disconnect"
    private const val WORLD_BORDER_COORDINATE = 30_000_000

    @Tab(TRIGGERS_TAB) private val armor by setting("Armor", false, "Disconnect when an equipped armor piece's durability drops below the set limit.")
    @Tab(TRIGGERS_TAB) @Group(ARMOR_GROUP) private val minArmorDurability by setting("Min Armor Durability", 10, 1..50, 1, "Disconnect when any equipped armor piece's remaining durability falls below this.") { armor }
    @Tab(TRIGGERS_TAB) @Group(ARMOR_GROUP) @Group(COORDINATE_DISCONNECT_GROUP) private val coordinates by setting("Coordinates", false, "Disconnect from the server when selected coordinate limits are reached or passed.")
    @Tab(DISCONNECT_CONDITIONS_TAB) @Group(COORDINATE_DISCONNECT_GROUP) private val xCoordinate by setting("X Axis", false, "Check the player's X coordinate.") { coordinates }
    @Tab(DISCONNECT_CONDITIONS_TAB) @Group(COORDINATE_DISCONNECT_GROUP) private val xCoordinateMode by setting("X Mode", CoordinateMode.LowerOrEqual, "Choose whether X disconnects at or below the limit, or at or above it.") { coordinates && xCoordinate }
    @Tab(DISCONNECT_CONDITIONS_TAB) @Group(COORDINATE_DISCONNECT_GROUP) private val xCoordinateLimit by setting("X Value", 0, -WORLD_BORDER_COORDINATE..WORLD_BORDER_COORDINATE, 1, "The X coordinate limit to disconnect at or beyond.") { coordinates && xCoordinate }
    @Tab(DISCONNECT_CONDITIONS_TAB) @Group(COORDINATE_DISCONNECT_GROUP) private val zCoordinate by setting("Z Axis", false, "Check the player's Z coordinate.") { coordinates }
    @Tab(DISCONNECT_CONDITIONS_TAB) @Group(COORDINATE_DISCONNECT_GROUP) private val zCoordinateMode by setting("Z Mode", CoordinateMode.LowerOrEqual, "Choose whether Z disconnects at or below the limit, or at or above it.") { coordinates && zCoordinate }
    @Tab(DISCONNECT_CONDITIONS_TAB) @Group(COORDINATE_DISCONNECT_GROUP) private val zCoordinateLimit by setting("Z Value", 0, -WORLD_BORDER_COORDINATE..WORLD_BORDER_COORDINATE, 1, "The Z coordinate limit to disconnect at or beyond.") { coordinates && zCoordinate }
    @Tab(DISCONNECT_CONDITIONS_TAB) private val armorSmart by setting("Smart Toggle", true, "Stop re-triggering on armor until durability climbs back above the minimum.") { armor }

    @Tab(TRIGGERS_TAB) private val entities by setting("Entity", false, "Disconnect when an entity of a selected type is within range.")
    @Tab(TRIGGERS_TAB) @Group(ENTITY_GROUP) private val selectedEntities by setting("Entities", setOf(Registries.ENTITY_TYPE.getId(EntityType.TNT_MINECART).path), Registries.ENTITY_TYPE.ids.map { it.path }.sorted(), "Select specific entities.") { entities }
    @Tab(TRIGGERS_TAB) @Group(ENTITY_GROUP) private val entityRange by setting("Entity Range", 10.0, 0.0..100.0, 1.0, "The range to check for entities.", unit = " blocks") { entities }
    @Tab(TRIGGERS_TAB) @Group(ENTITY_GROUP) private val entitiesSmart by setting("Smart Toggle", true, "Stop re-triggering on entities until none of the selected types are within range.") { entities }

    // ToDo: Only those DamageTypes are reported by the server. why?
    @Tab(TRIGGERS_TAB) private val damage by setting("Damage", false, "Disconnect when taking damage of a selected type.")
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val generic by setting("Generic", false, "Disconnect from the server when you take generic damage. (will always trigger!)") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val inFire by setting("Burning", false, "Disconnect from the server when you take fire damage.") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val lava by setting("Lava", false, "Disconnect from the server when you take lava damage.") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val hotFloor by setting("Hot Floor", false, "Disconnect from the server when you take \"hot floor\" damage.") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val drown by setting("Drown", false, "Disconnect from the server when you take drowning damage.") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val cactus by setting("Cactus", false, "Disconnect from the server when you take cactus damage.") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val fall by setting("Fall", false, "Disconnect from the server when you take fall damage.") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val outOfWorld by setting("Out of World", false, "Disconnect from the server when you take \"out of the world\" damage") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val wither by setting("Wither", false, "Disconnect from the server when you take wither damage.") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val stalagmite by setting("Stalagmite", false, "Disconnect from the server when you take stalagmite damage.") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val arrow by setting("Arrow", false, "Disconnect from the server when you take arrow damage.") { damage }
    @Tab(TRIGGERS_TAB) @Group(DAMAGE_TRIGGER_GROUP) private val trident by setting("Trident", false, "Disconnect from the server when you take trident damage.") { damage }

    @Tab(GENERAL_TAB) private val hideDetails by setting("Hide Details on Disconnect Screen", false, "Initially hide all details on the disconnect screen")
    @Tab(GENERAL_TAB) @Group(PACKET_DISCONNECT_GROUP) private val invalidHotbarDisconnect by setting("Select Invalid Hotbar Slot", false, "Sends an invalid hotbar selection to force the server to kick the player")
    @Tab(GENERAL_TAB) @Group(PACKET_DISCONNECT_GROUP) private val attackSelfDisconnect by setting("Attack Self", false, "Sends an attack self packet to force the server to kick the player")
    @Tab(GENERAL_TAB) @Group(PACKET_DISCONNECT_GROUP) private val impossibleTimestampChatDisconnect by setting("Send Impossible Chat Timestamp", false, "Sends a chat message with an impossible timestamp to force the server to kick the player")

    private var disconnectDetails: DisconnectDetails? = null
    private var disconnectInProgress: Boolean = false
    private val joinTimer = TickTimer()
    var lastReconnectTarget: ReconnectTarget? = null

    init {
        listen<TickEvent.Pre> {
            joinTimer.tick()

            // Re-enable disarmed triggers once everything is loaded
            if (isLoaded(player.blockPos) && !isIn2b2tQueue() && joinTimer.hasSurpassed(JOIN_GRACE_TICKS)) {
                Reason.entries.forEach { reason ->
                    if (reason.smartToggle() && !reason.armed && (!reason.enabled() || reason.shouldRearm(this))) {
                        reason.armed = true
                        // Notify only when it recovered while active, not when it re-armed due to being disabled.
                        if (reason.enabled()) AutoDisconnect.info("${reason.displayName} re-armed")
                    }
                }
            }
            //check for reasons to disconnect
            val matchingReason = Reason.entries
                .firstNotNullOfOrNull { reason ->
                    if (reason.enabled() && (!reason.smartToggle() || reason.armed) && reason.generateReason(this) != null) {
                        reason
                    }
                    else null
                }
            if (matchingReason != null) {
                val reasonText = matchingReason.generateReason(this) ?: buildText { literal("Reason lost to the abyss") }
                if (requestDisconnect(reasonText) && matchingReason.smartToggle()) {
                    matchingReason.armed = false
                }
            }
        }

        listen<WorldEvent.Join> {
            disconnectInProgress = false
            joinTimer.reset()
        }

        listen<PlayerEvent.Health> { event ->
            val damageHandlers = listOf(
                inFire to DamageTypes.IN_FIRE,
                lava to DamageTypes.LAVA,
                hotFloor to DamageTypes.HOT_FLOOR,
                drown to DamageTypes.DROWN,
                cactus to DamageTypes.CACTUS,
                fall to DamageTypes.FALL,
                outOfWorld to DamageTypes.OUT_OF_WORLD,
                generic to DamageTypes.GENERIC,
                wither to DamageTypes.WITHER,
                stalagmite to DamageTypes.STALAGMITE,
                arrow to DamageTypes.ARROW,
                trident to DamageTypes.TRIDENT
            )

            player.recentDamageSource?.let { source ->
                damageHandlers.firstOrNull { (enabled, damageSource) ->
                    enabled && source.isOf(damageSource)
                }?.let {
                    damageDisconnect(source, event.amount)
                }
            }
        }
    }

    private fun SafeContext.damageDisconnect(source: DamageSource, amount: Float) {
        buildText {
            literal("Got ")
            highlighted(amount.format())
            literal(" damage of type ")
            highlighted(source.name)
            source.attacker?.let {
                literal(" from attacker ")
                if (it.customName != null) text(it.name)
                else highlighted(it.name.string)
            }
            source.source?.let {
                literal(" by source ")
                if (it.customName != null) text(it.name)
                else highlighted(it.name.string)
            }
            source.position?.let {
                literal(" at position ")
                highlighted(it.format())
            }
            literal(".")
        }.let {
            requestDisconnect(it)
        }
    }

    /**
     * Performs the disconnect for [reasonText] if allowed, returning true if it went
     * through. Returns false (and does nothing) when the player isn't in a survival-like
     * game mode or a disconnect is already in progress.
     */
    private fun SafeContext.requestDisconnect(reasonText: Text): Boolean {
        if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE || disconnectInProgress) return false
        disconnectInProgress = true
        ScreenshotRecorder.takeScreenshot(Lambda.mc.framebuffer, 1) { image ->
            val imageIdentifier = Identifier.of("lambda", "auto_disconnect_screenshot")
            val texture = NativeImageBackedTexture({ "auto-disconnect-screenshot" }, image)
            mc.textureManager.registerTexture(imageIdentifier, texture)
            disconnectDetails = DisconnectDetails(
                imageIdentifier = imageIdentifier,
                imageHeight = image.height,
                imageWidth = image.width,
                reason = reasonText,
                details = disconnectScreenText(reasonText),
                hideDetails = hideDetails
            )

            sendForcedDisconnectPackets()
            //message should never appear to the user, but text is included as a backup in case it does
            connection.connection.disconnect(buildText{ literal("AutoDisconnect: $reasonText") })

            playSound(SoundEvents.BLOCK_ANVIL_LAND)
        }
        return true
    }

    private fun SafeContext.sendForcedDisconnectPackets() {
        if (invalidHotbarDisconnect) {
            connection.sendPacket(UpdateSelectedSlotC2SPacket(INVALID_HOTBAR_SLOT))
        }

        if (attackSelfDisconnect) {
            interaction.attackEntity(player, player)
        }

        if (impossibleTimestampChatDisconnect) {
            connection.sendPacket(
                ChatMessageC2SPacket(
                    "",
                    Instant.ofEpochSecond(IMPOSSIBLE_CHAT_TIMESTAMP),
                    0L,
                    null,
                    LastSeenMessageList.Acknowledgment(
                        0,
                        BitSet.valueOf(ByteArray(LastSeenMessageList.MAX_ENTRIES)),
                        LastSeenMessageList.Acknowledgment.NO_CHECKSUM
                    )
                )
            )
        }
    }

    fun consumeDetails(): DisconnectDetails? {
        val details = disconnectDetails
        disconnectDetails = null
        if (details != null) {
            disconnectInProgress = false
        }
        return details
    }

    private fun SafeContext.disconnectScreenText(text: Text) = buildText {
        text(prefix(CommunicationUtils.LogLevel.Warn.logoColor))
        text(text)
        literal("\n\n")
        literal("Disconnected at ")
        highlighted(player.pos.format())
        literal(" on ")
        highlighted(CommunicationUtils.currentTime())
        literal(" with ")
        highlighted(player.fullHealth.format())
        literal(" health.")
        if (player.isSubmergedInWater) {
            literal("\n")
            literal("Submerged in water, had ")
            highlighted("${player.air}")
            literal(" ticks left of breath.")
        }
        if (player.isInLava) {
            literal("\n")
            literal("In lava, had ")
            highlighted("${player.air}")
            literal(" ticks left of breath.")
        }
        if (player.isOnFire) {
            literal("\n")
            literal("Burning for ")
            highlighted("${player.fireTicks}")
            literal(" ticks.")
        }
        if (isDisabled) {
            color(Color.YELLOW) {
                literal("\n\n")
                literal("AutoDisconnect disabled.")
            }
        }
    }

    private fun CoordinateMode.reached(value: Double, limit: Int): Boolean =
        when (this) {
            CoordinateMode.GreaterOrEqual -> value >= limit
            CoordinateMode.LowerOrEqual -> value <= limit
        }

    private fun buildCoordinateDisconnectReason(
        axis: String,
        value: Double,
        limit: Int,
        mode: CoordinateMode
    ) = buildText {
        literal("Player ")
        highlighted(axis)
        literal(" coordinate ")
        highlighted(value.format())
        literal(" reached ")
        highlighted(mode.displayName.lowercase())
        literal(" ")
        highlighted("$limit")
        literal("!")
    }

    enum class Reason(
        val displayName: String,
        val enabled: () -> Boolean,
        val smartToggle: () -> Boolean,
        val generateReason: SafeContext.() -> Text?,
        val shouldRearm: SafeContext.() -> Boolean = { generateReason(this) == null }
    ) {
        Health("Health", { health }, { healthSmart }, {
            if (player.fullHealth < minimumHealth) {
                buildText {
                    literal("Health ")
                    highlighted(player.fullHealth.format())
                    literal(" below minimum of ")
                    highlighted("$minimumHealth")
                    literal("!")
                }
            } else null
        }, shouldRearm = { player.fullHealth > maxOf(reEnableThreshold, minimumHealth) }),
        YLevel("Y Level", { yLevel }, { yLevelSmart }, {
            if (player.pos.y < minimumYLevel) {
                buildText {
                    literal("Player went below y level ")
                    highlighted("$minimumYLevel")
                    literal("!")
                }
            } else null
        }),
        Coordinates({ coordinates && (xCoordinate || zCoordinate) }, {
            when {
                xCoordinate && xCoordinateMode.reached(player.pos.x, xCoordinateLimit) ->
                    buildCoordinateDisconnectReason("X", player.pos.x, xCoordinateLimit, xCoordinateMode)
                zCoordinate && zCoordinateMode.reached(player.pos.z, zCoordinateLimit) ->
                    buildCoordinateDisconnectReason("Z", player.pos.z, zCoordinateLimit, zCoordinateMode)
                else -> null
            }
        }),
        Totem({ totem }, {
            val totemCount = player.allStacks.count { it.item == Items.TOTEM_OF_UNDYING }
            if (totemCount < minTotems) {
                buildText {
                    literal("Only ")
                    highlighted("$totemCount")
                    literal(" totems left, required minimum: ")
                    highlighted("$minTotems")
                    literal("!")
                }
            } else null
        }),
        Creeper("Creepers", { creeper }, { creeperSmart }, {
            fastEntitySearch<CreeperEntity>(15.0).find {
                it.getLerpedFuseTime(mc.tickDeltaF) > 0.0
                        && it.pos.distanceTo(player.pos) <= 5.0
            }?.let { creeper ->
                buildText {
                    literal("An ignited creeper was ")
                    highlighted(creeper.pos.distanceTo(player.pos).format())
                    literal(" blocks away!")
                }
            }
        }),
        Player("Players", { players }, { playersSmart }, {
            fastEntitySearch<PlayerEntity>(minPlayerDistance.toDouble()).find { otherPlayer ->
                otherPlayer != player
                        && player.distanceTo(otherPlayer) <= minPlayerDistance
                        && (!ignoreFriends || FriendHandler.isFriend(otherPlayer.uuid))
            }?.let { otherPlayer ->
                buildText {
                    literal("The player ")
                    text(otherPlayer.name)
                    literal(" was ")
                    highlighted("${otherPlayer.distanceTo(player).format()} blocks away")
                    literal("!")
                }
            }
        }),
        EndCrystal("Crystals", { crystals }, { crystalsSmart }, {
            fastEntitySearch<EndCrystalEntity>(MAX_CRYSTAL_DAMAGE_RANGE).firstNotNullOfOrNull { crystal ->
                if (crystalDamage(crystal.pos, player) <= 0.0) return@firstNotNullOfOrNull null

                val requiresTrigger = playerNearCrystal || projectileNearCrystal
                var potentialCrystalTriggerer: Entity? = null
                if (playerNearCrystal) {
                    potentialCrystalTriggerer = fastEntitySearch<PlayerEntity>(CRYSTAL_TRIGGER_RANGE, crystal.blockPos)
                        .firstOrNull { !(crystalIgnoreFriends && FriendHandler.isFriend(it.uuid)) }
                }
                if (potentialCrystalTriggerer == null && projectileNearCrystal) {
                    potentialCrystalTriggerer = fastEntitySearch<ProjectileEntity>(CRYSTAL_TRIGGER_RANGE, crystal.blockPos).firstOrNull()
                }
                if (requiresTrigger && potentialCrystalTriggerer == null) {
                    return@firstNotNullOfOrNull null
                }

                buildText {
                    literal("An End Crystal was ")
                    highlighted(crystal.pos.distanceTo(player.pos).format())
                    literal(" blocks away")
                    potentialCrystalTriggerer?.let {
                        literal(" and could be detonated by ")
                        if (it.customName != null) text(it.name)
                        else highlighted(it.name.string)
                    }
                }
            }
        }),
        FallDamage("Falls", { falls }, { fallsSmart }, {
            if (isFallDeadly() && player.fallDistance > fallDistance &&
                !player.hasStatusEffect(StatusEffects.LEVITATION) &&
                (player.gameMode == GameMode.ADVENTURE || player.gameMode == GameMode.SURVIVAL)
            ) buildText { literal("You just fell more than ${player.fallDistance} blocks and would take lethal damage") }
            else null
        }),
        Armor("Armor", { armor }, { armorSmart }, {
            player.armorSlots.firstOrNull { slot ->
                slot.stack.isDamageable && slot.stack.maxDamage - slot.stack.damage < minArmorDurability
            }?.let { slot ->
                buildText {
                    literal("Armor piece ")
                    text(slot.stack.name)
                    literal(" has only ")
                    highlighted("${slot.stack.maxDamage - slot.stack.damage}")
                    literal(" durability left, below minimum of ")
                    highlighted("$minArmorDurability")
                    literal("!")
                }
            }
        }),
        NearbyEntity("Entity", { entities }, { entitiesSmart }, {
            fastEntitySearch<Entity>(entityRange).find { entity ->
                entity != player
                        && player.distanceTo(entity) <= entityRange
                        && Registries.ENTITY_TYPE.getId(entity.type).path in selectedEntities
            }?.let { entity ->
                buildText {
                    literal("A ")
                    if (entity.customName != null) text(entity.name)
                    else highlighted(entity.name.string)
                    literal(" was ")
                    highlighted("${entity.distanceTo(player).format()} blocks away")
                    literal("!")
                }
            }
        });

        /**
         * A reason that fires is disarmed and won't trigger
         * again until its condition clears (it is then re-armed in the tick loop)
         */
        var armed: Boolean = true
    }

    private enum class CoordinateMode(override val displayName: String) : NamedEnum {
        GreaterOrEqual("Greater or Equal"),
        LowerOrEqual("Lower or Equal")
    }
}

data class DisconnectDetails(
    val imageIdentifier: Identifier,
    val imageHeight: Int,
    val imageWidth: Int,
    val reason: Text,
    val details: Text,
    val hideDetails: Boolean
)

sealed interface ReconnectTarget

data class MultiplayerReconnectTarget(
    val address: ServerAddress,
    val info: ServerInfo,
    val cookieStorage: CookieStorage?
) : ReconnectTarget

data class SingleplayerReconnectTarget(
    val levelName: String
) : ReconnectTarget