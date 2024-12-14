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

import com.intellij.idea.plugin.hybris.notifications.Notifications
import com.intellij.idea.plugin.hybris.system.cockpitng.meta.model.*
import com.intellij.idea.plugin.hybris.system.cockpitng.model.config.Config
import com.intellij.idea.plugin.hybris.system.cockpitng.model.core.ActionDefinition
import com.intellij.idea.plugin.hybris.system.cockpitng.model.core.EditorDefinition
import com.intellij.idea.plugin.hybris.system.cockpitng.model.core.WidgetDefinition
import com.intellij.idea.plugin.hybris.system.cockpitng.model.core.Widgets
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportProgress
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.messages.Topic
import com.intellij.util.xml.DomElement
import kotlinx.coroutines.*
import kotlin.time.measureTime

/**
 * Global Meta Model can be retrieved at any time and will ensure that only single Thread can perform its initialization/update
 *
 *
 * Main idea is that we have two levels of Meta Model cache:
 * 1. Global Meta Model cached at Project level with dependencies to all items.xml files in the Project.
 * - processing of the dependant PsiFiles is ignored and done during retrieval from the PsiFile cache
 * - once all dependant PsiFiles processed, each Meta Model will be merged into single one
 * 2. PsiFile (-config.xml) specific cache
 * - retrieving of that cache also performs processing of the PsiFile and pre-filling into MetaModel caches
 *
 * It is quite important to take into account possibility of interruption of the process, especially during Inspection and other heavy operations
 */
@Service(Service.Level.PROJECT)
class CngMetaModelAccess(private val project: Project, private val coroutineScope: CoroutineScope) {

    companion object {
        fun getInstance(project: Project): CngMetaModelAccess = project.getService(CngMetaModelAccess::class.java)

        val TOPIC = Topic("HYBRIS_COCKPITNG_SYSTEM_LISTENER", CngChangeListener::class.java)
        private val SINGLE_CONFIG_CACHE_KEY = Key.create<CachedValue<CngConfigMeta>>("SINGLE_CNG_CONFIG_CACHE")
        private val SINGLE_ACTION_DEFINITION_CACHE_KEY = Key.create<CachedValue<CngMetaActionDefinition>>("SINGLE_ACTION_DEFINITION_CACHE")
        private val SINGLE_WIDGET_DEFINITION_CACHE_KEY = Key.create<CachedValue<CngMetaWidgetDefinition>>("SINGLE_WIDGET_DEFINITION_CACHE")
        private val SINGLE_EDITOR_DEFINITION_CACHE_KEY = Key.create<CachedValue<CngMetaEditorDefinition>>("SINGLE_EDITOR_DEFINITION_CACHE")
        private val SINGLE_WIDGETS_MODEL_CACHE_KEY = Key.create<CachedValue<CngMetaWidgets>>("SINGLE_WIDGETS_CACHE")
    }

    private val myGlobalMetaModel = CngGlobalMetaModel()
    private val myMessageBus = project.messageBus

    @Volatile
    private var building: Boolean = false

    @Volatile
    private var initialized: Boolean = false

