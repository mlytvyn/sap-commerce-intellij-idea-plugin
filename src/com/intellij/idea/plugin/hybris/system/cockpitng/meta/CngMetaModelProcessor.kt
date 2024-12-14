/*
 * This file is part of "SAP Commerce Developers Toolset" plugin for IntelliJ IDEA.
 * Copyright (C) 2019-2024 EPAM Systems <hybrisideaplugin@epam.com> and contributors
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
package com.intellij.idea.plugin.hybris.system.cockpitng.meta

import com.intellij.idea.plugin.hybris.system.cockpitng.meta.impl.CngMetaModelNameProvider
import com.intellij.idea.plugin.hybris.system.cockpitng.meta.model.*
import com.intellij.idea.plugin.hybris.system.cockpitng.model.config.Config
import com.intellij.idea.plugin.hybris.system.cockpitng.model.core.*
import com.intellij.idea.plugin.hybris.system.type.meta.impl.CaseInsensitive.CaseInsensitiveConcurrentHashMap
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.util.xml.DomManager

@Service(Service.Level.PROJECT)
class CngMetaModelProcessor(private val myProject: Project) {

    companion object {
        fun getInstance(project: Project): CngMetaModelProcessor = project.getService(CngMetaModelProcessor::class.java)
    }

    private val myDomManager: DomManager = DomManager.getDomManager(myProject)

    fun processConfig(psiFile: PsiFile): CngConfigMeta? {
        psiFile.virtualFile ?: return null
        val dom = myDomManager.getFileElement(psiFile as XmlFile, Config::class.java)?.rootElement ?: return null

        val contexts = dom.contexts
            .filter { it.component.stringValue != null }
            .filter { it.component.stringValue!!.isNotBlank() }
            .map { CngContextMeta(psiFile, it, it.component.stringValue!!) }
        return CngConfigMeta(psiFile, dom, contexts)
    }

    fun processActionDefinition(psiFile: PsiFile): CngMetaActionDefinition? {
        psiFile.virtualFile ?: return null
        val dom = myDomManager.getFileElement(psiFile as XmlFile, ActionDefinition::class.java)
            ?.rootElement
            ?: return null
        val id = CngMetaModelNameProvider.extract(dom) ?: return null

        return CngMetaActionDefinition(psiFile, dom, id)
    }

    fun processWidgetDefinition(psiFile: PsiFile): CngMetaWidgetDefinition? {
        psiFile.virtualFile ?: return null
        val dom = myDomManager.getFileElement(psiFile as XmlFile, WidgetDefinition::class.java)
            ?.rootElement
            ?: return null
        val id = CngMetaModelNameProvider.extract(dom) ?: return null

        val settings = CaseInsensitiveConcurrentHashMap<String, CngMetaWidgetSetting>()
        dom.settings.settings
            .map { CngMetaWidgetSetting(psiFile, it) }
            .forEach { settings[it.id] = it }

        return CngMetaWidgetDefinition(psiFile, dom, id, settings)
    }

    fun processEditorDefinition(psiFile: PsiFile): CngMetaEditorDefinition? {
        psiFile.virtualFile ?: return null
        val dom = myDomManager.getFileElement(psiFile as XmlFile, EditorDefinition::class.java)
            ?.rootElement
            ?: return null
        val id = CngMetaModelNameProvider.extract(dom) ?: return null

        return CngMetaEditorDefinition(psiFile, dom, id)
    }

    fun processWidgets(psiFile: PsiFile): CngMetaWidgets? {
        psiFile.virtualFile ?: return null
        val dom = myDomManager.getFileElement(psiFile as XmlFile, Widgets::class.java)
            ?.rootElement
            ?: return null

        return CngMetaWidgets(
            psiFile,
            dom,
            processWidgets(psiFile, dom.widgets),
            dom.widgetExtensions
                .filter { it.widgetId.stringValue?.isNotBlank() ?: false }
                .map { CngMetaWidgetExtension(psiFile, it, processWidgets(psiFile, it.widgets)) }
        )
    }

    private fun processWidgets(
        psiFile: PsiFile,
        widgets: List<Widget>
    ): List<CngMetaWidget> = widgets
        // if ID is null we may need to re-index the project, faced such issue due broken Stubs
        .filter { it.id.exists() }
        .filter { it.id.stringValue?.isNotBlank() ?: false }
        .map {
            val subWidgets = if (it.widgets.isNotEmpty()) processWidgets(psiFile, it.widgets)
            else emptyList()
            CngMetaWidget(psiFile, it, subWidgets)
        }
}