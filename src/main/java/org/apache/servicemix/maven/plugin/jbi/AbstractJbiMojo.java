/*
 * Copyright 2005-2006 The Apache Software Foundation.
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
package org.apache.servicemix.maven.plugin.jbi;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.servicemix.maven.plugin.jbi.JbiResolutionListener.Node;

public abstract class AbstractJbiMojo extends AbstractMojo {

	public static final String META_INF = "META-INF";

	public static final String JBI_DESCRIPTOR = "jbi.xml";

	public static final String LIB_DIRECTORY = "lib";

	/**
	 * Maven ProjectHelper
	 * 
	 * @component
	 */
	protected MavenProjectHelper projectHelper;

	/**
	 * The maven project.
	 * 
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	protected MavenProject project;

	/**
	 * Directory that resources are copied to during the build.
	 * 
	 * @parameter expression="${project.build.directory}/${project.artifactId}-${project.version}-installer"
	 * @required
	 */
	protected File workDirectory;

	/**
	 * @component
	 */
	protected MavenProjectBuilder projectBuilder;

	/**
	 * @parameter default-value="${localRepository}"
	 */
	protected ArtifactRepository localRepo;

	/**
	 * @parameter default-value="${project.remoteArtifactRepositories}"
	 */
	protected List remoteRepos;

	/**
	 * @component
	 */
	protected ArtifactMetadataSource artifactMetadataSource;

	/**
	 * @component
	 */
	protected ArtifactResolver resolver;

	/**
	 * @component
	 */
	protected ArtifactCollector collector;

	/**
	 * @component
	 */
	protected ArtifactFactory factory;

	protected MavenProject getProject() {
		return project;
	}

	protected File getWorkDirectory() {
		return workDirectory;
	}

	public MavenProjectHelper getProjectHelper() {
		return projectHelper;
	}

	protected void removeBranch(JbiResolutionListener listener,
			Artifact artifact) {
		Node n = listener.getNode(artifact);
		if (n != null && n.getParent() != null) {
			n.getParent().getChildren().remove(n);
		}
	}

	protected void removeChildren(JbiResolutionListener listener,
			Artifact artifact) {
		Node n = listener.getNode(artifact);
		n.getChildren().clear();
	}

	protected Set getArtifacts(Node n, Set s) {
		s.add(n.getArtifact());
		for (Iterator iter = n.getChildren().iterator(); iter.hasNext();) {
			Node c = (Node) iter.next();
			getArtifacts(c, s);
		}
		return s;
	}

	protected void excludeBranch(Node n, Set excludes) {
		excludes.add(n);
		for (Iterator iter = n.getChildren().iterator(); iter.hasNext();) {
			Node c = (Node) iter.next();
			excludeBranch(c, excludes);
		}
	}

	protected void print(Node rootNode, String string) {
		getLog().info(string + rootNode.getArtifact());
		for (Iterator iter = rootNode.getChildren().iterator(); iter.hasNext();) {
			Node n = (Node) iter.next();
			print(n, string + "  ");
		}
	}

	protected Set retainArtifacts(Set includes, JbiResolutionListener listener) {
		HashSet finalIncludes = new HashSet();
		Set filteredArtifacts = getArtifacts(listener.getRootNode(),
				new HashSet());
		for (Iterator iter = includes.iterator(); iter.hasNext();) {
			Artifact artifact = (Artifact) iter.next();
			getLog().debug(
					"Checking to determine whether to include " + artifact);
			for (Iterator iter2 = filteredArtifacts.iterator(); iter2.hasNext();) {
				Artifact filteredArtifact = (Artifact) iter2.next();
				if (filteredArtifact.getArtifactId().equals(
						artifact.getArtifactId())) {
					getLog().info("Initial match for "+artifact+" to "+filteredArtifact);
					finalIncludes.add(artifact);
					// XXX Something is wrong with the versions??
//					if (filteredArtifact.getArtifactId().equals(
//							artifact.getArtifactId())
//							&& filteredArtifact.getVersion().equals(
//									artifact.getVersion())
//							&& filteredArtifact.getType().equals(
//									artifact.getType())) {
//						getLog().debug(
//								"Found in filtered artifacts, including!");
//						finalIncludes.add(artifact);
//					}
				}
			}

		}

		return finalIncludes;
	}

	protected JbiResolutionListener resolveProject() {
		Map managedVersions = null;
		try {
			managedVersions = createManagedVersionMap(project.getId(), project
					.getDependencyManagement());
		} catch (ProjectBuildingException e) {
			getLog().error(
					"An error occurred while resolving project dependencies.",
					e);
		}
		JbiResolutionListener listener = new JbiResolutionListener();
		try {
			collector.collect(project.getDependencyArtifacts(), project
					.getArtifact(), managedVersions, localRepo, remoteRepos,
					artifactMetadataSource, null, Collections
							.singletonList(listener));
		} catch (ArtifactResolutionException e) {
			getLog().error(
					"An error occurred while resolving project dependencies.",
					e);
		}
		return listener;
	}

	protected Map createManagedVersionMap(String projectId,
			DependencyManagement dependencyManagement)
			throws ProjectBuildingException {
		Map map;
		if (dependencyManagement != null
				&& dependencyManagement.getDependencies() != null) {
			map = new HashMap();
			for (Iterator i = dependencyManagement.getDependencies().iterator(); i
					.hasNext();) {
				Dependency d = (Dependency) i.next();

				try {
					VersionRange versionRange = VersionRange
							.createFromVersionSpec(d.getVersion());
					Artifact artifact = factory.createDependencyArtifact(d
							.getGroupId(), d.getArtifactId(), versionRange, d
							.getType(), d.getClassifier(), d.getScope());
					map.put(d.getManagementKey(), artifact);
				} catch (InvalidVersionSpecificationException e) {
					throw new ProjectBuildingException(projectId,
							"Unable to parse version '" + d.getVersion()
									+ "' for dependency '"
									+ d.getManagementKey() + "': "
									+ e.getMessage(), e);
				}
			}
		} else {
			map = Collections.EMPTY_MAP;
		}
		return map;
	}

}
