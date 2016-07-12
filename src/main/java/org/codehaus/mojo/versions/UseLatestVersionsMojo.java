package org.codehaus.mojo.versions;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;

/**
 * Replaces any version with the latest version.
 *
 * @author Stephen Connolly
 * @goal use-latest-versions
 * @requiresProject true
 * @requiresDirectInvocation true
 * @since 1.0-alpha-3
 */
public class UseLatestVersionsMojo
    extends AbstractVersionsDependencyUpdaterMojo
{
    /**
     * Whether to allow the major version number to be changed.
     *
     * @parameter property="allowMajorUpdates" default-value="true"
     * @since 1.2
     */
    protected Boolean allowMajorUpdates;

    /**
     * Whether to allow the minor version number to be changed.
     *
     * @parameter property="allowMinorUpdates" default-value="true"
     * @since 1.2
     */
    protected Boolean allowMinorUpdates;

    /**
     * Whether to allow the incremental version number to be changed.
     *
     * @parameter property="allowIncrementalUpdates" default-value="true"
     * @since 1.2
     */
    protected Boolean allowIncrementalUpdates;

    // ------------------------------ METHODS --------------------------

    /**
     * @param pom the pom to update.
     * @throws org.apache.maven.plugin.MojoExecutionException when things go wrong
     * @throws org.apache.maven.plugin.MojoFailureException when things go wrong in a very bad way
     * @throws javax.xml.stream.XMLStreamException when things go wrong with XML streaming
     * @see AbstractVersionsUpdaterMojo#update(org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader)
     */
    protected void update( ModifiedPomXMLEventReader pom )
        throws MojoExecutionException, MojoFailureException, XMLStreamException
    {
        try
        {
            if ( getProject().getDependencyManagement() != null && isProcessingDependencyManagement() )
            {
                DependencyManagement dependencyManagement = PomHelper.getRawModel( getProject() ).getDependencyManagement();
                if ( dependencyManagement != null )
                {
                    useLatestVersions( pom, dependencyManagement.getDependencies() );
                }
            }
            if ( isProcessingDependencies() )
            {
                useLatestVersions( pom, getProject().getDependencies() );
            }
        }
        catch ( ArtifactMetadataRetrievalException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
        catch ( IOException e ) {
            throw new MojoExecutionException( e.getMessage(), e );
        }
    }

    private void useLatestVersions( ModifiedPomXMLEventReader pom, Collection dependencies )
        throws XMLStreamException, MojoExecutionException, ArtifactMetadataRetrievalException
    {
        int segment = determineUnchangedSegment( allowMajorUpdates, allowMinorUpdates, allowIncrementalUpdates );
        Iterator i = dependencies.iterator();

        while ( i.hasNext() )
        {
            Dependency dep = (Dependency) i.next();

            if ( isExcludeReactor() && isProducedByReactor( dep ) )
            {
                getLog().info( "Ignoring reactor dependency: " + toString( dep ) );
                continue;
            }

            String version = dep.getVersion();
            Artifact artifact = this.toArtifact( dep );
            if ( !isIncluded( artifact ) )
            {
                continue;
            }

            getLog().debug( "Looking for newer versions of " + toString( dep ) );
            ArtifactVersions versions = getHelper().lookupArtifactVersions( artifact, false );
            ArtifactVersion[] newer =
                versions.getNewerVersions( version, segment, Boolean.TRUE.equals( allowSnapshots ) );
            if ( newer.length > 0 )
            {
                String newVersion = newer[newer.length - 1].toString();
                if ( PomHelper.setDependencyVersion( pom, dep.getGroupId(), dep.getArtifactId(), version, newVersion ) )
                {
                    getLog().info( "Updated " + toString( dep ) + " to version " + newVersion );
                }
            }
        }
    }

}
