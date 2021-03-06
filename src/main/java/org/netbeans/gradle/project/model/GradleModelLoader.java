package org.netbeans.gradle.project.model;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.gradle.tooling.BuildException;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProgressEvent;
import org.gradle.tooling.ProgressListener;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.UnknownModelException;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.idea.IdeaContentRoot;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.NbStrings;
import org.netbeans.gradle.project.ProjectExtensionRef;
import org.netbeans.gradle.project.api.entry.GradleProjectExtension;
import org.netbeans.gradle.project.java.model.NbSourceRoot;
import org.netbeans.gradle.project.properties.GlobalGradleSettings;
import org.netbeans.gradle.project.properties.GradleLocation;
import org.netbeans.gradle.project.properties.ProjectProperties;
import org.netbeans.gradle.project.tasks.DaemonTask;
import org.netbeans.gradle.project.tasks.GradleDaemonManager;
import org.netbeans.gradle.project.tasks.GradleTasks;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.RequestProcessor;
import org.openide.util.lookup.Lookups;

public final class GradleModelLoader {
    private static final Logger LOGGER = Logger.getLogger(GradleModelLoader.class.getName());

    private static final RequestProcessor PROJECT_LOADER
            = new RequestProcessor("Gradle-Project-Loader", 1, true);

    private static final GradleModelCache CACHE = new GradleModelCache(100);
    private static final ModelLoadSupport LISTENERS = new ModelLoadSupport();

