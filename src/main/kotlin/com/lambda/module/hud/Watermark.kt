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
import com.lambda.imgui.flag.ImGuiCol
import com.lambda.module.HudModule
import com.lambda.module.tag.ModuleTag
import java.awt.Color

@Suppress("unused")
object Watermark : HudModule(
    name = "Watermark",
    tag = ModuleTag.HUD,
    enabledByDefault = true,
) {
    private val watermarkText by setting("Text", "Lambda")
    private val textColor by setting(
        "Text Color",
        Color(80, 210, 255),
        "Watermark text color"
    )

    override fun ImGuiBuilder.buildLayout() {
        withStyleColor(ImGuiCol.Text, textColor.toImColor()) {
            text(watermarkText)
        }
    }

    private fun Color.toImColor() = ImColor.rgba(red, green, blue, alpha)
}
