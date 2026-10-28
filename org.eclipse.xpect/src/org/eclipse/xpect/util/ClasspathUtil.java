/*******************************************************************************
 * Copyright (c) 2012-2017 TypeFox GmbH and itemis AG.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Moritz Eysholdt - Initial contribution and API
 *******************************************************************************/

package org.eclipse.xpect.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;

import org.apache.log4j.Logger;
import org.eclipse.xpect.runner.XpectTestGlobalState;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;

import io.github.classgraph.ClassGraph;

public class ClasspathUtil {

	private static final String BUNDLE_SYMBOLIC_NAME = "Bundle-SymbolicName";

	private final static Logger LOG = Logger.getLogger(ClasspathUtil.class);

	@SuppressWarnings("resource")
	public static Collection<URL> findResources(String... fileNames) {
		Set<URL> result = Sets.newLinkedHashSet();
		ClassLoader classLoader = ClassLoader.getSystemClassLoader();
		try {
			for (String fileName : fileNames) {
				Enumeration<URL> resources = classLoader.getResources(fileName);
				while (resources.hasMoreElements())
					result.add(resources.nextElement());
			}
		} catch (IOException e) {
			LOG.error(e.getMessage(), e);
		}
		List<URL> urls = Lists.newArrayList(new ClassGraph().getClasspathURLs());
		// ClassGraph 5.x no longer reports the plain application class path (e.g. in standalone maven surefire)
		for (String entry : Splitter.on(File.pathSeparatorChar).omitEmptyStrings().split(System.getProperty("java.class.path", ""))) {
			try {
				urls.add(new File(entry).toURI().toURL());
			} catch (MalformedURLException e) {
				LOG.error(e.getMessage(), e);
			}
		}
		if (!urls.isEmpty()) {
			for (URL u : urls) {
				if (!u.getFile().endsWith(".jar")) {
					try {
						java.io.File f = new java.io.File(u.toURI());
						if (f.isDirectory()) {
							int levels = 5;
							W: while (levels >= 0 && f != null) {
								for (String fileName : fileNames) {
									java.io.File pl = new java.io.File(f + "/" + fileName);
									if (pl.isFile()) {
										result.add(pl.toURI().toURL());
										break W;
									}
								}
								levels--;
								f = f.getParentFile();
							}
						}
					} catch (URISyntaxException e) {
						LOG.error(e.getMessage(), e);
					} catch (MalformedURLException e) {
						LOG.error(e.getMessage(), e);
					}
				}
			}
		}
		// for some reason, ucl.getURLs() doesn't catch the current project in standalone maven surefire
		if (XpectTestGlobalState.INSTANCE.testClass() != null) {
			Class<?> clazz = XpectTestGlobalState.INSTANCE.testClass();
			String[] segments = clazz.getName().split("\\.");
			String fileName = Joiner.on('/').join(segments) + ".class";
			URL resource = clazz.getClassLoader().getResource(fileName);
			if ("jar".equals(resource.getProtocol())) {
				// URI location = URI.createURI(resource.toString()).trimSegments(segments.length).appendSegment("");
				// return location;
			} else {
				File classFile;
				try {
					classFile = new File(resource.toURI());
					File packageRootFolder = classFile;
					for (int i = 0; i < segments.length; i++) {
						packageRootFolder = packageRootFolder.getParentFile();
						if (packageRootFolder == null)
							LOG.error("Could not determine package root for " + clazz);
					}
					File current = packageRootFolder;
					W: while (current != null) {
						for (String name : fileNames) {
							java.io.File pl = new java.io.File(current, name);
							if (pl.isFile()) {
								result.add(pl.toURI().toURL());
								break W;
							}
						}
						current = current.getParentFile();
					}
				} catch (URISyntaxException e) {
					LOG.error(e.getMessage(), e);
				} catch (MalformedURLException e) {
					LOG.error(e.getMessage(), e);
				}
			}
		}
		return result;
	}

	public static String getSymbolicName(InputStream manifestInputStream) {
		try {
			Manifest manifest = new Manifest(manifestInputStream);
			return getSymbolicName(manifest);
		} catch (IOException e) {
			LOG.error("error parsing manifest", e);
		} finally {
			try {
				Closeables.close(manifestInputStream, true);
			} catch (IOException e) {
				LOG.error("error parsing manifest", e);
			}
		}
		return null;
	}

	public static String getSymbolicName(Manifest manifest) {
		String name = manifest.getMainAttributes().getValue(BUNDLE_SYMBOLIC_NAME);
		if (name != null) {
			int i = name.indexOf(';');
			if (i >= 0)
				name = name.substring(0, i);
		}
		return name;
	}
}
