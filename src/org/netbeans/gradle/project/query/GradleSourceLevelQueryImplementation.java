package org.netbeans.gradle.project.query;

import java.util.regex.Pattern;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.ProjectInitListener;
import org.netbeans.spi.java.queries.SourceLevelQueryImplementation2;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.ChangeSupport;

public final class GradleSourceLevelQueryImplementation
implements
        SourceLevelQueryImplementation2,
        ProjectInitListener {

    private final NbGradleProject project;
    private final ChangeSupport changes;

    public GradleSourceLevelQueryImplementation(NbGradleProject project) {
        this.project = project;
        this.changes = new ChangeSupport(this);
    }

    @Override
    public void onInitProject() {
        project.getProperties().getSourceLevel().addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                changes.fireChange();
            }
        });
    }

    @Override
    public Result getSourceLevel(FileObject javaFile) {
        // Assume that every source file must reside in the project directory.
        if (FileUtil.getRelativePath(project.getProjectDirectory(), javaFile) == null) {
            return null;
        }

        return new Result() {
            @Override
            public String getSourceLevel() {
                return project.getProperties().getSourceLevel().getValue();
            }

            @Override
            public void addChangeListener(ChangeListener listener) {
                changes.addChangeListener(listener);
            }

            @Override
            public void removeChangeListener(ChangeListener listener) {
                changes.addChangeListener(listener);
            }
        };
    }
}
