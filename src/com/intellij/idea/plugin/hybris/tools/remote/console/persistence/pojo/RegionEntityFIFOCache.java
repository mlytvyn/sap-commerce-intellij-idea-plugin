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

package com.intellij.idea.plugin.hybris.tools.remote.console.persistence.pojo;

import java.util.LinkedHashMap;
import java.util.Map;

public class RegionEntityFIFOCache<T> extends LinkedHashMap<String, RegionEntity<T>> {

    private static final long serialVersionUID = 2873734604163564844L;
    private static final int DEFAULT_VALUE = -1;

    private int maxNumberEntities;

    public RegionEntityFIFOCache(){
        this.maxNumberEntities = DEFAULT_VALUE;
    }

    public RegionEntityFIFOCache(int maxNumberEntities) {
        this.maxNumberEntities = maxNumberEntities;
    }

    @Override
    protected boolean removeEldestEntry(final Map.Entry<String, RegionEntity<T>> eldest) {
        if (maxNumberEntities < 0) {
            return false;
        }
        return this.size() > maxNumberEntities;
    }

}