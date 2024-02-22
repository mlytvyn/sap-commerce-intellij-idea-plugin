/*
 * This file is part of "SAP Commerce Developers Toolset" plugin for Intellij IDEA.
 * Copyright (C) 2019 EPAM Systems <hybrisideaplugin@epam.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

// Generated on Wed Jan 18 00:35:36 CET 2023
// DTD/Schema  :    http://www.hybris.com/cockpitng/config/extendedsplitlayout

package com.intellij.idea.plugin.hybris.system.cockpitng.model.widgets;

import com.intellij.idea.plugin.hybris.system.cockpitng.model.config.hybris.MergeMode;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.GenericAttributeValue;
import com.intellij.util.xml.Required;
import org.jetbrains.annotations.NotNull;

/**
 * http://www.hybris.com/cockpitng/config/extendedsplitlayout:LayoutMapping interface.
 */
public interface LayoutMapping extends DomElement {

    /**
     * Returns the value of the parentLayout child.
     *
     * @return the value of the parentLayout child.
     */
    @NotNull
    @com.intellij.util.xml.Attribute("parentLayout")
    @Required
    GenericAttributeValue<String> getParentLayout();


    /**
     * Returns the value of the selfLayout child.
     *
     * @return the value of the selfLayout child.
     */
    @NotNull
    @com.intellij.util.xml.Attribute("selfLayout")
    @Required
    GenericAttributeValue<String> getSelfLayout();


    /**
     * Returns the value of the merge-mode child.
     *
     * @return the value of the merge-mode child.
     */
    @NotNull
    @com.intellij.util.xml.Attribute("merge-mode")
    GenericAttributeValue<MergeMode> getMergeMode();


}
