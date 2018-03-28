package org.openrepose.gradle.plugins.jaxb

import com.google.inject.Guice
import com.google.inject.Injector

import org.gradle.api.Plugin

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.logging.Logger

import org.gradle.api.logging.Logging
import org.gradle.api.plugins.JavaPlugin
import org.openrepose.gradle.plugins.jaxb.ant.AntXjc
import org.openrepose.gradle.plugins.jaxb.converter.NamespaceToEpisodeConverter
import org.openrepose.gradle.plugins.jaxb.extension.JaxbExtension
import org.openrepose.gradle.plugins.jaxb.extension.XjcExtension
import org.openrepose.gradle.plugins.jaxb.factory.XsdDependencyTreeFactory
import org.openrepose.gradle.plugins.jaxb.guice.JaxbPluginModule
import org.openrepose.gradle.plugins.jaxb.resolver.EpisodeDependencyResolver
import org.openrepose.gradle.plugins.jaxb.resolver.ExternalDependencyResolver
import org.openrepose.gradle.plugins.jaxb.resolver.NamespaceResolver
import org.openrepose.gradle.plugins.jaxb.resolver.XjcResolver
import org.openrepose.gradle.plugins.jaxb.schema.factory.DocumentFactory
import org.openrepose.gradle.plugins.jaxb.schema.guice.DocSlurperModule
import org.openrepose.gradle.plugins.jaxb.task.JaxbDependencyTree
import org.openrepose.gradle.plugins.jaxb.task.JaxbXjc

/**
 * Defines the Jaxb Plugin.
 */
class JaxbPlugin implements Plugin<Project> {
  static final String JAXB_TASK_GROUP = 'parse'
  static final String JAXB_XSD_DEPENDENCY_TREE_TASK = 'xsd-dependency-tree'
  static final String JAXB_XJC_TASK = 'xjc'
  static final String JAXB_CONFIGURATION_NAME = 'jaxb'
  static final String XJC_CONFIGURATION_NAME = 'xjc'

  static final Logger log = Logging.getLogger(JaxbPlugin.class)

  private JaxbExtension jaxbExtension

  /**
   * Entry point for plugin.
   *
   * @param project  This plugins gradle project
   */
  void apply (Project project) {
    project.plugins.apply(JavaPlugin)
    Injector injector = Guice.createInjector([new JaxbPluginModule(), new DocSlurperModule()])
    configureJaxbExtension(project)
    configureJaxbConfiguration(project)
    JaxbDependencyTree jnt = configureJaxbDependencyTree(project, jaxbExtension, injector)
    configureXjcConfiguration(project)
    configureJaxbXjc(project, jaxbExtension, jnt, injector)
  }
  
  /**
   * Configures {@code JaxbExtension} and {@code XjcExtenstion} to sensible defaults.
   *
   * @param project  This plugins gradle project
   * @see JaxbExtension
   * @see XjcExtension
   */
  private void configureJaxbExtension(final Project project) {
    jaxbExtension = project.extensions.create('jaxb', JaxbExtension, project)
    jaxbExtension.with {
      xsdDir = "${project.projectDir}/src/main/resources/schema"
      xsdIncludes = ['**/*.xsd']
      episodesDir = "${project.buildDir}/generated-resources/episodes"
      bindingsDir = "${project.projectDir}/src/main/resources/schema"
      bindings = ['**/*.xjb']
    }
    def xjcExtension = project.jaxb.extensions.create('xjc', XjcExtension)
    xjcExtension.with {
      taskClassname = 'com.sun.tools.xjc.XJC2Task'
      destinationDir = "${project.buildDir}/generated-sources/xjc"
      producesDir = "${project.buildDir}/generated-sources/xjc"
      generateEpisodeFiles = true
      extension = true
      removeOldOutput = 'yes'
      header = true
      accessExternalSchema = 'file'
    }
  }

  /**
   * Configures this plugins {@code jaxb} configuration.
   *
   * @param project  This plugins gradle project
   */
  private void configureJaxbConfiguration(final Project project) {
    project.configurations.create(JAXB_CONFIGURATION_NAME) { 
      visible = true
      transitive = true
      description = "The JAXB XJC libraries to be used for this project."
    }
  }

  /**
   * Configures this plugins {@code xjc} configuration.
   *
   * @param project  This plugins gradle project
   */
  private void configureXjcConfiguration(final Project project) {
    project.configurations.create(XJC_CONFIGURATION_NAME) {
      visible = true
      transitive = true
      description = "The JAXB XJC plugin libraries to be used for this project."
    }
  }

