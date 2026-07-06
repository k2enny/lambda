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

package com.lambda.module.modules.client

import com.jagrosh.discordipc.IPCClient
import com.jagrosh.discordipc.entities.ActivityType
import com.jagrosh.discordipc.entities.RichPresence
import com.jagrosh.discordipc.entities.StatusDisplayType
import com.jagrosh.discordipc.entities.pipe.PipeStatus
import com.jagrosh.discordipc.exceptions.NoDiscordClientException
import com.lambda.Lambda
import com.lambda.Lambda.LOG
import com.lambda.config.entries.Setting.Companion.onValueChangeUnsafe
import com.lambda.context.SafeContext
import com.lambda.event.events.ClientEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listenOnce
import com.lambda.event.listener.UnsafeListener.Companion.listenUnsafe
import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import com.lambda.threading.runConcurrent
import com.lambda.util.Nameable
import com.lambda.util.extension.dimensionName
import com.lambda.util.extension.fullHealth
import com.lambda.util.extension.worldName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

@Suppress("unused")
object Discord : Module(
	name = "Discord",
	description = "Discord Rich Presence configuration",
	tag = ModuleTag.CLIENT,
	enabledByDefault = true,
) {
	private val delaySetting = setting("Update Delay", 5000L, 5000L..30000L, 100L, unit = "ms")
		.onValueChangeUnsafe { _, _ -> restartStatusUpdates() }
	private val delay by delaySetting
	private val activityType by setting("Activity Type", PresenceActivityType.Playing)
		.onValueChangeUnsafe { _, _ -> scheduleRichPresenceUpdate() }
	private val statusDisplayType by setting("Status Display Type", PresenceStatusDisplayType.Name)
		.onValueChangeUnsafe { _, _ -> scheduleRichPresenceUpdate() }
	private val showTime by setting("Show Time", true, description = "Show how long you have been playing for.")
		.onValueChangeUnsafe { _, _ -> scheduleRichPresenceUpdate() }
	private val line1Left by setting("Line 1 Left", LineInfo.World)
		.onValueChangeUnsafe { _, _ -> scheduleRichPresenceUpdate() }
	private val line1Right by setting("Line 1 Right", LineInfo.Username)
		.onValueChangeUnsafe { _, _ -> scheduleRichPresenceUpdate() }
	private val line2Left by setting("Line 2 Left", LineInfo.Version)
		.onValueChangeUnsafe { _, _ -> scheduleRichPresenceUpdate() }
	private val line2Right by setting("Line 2 Right", LineInfo.Fps)
		.onValueChangeUnsafe { _, _ -> scheduleRichPresenceUpdate() }

	private var ipcClient: IPCClient? = null
	private var statusJob: Job? = null
	private var configUpdateJob: Job? = null
	private var timestamp = System.currentTimeMillis()
	private var doNotTryToConnect = false
	@Volatile
	private var isShuttingDown = false

	init {
		launchStatusUpdates()

		listenOnce<TickEvent.Pre> {
			launchStatusUpdates()
			true
		}

		onEnableUnsafe {
			timestamp = System.currentTimeMillis()
			doNotTryToConnect = false
			isShuttingDown = false
			launchStatusUpdates()
		}

		onDisableUnsafe {
			statusJob?.cancel()
			statusJob = null
			configUpdateJob?.cancel()
			configUpdateJob = null
			shutdownIpc()
		}

		listenUnsafe<ClientEvent.Shutdown>(alwaysListen = true) {
			isShuttingDown = true
			statusJob?.cancel()
			statusJob = null
			configUpdateJob?.cancel()
			configUpdateJob = null
			shutdownIpc()
		}
	}

	private fun launchStatusUpdates() {
		if (statusJob?.isActive == true) return

		statusJob = runConcurrent(Dispatchers.IO) {
			while (true) {
				try {
					if (isShuttingDown) return@runConcurrent

					if (isEnabled) {
						connectIpc()
						sendRichPresence()
					} else {
						shutdownIpc()
					}
				} catch (exception: CancellationException) {
					throw exception
				} catch (throwable: Throwable) {
					if (isShuttingDown) return@runConcurrent
					LOG.error("Failed to update Discord Rich Presence", throwable)
					shutdownIpc()
				}

				delay(delay)
			}
		}
	}

	private fun restartStatusUpdates() {
		if (isShuttingDown || !isEnabled) return

		statusJob?.cancel()
		statusJob = null
		launchStatusUpdates()
	}

	private fun scheduleRichPresenceUpdate() {
		if (isShuttingDown || !isEnabled) return

		configUpdateJob?.cancel()
		configUpdateJob = runConcurrent(Dispatchers.IO) {
			delay(CONFIG_UPDATE_DEBOUNCE_MS)

			runCatching {
				connectIpc()
				sendRichPresence()
			}.onFailure {
				LOG.error("Failed to update Discord Rich Presence after config change.", it)
			}
		}
	}

	private fun connectIpc() {
		if (doNotTryToConnect || ipcClient?.status == PipeStatus.CONNECTED) return

		runCatching {
			ipcClient = IPCClient(Lambda.APP_ID.toLong()).also { it.connect() }
		}.onFailure {
			if (it is NoDiscordClientException) {
				LOG.warn("No Discord client for Rich Presence.")
			} else {
				LOG.error("Failed to connect to Discord Rich Presence.", it)
			}

			doNotTryToConnect = true
		}.onSuccess {
			LOG.info("Successfully connected to Discord Rich Presence.")
		}
	}

	private fun shutdownIpc() {
		val client = ipcClient ?: return

		if (client.status != PipeStatus.CONNECTED) {
			ipcClient = null
			return
		}

		runCatching {
			client.close()
		}.onFailure {
			LOG.error("Failed to close Discord Rich Presence.", it)
		}.onSuccess {
			LOG.info("Successfully closed Discord Rich Presence.")
		}

		ipcClient = null
	}

	private fun sendRichPresence() {
		val client = ipcClient
		if (client == null || client.status != PipeStatus.CONNECTED) return

		val context = SafeContext.create()
		client.sendRichPresence {
			setActivityType(activityType.activityType)
			setStatusDisplayType(statusDisplayType.statusDisplayType)
			if (showTime) setStartTimestamp(timestamp)
			setLargeImageWithTooltip("lambda", Lambda.VERSION)
			setDetails("${line1Left.resolve(context)} | ${line1Right.resolve(context)}".asDiscordText())
			setState("${line2Left.resolve(context)} | ${line2Right.resolve(context)}".asDiscordText())
		}
	}

	private inline fun IPCClient.sendRichPresence(builderAction: RichPresence.Builder.() -> Unit) =
		sendRichPresence(RichPresence.Builder().apply(builderAction).build())

	private fun String.asDiscordText() = take(128).ifBlank { Lambda.MOD_NAME }

	private enum class PresenceActivityType(val activityType: ActivityType) : Nameable {
		Playing(ActivityType.Playing),
		Listening(ActivityType.Listening),
		Watching(ActivityType.Watching),
		Competing(ActivityType.Competing),
	}

	private enum class PresenceStatusDisplayType(val statusDisplayType: StatusDisplayType) : Nameable {
		Name(StatusDisplayType.Name),
		State(StatusDisplayType.State),
		Details(StatusDisplayType.Details),
	}

	private enum class LineInfo(val value: SafeContext.() -> String) : Nameable {
		Version({ Lambda.VERSION }),
		World({ worldName }),
		Username({ mc.session.username }),
		Health({ "${player.fullHealth} HP" }),
		Hunger({ "${player.hungerManager.foodLevel} Hunger" }),
		Dimension({ world.dimensionName }),
		Fps({ "${mc.currentFps} FPS" });
	}

	private fun LineInfo.resolve(context: SafeContext?) =
		context?.let { value.invoke(it) } ?: when (this) {
			LineInfo.Version -> Lambda.VERSION
			LineInfo.World -> "Main Menu"
			LineInfo.Username -> Lambda.mc.session.username
			LineInfo.Health -> "No Health"
			LineInfo.Hunger -> "No Hunger"
			LineInfo.Dimension -> "No Dimension"
			LineInfo.Fps -> "${Lambda.mc.currentFps} FPS"
		}

	private const val CONFIG_UPDATE_DEBOUNCE_MS = 150L
}
