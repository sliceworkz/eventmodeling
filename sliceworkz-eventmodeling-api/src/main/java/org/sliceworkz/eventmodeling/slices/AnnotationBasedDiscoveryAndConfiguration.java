/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sliceworkz.eventmodeling.slices;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnnotationBasedDiscoveryAndConfiguration {
	
    private static final Logger LOGGER = LoggerFactory.getLogger(AnnotationBasedDiscoveryAndConfiguration.class);

    public static <T> List<T> instantiateAndConfigure(Class<? extends java.lang.annotation.Annotation> annotationClass, Package basePackage, Predicate<T> predicate, Consumer<T> consumer ) {
        List<Class<?>> classesWithAnnotationInPackage = findAnnotatedClasses(basePackage.getName(), annotationClass);

        List<T> result = new ArrayList<>();
        
        for (Class<?> classToBeInstantiated : classesWithAnnotationInPackage) {
            try {
                LOGGER.info("found annotated class {}", classToBeInstantiated.getCanonicalName());
                @SuppressWarnings("unchecked")
				T instantiatedAnnotatedClass = (T) classToBeInstantiated.getDeclaredConstructor().newInstance();
                if ( predicate.test(instantiatedAnnotatedClass)) {
	                consumer.accept(instantiatedAnnotatedClass);
	                result.add(instantiatedAnnotatedClass);
                }
            } catch (Exception e) {
                LOGGER.error("error registering annotated class %s".formatted(classToBeInstantiated.getCanonicalName()), e);
                throw new RuntimeException("Failed to register annotated class: " + classToBeInstantiated.getName(), e);
            }
        }
        
        return result;
    }

    private static List<Class<?>> findAnnotatedClasses(String basePackageName, Class<? extends java.lang.annotation.Annotation> annotation) {
        List<Class<?>> classes = new ArrayList<>();
        String path = basePackageName.replace('.', '/');
        
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = classLoader.getResources(path);
            
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String protocol = resource.getProtocol();
                
                if ("file".equals(protocol)) {
                    // Handle file system classes
                    classes.addAll(findClassesInDirectory(new File(resource.getFile()), basePackageName, annotation));
                } else if ("jar".equals(protocol)) {
                    // Handle JAR file classes
                    String jarPath = resource.getPath().substring(5, resource.getPath().indexOf("!"));
                    classes.addAll(findClassesInJar(jarPath, basePackageName, annotation));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error scanning for annotated classes", e);
            throw new RuntimeException(e);
        }
        
        return classes;
    }

    private static List<Class<?>> findClassesInDirectory(File directory, String packageName, Class<? extends java.lang.annotation.Annotation> annotation) {
        List<Class<?>> classes = new ArrayList<>();
        
        if (!directory.exists()) {
            return classes;
        }
        
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            paths.filter(path -> path.toString().endsWith(".class"))
                 .forEach(path -> {
                     try {
                         String className = getClassName(directory.toPath(), path, packageName);
                         Class<?> clazz = Class.forName(className);
                         if (clazz.isAnnotationPresent(annotation)) {
                             classes.add(clazz);
                         }
                     } catch (ClassNotFoundException | NoClassDefFoundError e) {
                         LOGGER.error("Could not load class: {}", e.getMessage());
                         throw new RuntimeException(e);
                     }
                 });
        } catch (IOException e) {
            LOGGER.error("Error reading directory", e);
            throw new RuntimeException(e);
        }
        
        return classes;
    }

    private static String getClassName(Path baseDir, Path classFile, String basePackage) {
        String relativePath = baseDir.relativize(classFile).toString();
        String className = relativePath.replace(File.separatorChar, '.')
                                       .replace(".class", "");
        return basePackage + "." + className;
    }

    private static List<Class<?>> findClassesInJar(String jarPath, String packageName, Class<? extends java.lang.annotation.Annotation> annotation) {
        List<Class<?>> classes = new ArrayList<>();
        String path = packageName.replace('.', '/');
        
        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                
                if (entryName.startsWith(path) && entryName.endsWith(".class")) {
                    String className = entryName.replace('/', '.')
                                                .replace(".class", "");
                    try {
                        Class<?> clazz = Class.forName(className);
                        if (clazz.isAnnotationPresent(annotation)) {
                            classes.add(clazz);
                        }
                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                        LOGGER.error("Could not load class: {}", e.getMessage());
                        throw new RuntimeException(e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error reading JAR file", e);
            throw new RuntimeException(e);
        }
        
        return classes;
    }
}