    private val myGlobalMetaModelCache = CachedValuesManager.getManager(project).createCachedValue(
        {
            val processor = CngMetaModelProcessor.getInstance(project)
            val collector = CngMetaModelCollector.getInstance(project)

            val metaDependencies = runBlocking {
                withBackgroundProgress(project, "Re-building CockpitNG System...", true) {

                    val dependencies = CngMetaDependencies()
                    listOf(
                        this.async { dependencies.configPsiFiles = collector.collectDependencies(Config::class.java) { _ -> true } },
                        this.async { dependencies.actionDefinitionPsiFiles = collector.collectDependencies(ActionDefinition::class.java) { dom -> dom.rootElement.id.exists() } },
                        this.async { dependencies.widgetDefinitionPsiFiles = collector.collectDependencies(WidgetDefinition::class.java) { dom -> dom.rootElement.id.exists() } },
                        this.async { dependencies.editorDefinitionPsiFiles = collector.collectDependencies(EditorDefinition::class.java) { dom -> dom.rootElement.id.exists() } },
                        this.async { dependencies.widgetPsiFiles = collector.collectDependencies(Widgets::class.java) { _ -> true } },
                    )
                        .awaitAll()

                    val allDependencies = dependencies.allPsiFiles

                    val reportProgress = reportProgress(allDependencies.size) { progressReporter ->
                        listOf(
                            dependencies.configPsiFiles.map {
                                progressReporter.sizedStep(1, "Processing: ${it.name}") {
                                    this.async {
                                        retrieveSingleMetaModelPerFile(it, SINGLE_CONFIG_CACHE_KEY, { file -> processor.processConfig(file) })
                                            .also { dependencies.metaConfigs.add(it) }
                                    }
                                }
                            },
                            dependencies.actionDefinitionPsiFiles.map {
                                progressReporter.sizedStep(1, "Processing: ${it.name}") {
                                    this.async {
                                        retrieveSingleMetaModelPerFile(it, SINGLE_ACTION_DEFINITION_CACHE_KEY, { file -> processor.processActionDefinition(file) })
                                            .also { dependencies.metaActionDefinitions.add(it) }
                                    }
                                }
                            },
                            dependencies.widgetDefinitionPsiFiles.map {
                                progressReporter.sizedStep(1, "Processing: ${it.name}") {
                                    this.async {
                                        retrieveSingleMetaModelPerFile(it, SINGLE_WIDGET_DEFINITION_CACHE_KEY, { file -> processor.processWidgetDefinition(file) })
                                            .also { dependencies.metaWidgetDefinitions.add(it) }
                                    }
                                }
                            },
                            dependencies.editorDefinitionPsiFiles.map {
                                progressReporter.sizedStep(1, "Processing: ${it.name}") {
                                    this.async {
                                        retrieveSingleMetaModelPerFile(it, SINGLE_EDITOR_DEFINITION_CACHE_KEY, { file -> processor.processEditorDefinition(file) })
                                            .also { dependencies.metaEditorDefinitions.add(it) }
                                    }
                                }
                            },
                            dependencies.widgetPsiFiles.map {
                                progressReporter.sizedStep(1, "Processing: ${it.name}") {
                                    this.async {
                                        retrieveSingleMetaModelPerFile(it, SINGLE_WIDGETS_MODEL_CACHE_KEY, { file -> processor.processWidgets(file) })
                                            .also { dependencies.metaWidgets.add(it) }
                                    }
                                }
                            },
                        )
                            .flatten()
                            .awaitAll()
                    }

                    dependencies
                }
            }

            CngMetaModelMerger.merge(
                myGlobalMetaModel,
                metaDependencies.metaConfigs,
                metaDependencies.metaActionDefinitions,
                metaDependencies.metaWidgetDefinitions,
                metaDependencies.metaEditorDefinitions,
                metaDependencies.metaWidgets
            )

            val dependencies = metaDependencies.allPsiFiles

            CachedValueProvider.Result.create(myGlobalMetaModel, dependencies.ifEmpty { ModificationTracker.EVER_CHANGED })
        }, false
    )

    private class CngMetaDependencies() {
        var configPsiFiles = setOf<PsiFile>()
        var actionDefinitionPsiFiles = setOf<PsiFile>()
        var widgetDefinitionPsiFiles = setOf<PsiFile>()
        var editorDefinitionPsiFiles = setOf<PsiFile>()
        var widgetPsiFiles = setOf<PsiFile>()

        var metaConfigs = mutableListOf<CngConfigMeta>()
        var metaActionDefinitions = mutableListOf<CngMetaActionDefinition>()
        var metaWidgetDefinitions = mutableListOf<CngMetaWidgetDefinition>()
        var metaEditorDefinitions = mutableListOf<CngMetaEditorDefinition>()
        var metaWidgets = mutableListOf<CngMetaWidgets>()

        val allPsiFiles
            get() = (configPsiFiles + actionDefinitionPsiFiles + widgetDefinitionPsiFiles + editorDefinitionPsiFiles + widgetPsiFiles)
                .toTypedArray()
    }

    fun initMetaModel() {
        building = true

        coroutineScope
            .launch(Dispatchers.IO) {
                val measureTime = measureTime {
                    myGlobalMetaModelCache.value
                }

                withContext(Dispatchers.EDT) {
                    Notifications.create(
                        NotificationType.INFORMATION,
                        "CNG loaded in: $measureTime"
                    )
                        .notify(project)
                }
            }
            .invokeOnCompletion {
                building = false
                initialized = true

                myMessageBus.syncPublisher(TOPIC).cngSystemChanged(myGlobalMetaModel)
            }
    }

    fun getMetaModel(): CngGlobalMetaModel {
        if (building || !initialized || DumbService.isDumb(project)) throw ProcessCanceledException()

        if (myGlobalMetaModelCache.hasUpToDateValue()) {
            return myGlobalMetaModelCache.value
        }

        initMetaModel()

        throw ProcessCanceledException()
    }

    private fun <D : DomElement, T : CngMeta<D>> retrieveSingleMetaModelPerFile(
        psiFile: PsiFile,
        key: Key<CachedValue<T>>,
        resultProcessor: (input: PsiFile) -> T?
    ): T = CachedValuesManager.getManager(project).getCachedValue(
        psiFile, key,
        {
            val value = runBlocking {
                readAction {
                    resultProcessor.invoke(psiFile)
                }
            }

            CachedValueProvider.Result.create(value, psiFile)
        },
        false
    )
}