/*
 * This file is part of "SAP Commerce Developers Toolset" plugin for IntelliJ IDEA.
 * Copyright (C) 2019-2025 EPAM Systems <hybrisideaplugin@epam.com> and contributors
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

package com.intellij.idea.plugin.hybris.toolwindow.loggers

import com.intellij.ide.IdeBundle
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.idea.plugin.hybris.tools.logging.CxLoggerAccess
import com.intellij.idea.plugin.hybris.tools.logging.CxLoggerModel
import com.intellij.idea.plugin.hybris.tools.logging.LogLevel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiPackage
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.startOffset
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.util.asSafely
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

class LoggersStateView(
    private val project: Project,
    private val coroutineScope: CoroutineScope
) : Disposable {

    private val showNewLoggerPanel = AtomicBooleanProperty(false)
    private lateinit var contentPlaceholder: Placeholder

    val component: DialogPanel
        get() = panel {
            row {
                cell(newLoggerPanel())
                    .visibleIf(showNewLoggerPanel)
                    .resizableColumn()
                    .align(Align.FILL)
            }

            row {
                contentPlaceholder = placeholder()
                    .resizableColumn()
                    .align(Align.FILL)
            }.resizableRow()
        }
            .apply { renderNothingSelected() }

    fun renderFetchLoggers() = renderText("Fetch Loggers State")
    fun renderNoLoggerTemplates() = renderText("No Logger Templates")
    fun renderNothingSelected() = renderText(IdeBundle.message("empty.text.nothing.selected"))

    fun renderLoggers(loggers: Map<String, CxLoggerModel>) {
        showNewLoggerPanel.set(true)

        contentPlaceholder.component = JBScrollPane(loggersView(loggers)).apply {
            setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            border = JBEmptyBorder(0)
        }
    }

    private fun loggersView(loggers: Map<String, CxLoggerModel>) = panel {
        val editable = AtomicBooleanProperty(true)

        loggers.values
            .sortedBy { it.name }
            .forEach { cxLogger ->
                row {
                    val model = DefaultComboBoxModel<LogLevel>().apply {
                        LogLevel.entries
                            .filter { it != LogLevel.CUSTOM || (cxLogger.level == LogLevel.CUSTOM) }
                            .forEach { addElement(it) }
                    }
                    comboBox(
                        model,
                        renderer = SimpleListCellRenderer.create { label, value, _ ->
                            if (value != null) {
                                label.icon = value.icon
                                label.text = value.name
                            }
                        }
                    )
                        .align(AlignX.FILL)
                        .bindItem({ cxLogger.level }, { _ -> })
                        .enabledIf(editable)
                        .component
                        .addItemListener { event ->
                            val currentCxLogger = loggers[cxLogger.name] ?: return@addItemListener
                            val newLogLevel = event.item.asSafely<LogLevel>() ?: return@addItemListener

                            if (currentCxLogger.level != newLogLevel) {
                                editable.set(false)
                                CxLoggerAccess.getInstance(project).setLogger(cxLogger.name, newLogLevel)
                            }
                        }

                    icon(cxLogger.icon)
                        .gap(RightGap.SMALL)

                    if (cxLogger.resolved) {
                        link(cxLogger.name) {
                            cxLogger.psiElementPointer?.element?.let { psiElement ->
                                when (psiElement) {
                                    is PsiPackage -> {
                                        coroutineScope.launch {
                                            val directory = readAction {
                                                psiElement.getDirectories(GlobalSearchScope.allScope(project))
                                                    .firstOrNull()
                                            } ?: return@launch

                                            edtWriteAction {
                                                ProjectView.getInstance(project).selectPsiElement(directory, true)
                                            }
                                        }
                                    }

                                    is PsiClass -> PsiNavigationSupport.getInstance()
                                        .createNavigatable(project, psiElement.containingFile.virtualFile, psiElement.startOffset)
                                        .navigate(true)
                                }
                            }
                        }
                    } else {
                        label(cxLogger.name)
                    }

                    label(cxLogger.parentName ?: "")
                }
                    .layout(RowLayout.PARENT_GRID)
            }
    }
        .apply {
            border = JBUI.Borders.empty(16)
        }

    private fun renderText(text: String) {
        showNewLoggerPanel.set(false)

        contentPlaceholder.component = JBPanelWithEmptyText()
            .withEmptyText(text)
    }

    private fun newLoggerPanel(): JComponent {
        val actionPanel = panel {
            row {
                val loggerLevelField = comboBox(
                    model = DefaultComboBoxModel<LogLevel>().apply {
                        LogLevel.entries
                            .filter { it != LogLevel.CUSTOM }
                            .forEach { addElement(it) }
                    },
                    renderer = SimpleListCellRenderer.create { label, value, _ ->
                        if (value != null) {
                            label.icon = value.icon
                            label.text = value.name
                        }
                    }
                )
                    .comment("Effective level")
                    .component

                val loggerNameField = textField()
                    .resizableColumn()
                    .align(AlignX.FILL)
                    .comment("Logger (package or class name)")
                    .component

                button("Apply Logger") {
                    loggerNameField.isEnabled = false
                    loggerLevelField.isEnabled = false

                    CxLoggerAccess.getInstance(project).setLogger(loggerNameField.text!!, loggerLevelField.selectedItem as LogLevel) { coroutineScope, _ ->
                        coroutineScope.launch {
                            withContext(Dispatchers.EDT) {
                                loggerNameField.isEnabled = true
                                loggerLevelField.isEnabled = true
                            }
                        }
                    }
                }
            }
        }
            .apply {
                registerValidators(this@LoggersStateView)

                background = JBUI.CurrentTheme.Banner.INFO_BACKGROUND
                border = JBUI.Borders.merge(
                    JBUI.Borders.empty(16),
                    JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR),
                    true
                )
//                border = JBUI.Borders.empty(16)
            }

        return actionPanel
//        return panel {
//            row {
//                cell(actionPanel)
//                    .resizableColumn()
//                    .align(AlignX.FILL)
//            }
//        }
    }

    override fun dispose() = Unit

}