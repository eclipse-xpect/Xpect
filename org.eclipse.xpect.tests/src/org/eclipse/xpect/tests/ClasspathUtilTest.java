/**
 * Copyright (c) 2026 Advantest
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.xpect.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.eclipse.xpect.runner.XpectTestGlobalState;
import org.eclipse.xpect.util.ClasspathUtil;
import org.junit.Test;

/**
 * Checks that resource discovery behaves the same with ClassGraph 4 and 5.
 * The test JARs are copied to {@code target/classgraph-libs} by Maven.
 */
public class ClasspathUtilTest {

	/**
	 * Finds a resource outside the classes directory using each ClassGraph version.
	 * The expected URL also prevents two empty results from passing the comparison.
	 */
	@Test
	public void testFindResourcesWithClassGraph4And5() throws Exception {
		Path testClasses = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
		assertTrue("Run this test as a plain JUnit test from a classes directory", Files.isDirectory(testClasses));
		Path project = testClasses;
		while (project != null && !Files.isRegularFile(project.resolve("pom.xml")))
			project = project.getParent();
		assertTrue("Could not locate org.eclipse.xpect.tests", project != null);

		// A sibling of the classes directory is not visible to ClassLoader.getResources().
		// ClasspathUtil must find it by walking up from a ClassGraph classpath URL.
		Path resource = Files.createTempFile(testClasses.getParent(), "xpect-classpathutil-", ".xml");
		try {
			URL expected = resource.toUri().toURL();
			String fileName = resource.getFileName().toString();
			Collection<URL> result4 = findWithClassGraph(classGraphJars(project, "4"), testClasses, fileName);
			Collection<URL> result5 = findWithClassGraph(classGraphJars(project, "5"), testClasses, fileName);
			assertEquals("ClassGraph 4 must find the test resource", Collections.singleton(expected), result4);
			assertEquals("ClassGraph 4 and 5 must find the same resources", result4, result5);
		} finally {
			Files.deleteIfExists(resource);
		}
	}

	/** A test class loaded from a non-file URL cannot be used as a filesystem root. */
	@Test
	public void testFindResourcesWithNonFileTestClassURL() throws Exception {
		URL nonFileResource = new URL("https://example.invalid/Test.class");
		ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
			@Override
			public URL getResource(String name) {
				return name.endsWith(".class") ? nonFileResource : super.getResource(name);
			}
		};
		Class<?> testClass = Proxy.newProxyInstance(loader, new Class<?>[] { Runnable.class }, (proxy, method, args) -> null).getClass();
		XpectTestGlobalState state = XpectTestGlobalState.INSTANCE;
		var previousModel = state.model();
		Class<?> previousTestClass = state.testClass();
		try {
			state.set(previousModel, testClass);
			assertTrue(ClasspathUtil.findResources("missing-xpect-" + UUID.randomUUID()).isEmpty());
		} finally {
			state.set(previousModel, previousTestClass);
		}
	}

	/** Returns the version-specific JARs copied during {@code process-test-resources}. */
	private List<URL> classGraphJars(Path project, String majorVersion) throws Exception {
		Path libs = project.resolve("target/classgraph-libs/" + majorVersion);
		assertTrue("Missing " + libs + "; run mvn process-test-resources", Files.isDirectory(libs));
		List<Path> mainJars = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(libs, "classgraph-" + majorVersion + ".*.jar")) {
			for (Path jar : stream)
				mainJars.add(jar);
		}
		assertEquals("Expected one ClassGraph " + majorVersion + " JAR in " + libs + "; run mvn clean test after changing versions", 1, mainJars.size());
		String fileName = mainJars.get(0).getFileName().toString();
		String version = fileName.substring("classgraph-".length(), fileName.length() - ".jar".length());

		String[] artifacts;
		if (majorVersion.equals("5"))
			artifacts = new String[] { "classgraph", "classgraph-classpath", "classgraph-vfs", "classgraph-base" };
		else
			artifacts = new String[] { "classgraph" };

		List<URL> jars = new ArrayList<>();
		for (String artifact : artifacts) {
			Path jar = libs.resolve(artifact + "-" + version + ".jar");
			assertTrue("Missing " + jar + "; download with mvn dependency:copy -Dartifact=io.github.classgraph:" + artifact + ":" + version + " -DoutputDirectory=" + libs,
					Files.isRegularFile(jar));
			jars.add(jar.toUri().toURL());
		}
		return jars;
	}

	/** Invokes a separately loaded ClasspathUtil with the selected ClassGraph version. */
	@SuppressWarnings("unchecked")
	private Collection<URL> findWithClassGraph(List<URL> classGraphJars, Path testClasses, String fileName) throws Exception {
		List<URL> urls = new ArrayList<>(classGraphJars);
		urls.add(testClasses.toUri().toURL());
		try (URLClassLoader loader = new ClassGraphTestClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader())) {
			Thread thread = Thread.currentThread();
			ClassLoader previous = thread.getContextClassLoader();
			try {
				thread.setContextClassLoader(loader);
				Class<?> util = loader.loadClass(ClasspathUtil.class.getName());
				
				// Check that the correct ClassGraph classes are used (to avoid mixing up)
				Class<?> classGraph = loader.loadClass("io.github.classgraph.ClassGraph");
				assertSame("ClassGraph must be loaded by the isolated loader", loader, classGraph.getClassLoader());
				assertNotNull("ClassGraph must have a code source", classGraph.getProtectionDomain().getCodeSource());
				assertEquals("ClassGraph must come from the selected JAR", classGraphJars.get(0), classGraph.getProtectionDomain().getCodeSource().getLocation());
				
				Method findResources = util.getMethod("findResources", String[].class);
				return (Collection<URL>) findResources.invoke(null, (Object) new String[] { fileName });
			} finally {
				thread.setContextClassLoader(previous);
			}
		}
	}

	/** Isolates ClassGraph and ClasspathUtil while delegating their other dependencies. */
	private static class ClassGraphTestClassLoader extends URLClassLoader {
		ClassGraphTestClassLoader(URL[] urls, ClassLoader parent) {
			super(urls, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			// Parent-first loading would reuse the ClassGraph version of the JUnit runtime.
			if (!name.equals(ClasspathUtil.class.getName()) && !name.startsWith("io.github.classgraph.") && !name.startsWith("nonapi.io.github.classgraph."))
				return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> type = findLoadedClass(name);
				if (type == null)
					type = findClass(name);
				if (resolve)
					resolveClass(type);
				return type;
			}
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (!name.equals(ClasspathUtil.class.getName()))
				return super.findClass(name);
			// Eclipse may not expose Xpect's classes through a usable CodeSource URL.
			try (InputStream stream = ClasspathUtil.class.getResourceAsStream("ClasspathUtil.class")) {
				if (stream == null)
					throw new ClassNotFoundException(name);
				byte[] bytes = stream.readAllBytes();
				return defineClass(name, bytes, 0, bytes.length);
			} catch (IOException e) {
				throw new ClassNotFoundException(name, e);
			}
		}
	}
}
