/*******************************************************************************
 * Copyright (c) 2000, 2018 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.core.builder;

import static org.eclipse.jdt.internal.core.JavaModelManager.trace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.IResourceProxyVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.CompilationParticipant;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.impl.CompilerStats;
import org.eclipse.jdt.internal.core.CompilationGroup;
import org.eclipse.jdt.internal.core.util.Messages;
import org.eclipse.jdt.internal.core.util.Util;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class BatchImageBuilder extends AbstractImageBuilder {

	private IncrementalImageBuilder incrementalBuilder; // if annotations or secondary types have to be processed after the compile loop
	private ArrayList secondaryTypes; // qualified names for all secondary types found during batch compile
	private Set<String> typeLocatorsWithUndefinedTypes; // type locators for all source files with errors that may be caused by 'not found' secondary types
	private final CompilationGroup compilationGroup;

	/*  leave 2 threads for compiler + reader.*/
	private static final ExecutorService WRITER_SERVICE = createExecutor(Math.max(1, Runtime.getRuntime().availableProcessors() - 2));

	private static ThreadPoolExecutor createExecutor(int threadCount) {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(threadCount, threadCount,
				/* keepAliveTime */ 5, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), r -> {
					Thread t = new Thread(r, "Compiler Class File Writer"); //$NON-NLS-1$
					t.setDaemon(true);
					return t;
				});
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}
	private static final long MAX_CLASS_CONTENTS_BYTES_QUEUED = 100_000_000; // 100MB
	private final Map<IFile, byte[]> classContents = new HashMap<>();
	private long classContentsBytesQueued;
	private boolean batchMode;


protected BatchImageBuilder(JavaBuilder javaBuilder, boolean buildStarting, CompilationGroup compilationGroup) {
	super(javaBuilder, buildStarting, null, compilationGroup);
	this.compilationGroup = compilationGroup;
	this.nameEnvironment.isIncrementalBuild = false;
	this.incrementalBuilder = null;
	this.secondaryTypes = null;
	this.typeLocatorsWithUndefinedTypes = null;
}

protected BatchImageBuilder(BatchImageBuilder batchImageBuilder, boolean buildStarting, CompilationGroup compilationGroup) {
	super(batchImageBuilder.javaBuilder, buildStarting, batchImageBuilder.newState, compilationGroup);
	this.compilationGroup = compilationGroup;
	this.nameEnvironment.isIncrementalBuild = false;
	this.incrementalBuilder = null;
	this.secondaryTypes = null;
	this.typeLocatorsWithUndefinedTypes = null;
}


public void build() {
	if (JavaBuilder.DEBUG)
		System.out.println("FULL build"); //$NON-NLS-1$

	try {
		this.notifier.subTask(Messages.bind(Messages.build_cleaningOutput, this.javaBuilder.currentProject.getName()));
		if(this.compilationGroup != CompilationGroup.TEST) {
			JavaBuilder.removeProblemsAndTasksFor(this.javaBuilder.currentProject);
		}
		cleanOutputFolders(true);
		this.notifier.updateProgressDelta(0.05f);

		this.notifier.subTask(Messages.build_analyzingSources);
		LinkedHashSet<SourceFile> sourceFiles = new LinkedHashSet<>(33);
		addAllSourceFiles(sourceFiles);
		this.notifier.updateProgressDelta(0.10f);

		if (sourceFiles.size() > 0) {
			SourceFile[] allSourceFiles = new SourceFile[sourceFiles.size()];
			sourceFiles.toArray(allSourceFiles);

			this.notifier.setProgressPerCompilationUnit(0.75f / allSourceFiles.length);
			this.workQueue.addAll(allSourceFiles);
			compile(allSourceFiles);

			if (this.typeLocatorsWithUndefinedTypes != null)
				if (this.secondaryTypes != null && !this.secondaryTypes.isEmpty())
					rebuildTypesAffectedBySecondaryTypes();
			if (this.incrementalBuilder != null)
				this.incrementalBuilder.buildAfterBatchBuild();
		}

		if (this.javaBuilder.javaProject.hasCycleMarker())
			this.javaBuilder.mustPropagateStructuralChanges();
	} catch (CoreException e) {
		throw internalException(e);
	} finally {
		if (JavaBuilder.SHOW_STATS)
			printStats();
		cleanUp();
	}
}

@Override
protected void acceptSecondaryType(ClassFile classFile) {
	if (this.secondaryTypes != null)
		this.secondaryTypes.add(classFile.fileName());
}