    static {
        CACHE.setMaxCapacity(GlobalGradleSettings.getProjectCacheSize().getValue());
        GlobalGradleSettings.getProjectCacheSize().addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                CACHE.setMaxCapacity(GlobalGradleSettings.getProjectCacheSize().getValue());
            }
        });
    }

    public static void addModelLoadedListener(ModelLoadListener listener) {
        LISTENERS.addListener(listener);
    }

    public static void removeModelLoadedListener(ModelLoadListener listener) {
        LISTENERS.removeListener(listener);
    }

    public static GradleConnector createGradleConnector(final NbGradleProject project) {
        final GradleConnector result = GradleConnector.newConnector();

        File gradleUserHome = GlobalGradleSettings.getGradleUserHomeDir().getValue();
        if (gradleUserHome != null) {
            result.useGradleUserHomeDir(gradleUserHome);
        }

        GradleLocation gradleLocation;
        ProjectProperties projectProperties = project.tryGetLoadedProperties();
        if (projectProperties == null) {
            LOGGER.warning("Could not wait for retrieving the project properties. Using the globally defined one");
            gradleLocation = GlobalGradleSettings.getGradleHome().getValue();
        }
        else {
            gradleLocation = projectProperties.getGradleLocation().getValue();
        }

        gradleLocation.applyLocation(new GradleLocation.Applier() {
            @Override
            public void applyVersion(String versionStr) {
                result.useGradleVersion(versionStr);
            }

            @Override
            public void applyDirectory(File gradleHome) {
                result.useInstallation(gradleHome);
            }

            @Override
            public void applyDistribution(URI location) {
                result.useDistribution(location);
            }

            @Override
            public void applyDefault() {
            }
        });

        return result;
    }

    private static NbGradleModel tryGetFromCache(File projectDir) {
        File settingsFile = NbGradleModel.findSettingsGradle(projectDir);
        if (settingsFile == null) {
            LOGGER.log(Level.WARNING, "Settings file of the project disappeared: {0}", projectDir);
            return null;
        }

        NbGradleModel result = projectDir != null
                ? CACHE.tryGet(projectDir, settingsFile)
                : null;

        if (result != null && result.isDirty()) {
            result = null;
        }
        return result;
    }

    public static void fetchModel(
            final NbGradleProject project,
            final ModelRetrievedListener listener) {
        fetchModel(project, false, listener);
    }

    public static void fetchModel(
            final NbGradleProject project,
            final boolean mayFetchFromCache,
            final ModelRetrievedListener listener) {
        if (project == null) throw new NullPointerException("project");
        if (listener == null) throw new NullPointerException("listener");

        final File projectDir = project.getProjectDirectoryAsFile();
        String caption = NbStrings.getLoadingProjectText(project.getDisplayName());
        GradleDaemonManager.submitGradleTask(PROJECT_LOADER, caption, new DaemonTask() {
            @Override
            public void run(ProgressHandle progress) {
                NbGradleModel model = null;
                Throwable error = null;
                try {
                    if (mayFetchFromCache) {
                        model = tryGetFromCache(projectDir);
                    }
                    if (model == null || model.hasUnloadedExtensions(project)) {
                        model = loadModelWithProgress(project, progress, model);
                    }
                } catch (IOException ex) {
                    error = ex;
                } catch (BuildException ex) {
                    error = ex;
                } catch (GradleConnectionException ex) {
                    error = ex;
                } finally {
                    listener.onComplete(model, error);
                }
            }
        }, true, GradleTasks.projectTaskCompleteListener(project));
    }

    public static File getScriptJavaHome(NbGradleProject project) {
        if (project == null) throw new NullPointerException("project");

        JavaPlatform platform = project.getProperties().getScriptPlatform().getValue();
        FileObject jdkHomeObj = platform != null
                ? GlobalGradleSettings.getHomeFolder(platform)
                : null;

        if (jdkHomeObj != null) {
            // This is necessary for unit test code because JavaPlatform returns
            // the jre inside the JDK.
            if ("jre".equals(jdkHomeObj.getNameExt().toLowerCase(Locale.ROOT))) {
                FileObject parent = jdkHomeObj.getParent();
                if (parent != null) {
                    jdkHomeObj = parent;
                }
            }
        }

        return jdkHomeObj != null ? FileUtil.toFile(jdkHomeObj) : null;
    }

    private static <T> T getRawModelWithProgress(
            NbGradleProject project,
            final ProgressHandle progress,
            ProjectConnection projectConnection,
            Class<T> model) {
        return getModelWithProgress(project, progress, projectConnection, model);
    }

    private static <T> T getModelWithProgress(
            NbGradleProject project,
            final ProgressHandle progress,
            ProjectConnection projectConnection,
            Class<T> model) {
        ModelBuilder<T> builder = projectConnection.model(model);

        File jdkHome = getScriptJavaHome(project);
        if (jdkHome != null && !jdkHome.getPath().isEmpty()) {
            builder.setJavaHome(jdkHome);
        }

        List<String> globalJvmArgs = GlobalGradleSettings.getGradleJvmArgs().getValue();

        if (globalJvmArgs != null && !globalJvmArgs.isEmpty()) {
            builder.setJvmArguments(globalJvmArgs.toArray(new String[0]));
        }

        builder.addProgressListener(new ProgressListener() {
            @Override
            public void statusChanged(ProgressEvent pe) {
                progress.progress(pe.getDescription());
            }
        });

        return builder.get();
    }

    public static File tryGetModuleDir(IdeaModule module) {
        DomainObjectSet<? extends IdeaContentRoot> contentRoots = module.getContentRoots();
        return contentRoots.isEmpty() ? null : contentRoots.getAt(0).getRootDirectory();
    }

    public static IdeaModule tryFindMainModule(File projectDir, IdeaProject ideaModel) {
        for (IdeaModule module: ideaModel.getModules()) {
            File moduleDir = tryGetModuleDir(module);
            if (moduleDir != null && moduleDir.equals(projectDir)) {
                return module;
            }
        }
        return null;
    }

    private static void introduceLoadedModel(NbGradleModel model) {
        CACHE.addToCache(model);
        LISTENERS.fireEvent(model);
    }

    private static void getExtensionModels(
            NbGradleProject project,
            ProgressHandle progress,
            ProjectConnection projectConnection,
            NbGradleModel result) {

        Lookup allModels = result.getAllModels();
        for (ProjectExtensionRef extensionRef: result.getUnloadedExtensions(project)) {
            GradleProjectExtension extension = extensionRef.getExtension();
            List<Object> extensionModels = new LinkedList<Object>();

            for (List<Class<?>> modelRequest: extension.getGradleModels()) {
                for (Class<?> modelClass: modelRequest) {
                    try {
                        Object model = allModels.lookup(modelClass);
                        if (model == null) {
                            model = getRawModelWithProgress(
                                    project, progress, projectConnection, modelClass);
                        }
                        extensionModels.add(model);
                        break;
                    } catch (UnknownModelException ex) {
                        Throwable loggedException = LOGGER.isLoggable(Level.FINE)
                                ? ex
                                : null;
                        LOGGER.log(Level.INFO, "Cannot find model " + modelClass.getName(), loggedException);
                    }
                }
            }

            result.setModelsForExtension(extensionRef, Lookups.fixed(extensionModels.toArray()));
        }
    }

    public static List<IdeaModule> getChildModules(IdeaModule module) {
        Collection<? extends GradleProject> children = module.getGradleProject().getChildren();
        Set<String> childrenPaths = new HashSet<String>(2 * children.size());
        for (GradleProject child: children) {
            childrenPaths.add(child.getPath());
        }

        List<IdeaModule> result = new LinkedList<IdeaModule>();
        for (IdeaModule candidateChild: module.getProject().getModules()) {
            if (childrenPaths.contains(candidateChild.getGradleProject().getPath())) {
                result.add(candidateChild);
            }
        }
        return result;
    }

    private static GradleProjectInfo tryCreateProjectTreeFromIdea(IdeaModule module) {
        File moduleDir = tryGetModuleDir(module);
        if (moduleDir == null) {
            return null;
        }

        int expectedChildCount = module.getGradleProject().getChildren().size();
        List<GradleProjectInfo> children = new ArrayList<GradleProjectInfo>(expectedChildCount);
        for (IdeaModule child: getChildModules(module)) {
            GradleProjectInfo childInfo = tryCreateProjectTreeFromIdea(child);
            if (childInfo != null) {
                children.add(childInfo);
            }
        }

        return new GradleProjectInfo(module.getGradleProject(), moduleDir, children);
    }

    private static NbGradleModel loadMainModelFromIdeaModule(IdeaModule ideaModule) throws IOException {
        GradleProjectInfo projectInfo = tryCreateProjectTreeFromIdea(ideaModule);
        if (projectInfo == null) {
            throw new IOException("Failed to create project info for project: " + ideaModule.getName());
        }

        NbGradleModel result = new NbGradleModel(projectInfo, projectInfo.getProjectDir());
        result.setMainModels(Lookups.singleton(ideaModule.getProject()));
        return result;
    }

    private static NbGradleModel loadMainModel(
            NbGradleProject project,
            ProgressHandle progress,
            ProjectConnection projectConnection,
            List<NbGradleModel> deduced) throws IOException {

        IdeaProject ideaProject
                = getRawModelWithProgress(project, progress, projectConnection, IdeaProject.class);

        File projectDir = project.getProjectDirectoryAsFile();
        IdeaModule mainModule = tryFindMainModule(projectDir, ideaProject);
        if (mainModule == null) {
            throw new IOException("Failed to find idea module for project: " + project.getDisplayName());
        }

        for (IdeaModule otherModule: ideaProject.getModules()) {
            // This comparison is not strictly necessary but there is no reason
            // to reparse the main project.
            if (otherModule != mainModule) {
                deduced.add(loadMainModelFromIdeaModule(otherModule));
            }
        }

        return loadMainModelFromIdeaModule(mainModule);
    }

    private static void introduceProjects(
            NbGradleProject project,
            List<NbGradleModel> otherModels,
            NbGradleModel mainModel) {

        Map<File, NbGradleModel> projects = new HashMap<File, NbGradleModel>(2 * otherModels.size() + 1);
        for (NbGradleModel otherModel: otherModels) {
            projects.put(otherModel.getProjectDir(), otherModel);
        }
        projects.put(mainModel.getProjectDir(), mainModel);

        for (ProjectExtensionRef extensionRef: project.getExtensionRefs()) {
            GradleProjectExtension extension = extensionRef.getExtension();
            Map<File, Lookup> deduced
                    = extension.deduceModelsForProjects(mainModel.getModelsForExtension(extensionRef));

            for (Map.Entry<File, Lookup> entry: deduced.entrySet()) {
                NbGradleModel deducedModel = projects.get(entry.getKey());
                if (deducedModel != null) {
                    deducedModel.setModelsForExtension(extensionRef, entry.getValue());
                }
            }
        }

        for (NbGradleModel model: projects.values()) {
            introduceLoadedModel(model);
        }
    }

    private static NbGradleModel loadModelWithProgress(
            NbGradleProject project,
            ProgressHandle progress,
            NbGradleModel proposedModel) throws IOException {
        File projectDir = project.getProjectDirectoryAsFile();

        LOGGER.log(Level.INFO, "Loading Gradle project from directory: {0}", projectDir);

        List<NbGradleModel> otherModels = new LinkedList<NbGradleModel>();
        NbGradleModel result = proposedModel;

        GradleConnector gradleConnector = createGradleConnector(project);
        gradleConnector.forProjectDirectory(projectDir);
        ProjectConnection projectConnection = null;
        try {
            projectConnection = gradleConnector.connect();

            // TODO: We should fill otherModels from result if result is not
            //   null. This could be done if NbGradleModel could store all
            //   projects it could preparse.
            if (result == null) {
                result = loadMainModel(project, progress, projectConnection, otherModels);
            }

            getExtensionModels(project, progress, projectConnection, result);
        } finally {
            if (projectConnection != null) {
                projectConnection.close();
            }
        }

        progress.progress(NbStrings.getParsingModel());

        introduceProjects(project, otherModels, result);

        return result;
    }

    public static NbGradleModel createEmptyModel(File projectDir) {
        return createEmptyModel(projectDir, Lookup.EMPTY);
    }

    public static NbGradleModel createEmptyModel(File projectDir, Lookup extensionModels) {
        return new NbGradleModel(GradleProjectInfo.createEmpty(projectDir), projectDir);
    }

    private static <K, V> void addToMap(Map<K, List<V>> map, K key, V value) {
        List<V> valueList = map.get(key);
        if (valueList == null) {
            valueList = new LinkedList<V>();
            map.put(key, valueList);
        }
        valueList.add(value);
    }

    public static List<NbSourceRoot> nameSourceRoots(List<File> files) {
        // The common case
        if (files.size() == 1) {
            File file = files.get(0);
            return Collections.singletonList(new NbSourceRoot(file, file.getName()));
        }

        Map<String, List<FileWithBase>> nameToFile
                = new HashMap<String, List<FileWithBase>>(files.size() * 2 + 1);

        int fileIndex = 0;
        for (File file: files) {
            String name = file.getName();
            File parent = file.getParentFile();
            addToMap(nameToFile, name, new FileWithBase(fileIndex, parent, file));
            fileIndex++;
        }

        boolean didSomething;
        do {
            didSomething = false;

            List<Map.Entry<String, List<FileWithBase>>> currentEntries
                    = new ArrayList<Map.Entry<String, List<FileWithBase>>>(nameToFile.entrySet());
            for (Map.Entry<String, List<FileWithBase>> entry: currentEntries) {
                String entryName = entry.getKey();
                List<FileWithBase> entryFiles = entry.getValue();

                int renameableCount = 0;
                for (FileWithBase file: entryFiles) {
                    if (file.base != null) renameableCount++;
                }

                if (renameableCount > 1) {
                    nameToFile.remove(entryName);
                    for (FileWithBase file: entryFiles) {
                        if (file.base != null) {
                            String newName = file.base.getName() + '/' + entryName;
                            File newParent = file.base.getParentFile();
                            addToMap(nameToFile,
                                    newName,
                                    new FileWithBase(file.index, newParent, file.file));
                        }
                        else {
                            addToMap(nameToFile, entryName, file);
                        }
                    }
                    didSomething = true;
                }
            }
        } while (didSomething);

        NbSourceRoot[] result = new NbSourceRoot[fileIndex];
        for (Map.Entry<String, List<FileWithBase>> entry: nameToFile.entrySet()) {
            String entryName = entry.getKey();
            for (FileWithBase file: entry.getValue()) {
                result[file.index] = new NbSourceRoot(file.file, entryName);
            }
        }

        return Arrays.asList(result);
    }

    private static final class FileWithBase {
        public final int index;
        public final File base;
        public final File file;

        public FileWithBase(int index, File base, File file) {
            assert file != null;

            this.index = index;
            this.base = base;
            this.file = file;
        }
    }

    private GradleModelLoader() {
        throw new AssertionError();
    }
}
