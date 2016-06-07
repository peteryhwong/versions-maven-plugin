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
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.mojo.versions.api.ArtifactAssociation;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.api.PropertyVersions;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;

import javax.xml.stream.XMLStreamException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces any version with the next version.
 *
 * @author Stephen Connolly
 * @goal use-next-versions
 * @requiresProject true
 * @requiresDirectInvocation true
 * @since 1.0-alpha-3
 */
public class UseNextVersionsMojo
    extends AbstractVersionsDependencyUpdaterMojo
{

    /**
     * Pattern to match a snapshot version.
     */
    private static final Pattern MATCH_SNAPSHOT_REGEX = Pattern.compile( "^(.+)-((SNAPSHOT)|(\\d{8}\\.\\d{6}-\\d+))$" );

    /**
     * Whether to set the parent to the next parent version. If not set will default to false.
     *
     * @parameter property="processParent" defaultValue="false"
     * @since 1.0-alpha-3
     */
    private Boolean processParent;

    /**
     * Whether to set the properties to the next versions of specific artifacts. If not set will default to false.
     *
     * @parameter property="processProperties" defaultValue="false"
     * @since 1.0-alpha-3
     */
    private Boolean processProperties;

    /**
     * Whether to only update snapshots to the next versions of specific artifacts. If not set will default to false.
     *
     * @parameter property="processSnapshotsOnly" defaultValue="false"
     * @since 1.0-alpha-3
     */
    private Boolean processSnapshotsOnly;
    
    /**
     * A comma separated list of properties to update.
     *
     * @parameter property="includeProperties"
     * @since 1.0-alpha-1
     */
    private String includeProperties = null;

    /**
     * A comma separated list of properties to not update.
     *
     * @parameter property="excludeProperties"
     * @since 1.0-alpha-1
     */
    private String excludeProperties = null;

    /**
     * Whether properties linking versions should be auto-detected or not.
     *
     * @parameter property="autoLinkItems" defaultValue="true"
     * @since 1.0-alpha-2
     */
    private Boolean autoLinkItems;
    
    // ------------------------------ METHODS --------------------------

    /**
     * @param pom the pom to update.
     * @throws org.apache.maven.plugin.MojoExecutionException when things go wrong
     * @throws org.apache.maven.plugin.MojoFailureException when things go wrong in a very bad way
     * @throws javax.xml.stream.XMLStreamException when things go wrong with XML streaming
     * @see org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo#update(org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader)
     */
    protected void update( ModifiedPomXMLEventReader pom )
        throws MojoExecutionException, MojoFailureException, XMLStreamException
    {
        try
        {
            if ( getProject().getDependencyManagement() != null && isProcessingDependencyManagement() )
            {
                useNextVersions( pom, getProject().getDependencyManagement().getDependencies() );
            }
            if ( isProcessingDependencies() )
            {
                useNextVersions( pom, getProject().getDependencies() );
            }
        }
        catch ( ArtifactMetadataRetrievalException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
        
        if (Boolean.TRUE.equals( processProperties ) ) 
        {
            updateProperties( pom );
        }
        
        if (Boolean.TRUE.equals( processParent ) ) 
        {
            try 
            {
                updateParent( pom );
            } catch (ArtifactMetadataRetrievalException e) 
            {
                throw new MojoExecutionException( e.getMessage(), e );
            }
        }
    }
    
    private void updateParent( ModifiedPomXMLEventReader pom ) 
        throws MojoExecutionException, XMLStreamException, ArtifactMetadataRetrievalException
    {
        
        MavenProject parent = getProject().getParent();
        if ( parent == null )
        {
            getLog().info( "Project does not have a parent" );
            return;
        }

        if ( reactorProjects.contains( parent ) )
        {
            getLog().info( "Project's parent is part of the reactor" );
            return;
        }

        String currentVersion = parent.getVersion();
        
        Matcher versionMatcher = MATCH_SNAPSHOT_REGEX.matcher( currentVersion );
        if ( !versionMatcher.matches() && processSnapshotsOnly )
        {
            getLog().info( "Ignoring non-snapshot parent " + parent.getGroupId() + ":" +  parent.getArtifactId());
            return;
        }
        
        VersionRange versionRange;
        try
        {
            versionRange = VersionRange.createFromVersionSpec( currentVersion );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new MojoExecutionException( "Invalid version range specification: " + currentVersion, e );
        }

        Artifact artifact = artifactFactory.createDependencyArtifact( parent.getGroupId(),
                                                                      parent.getArtifactId(),
                                                                      versionRange, "pom", null, null );
        ArtifactVersions versions =
            getHelper().lookupArtifactVersions( artifact, false );
        ArtifactVersion[] newer = versions.getNewerVersions( currentVersion, allowSnapshots );
                    
        if ( newer.length > 0 )
        {
            String newVersion = newer[0].toString();
            
            getLog().info( "Updating parent from " + currentVersion + " to " + newVersion );

            if ( PomHelper.setProjectParentVersion( pom, newVersion ) )
            {
                getLog().debug( "Made an update from " + currentVersion + " to " + newVersion );
            }

        }
                    
        
    }
    
    private void updateProperties( ModifiedPomXMLEventReader pom ) 
        throws MojoExecutionException, XMLStreamException 
    {
        boolean allowSnapshots = Boolean.TRUE.equals( this.allowSnapshots );
        Map<Property, PropertyVersions> propertyVersions =
            getHelper().getVersionPropertiesMap( getProject(), new Property[0], includeProperties, excludeProperties,
                                                 !Boolean.FALSE.equals( autoLinkItems ) );
        for ( Map.Entry<Property, PropertyVersions> entry : propertyVersions.entrySet() )
        {
            Property property = entry.getKey();
            PropertyVersions version = entry.getValue();

            final String currentVersion = getProject().getProperties().getProperty( property.getName() );
            if ( currentVersion == null )
            {
                continue;
            }
            boolean canUpdateProperty = true;
            for ( ArtifactAssociation association : version.getAssociations() )
            {
                if ( ! isIncluded( association.getArtifact() ) )
                {
                    getLog().info( "Not updating the property ${" + property.getName()
                    + "} because it is used by artifact " + association.getArtifact().toString()
                    + " and that artifact is not included in the list of " + " allowed artifacts to be updated." );
                    canUpdateProperty = false;
                    break;
                }
            }
            
            Matcher versionMatcher = MATCH_SNAPSHOT_REGEX.matcher( currentVersion );
            if ( !versionMatcher.matches() && processSnapshotsOnly )
            {
                getLog().info( "Ignoring non-snapshot property  ${" + property.getName() + "}" );
                continue;
            }

            if ( canUpdateProperty )
            {
                ArtifactVersion nextVersion = 
                    version.getNextVersion( currentVersion, property, allowSnapshots, reactorProjects, getHelper() );
                
                if ( nextVersion == null || currentVersion.equals( nextVersion.toString() ) )
                {
                    getLog().info( "Property ${" + property.getName() + "}: Leaving unchanged as " + currentVersion );
                }
                else if ( PomHelper.setPropertyVersion( pom, version.getProfileId(), property.getName(), nextVersion.toString() ) )
                {
                    getLog().info( "Updated ${" + property.getName() + "} from " + currentVersion + " to " + nextVersion );
                }
            }

        }
    }

    private void useNextVersions( ModifiedPomXMLEventReader pom, Collection dependencies )
        throws XMLStreamException, MojoExecutionException, ArtifactMetadataRetrievalException
    {
        Iterator i = dependencies.iterator();
        
        boolean allowSnapshots = Boolean.TRUE.equals( this.allowSnapshots );

        while ( i.hasNext() )
        {
            Dependency dep = (Dependency) i.next();

            if ( isExcludeReactor() && isProducedByReactor( dep ) )
            {
                getLog().info( "Ignoring reactor dependency: " + toString( dep ) );
                continue;
            }

            String version = dep.getVersion();
            
            Matcher versionMatcher = MATCH_SNAPSHOT_REGEX.matcher( version );
            if ( !versionMatcher.matches() && processSnapshotsOnly )
            {
                getLog().info( "Ignoring non-snapshot dependency: " + toString( dep ) );
                continue;
            }
            
            Artifact artifact = this.toArtifact( dep );
            if ( !isIncluded( artifact ) )
            {
                continue;
            }

            getLog().debug( "Looking for newer versions of " + toString( dep ) );
            ArtifactVersions versions =
                getHelper().lookupArtifactVersions( artifact, false );
            ArtifactVersion[] newer = versions.getNewerVersions( version, allowSnapshots );
            if ( newer.length > 0 )
            {
                String newVersion = newer[0].toString();
                if ( PomHelper.setDependencyVersion( pom, dep.getGroupId(), dep.getArtifactId(), version, newVersion ) )
                {
                    getLog().info( "Updated " + toString( dep ) + " to version " + newVersion );
                }
            }
        }
    }

}