protected void cleanOutputFolders(boolean copyBack) throws CoreException {
	boolean deleteAll = JavaCore.CLEAN.equals(
		this.javaBuilder.javaProject.getOption(JavaCore.CORE_JAVA_BUILD_CLEAN_OUTPUT_FOLDER, true));
	if (deleteAll) {
		if (this.compilationGroup != CompilationGroup.TEST) {
			// CompilationGroup.MAIN is done first, so this notifies the participants only once
			// calling this for CompilationGroup.TEST could cases generated files for CompilationGroup.MAIN to be deleted.
			if (this.javaBuilder.participants != null)
				for (CompilationParticipant participant : this.javaBuilder.participants)
					participant.cleanStarting(this.javaBuilder.javaProject);
		}

		Set<IContainer> visited = new LinkedHashSet<>(this.sourceLocations.length);
		for (ClasspathMultiDirectory sourceLocation : this.sourceLocations) {
			this.notifier.subTask(Messages.bind(Messages.build_cleaningOutput, this.javaBuilder.currentProject.getName()));
			if (sourceLocation.hasIndependentOutputFolder) {
				IContainer outputFolder = sourceLocation.binaryFolder;
				if (visited.add(outputFolder)) {
					if (outputFolder.exists()) { // if folder does not exists we can't delete it
							// (reasons include: deleted already by an overlapping output folder, or another process deleted it)
							// without this check the Eclipse resource API would bail out...
						IResource[] members = outputFolder.members();
						for (IResource member : members) {
							if (!member.isDerived()) {
								member.accept(
									new IResourceVisitor() {
										@Override
										public boolean visit(IResource resource) throws CoreException {
											resource.setDerived(true, null);
											return resource.getType() != IResource.FILE;
										}
									}
								);
							}
							try {
								member.delete(IResource.FORCE, null);
							} catch(CoreException e) {
								Util.log(e, "Error occurred while deleting: " + member.getFullPath()); //$NON-NLS-1$
							}
						}
					}
				}
				this.notifier.checkCancel();
				if (copyBack)
					copyExtraResourcesBack(sourceLocation, true);
			} else if (sourceLocation.binaryFolder.exists()) { // if folder does not exists we can't delete it and we can't visit children
				boolean isOutputFolder = sourceLocation.sourceFolder.equals(sourceLocation.binaryFolder);
				final char[][] exclusionPatterns =
					isOutputFolder
						? sourceLocation.exclusionPatterns
						: null; // ignore exclusionPatterns if output folder == another source folder... not this one
				final char[][] inclusionPatterns =
					isOutputFolder
						? sourceLocation.inclusionPatterns
						: null; // ignore inclusionPatterns if output folder == another source folder... not this one
				sourceLocation.binaryFolder.accept(
					new IResourceProxyVisitor() {
						@Override
						public boolean visit(IResourceProxy proxy) throws CoreException {
							if (proxy.getType() == IResource.FILE) {
								if (org.eclipse.jdt.internal.compiler.util.Util.isClassFileName(proxy.getName())) {
									IResource resource = proxy.requestResource();
									if (exclusionPatterns != null || inclusionPatterns != null)
										if (Util.isExcluded(resource.getFullPath(), inclusionPatterns, exclusionPatterns, false))
											return false;
									if (!resource.isDerived())
										resource.setDerived(true, null);
									try {
										resource.delete(IResource.FORCE, null);
									} catch(CoreException e) {
										Util.log(e, "Error occurred while deleting: " + resource.getFullPath()); //$NON-NLS-1$
									}
								}
								return false;
							}
							if (exclusionPatterns != null && inclusionPatterns == null) // must walk children if inclusionPatterns != null
								if (Util.isExcluded(proxy.requestFullPath(), null, exclusionPatterns, true))
									return false;
							BatchImageBuilder.this.notifier.checkCancel();
							return true;
						}
					},
					IResource.NONE
				);
				this.notifier.checkCancel();
			}
			this.notifier.checkCancel();
		}
	} else if (copyBack) {
		for (ClasspathMultiDirectory sourceLocation : this.sourceLocations) {
			if (sourceLocation.hasIndependentOutputFolder)
				copyExtraResourcesBack(sourceLocation, false);
			this.notifier.checkCancel();
		}
	}
}

@Override
protected void cleanUp() {
	this.incrementalBuilder = null;
	this.secondaryTypes = null;
	this.typeLocatorsWithUndefinedTypes = null;
	super.cleanUp();
}

@Override
protected void compile(SourceFile[] units, SourceFile[] additionalUnits, boolean compilingFirstGroup) {
	if (additionalUnits != null && this.secondaryTypes == null)
		this.secondaryTypes = new ArrayList(7);
	super.compile(units, additionalUnits, compilingFirstGroup);
}