  /**
   * Adds a dependency on tasks with the specified name in other projects.  The other projects are determined from
   * project lib dependencies using the specified configuration name. These may be projects this project depends on or
   * projects that depend on this project based on the useDependOn argument.
   *
   * @param task Task to add dependencies to
   * @param useDependedOn if true, add tasks from projects this project depends on, otherwise use projects that depend
   * on this one.
   * @param otherProjectTaskName name of task in other projects
   * @param configurationName name of configuration to use to find the other projects
   */
  private void addDependsOnTaskInOtherProjects(final Task task,
                                               boolean useDependedOn,
                                               String otherProjectTaskName,
                                               String configurationName) {
    Project project = task.getProject()
    final Configuration configuration = project.getConfigurations().getByName(configurationName)
    task.dependsOn(configuration.getTaskDependencyFromProjectDependency(useDependedOn,otherProjectTaskName))
  }

  /**
   * Configures this plugins {@code xsd-dependency-tree} task.
   *
   * @param project  This plugins gradle project
   * @param jaxb  This plugins extension
   * @param injector  This plugins Guice injector
   * @return  this plugins dependency tree task, configured
   * @see JaxbDependencyTree
   */
  private JaxbDependencyTree configureJaxbDependencyTree(final Project project,
                                                         JaxbExtension jaxb,
                                                         def injector) {
    JaxbDependencyTree jnt = project.tasks.create(JAXB_XSD_DEPENDENCY_TREE_TASK, JaxbDependencyTree)
    jnt.description = "go through the folder ${jaxb.xsdDir} folder " +
      "and find all unique namespaces, create a namespace graph and parse in " +
      "the graph order with jaxb"
    jnt.group = JAXB_TASK_GROUP
    jnt.conventionMapping.generatedFilesDirectory = {
      new File((String)project.jaxb.xjc.destinationDir)
    }
    jnt.conventionMapping.xsds = {
      project.fileTree(
              dir: new File((String)project.jaxb.xsdDir),
              include: project.jaxb.xsdIncludes
      )
    }
    jnt.conventionMapping.docFactory = {
      injector.getInstance(DocumentFactory.class)
    }
    jnt.conventionMapping.namespaceResolver = {
      injector.getInstance(NamespaceResolver.class)
    }
    jnt.conventionMapping.externalDependencyResolver = {
      injector.getInstance(ExternalDependencyResolver.class)
    }
    jnt.conventionMapping.dependencyTreeFactory = {
      injector.getInstance(XsdDependencyTreeFactory.class)
    }
    // dependencies on projects with config jaxb, adds their xjc task to this
    // tasks dependencies
    addDependsOnTaskInOtherProjects(jnt, true, JAXB_XJC_TASK,
    				    JAXB_CONFIGURATION_NAME)
    return jnt
  }

  /**
   * Configures this plugins {@code xjc} task.
   *
   * @param project  This plugins gradle project
   * @param jaxb  This plugins extension
   * @param jnt  This plugins {@code xsd-dependency-tree} task (depended upon)
   * @param injector  This plugins Guice injector
   * @return  this plugins xjc tree task, configured
   * @see JaxbXjc
   */
  private void configureJaxbXjc(final Project project,
                                JaxbExtension jaxb,
                                JaxbDependencyTree jnt,
                                def injector) {
    JaxbXjc xjc = project.tasks.create(JAXB_XJC_TASK, JaxbXjc)
    xjc.description = "run through the Directory Graph for " +
            "${jaxb.xsdDir} and parse all schemas in order while " +
            (project.jaxb.xjc.generateEpisodeFiles ? "" : "not ") +
            "generating episode files to ${jaxb.episodesDir}"
    xjc.group = JAXB_TASK_GROUP
    xjc.dependsOn(jnt)
    xjc.conventionMapping.manager = { project.jaxb.dependencyGraph }
    xjc.conventionMapping.episodeDirectory = {
      new File((String)project.jaxb.episodesDir)
    }
    xjc.conventionMapping.bindings = {
       // empty file collection if no bindings listed, so that
       // files.files == empty set
       project.jaxb.bindings.isEmpty() ? project.files() :
       project.fileTree(
               dir:new File((String)project.jaxb.bindingsDir),
               include: project.jaxb.bindings
       )
     }
    // these two (generatedFilesDirectory and schemasDirectory)
    // are here so that gradle can check if anything
    // has changed in these folders and run this task again if so
    // they serve no other purpose than this
    xjc.conventionMapping.generatedFilesDirectory = {
      new File((String)project.jaxb.xjc.destinationDir)
    }
    // this is here in case there are bindings present, in which case a dependency tree type
    // evaluation just won't work.
    xjc.conventionMapping.xsds = {
      project.fileTree(
              dir: new File((String)project.jaxb.xsdDir),
              include: project.jaxb.xsdIncludes
      )
    }
    xjc.conventionMapping.schemasDirectory = { new File((String)project.jaxb.xsdDir) }
    xjc.conventionMapping.xjc = { injector.getInstance(AntXjc.class) }
    xjc.conventionMapping.episodeConverter = { injector.getInstance(NamespaceToEpisodeConverter.class) }
    xjc.conventionMapping.xjcResolver = { injector.getInstance(XjcResolver.class) }
    xjc.conventionMapping.dependencyResolver = {
      injector.getInstance(EpisodeDependencyResolver.class)
    }
  }
}
