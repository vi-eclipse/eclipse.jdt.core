/*******************************************************************************
 * Copyright (c) 2000, 2019 IBM Corporation and others.
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
 *     Stephan Herrmann - Contribution for
 *								Bug 440477 - [null] Infrastructure for feeding external annotations into compilation
 *     Karsten Thoms - Bug 532505
 *     Sebastian Zarnekow - Contribution for
 *								Bug 545491 - Poor performance of ReferenceCollection with many source files
 *******************************************************************************/
package org.eclipse.jdt.internal.core.builder;

import static org.eclipse.jdt.internal.core.JavaModelManager.trace;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.env.AccessRule;
import org.eclipse.jdt.internal.compiler.env.AccessRuleSet;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.AddExports;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.AddReads;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.UpdateKind;
import org.eclipse.jdt.internal.compiler.util.CharArray;
import org.eclipse.jdt.internal.compiler.util.CharCharArray;
import org.eclipse.jdt.internal.compiler.util.Util;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.util.DeduplicationUtil;

@SuppressWarnings({"rawtypes", "unchecked"})
public class State {
// NOTE: this state cannot contain types that are not defined in this project

String javaProjectName;
public ClasspathMultiDirectory[] sourceLocations;
public ClasspathMultiDirectory[] testSourceLocations;
public ClasspathLocation[] binaryLocations;
public ClasspathLocation[] testBinaryLocations;
// keyed by the project relative path of the type (i.e. "src1/p1/p2/A.java"), value is a ReferenceCollection or an AdditionalTypeCollection
Map<String, ReferenceCollection> references;
// Holds a mapping of types to a path to detect duplicate type definitions (possibly depending on the release for multi-release types)
public TypeLocators typeLocators;

int buildNumber;
long lastStructuralBuildTime;
HashMap<String, Long> structuralBuildTimes;

private long previousStructuralBuildTime;
private StringSet structurallyChangedTypes;
public static int MaxStructurallyChangedTypes = 100; // keep track of ? structurally changed types, otherwise consider all to be changed

public static final byte VERSION = 0x0027;

static final byte SOURCE_FOLDER = 1;
static final byte BINARY_FOLDER = 2;
static final byte EXTERNAL_JAR = 3;
static final byte INTERNAL_JAR = 4;

/** typical values of accessRule.pattern for encoding hint */
private static final int[] PROBLEM_IDS = new int[] { 0, IProblem.ForbiddenReference, IProblem.DiscouragedReference,
		IProblem.ForbiddenReference | AccessRule.IgnoreIfBetter,
		IProblem.DiscouragedReference | AccessRule.IgnoreIfBetter };

State() {
	// constructor with no argument
	this.typeLocators = new TypeLocators();
}

protected State(JavaBuilder javaBuilder) {
	this.previousStructuralBuildTime = -1;
	this.structurallyChangedTypes = null;
	this.javaProjectName = javaBuilder.currentProject.getName();
	this.sourceLocations = javaBuilder.nameEnvironment.sourceLocations;
	this.binaryLocations = javaBuilder.nameEnvironment.binaryLocations;
	this.testSourceLocations = javaBuilder.testNameEnvironment.sourceLocations;
	this.testBinaryLocations = javaBuilder.testNameEnvironment.binaryLocations;
	this.references = new LinkedHashMap<>(7);
	this.typeLocators = new TypeLocators();

	this.buildNumber = 0; // indicates a full build
	this.lastStructuralBuildTime = computeStructuralBuildTime(javaBuilder.lastState == null ? 0 : javaBuilder.lastState.lastStructuralBuildTime);
	this.structuralBuildTimes = new HashMap<>();
}

long computeStructuralBuildTime(long previousTime) {
	long newTime = System.currentTimeMillis();
	if (newTime <= previousTime)
		newTime = previousTime + 1;
	return newTime;
}

void copyFrom(State lastState) {
	this.previousStructuralBuildTime = lastState.previousStructuralBuildTime;
	this.structurallyChangedTypes = lastState.structurallyChangedTypes;
	this.buildNumber = lastState.buildNumber + 1;
	this.lastStructuralBuildTime = lastState.lastStructuralBuildTime;
	this.structuralBuildTimes = lastState.structuralBuildTimes;

	this.references = new LinkedHashMap<>(lastState.references);
	this.typeLocators = new TypeLocators(lastState.typeLocators);
}

/**
 * Compares this build state with other one in terms of persistence (transient data is ignored)
 */
@Override
public boolean equals(Object obj) {
	if (this == obj) {
		return true;
	}
	if (!(obj instanceof State)) {
		return false;
	}
	State other = (State) obj;
	return this.buildNumber == other.buildNumber
			&& this.lastStructuralBuildTime == other.lastStructuralBuildTime
			&& Objects.equals(this.javaProjectName, other.javaProjectName)
			&& Arrays.equals(this.sourceLocations, other.sourceLocations)
			&& Arrays.equals(this.binaryLocations, other.binaryLocations)
			&& Arrays.equals(this.testSourceLocations, other.testSourceLocations)
			&& Arrays.equals(this.testBinaryLocations, other.testBinaryLocations)
			&& Objects.equals(this.typeLocators, other.typeLocators)
			&& Objects.equals(this.references, other.references);
// Below fields aren't persisted
//			&& this.previousStructuralBuildTime == other.previousStructuralBuildTime
//			&& Arrays.equals(this.knownPackageNames, other.knownPackageNames)
//			&& Objects.equals(this.structurallyChangedTypes, other.structurallyChangedTypes)
//			&& Objects.equals(this.structuralBuildTimes, other.structuralBuildTimes)
}

@Override
public int hashCode() {
	return 31 + Objects.hash(this.javaProjectName);
}

public char[][] getDefinedTypeNamesFor(String typeLocator) {
	Object c = this.references.get(typeLocator);
	if (c instanceof AdditionalTypeCollection)
		return ((AdditionalTypeCollection) c).definedTypeNames;
	return null; // means only one type is defined with the same name as the file... saves space
}

public Map<String, ReferenceCollection> getReferences() {
	return this.references;
}

StringSet getStructurallyChangedTypes(State prereqState) {
	if (prereqState != null && prereqState.previousStructuralBuildTime > 0) {
		Object o = this.structuralBuildTimes.get(prereqState.javaProjectName);
		long previous = o == null ? 0 : ((Long) o).longValue();
		if (previous == prereqState.previousStructuralBuildTime)
			return prereqState.structurallyChangedTypes;
	}
	return null;
}

public boolean isDuplicateLocator(String qualifiedTypeName, String typeLocator, int release) {
	return this.typeLocators.isDuplicateLocator(qualifiedTypeName, typeLocator, release);
}

public boolean isKnownPackage(String qualifiedPackageName) {
	return this.typeLocators.isKnownPackage(qualifiedPackageName);
}

public boolean isKnownType(String qualifiedTypeName) {
	return this.typeLocators.isKnownType(qualifiedTypeName);
}

boolean isSourceFolderEmpty(IContainer sourceFolder) {
	return this.typeLocators.isSourceFolderEmpty(sourceFolder);
}

void record(String typeLocator, char[][][] qualifiedRefs, char[][] simpleRefs, char[][] rootRefs, char[] mainTypeName, ArrayList typeNames) {
	if (typeNames.size() == 1 && CharOperation.equals(mainTypeName, (char[]) typeNames.get(0))) {
		this.references.put(typeLocator, new ReferenceCollection(qualifiedRefs, simpleRefs, rootRefs));
	} else {
		char[][] definedTypeNames = new char[typeNames.size()][]; // can be empty when no types are defined
		typeNames.toArray(definedTypeNames);
		this.references.put(typeLocator, new AdditionalTypeCollection(definedTypeNames, qualifiedRefs, simpleRefs, rootRefs));
	}
}

void recordLocatorForType(String qualifiedTypeName, String typeLocator, int release) {
	this.typeLocators.recordLocatorForType(qualifiedTypeName, typeLocator, release);
}

void recordStructuralDependency(IProject prereqProject, State prereqState) {
	if (prereqState != null)
		if (prereqState.lastStructuralBuildTime > 0) // can skip if 0 (full build) since its assumed to be 0 if unknown
			this.structuralBuildTimes.put(prereqProject.getName(), Long.valueOf(prereqState.lastStructuralBuildTime));
}

void removeLocator(String typeLocatorToRemove, int release) {
	this.references.remove(typeLocatorToRemove);
	this.typeLocators.removeLocator(typeLocatorToRemove, release);
}

void removePackage(IResourceDelta sourceDelta, int release) {
	IResource resource = sourceDelta.getResource();
	switch(resource.getType()) {
		case IResource.FOLDER :
			IResourceDelta[] children = sourceDelta.getAffectedChildren();
			for (IResourceDelta child : children)
				removePackage(child, release);
			return;
		case IResource.FILE :
			IPath typeLocatorPath = resource.getProjectRelativePath();
			if (org.eclipse.jdt.internal.core.util.Util.isJavaLikeFileName(typeLocatorPath.lastSegment()))
				removeLocator(typeLocatorPath.toString(), release);
	}
}

void removeQualifiedTypeName(String qualifiedTypeNameToRemove) {
	this.typeLocators.removeLocator(qualifiedTypeNameToRemove);
}

static State read(IProject project, DataInputStream input) throws IOException, CoreException {
	CompressedReader in = new CompressedReader(input);
	if (JavaBuilder.DEBUG) {
		trace("About to read state " + project.getName()); //$NON-NLS-1$
	}
	if (VERSION != in.readByte()) {
		if (JavaBuilder.DEBUG) {
			trace("Found non-compatible state version... answered null for " + project.getName()); //$NON-NLS-1$
		}
		return null;
	}

	State newState = new State();
	newState.javaProjectName = in.readStringUsingDictionary();
	if (!project.getName().equals(newState.javaProjectName)) {
		if (JavaBuilder.DEBUG) {
			trace("Project's name does not match... answered null"); //$NON-NLS-1$
		}
		return null;
	}
	newState.buildNumber = in.readInt();
	newState.lastStructuralBuildTime = in.readLong();

	ArrayList<ClasspathLocation> allLocationsForEEA = null;
	if (JavaCore.ENABLED.equals(JavaCore.create(project).getOption(JavaCore.CORE_JAVA_BUILD_EXTERNAL_ANNOTATIONS_FROM_ALL_LOCATIONS, true))) {
		allLocationsForEEA = new ArrayList<>(); // signals that we are collecting locations
	}

	newState.sourceLocations = readSourceLocations(project, in, allLocationsForEEA);
	newState.binaryLocations = readBinaryLocations(project, in, newState.sourceLocations, allLocationsForEEA);

	newState.testSourceLocations = readSourceLocations(project, in, allLocationsForEEA);
	newState.testBinaryLocations = readBinaryLocations(project, in, newState.testSourceLocations, allLocationsForEEA);

	int length;
	newState.structuralBuildTimes = new HashMap<>(length = in.readInt());
	for (int i = 0; i < length; i++)
		newState.structuralBuildTimes.put(in.readStringUsingDictionary(), Long.valueOf(in.readLong()));

	String[] internedTypeLocators = new String[length = in.readInt()];
	for (int i = 0; i < length; i++)
		internedTypeLocators[i] = in.readStringUsingLast();
	newState.typeLocators.read(in, internedTypeLocators);

	/*
	 * Here we read global arrays of names for the entire project - do not mess up the ordering while interning
	 */
	char[][] internedRootNames = ReferenceCollection.internSimpleNames(readNames(in), false /* keep well known */, false /* do not sort */);
	char[][] internedSimpleNames = ReferenceCollection.internSimpleNames(readNames(in), false /* keep well known */, false /* do not sort */);
	char[][][] internedQualifiedNames = new char[length = in.readInt()][][];
	for (int i = 0; i < length; i++) {
		int qLength = in.readInt();
		char[][] qName = new char[qLength][];
		for (int j = 0; j < qLength; j++)
			qName[j] = internedSimpleNames[in.readIntInRange(internedSimpleNames.length)];
		internedQualifiedNames[i] = qName;
	}
	internedQualifiedNames = ReferenceCollection.internQualifiedNames(internedQualifiedNames, false /* drop well known */, false /* do not sort */);

	length = in.readInt();
	newState.references = new LinkedHashMap((int) (length / 0.75 + 1));
	for (int i = 0; i < length; i++) {
		String typeLocator = internedTypeLocators[in.readInt()];
		ReferenceCollection collection = null;
		switch (in.readByte()) {
			case 1 :
				char[][] additionalTypeNames = readNames(in);
				char[][][] qualifiedNames = new char[in.readInt()][][];
				for (int j = 0, m = qualifiedNames.length; j < m; j++)
					qualifiedNames[j] = internedQualifiedNames[in.readIntInRange(internedQualifiedNames.length)];
				char[][] simpleNames = new char[in.readInt()][];
				for (int j = 0, m = simpleNames.length; j < m; j++)
					simpleNames[j] = internedSimpleNames[in.readIntInRange(internedSimpleNames.length)];
				char[][] rootNames = new char[in.readInt()][];
				for (int j = 0, m = rootNames.length; j < m; j++)
					rootNames[j] = internedRootNames[in.readIntInRange(internedRootNames.length)];
				collection = new AdditionalTypeCollection(additionalTypeNames, qualifiedNames, simpleNames, rootNames);
				break;
			case 2 :
				char[][][] qNames = new char[in.readInt()][][];
				for (int j = 0, m = qNames.length; j < m; j++)
					qNames[j] = internedQualifiedNames[in.readIntInRange(internedQualifiedNames.length)];
				char[][] sNames = new char[in.readInt()][];
				for (int j = 0, m = sNames.length; j < m; j++)
					sNames[j] = internedSimpleNames[in.readIntInRange(internedSimpleNames.length)];
				char[][] rNames = new char[in.readInt()][];
				for (int j = 0, m = rNames.length; j < m; j++)
					rNames[j] = internedRootNames[in.readIntInRange(internedRootNames.length)];
				collection = new ReferenceCollection(qNames, sNames, rNames);
		}
		newState.references.put(typeLocator, collection);
	}
	if (JavaBuilder.DEBUG) {
		trace("Successfully read state for " + newState.javaProjectName); //$NON-NLS-1$
	}
	return newState;
}

private static ClasspathMultiDirectory[] readSourceLocations(IProject project, CompressedReader in, List<ClasspathLocation> allLocationsForEEA) throws IOException {
	int length = in.readInt();
	ClasspathMultiDirectory[] sourceLocations = new ClasspathMultiDirectory[length];
	for (int i = 0; i < length; i++) {
		IContainer sourceFolder = project, outputFolder = project;
		String folderName;
		if ((folderName = in.readStringUsingDictionary()).length() > 0) sourceFolder = project.getFolder(folderName);
		if ((folderName = in.readStringUsingDictionary()).length() > 0) outputFolder = project.getFolder(folderName);
		char[][] inclusionPatterns = readNames(in);
		char[][] exclusionPatterns = readNames(in);
		boolean ignoreOptionalProblems = in.readBoolean();
		IPath path = readNullablePath(in);
		boolean hasIndependentOutputFolder = in.readBoolean();
		int release = in.readInt();
		ClasspathMultiDirectory md =
			(ClasspathMultiDirectory) ClasspathLocation.forSourceFolder(sourceFolder, outputFolder, inclusionPatterns, exclusionPatterns, ignoreOptionalProblems, path, release);
		if (hasIndependentOutputFolder)
			md.hasIndependentOutputFolder = true;
		sourceLocations[i] = md;
		if (allLocationsForEEA != null) {
			md.connectAllLocationsForEEA(allLocationsForEEA, true);
		}
	}
	return sourceLocations;
}

private static ClasspathLocation[] readBinaryLocations(IProject project, CompressedReader in, ClasspathMultiDirectory[] sourceLocations, ArrayList<ClasspathLocation> allLocationsForEEA) throws IOException, CoreException {
	int length = in.readInt();
	ClasspathLocation[] locations = new ClasspathLocation[length];
	IWorkspaceRoot root = project.getWorkspace().getRoot();
	for (int i = 0; i < length; i++) {
		byte kind = in.readByte();
		switch (kind) {
			case SOURCE_FOLDER :
				locations[i] = sourceLocations[in.readInt()];
				break;
			case BINARY_FOLDER :
				IPath path = new Path(in.readStringUsingDictionary());
				IContainer outputFolder = path.segmentCount() == 1
					? (IContainer) root.getProject(path.toString())
					: (IContainer) root.getFolder(path);
				locations[i] = ClasspathLocation.forBinaryFolder(outputFolder, in.readBoolean(),
							readRestriction(in), new Path(in.readStringUsingDictionary()), in.readBoolean());
				break;
			case EXTERNAL_JAR :
				String jarPath = in.readStringUsingDictionary();
				if (Util.isJrt(jarPath)) {
					locations[i] = ClasspathLocation.forJrtSystem(jarPath, readRestriction(in), new Path(in.readStringUsingDictionary()), in.readStringUsingDictionary());
				} else {
					locations[i] = ClasspathLocation.forLibrary(jarPath, in.readLong(),
							readRestriction(in), new Path(in.readStringUsingDictionary()), in.readBoolean(), in.readStringUsingDictionary());
				}
				break;
			case INTERNAL_JAR :
					locations[i] = ClasspathLocation.forLibrary(root.getFile(new Path(in.readStringUsingDictionary())),
							readRestriction(in), new Path(in.readStringUsingDictionary()), in.readBoolean(), in.readStringUsingDictionary());
					break;
		}
		ClasspathLocation loc = locations[i];
		if (allLocationsForEEA != null) {
			loc.connectAllLocationsForEEA(allLocationsForEEA, true);
		}
		char[] patchName = in.readChars();
		loc.patchModuleName = patchName.length > 0 ? new String(patchName) : null;
		int limitSize = in.readInt();
		if (limitSize != 0) {
			loc.limitModuleNames = new LinkedHashSet<>(limitSize);
			for (int j = 0; j < limitSize; j++) {
				loc.limitModuleNames.add(in.readStringUsingDictionary());
			}
		} else {
			loc.limitModuleNames = null;
		}
		IUpdatableModule.UpdatesByKind updates = new IUpdatableModule.UpdatesByKind();
		List<Consumer<IUpdatableModule>> packageUpdates = null;
		int packageUpdatesSize = in.readInt();
		if (packageUpdatesSize != 0) {
			packageUpdates = updates.getList(UpdateKind.PACKAGE, true);
			for (int j = 0; j < packageUpdatesSize; j++) {
				char[] pkgName = in.readChars();
				char[][] targets = readNames(in);
				packageUpdates.add(new AddExports(pkgName, targets));
			}
		}
		List<Consumer<IUpdatableModule>> moduleUpdates = null;
		int moduleUpdatesSize = in.readInt();
		if (moduleUpdatesSize != 0) {
			moduleUpdates = updates.getList(UpdateKind.MODULE, true);
			char[] modName = in.readChars();
			moduleUpdates.add(new AddReads(modName));
		}
		if (packageUpdates != null || moduleUpdates != null)
			loc.updates = updates;
	}
	return locations;
}

private static IPath readNullablePath(CompressedReader in) throws IOException {
	String path = in.readStringUsingDictionary();
	if (!path.isEmpty())
		return new Path(path);
	return null;
}

private static AccessRuleSet readRestriction(CompressedReader in) throws IOException {
	int length = in.readInt();
	if (length == 0) return null; // no restriction specified
	AccessRule[] accessRules = new AccessRule[length];
	JavaModelManager manager = JavaModelManager.getJavaModelManager();
	for (int i = 0; i < length; i++) {
		char[] pattern = in.readCharsUsingLast();
		int problemId = in.readIntWithHint(PROBLEM_IDS);
		accessRules[i] = manager.getAccessRuleForProblemId(pattern, problemId);
	}
	return new AccessRuleSet(accessRules, in.readByte(), DeduplicationUtil.intern(in.readStringUsingDictionary()));
}

void tagAsNoopBuild() {
	this.buildNumber = -1; // tag the project since it has no source folders and can be skipped
}

boolean wasNoopBuild() {
	return this.buildNumber == -1;
}

void tagAsStructurallyChanged() {
	this.previousStructuralBuildTime = this.lastStructuralBuildTime;
	this.structurallyChangedTypes = new StringSet(7);
	this.lastStructuralBuildTime = computeStructuralBuildTime(this.previousStructuralBuildTime);
}

boolean wasStructurallyChanged(IProject prereqProject, State prereqState) {
	if (prereqState != null) {
		Object o = this.structuralBuildTimes.get(prereqProject.getName());
		long previous = o == null ? 0 : ((Long) o).longValue();
		if (previous == prereqState.lastStructuralBuildTime) return false;
	}
	return true;
}

void wasStructurallyChanged(String typeName) {
	if (this.structurallyChangedTypes != null) {
		if (this.structurallyChangedTypes.elementSize > MaxStructurallyChangedTypes)
			this.structurallyChangedTypes = null; // too many to keep track of
		else
			this.structurallyChangedTypes.add(typeName);
	}
}

void write(DataOutputStream output) throws IOException {
	CompressedWriter out=new CompressedWriter(output);
/*
 * byte		VERSION
 * String		project name
 * int			build number
 * int			last structural build number
*/
	out.writeByte(VERSION);
	out.writeStringUsingDictionary(this.javaProjectName);
	out.writeInt(this.buildNumber);
	out.writeLong(this.lastStructuralBuildTime);

/*
 * ClasspathMultiDirectory[]
 * int			id
 * String		path(s)
 */
	writeSourceLocations(out, this.sourceLocations);

/*
 * ClasspathLocation[]
 * int			id
 * String		path(s)
 */
	writeBinaryLocations(out, this.binaryLocations, this.sourceLocations);

/*
 * ClasspathMultiDirectory[]
 * int			id
 * String		path(s)
 */
	writeSourceLocations(out, this.testSourceLocations);

/*
 * ClasspathLocation[]
 * int			id
 * String		path(s)
 */
	writeBinaryLocations(out, this.testBinaryLocations, this.testSourceLocations);

/*
 * Structural build numbers table
 * String		prereq project name
 * int			last structural build number
*/
	out.writeInt(this.structuralBuildTimes.size());
	for (Entry<String, Long> entry: this.structuralBuildTimes.entrySet()) {
		out.writeStringUsingDictionary(entry.getKey());
		out.writeLong(entry.getValue().longValue());
	}

/*
 * String[]	Interned type locators
 */
	out.writeInt(this.references.size());
	Map<String, Integer> internedTypeLocators = new HashMap<>(this.references.size());
	for (String key : this.references.keySet()) {
		out.writeStringUsingLast(key);
		internedTypeLocators.put(key, internedTypeLocators.size());
	}

	this.typeLocators.write(out, internedTypeLocators);

/*
 * char[][]	Interned root names
 * char[][][]	Interned qualified names
 * char[][]	Interned simple names
 */
	Map<CharArray, Integer> internedRootNames = new HashMap<>();
	Map<CharCharArray, Integer> internedQualifiedNames = new HashMap<>();
	Map<CharArray, Integer> internedSimpleNames = new HashMap<>();
	for (ReferenceCollection collection : this.references.values()) {
		for (char[] rName : collection.rootReferences) {
			// remember the names have been interned
			internedRootNames.putIfAbsent(new CharArray(rName), internedRootNames.size());
		}
		for (char[][] qName : collection.qualifiedNameReferences) {
			// remember the names have been interned
			if (internedQualifiedNames.putIfAbsent(new CharCharArray(qName), internedQualifiedNames.size()) == null) {
				for (char[] sName : qName) {
					// remember the names have been interned
					internedSimpleNames.putIfAbsent(new CharArray(sName), internedSimpleNames.size());
				}
			}
		}
		for (char[] sName : collection.simpleNameReferences) {
			// remember the names have been interned
			internedSimpleNames.putIfAbsent(new CharArray(sName), internedSimpleNames.size());
		}
	}
	char[][] internedArray = new char[internedRootNames.size()][];
	for (Entry<CharArray, Integer> entry: internedRootNames.entrySet()) {
			int index = entry.getValue().intValue();
			internedArray[index] = entry.getKey().getKey();
	}
	writeNames(internedArray, out);
	// now write the interned simple names
	internedArray = new char[internedSimpleNames.size()][];
	for (Entry<CharArray, Integer> entry: internedSimpleNames.entrySet()) {
		int index = entry.getValue().intValue();
		internedArray[index] = entry.getKey().getKey();
	}
	writeNames(internedArray, out);
	// now write the interned qualified names as arrays of interned simple names
	char[][][] internedQArray = new char[internedQualifiedNames.size()][][];
	for (Entry<CharCharArray, Integer> entry: internedQualifiedNames.entrySet()) {
		int index = entry.getValue().intValue();
		internedQArray[index] = entry.getKey().getKey();
	}
	out.writeInt(internedQArray.length);
	for (char[][] qName : internedQArray) {
		int qLength = qName.length;
		out.writeInt(qLength);
		for (char[] qN:qName) {
			Integer index = internedSimpleNames.get(new CharArray(qN));
			out.writeIntInRange(index.intValue(), internedSimpleNames.size());
		}
	}

/*
 * References table
 * int		interned locator id
 * ReferenceCollection
*/
	out.writeInt(this.references.size());
	for (Entry<String, ReferenceCollection> entry : this.references.entrySet()) {
		String key = entry.getKey();
		Integer index = internedTypeLocators.get(key);
		out.writeInt(index.intValue());
		ReferenceCollection collection = entry.getValue();
		if (collection instanceof AdditionalTypeCollection) {
			out.writeByte(1);
			AdditionalTypeCollection atc = (AdditionalTypeCollection) collection;
			writeNames(atc.definedTypeNames, out);
		} else {
			out.writeByte(2);
		}
		char[][][] qNames = collection.qualifiedNameReferences;
		int qLength = qNames.length;
		out.writeInt(qLength);
		for (char[][] qName:qNames) {
			Integer i = internedQualifiedNames.get(new CharCharArray(qName));
			out.writeIntInRange(i.intValue(), internedQualifiedNames.size());
		}
		char[][] sNames = collection.simpleNameReferences;
		int sLength = sNames.length;
		out.writeInt(sLength);
		for (char[] sName: sNames) {
			Integer i = internedSimpleNames.get(new CharArray(sName));
			out.writeIntInRange(i.intValue(), internedSimpleNames.size());
		}
		char[][] rNames = collection.rootReferences;
		int rLength = rNames.length;
		out.writeInt(rLength);
		for (char[] rName: rNames) {
			Integer i = internedRootNames.get(new CharArray(rName));
			out.writeIntInRange(i.intValue(), internedRootNames.size());
		}
	}
}

private void writeSourceLocations(CompressedWriter out, ClasspathMultiDirectory[] srcLocations) throws IOException {
	out.writeInt(srcLocations.length);
	for (ClasspathMultiDirectory md: srcLocations) {
		out.writeStringUsingDictionary(md.sourceFolder.getProjectRelativePath().toString());
		out.writeStringUsingDictionary(md.binaryFolder.getProjectRelativePath().toString());
		writeNames(md.inclusionPatterns, out);
		writeNames(md.exclusionPatterns, out);
		out.writeBoolean(md.ignoreOptionalProblems);
		writeNullablePath(md.externalAnnotationPath, out);
		out.writeBoolean(md.hasIndependentOutputFolder);
		out.writeInt(md.release);
	}
}

private void writeBinaryLocations(CompressedWriter out, ClasspathLocation[] locations, ClasspathMultiDirectory[] srcLocations)
		throws IOException {
	/*
	 * ClasspathLocation[]
	 * int			id
	 * String		path(s)
	 * String       module updates
	*/

	out.writeInt(locations.length);
	for (ClasspathLocation c : locations) {
		if (c instanceof ClasspathMultiDirectory) {
			out.writeByte(SOURCE_FOLDER);
			for (int j = 0, m = srcLocations.length; j < m; j++) {
				if (srcLocations[j] == c) {
					out.writeInt(j);
					//continue next;
				}
			}
		} else if (c instanceof ClasspathDirectory) {
			out.writeByte(BINARY_FOLDER);
			ClasspathDirectory cd = (ClasspathDirectory) c;
			out.writeStringUsingDictionary(cd.binaryFolder.getFullPath().toString());
			out.writeBoolean(cd.isOutputFolder);
			writeRestriction(cd.accessRuleSet, out);
			writeNullablePath(cd.externalAnnotationPath, out);
			out.writeBoolean(cd.isOnModulePath);
		} else if (c instanceof ClasspathJar) {
			ClasspathJar jar = (ClasspathJar) c;
			if (jar.resource == null) {
				out.writeByte(EXTERNAL_JAR);
				out.writeStringUsingDictionary(jar.zipFilename);
				out.writeLong(jar.lastModified());
			} else {
				out.writeByte(INTERNAL_JAR);
				out.writeStringUsingDictionary(jar.resource.getFullPath().toString());
			}
			writeRestriction(jar.accessRuleSet, out);
			writeNullablePath(jar.externalAnnotationPath, out);
			out.writeBoolean(jar.isOnModulePath);
			out.writeStringUsingDictionary(jar.compliance == null ? "" : jar.compliance); //$NON-NLS-1$

		} else if (c instanceof ClasspathJrt) {
			ClasspathJrt jrt = (ClasspathJrt) c;
			out.writeByte(EXTERNAL_JAR);
			out.writeStringUsingDictionary(jrt.zipFilename);
			writeRestriction(jrt.accessRuleSet, out);
			writeNullablePath(jrt.externalAnnotationPath, out);
			if (jrt instanceof ClasspathJrtWithReleaseOption)
				out.writeStringUsingDictionary(((ClasspathJrtWithReleaseOption) jrt).release);
			else
				out.writeStringUsingDictionary(""); //$NON-NLS-1$
		}
		char[] patchName = c.patchModuleName == null ? CharOperation.NO_CHAR : c.patchModuleName.toCharArray();
		out.writeChars(patchName);
		if (c.limitModuleNames != null) {
			out.writeInt(c.limitModuleNames.size());
			for (String name : c.limitModuleNames) {
				out.writeStringUsingDictionary(name);
			}
		} else {
			out.writeInt(0);
		}
		if (c.updates != null) {
			List<Consumer<IUpdatableModule>> pu = c.updates.getList(UpdateKind.PACKAGE, false);
			if (pu != null) {
				Map<String, List<AddExports>> map = pu.stream().filter(AddExports.class::isInstance)
						.map(AddExports.class::cast)
						.collect(Collectors.groupingBy(addExport -> CharOperation.charToString(addExport.getName())));
				out.writeInt(map.size());
				map.entrySet().stream().forEach(entry -> {
					String pkgName = entry.getKey();
					try {
						out.writeChars(pkgName.toCharArray());
						char[][] targetModules = entry.getValue().stream()
								.map(AddExports::getTargetModules).filter(targets -> targets != null)
								.reduce(CharOperation::arrayConcat).orElse(null);
						writeNames(targetModules, out);
					} catch (IOException e) {
						// ignore
					}

				});
			} else {
				out.writeInt(0);
			}
			List<Consumer<IUpdatableModule>> mu = c.updates.getList(UpdateKind.MODULE, false);
			if (mu != null) {
				// TODO, here we cannot handle MODULE_MAIN_CLASS nor MODULE_PACKAGES (ModuleUpdater stores a lambda), should we?
				List<AddReads> allReads = mu.stream().filter(AddReads.class::isInstance).map(AddReads.class::cast).collect(Collectors.toList());
				out.writeInt(allReads.size());
				for (AddReads m : allReads) {
					out.writeChars(m.getTarget());
				}
			} else {
				out.writeInt(0);
			}
		} else {
			out.writeInt(0);
			out.writeInt(0);
		}
	}
}

private void writeNames(char[][] names, CompressedWriter out) throws IOException {
	int length = names == null ? 0 : names.length;
	out.writeInt(length);
	if (names != null) {
		for (char[] name : names) {
			out.writeChars(name);
		}
	}
}

private static char[][] readNames(CompressedReader in) throws IOException {
	int length = in.readInt();
	char[][] names = new char[length][];
	for (int i = 0; i < length; i++)
		names[i] = in.readChars();
	return names;
}

private void writeNullablePath(String path, CompressedWriter out) throws IOException {
	out.writeStringUsingDictionary(path != null ? path : ""); //$NON-NLS-1$
}

private void writeRestriction(AccessRuleSet accessRuleSet, CompressedWriter out) throws IOException {
	if (accessRuleSet == null) {
		out.writeInt(0);
	} else {
		AccessRule[] accessRules = accessRuleSet.getAccessRules();
		int length = accessRules.length;
		out.writeInt(length);
		if (length != 0) {
			for (AccessRule accessRule : accessRules) {
				out.writeCharsUsingLast(accessRule.pattern);
				out.writeIntWithHint(accessRule.problemId, PROBLEM_IDS);
			}
			out.writeByte(accessRuleSet.classpathEntryType);
			out.writeStringUsingDictionary(accessRuleSet.classpathEntryName);
		}
	}
}

/**
 * Returns a string representation of the receiver.
 */
@Override
public String toString() {
	return "State for " + this.javaProjectName //$NON-NLS-1$
		+ " (#" + this.buildNumber //$NON-NLS-1$
			+ " @ " + new Date(this.lastStructuralBuildTime) //$NON-NLS-1$
				+ ")"; //$NON-NLS-1$
}

}