protected void copyExtraResourcesBack(ClasspathMultiDirectory sourceLocation, final boolean deletedAll) throws CoreException {
	// When, if ever, does a builder need to copy resources files (not .java or .class) into the output folder?
	// If we wipe the output folder at the beginning of the build then all 'extra' resources must be copied to the output folder.

	this.notifier.subTask(Messages.build_copyingResources);
	final int segmentCount = sourceLocation.sourceFolder.getFullPath().segmentCount();
	final char[][] exclusionPatterns = sourceLocation.exclusionPatterns;
	final char[][] inclusionPatterns = sourceLocation.inclusionPatterns;
	final IContainer outputFolder = sourceLocation.binaryFolder;
	final boolean isAlsoProject = sourceLocation.sourceFolder.equals(this.javaBuilder.currentProject);
	sourceLocation.sourceFolder.accept(
		new IResourceProxyVisitor() {
			@Override
			public boolean visit(IResourceProxy proxy) throws CoreException {
				IResource resource = null;
				switch(proxy.getType()) {
					case IResource.FILE :
						if (org.eclipse.jdt.internal.core.util.Util.isJavaLikeFileName(proxy.getName()) ||
							org.eclipse.jdt.internal.compiler.util.Util.isClassFileName(proxy.getName())) return false;

						resource = proxy.requestResource();
						if (BatchImageBuilder.this.javaBuilder.filterExtraResource(resource)) return false;
						if (exclusionPatterns != null || inclusionPatterns != null)
							if (Util.isExcluded(resource.getFullPath(), inclusionPatterns, exclusionPatterns, false))
								return false;

						IPath partialPath = resource.getFullPath().removeFirstSegments(segmentCount);
						IResource copiedResource = outputFolder.getFile(partialPath);
						if (copiedResource.exists()) {
							if (deletedAll) {
								IResource originalResource = findOriginalResource(partialPath);
								String id = originalResource.getFullPath().removeFirstSegments(1).toString();
								createProblemFor(
									resource,
									null,
									Messages.bind(Messages.build_duplicateResource, id),
									BatchImageBuilder.this.javaBuilder.javaProject.getOption(JavaCore.CORE_JAVA_BUILD_DUPLICATE_RESOURCE, true));
								return false;
							}
							copiedResource.delete(IResource.FORCE, null); // last one wins
						}
						createFolder(partialPath.removeLastSegments(1), outputFolder); // ensure package folder exists
						copyResource(resource, copiedResource);
						return false;
					case IResource.FOLDER :
						BatchImageBuilder.this.notifier.checkCancel();
						resource = proxy.requestResource();
						if (BatchImageBuilder.this.javaBuilder.filterExtraResource(resource)) return false;
						if (isAlsoProject && isExcludedFromProject(resource.getFullPath())) return false; // the sourceFolder == project
						if (exclusionPatterns != null && inclusionPatterns == null) // must walk children if inclusionPatterns != null
							if (Util.isExcluded(resource.getFullPath(), null, exclusionPatterns, true))
								return false;
				}
				return true;
			}
		},
		IResource.NONE
	);
}

protected IResource findOriginalResource(IPath partialPath) {
	for (ClasspathMultiDirectory sourceLocation : this.sourceLocations) {
		if (sourceLocation.hasIndependentOutputFolder) {
			IResource originalResource = sourceLocation.sourceFolder.getFile(partialPath);
			if (originalResource.exists()) return originalResource;
		}
	}
	return null;
}

