/*
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
package org.apache.river.maven;

import java.io.Externalizable;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.Modifier;
import javassist.NotFoundException;
import org.codehaus.plexus.util.DirectoryScanner;

@Mojo(name = "serialver", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class SerialVersionMojo extends AbstractMojo {

  private static final String SERIAL_VERSION_FIELD = "serialVersionUID";

  @Parameter(defaultValue = "${project.build.outputDirectory}", property = "classDir", required = true)
  private File classDirectory;

  @Parameter(defaultValue = "${project.build.directory}/processed-classes", property = "outputDir", required = true)
  private File outputDirectory;

  @Parameter(defaultValue = "false", property = "keepOriginal", required = true)
  private boolean keepOriginal;

  @Parameter(defaultValue = "false", property = "overwrite", required = true)
  private boolean overwrite;

  @Override
  public void execute() throws MojoExecutionException {

    try {
      
      URLClassLoader classLoader = new URLClassLoader(new URL[]{classDirectory.toURI().toURL()});

      ClassPool classPool = ClassPool.getDefault();
      classPool.appendClassPath(classDirectory.getAbsolutePath());

      DirectoryScanner directoryScanner = new DirectoryScanner();
      directoryScanner.setBasedir(classDirectory);
      directoryScanner.setIncludes(new String[]{"**/*.class"});
      directoryScanner.scan();

      String[] classFileNames = directoryScanner.getIncludedFiles();
      getLog().debug(String.format("Processing class files: %s", Arrays.toString(classFileNames)));

      for (String classFileName : classFileNames) {
        String className = getClassName(classFileName);

        Class<?> jClass = Class.forName(className, false, classLoader);
        CtClass ctClass = classPool.get(className);

        if (isSerializableButNotExternalizable(jClass)) {

          long calculatedSerialVersionUID = calculateSerialVersionUID(jClass);

          boolean shouldAddSerialVersionUID = false;
          CtField ctField = null;
          try {
            ctField = ctClass.getDeclaredField(SERIAL_VERSION_FIELD);
            long existingSerialVersionUID = (long) ctField.getConstantValue();
            if (existingSerialVersionUID != calculatedSerialVersionUID) {
              if (overwrite) {
                ctClass.removeField(ctField);
                shouldAddSerialVersionUID = true;
              } else {
                getLog().warn(String.format("Class='%s': calculated serialVersionUID='%s', existing serialVersionUID='%s'.", className, calculatedSerialVersionUID, existingSerialVersionUID));
              }
            }
          } catch (NotFoundException e) {
            shouldAddSerialVersionUID = true;
          }

          if (shouldAddSerialVersionUID) {
            getLog().debug(String.format("Class='%s': adding serialVersionUID='%s'", classFileName, calculatedSerialVersionUID));
            ctField = new CtField(CtClass.longType, SERIAL_VERSION_FIELD, ctClass);
            ctField.setModifiers(Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL);
            ctClass.addField(ctField, CtField.Initializer.constant(calculatedSerialVersionUID));
            ctClass.writeFile(outputDirectory.getAbsolutePath());
          }

          if (!keepOriginal) {
            getLog().debug(String.format("Removing class file: '%s'", classFileName));
            Files.deleteIfExists(Paths.get(classDirectory.getAbsolutePath(), classFileName));
          }
        }
      }

    } catch (ClassNotFoundException | NotFoundException | IllegalStateException | CannotCompileException | IOException e) {
      throw new MojoExecutionException("Error processing class files", e);
    }
  }

  private boolean isSerializableButNotExternalizable(Class<?> jClass) {
    return (!Externalizable.class.isAssignableFrom(jClass) && (Serializable.class.isAssignableFrom(jClass)));
  }

  private String getClassName(String classFileName) {
    return classFileName.replace("/", ".").substring(0, classFileName.lastIndexOf(".class"));
  }

  private long calculateSerialVersionUID(Class<?> jClass) {
    return ObjectStreamClass.lookup(jClass).getSerialVersionUID();
  }
}
