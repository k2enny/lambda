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

package com.lambda.module.hud

import com.lambda.gui.dsl.ImGuiBuilder
import com.lambda.imgui.ImColor
import com.lambda.imgui.ImGui
import com.lambda.imgui.flag.ImGuiCol
import com.lambda.interaction.handlers.FriendHandler.isFriend
import com.lambda.module.HudModule
import com.lambda.module.tag.ModuleTag
import com.lambda.threading.runSafe
import com.lambda.util.NamedEnum
import java.awt.Color

@Suppress("unused")
object PlayerList : HudModule(
	name = "PlayerList",
	description = "Displays players loaded in the rendered world",
	tag = ModuleTag.HUD,
) {
	private val textColor by setting("Text Color", Color(80, 210, 255), "Player name color")
	private val friendColor by setting("Friend Color", Color(90, 255, 120), "Friend name color")
	private val alignment by setting("Alignment", Alignment.Left, "Align shorter names to the left or right")
	private val sortOrder by setting("Sort Order", SortOrder.DistanceNearToFar, "Order the player list")

	override fun ImGuiBuilder.buildLayout() {
		runSafe {
			val rows = world.players
				.asSequence()
				.filter { it !== player }
				.map {
					val name = it.gameProfile.name
					PlayerRow(
						name = name,
						friend = it.isFriend,
						distanceSquared = player.squaredDistanceTo(it),
						width = ImGui.calcTextSize(name).x,
					)
				}
				.toList()
				.sorted()

			val maxWidth = rows.maxOfOrNull { it.width } ?: return@runSafe
			val lineStartX = cursorPosX

			rows.forEach { row ->
				cursorPosX = when (alignment) {
					Alignment.Left -> lineStartX
					Alignment.Right -> lineStartX + maxWidth - row.width
				}

				withStyleColor(ImGuiCol.Text, (if (row.friend) friendColor else textColor).toImColor()) {
					text(row.name)
				}
			}
		}
	}

	private fun List<PlayerRow>.sorted() =
		when (sortOrder) {
			SortOrder.DistanceNearToFar -> sortedBy { it.distanceSquared }
			SortOrder.DistanceFarToNear -> sortedByDescending { it.distanceSquared }
			SortOrder.LengthLongToShort -> sortedByDescending { it.width }
			SortOrder.LengthShortToLong -> sortedBy { it.width }
			SortOrder.NameAToZ -> sortedBy { it.name.lowercase() }
			SortOrder.NameZToA -> sortedByDescending { it.name.lowercase() }
		}

	private fun Color.toImColor() = ImColor.rgba(red, green, blue, alpha)

	private data class PlayerRow(
		val name: String,
		val friend: Boolean,
		val distanceSquared: Double,
		val width: Float,
	)

	private enum class Alignment(override val displayName: String) : NamedEnum {
		Left("Left"),
		Right("Right"),
	}

	private enum class SortOrder(override val displayName: String) : NamedEnum {
		DistanceNearToFar("Distance: Near to Far"),
		DistanceFarToNear("Distance: Far to Near"),
		LengthLongToShort("Length: Long to Short"),
		LengthShortToLong("Length: Short to Long"),
		NameAToZ("Name: A to Z"),
		NameZToA("Name: Z to A"),
	}
}