private void printStats() {
	if (this.compiler == null) return;
	CompilerStats compilerStats = this.compiler.stats;
	long time = compilerStats.elapsedTime();
	long lineCount = compilerStats.lineCount;
	double speed = ((int) (lineCount * 10000.0 / time)) / 10.0;
	System.out.println("\n>FULL BUILD STATS for: "+this.javaBuilder.javaProject.getElementName()); //$NON-NLS-1$
	System.out.println(">   compiled " + lineCount + " lines in " + time + " ms: " + speed + " lines/s"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	System.out.print(">   parse: " + compilerStats.parseTime + " ms (" + ((int) (compilerStats.parseTime * 1000.0 / time)) / 10.0 + "%)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	System.out.print(", resolve: " + compilerStats.resolveTime + " ms (" + ((int) (compilerStats.resolveTime * 1000.0 / time)) / 10.0 + "%)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	System.out.print(", analyze: " + compilerStats.analyzeTime + " ms (" + ((int) (compilerStats.analyzeTime * 1000.0 / time)) / 10.0 + "%)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	System.out.println(", generate: " + compilerStats.generateTime + " ms (" + ((int) (compilerStats.generateTime * 1000.0 / time)) / 10.0 + "%)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
}

@Override
protected void processAnnotationResults(CompilationParticipantResult[] results) {
	// to compile the compilation participant results, we need to incrementally recompile all affected types
	// whenever the generated types are initially added or structurally changed
	if (this.incrementalBuilder == null)
		this.incrementalBuilder = new IncrementalImageBuilder(this, this.compilationGroup);
	this.incrementalBuilder.processAnnotationResults(results);
}

protected void rebuildTypesAffectedBySecondaryTypes() {
	// to compile types that could not find 'missing' secondary types because of multiple
	// compile groups, we need to incrementally recompile all affected types as if the missing
	// secondary types have just been added, see bug 146324
	if (this.incrementalBuilder == null)
		this.incrementalBuilder = new IncrementalImageBuilder(this, this.compilationGroup);

	int count = this.secondaryTypes.size();
	Set<String> qualifiedNames = new HashSet<>(count * 2);
	Set<String> simpleNames = new HashSet<>(count);
	Set<String> rootNames = new HashSet<>(3);
	while (--count >=0) {
		char[] secondaryTypeName = (char[]) this.secondaryTypes.get(count);
		IPath path = new Path(null, new String(secondaryTypeName));
		this.incrementalBuilder.addDependentsOf(path, false, qualifiedNames, simpleNames, rootNames);
	}
	this.incrementalBuilder.addAffectedSourceFiles(
		qualifiedNames,
		simpleNames,
		rootNames,
		this.typeLocatorsWithUndefinedTypes);
}

@Override
protected void storeProblemsFor(SourceFile sourceFile, CategorizedProblem[] problems) throws CoreException {
	if (sourceFile == null || problems == null || problems.length == 0) return;

	for (int i = problems.length; --i >= 0;) {
		CategorizedProblem problem = problems[i];
		if (problem != null && problem.getID() == IProblem.UndefinedType) {
			if (this.typeLocatorsWithUndefinedTypes == null)
				this.typeLocatorsWithUndefinedTypes = new HashSet<>(3);
			this.typeLocatorsWithUndefinedTypes.add(sourceFile.typeLocator());
			break;
		}
	}

	super.storeProblemsFor(sourceFile, problems);
}

@Override
public String toString() {
	return "batch image builder for:\n\tnew state: " + this.newState; //$NON-NLS-1$
}

@Override
public void startBatch() {
	this.batchMode = true;
}

@Override
protected void writeClassFileContents(ClassFile classFile, IFile file, String qualifiedFileName, boolean isTopLevelType,
		SourceFile compilationUnit) throws CoreException {
	byte[] content = classFile.getBytes();
	if (this.batchMode) {
		if (JavaBuilder.DEBUG) {
			trace("Batching changed class file " + file.getName());//$NON-NLS-1$
		}
		// flush before limit to avoid OOME:
		if (!this.classContents.isEmpty() && this.classContentsBytesQueued + content.length >= MAX_CLASS_CONTENTS_BYTES_QUEUED) {
			flushBatch();
		}
		this.classContents.put(file, content);
		this.classContentsBytesQueued += content.length;
	} else {
		if (JavaBuilder.DEBUG) {
			trace("Writing changed class file " + file.getName());//$NON-NLS-1$
		}
		file.write(content, true, true, false, null);
	}
}

@Override
public void endBatch() {
	try {
		flushBatch();
	} finally {
		this.batchMode = false;
	}
}

@Override
public void flushBatch() {
	try {
		if (JavaBuilder.DEBUG) {
			this.classContents.keySet().forEach(file -> trace("Writing changed class file " + file.getName()));//$NON-NLS-1$
		}
		ResourcesPlugin.getWorkspace().write(this.classContents, true, true, false, null, WRITER_SERVICE);
	} catch (CoreException e) {
		// Already existing class files should not happen:
		// Duplicate classes get marked earlier with a "The type {} is already defined"
		Util.log(e, "Failed to write some of the class files: " + this.classContents.keySet().stream() //$NON-NLS-1$
				.map(f -> f.getFullPath().toString()).collect(Collectors.joining(", "))); //$NON-NLS-1$
		createProblemFor(this.javaBuilder.currentProject, null, Messages.build_inconsistentClassFile, JavaCore.ERROR);
	} finally {
		this.classContents.clear();
		this.classContentsBytesQueued = 0;
	}
}
}