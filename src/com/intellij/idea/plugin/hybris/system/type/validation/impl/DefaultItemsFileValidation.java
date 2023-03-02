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

package com.intellij.idea.plugin.hybris.system.type.validation.impl;

import com.intellij.idea.plugin.hybris.common.HybrisConstants;
import com.intellij.idea.plugin.hybris.notifications.Notifications;
import com.intellij.idea.plugin.hybris.system.type.validation.ItemsFileValidation;
import com.intellij.idea.plugin.hybris.system.type.validation.ItemsXmlValidator;
import com.intellij.idea.plugin.hybris.system.type.model.EnumType;
import com.intellij.idea.plugin.hybris.system.type.model.ItemType;
import com.intellij.idea.plugin.hybris.system.type.model.Items;
import com.intellij.idea.plugin.hybris.system.type.model.Relation;
import com.intellij.idea.plugin.hybris.system.type.model.TypeGroup;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomManager;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.intellij.idea.plugin.hybris.common.HybrisConstants.HYBRIS_ITEMS_XML_FILE_ENDING;
import static com.intellij.idea.plugin.hybris.common.HybrisConstants.CLASS_ITEM_ROOT;
import static com.intellij.idea.plugin.hybris.common.utils.HybrisI18NBundleUtils.message;
import static com.intellij.idea.plugin.hybris.system.type.utils.TSUtils.getString;

/**
 * @author Vlad Bozhenok <vladbozhenok@gmail.com>
 */
public class DefaultItemsFileValidation implements ItemsFileValidation {

    private static final Logger LOG = Logger.getInstance(DefaultItemsFileValidation.class);

    private static final ItemsXmlValidator<EnumType> ENUMS_VALIDATOR = new DefaultEnumTypeClassValidation();
    private static final ItemsXmlValidator<ItemType> ITEM_TYPES_VALIDATOR = new DefaultItemTypeClassValidation();
    private static final ItemsXmlValidator<Relation> RELATIONS_VALIDATOR = new DefaultRelationValidation();

    private final Project project;

    public DefaultItemsFileValidation(@NotNull final Project project) {
        this.project = project;
    }

    @Override
    public boolean isFileOutOfDate(@NotNull final VirtualFile file) {
        if (file.getName().endsWith(HYBRIS_ITEMS_XML_FILE_ENDING)) {
            return this.isFileOutOfDateWithGeneratedClasses(file);
        }

        return false;
    }

    @Override
    public void showNotification() {
        Notifications.create(
                NotificationType.WARNING,
                message("hybris.notification.ts.validation.title"),
                message("hybris.notification.ts.validation.content")
            )
            .hideAfter(10)
            .notify(project);
    }

    private boolean isFileOutOfDateWithGeneratedClasses(@NotNull final VirtualFile file) {
        try {
            final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();

            if (indicator != null) {
                indicator.setText2(message("hybris.startupActivity.itemsXmlValidation.progress.subTitle.validating", file.getName()));
            }

            final DomManager domManager = DomManager.getDomManager(this.project);

            final PsiManager psiManager = PsiManager.getInstance(this.project);
            final PsiFile psiFile = psiManager.findFile(file);

            if (psiFile instanceof final XmlFile xmlFile) {
                final DomFileElement<Items> fileElement = domManager.getFileElement(xmlFile, Items.class);
                if (null == fileElement) {
                    return true;
                }

                final Items itemsRootElement = fileElement.getRootElement();
                final Map<String, PsiClass> inheritedEnumClasses = this.findAllInheritClasses(
                    this.project, HybrisConstants.CLASS_ENUM_ROOT
                );

                final List<EnumType> enumTypes = itemsRootElement.getEnumTypes().getEnumTypes();
                if (ENUMS_VALIDATOR.validate(enumTypes, inheritedEnumClasses)) {
                    return true;
                }

                final Map<String, PsiClass> inheritedItemClasses = this.findAllInheritClasses(
                    this.project, CLASS_ITEM_ROOT
                );

                final List<ItemType> filteredItemTypes = this.getItemTypesExcludeRelations(itemsRootElement);
                if (ITEM_TYPES_VALIDATOR.validate(filteredItemTypes, inheritedItemClasses)) {
                    return true;
                }

                final List<TypeGroup> typeGroups = itemsRootElement.getItemTypes().getTypeGroups();
                for (TypeGroup typeGroup : typeGroups) {
                    final List<ItemType> groupedItemTypeList = typeGroup.getItemTypes();
                    if (ITEM_TYPES_VALIDATOR.validate(groupedItemTypeList, inheritedItemClasses)) {
                        return true;
                    }
                }

                final List<Relation> relations = itemsRootElement.getRelations().getRelations();
                if (RELATIONS_VALIDATOR.validate(relations, inheritedItemClasses)) {
                    return true;
                }
            }
        } catch (IndexNotReadyException ignore) {
            //do not validate Items.xml until index is not ready
        } catch (Exception e) {
            LOG.error(String.format("Items validation error. File: %s", file.getName()), e);
        }

        return false;
    }

    // Some people define relations as items and add attributes to them, we need to exclude those because
    // classes for them often are not being generated at all
    @NotNull
    private List<ItemType> getItemTypesExcludeRelations(@NotNull final Items itemsRootElement) {
        final Set<String> relationsMap = itemsRootElement.getRelations().getRelations().stream().map(
            relation -> getString(relation.getCode())
        ).collect(Collectors.toSet());

        return itemsRootElement.getItemTypes().getItemTypes().stream().filter(itemType -> !relationsMap.contains(
            getString(itemType.getCode())
        )).collect(Collectors.toList());
    }

    @NotNull
    private Map<String, PsiClass> findAllInheritClasses(
        @NotNull final Project project,
        @NotNull final String rootClass
    ) {
        final PsiClass itemRootClass = JavaPsiFacade.getInstance(project).findClass(
            rootClass, GlobalSearchScope.allScope(project)
        );

        if (null == itemRootClass) {
            return Collections.emptyMap();
        }

        final Collection<PsiClass> foundClasses = ClassInheritorsSearch.search(itemRootClass).findAll();
        final Map<String, PsiClass> result = new CaseInsensitiveMap<>();

        result.put(itemRootClass.getName(), itemRootClass);

        for (final PsiClass psiClass : foundClasses) {
            result.put(psiClass.getName(), psiClass);
        }

        return result;
    }
}
