/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.component.model;

import com.google.common.collect.Sets;
import org.apache.ivy.core.module.descriptor.*;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.api.internal.artifacts.DefaultModuleVersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.IvyUtil;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ReflectiveDependencyDescriptorFactory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector;
import org.gradle.internal.component.local.model.DefaultProjectDependencyMetadata;

import java.lang.reflect.Field;
import java.util.*;

public class DefaultDependencyMetadata implements DependencyMetadata {
    private final DependencyDescriptor dependencyDescriptor;
    private final DefaultModuleVersionSelector requested;

    public DefaultDependencyMetadata(DependencyDescriptor dependencyDescriptor) {
        this.dependencyDescriptor = dependencyDescriptor;
        ModuleRevisionId dependencyRevisionId = dependencyDescriptor.getDependencyRevisionId();
        requested = new DefaultModuleVersionSelector(dependencyRevisionId.getOrganisation(), dependencyRevisionId.getName(), dependencyRevisionId.getRevision());
    }

    public DefaultDependencyMetadata(ModuleVersionIdentifier moduleVersionIdentifier) {
        dependencyDescriptor = new DefaultDependencyDescriptor(IvyUtil.createModuleRevisionId(moduleVersionIdentifier), false);
        requested = new DefaultModuleVersionSelector(moduleVersionIdentifier.getGroup(), moduleVersionIdentifier.getName(), moduleVersionIdentifier.getVersion());
    }

    public DefaultDependencyMetadata(ModuleComponentIdentifier componentIdentifier) {
        dependencyDescriptor = new DefaultDependencyDescriptor(IvyUtil.createModuleRevisionId(componentIdentifier), false);
        requested = new DefaultModuleVersionSelector(componentIdentifier.getGroup(), componentIdentifier.getModule(), componentIdentifier.getVersion());
    }

    @Override
    public String toString() {
        return dependencyDescriptor.toString();
    }

    public ModuleVersionSelector getRequested() {
        return requested;
    }

    @Override
    public String[] getModuleConfigurations() {
        return dependencyDescriptor.getModuleConfigurations();
    }

    @Override
    public String[] getDependencyConfigurations(String moduleConfiguration, String requestedConfiguration) {
        return dependencyDescriptor.getDependencyConfigurations(moduleConfiguration, requestedConfiguration);
    }

    public ExcludeRule[] getExcludeRules(Collection<String> configurations) {
        return dependencyDescriptor.getExcludeRules(configurations.toArray(new String[configurations.size()]));
    }

    public boolean isChanging() {
        return dependencyDescriptor.isChanging();
    }

    public boolean isTransitive() {
        return dependencyDescriptor.isTransitive();
    }

    public boolean isForce() {
        return dependencyDescriptor.isForce();
    }

    public String getDynamicConstraintVersion() {
        return dependencyDescriptor.getDynamicConstraintDependencyRevisionId().getRevision();
    }

    public DependencyDescriptor getDescriptor() {
        return dependencyDescriptor;
    }

    public Set<ComponentArtifactMetadata> getArtifacts(ConfigurationMetadata fromConfiguration, ConfigurationMetadata toConfiguration) {
        String[] targetConfigurations = fromConfiguration.getHierarchy().toArray(new String[fromConfiguration.getHierarchy().size()]);
        DependencyArtifactDescriptor[] dependencyArtifacts = dependencyDescriptor.getDependencyArtifacts(targetConfigurations);
        if (dependencyArtifacts.length == 0) {
            return Collections.emptySet();
        }
        Set<ComponentArtifactMetadata> artifacts = new LinkedHashSet<ComponentArtifactMetadata>();
        for (DependencyArtifactDescriptor artifactDescriptor : dependencyArtifacts) {
            DefaultIvyArtifactName artifact = DefaultIvyArtifactName.forIvyArtifact(artifactDescriptor);
            artifacts.add(toConfiguration.artifact(artifact));
        }
        return artifacts;
    }

    public Set<IvyArtifactName> getArtifacts() {
        DependencyArtifactDescriptor[] dependencyArtifacts = dependencyDescriptor.getAllDependencyArtifacts();
        if (dependencyArtifacts.length == 0) {
            return Collections.emptySet();
        }
        Set<IvyArtifactName> artifactSet = Sets.newLinkedHashSet();
        for (DependencyArtifactDescriptor artifactDescriptor : dependencyArtifacts) {
            DefaultIvyArtifactName artifact = DefaultIvyArtifactName.forIvyArtifact(artifactDescriptor);
            artifactSet.add(artifact);
        }
        return artifactSet;
    }

    public DependencyMetadata withRequestedVersion(String requestedVersion) {
        if (requestedVersion.equals(requested.getVersion())) {
            return this;
        }
        return new DefaultDependencyMetadata(dependencyDescriptor.clone(IvyUtil.createModuleRevisionId(dependencyDescriptor.getDependencyRevisionId(), requestedVersion)));
    }

    @Override
    public DependencyMetadata withTarget(ComponentSelector target) {
        if (target instanceof ModuleComponentSelector) {
            ModuleComponentSelector moduleTarget = (ModuleComponentSelector) target;
            ModuleVersionSelector requestedVersion = DefaultModuleVersionSelector.newSelector(moduleTarget.getGroup(), moduleTarget.getModule(), moduleTarget.getVersion());
            if (requestedVersion.equals(requested)) {
                return this;
            }
            ModuleRevisionId requestedId = IvyUtil.createModuleRevisionId(requestedVersion.getGroup(), requestedVersion.getName(), requestedVersion.getVersion());
            DependencyDescriptor substitutedDescriptor = new ReflectiveDependencyDescriptorFactory().create(dependencyDescriptor, requestedId);
            return new DefaultDependencyMetadata(substitutedDescriptor);
        } else if (target instanceof ProjectComponentSelector) {
            // TODO:Prezi what to do here?
            ProjectComponentSelector projectTarget = (ProjectComponentSelector) target;
            return new DefaultProjectDependencyMetadata(dependencyDescriptor, projectTarget.getProjectPath());
        } else {
            throw new AssertionError();
        }
    }

    public DependencyMetadata withChanging() {
        if (dependencyDescriptor.isChanging()) {
            return this;
        }

        DependencyDescriptor forcedChanging = dependencyDescriptor.clone(dependencyDescriptor.getDependencyRevisionId());
        try {
            Field field = DefaultDependencyDescriptor.class.getDeclaredField("isChanging");
            field.setAccessible(true);
            field.set(forcedChanging, true);
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
        return new DefaultDependencyMetadata(forcedChanging);
    }

    public ComponentSelector getSelector() {
        return DefaultModuleComponentSelector.newSelector(requested.getGroup(), requested.getName(), requested.getVersion());
    }
}