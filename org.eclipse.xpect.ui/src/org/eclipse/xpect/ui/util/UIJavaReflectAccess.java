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

package org.eclipse.xpect.ui.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.jar.JarFile;

import org.apache.log4j.Logger;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.xpect.util.ClasspathUtil;
import org.eclipse.xpect.util.IJavaReflectAccess;
import org.eclipse.xtext.common.types.JvmExecutable;
import org.eclipse.xtext.common.types.JvmField;
import org.eclipse.xtext.common.types.JvmFormalParameter;
import org.eclipse.xtext.common.types.JvmOperation;
import org.eclipse.xtext.common.types.JvmType;
import org.eclipse.xtext.common.types.access.IMirror;
import org.eclipse.xtext.common.types.access.TypeResource;
import org.eclipse.xtext.common.types.access.binary.BinaryClassMirror;
import org.eclipse.xtext.common.types.access.jdt.JdtTypeMirror;
import org.eclipse.xtext.common.types.util.JavaReflectAccess;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.google.common.collect.Lists;
import com.google.common.io.Closeables;

@SuppressWarnings("restriction")
public class UIJavaReflectAccess implements IJavaReflectAccess {

	private final static Logger LOG = Logger.getLogger(UIJavaReflectAccess.class);

	private Bundle getBundle(BinaryClassMirror mirror) {
		Class<?> mirroredClass = mirror.getMirroredClass();
		return FrameworkUtil.getBundle(mirroredClass);
	}

	private Bundle getBundle(JdtTypeMirror mirror) {
		IType mirroredType = mirror.getMirroredType();
		IPackageFragmentRoot fragmentRoot = getPackageFragmentRoot(mirroredType);
		if (fragmentRoot == null || fragmentRoot.getPath() == null)
			return null;
		File file = fragmentRoot.getPath().toFile();
		String bundleName;
		if (file.isFile())
			bundleName = getBundleNameFromJar(file);
		else if (file.isDirectory())
			bundleName = getBundleNameFromDir(file);
		else
			return null;
		if (bundleName == null)
			return null;
		return Platform.getBundle(bundleName);
	}

	private String getBundleNameFromDir(File file) {
		File current = file;
		int counter = 5;
		while (current != null && counter > 0) {
			File cand = new File(current + "/META-INF/MANIFEST.MF");
			if (cand.isFile()) {
				try {
					return ClasspathUtil.getSymbolicName(new FileInputStream(cand));
				} catch (FileNotFoundException e) {
					LOG.error("Can't get symbolic name from " + cand, e);
					return null;
				}
			}
			current = current.getParentFile();
		}
		return null;
	}

	private String getBundleNameFromJar(File file) {
		JarFile jarFile;
		try {
			jarFile = new JarFile(file);
			try {
				return ClasspathUtil.getSymbolicName(jarFile.getManifest());
			} catch (IOException e) {
				LOG.error("Can't get symbolic name from " + file, e);
				return null;
			} finally {
				Closeables.close(jarFile, true);
			}
		} catch (IOException e1) {
			LOG.error("Can't get symbolic name from " + file, e1);
			return null;
		}
	}

	public Field getField(JvmField field) {
		Class<?> rawType = getRawType(field.getDeclaringType());
		if (rawType == null)
			return null;
		try {
			return rawType.getDeclaredField(field.getSimpleName());
		} catch (Exception e) {
			LOG.error("Can't find " + field.getIdentifier(), e);
			return null;
		}
	}

	public Method getMethod(JvmOperation operation) {
		Class<?> rawType = getRawType(operation.getDeclaringType());
		if (rawType == null)
			return null;
		Class<?>[] paramTypes = getParamTypes(operation);
		try {
			return rawType.getDeclaredMethod(operation.getSimpleName(), paramTypes);
		} catch (Exception e) {
			LOG.error("Can't find " + operation.getIdentifier(), e);
			return null;
		}
	}

	private IPackageFragmentRoot getPackageFragmentRoot(IJavaElement ele) {
		IJavaElement current = ele;
		while (current != null) {
			if (current instanceof IPackageFragmentRoot)
				return (IPackageFragmentRoot) current;
			current = current.getParent();
		}
		return null;
	}

	private Class<?>[] getParamTypes(JvmExecutable exe) {
		List<JvmFormalParameter> parameters = exe.getParameters();
		List<Class<?>> result = Lists.newArrayList();
		for (JvmFormalParameter p : parameters) {
			result.add(getRawType(p.getParameterType().getType()));
		}
		return result.toArray(new Class<?>[result.size()]);
	}

	public Class<?> getRawType(JvmType jvmType) {
		if (jvmType == null || jvmType.eIsProxy())
			return null;
		XtextResourceSet resourceSet = (XtextResourceSet) jvmType.eResource().getResourceSet();
		if (resourceSet.getClasspathURIContext() instanceof ClassLoader) {
			JavaReflectAccess access = new JavaReflectAccess();
			access.setClassLoader((ClassLoader) resourceSet.getClasspathURIContext());
			return access.getRawType(jvmType);
		}
		if (!(jvmType.eResource() instanceof TypeResource))
			return null;
		IMirror typeMirror = ((TypeResource) jvmType.eResource()).getMirror();
		Bundle bundle = null;
		if (typeMirror instanceof JdtTypeMirror)
			bundle = getBundle((JdtTypeMirror) typeMirror);
		if (typeMirror instanceof BinaryClassMirror)
			bundle = getBundle((BinaryClassMirror) typeMirror);
		if (bundle == null)
			return null;
		String className = jvmType.getQualifiedName();
		try {
			return bundle.loadClass(className);
		} catch (ClassNotFoundException e) {
			LOG.error("Could not find Java class for " + jvmType.eClass().getName() + " " + jvmType.getIdentifier(), e);
			return null;
		}
	}

}
