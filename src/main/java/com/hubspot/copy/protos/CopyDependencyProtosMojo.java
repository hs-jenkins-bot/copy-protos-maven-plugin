package com.hubspot.copy.protos;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.reflections.Reflections;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.util.ConfigurationBuilder;

import com.google.common.base.Predicate;

@Mojo(
        name = "copy-protos",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES,
        threadSafe = true,
        requiresDependencyResolution = ResolutionScope.TEST
)
public class CopyDependencyProtosMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${project.build.directory}/generated-resources/dependency-protobufs")
  private File outputDirectory;

  @Parameter(property = "copy-dependency-protos.plugin.skip", defaultValue = "false")
  private boolean skip;

  @Override
  public void execute() throws MojoExecutionException {
    if (skip) {
      getLog().info("Skipping plugin execution");
      return;
    } else if ("pom".equals(project.getPackaging())) {
      getLog().info("Skipping pom project");
      return;
    }

    final List<String> classpathElements;
    try {
      classpathElements = new ArrayList<>(project.getTestClasspathElements());
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("Error resolving dependencies", e);
    }

    List<URL> urls = toUrls(classpathElements);
    Set<String> protos = findProtos(urls);

    copyProtos(urls, outputDirectory.toPath(), protos);
  }

  private void copyProtos(
          List<URL> urls,
          Path outputDirectory,
          Set<String> protos
  ) throws MojoExecutionException {
    ClassLoader classLoader = URLClassLoader.newInstance(urls.toArray(new URL[urls.size()]));

    for (String proto : protos) {
      try {
        List<URL> protoUrls = Collections.list(classLoader.getResources(proto));
        if (protoUrls.isEmpty()) {
          throw new IllegalStateException("Proto " + proto + " seems to have disappeared?");
        }

        for (URL url : protoUrls) {
          URLConnection connection = url.openConnection();
          if (!(connection instanceof JarURLConnection)) {
            throw new MojoExecutionException("Expected proto to be in a JAR, invalid URL " + url);
          }
          File jar = new File(((JarURLConnection) connection).getJarFileURL().getFile());
          Artifact artifact = findArtifactWithFile(project.getArtifacts(), jar);
          if (artifact == null) {
            throw new MojoExecutionException("Unable to find artifact for JAR " + jar);
          }

          Path target = outputDirectory;
          for (String part : artifact.getGroupId().split("\\.")) {
            target = target.resolve(part);
          }
          target = target.resolve(artifact.getArtifactId());
          target = target.resolve(proto);

          Files.createDirectories(target.getParent());
          Files.copy(url.openStream(), target, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException e) {
        throw new MojoExecutionException("Error copying proto " + proto, e);
      }
    }
  }

  private static List<URL> toUrls(List<String> paths) throws MojoExecutionException {
    List<File> files = presentFiles(paths);
    List<URL> urls = new ArrayList<>(files.size());
    for (File file : files) {
      try {
        urls.add(file.toURI().toURL());
      } catch (MalformedURLException e) {
        throw new MojoExecutionException("Error constructing classpath URLs", e);
      }
    }

    return urls;
  }

  private static Set<String> findProtos(List<URL> classpathElements) {
    Predicate<String> protoFile = new Predicate<String>() {

      @Override
      public boolean apply(@Nullable String name) {
        return name != null && name.endsWith(".proto");
      }
    };

    ConfigurationBuilder configuration = new ConfigurationBuilder()
            .addUrls(classpathElements)
            .filterInputsBy(protoFile)
            .setScanners(new ResourcesScanner());
    return new Reflections(configuration).getResources(protoFile);
  }

  private static Artifact findArtifactWithFile(Set<Artifact> artifacts, File file) {
    for (Artifact artifact : artifacts) {
      if (file.equals(artifact.getFile())) {
        return artifact;
      }
    }

    return null;
  }

  private static List<File> presentFiles(List<String> paths) {
    List<File> files = new ArrayList<>();
    for (String path : paths) {
      File file = new File(path);
      if (file.getAbsoluteFile().isFile()) {
        files.add(file);
      }
    }

    return files;
  }
}
