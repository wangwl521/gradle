/*
 * Copyright 2017 the original author or authors.
 *
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

package org.gradle.api.internal.changedetection.state;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.gradle.api.NonNullApi;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.changedetection.state.mirror.FileSnapshotHelper;
import org.gradle.api.internal.changedetection.state.mirror.HierarchicalFileTreeVisitor;
import org.gradle.api.internal.changedetection.state.mirror.ImmutablePhysicalDirectorySnapshot;
import org.gradle.api.internal.changedetection.state.mirror.MirrorUpdatingDirectoryWalker;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalFileTreeVisitor;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalSnapshotBackedVisitableTree;
import org.gradle.api.internal.changedetection.state.mirror.VisitableDirectoryTree;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.file.collections.DirectoryFileTree;
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory;
import org.gradle.api.internal.file.collections.ImmutableFileCollection;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.cache.internal.ProducerGuard;
import org.gradle.caching.internal.BuildCacheHasher;
import org.gradle.caching.internal.DefaultBuildCacheHasher;
import org.gradle.internal.Factory;
import org.gradle.internal.file.FileMetadataSnapshot;
import org.gradle.internal.file.FileType;
import org.gradle.internal.hash.FileHasher;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.normalization.internal.InputNormalizationStrategy;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Deque;
import java.util.List;

/**
 * Responsible for snapshotting various aspects of the file system.
 *
 * Currently logic and state are split between this class and {@link FileSystemMirror}, as there are several instances of this class created in different scopes. This introduces some inefficiencies
 * that could be improved by shuffling this relationship around.
 *
 * The implementations attempt to do 2 things: avoid doing the same work in parallel (e.g. scanning the same directory from multiple threads, and avoid doing work where the result is almost certainly
 * the same as before (e.g. don't scan the output directory of a task a bunch of times).
 *
 * The implementations are currently intentionally very, very simple, and so there are a number of ways in which they can be made much more efficient. This can happen over time.
 */
@SuppressWarnings("Since15")
@NonNullApi
public class DefaultFileSystemSnapshotter implements FileSystemSnapshotter {
    private static final Joiner PATH_JOINER = Joiner.on(File.separatorChar);
    private final FileHasher hasher;
    private final StringInterner stringInterner;
    private final FileSystem fileSystem;
    private final FileSystemMirror fileSystemMirror;
    private final ProducerGuard<String> producingSelfSnapshots = ProducerGuard.striped();
    private final ProducerGuard<String> producingTrees = ProducerGuard.striped();
    private final ProducerGuard<String> producingAllSnapshots = ProducerGuard.striped();
    private final DefaultGenericFileCollectionSnapshotter snapshotter;
    private final MirrorUpdatingDirectoryWalker mirrorUpdatingDirectoryWalker;

    public DefaultFileSystemSnapshotter(FileHasher hasher, StringInterner stringInterner, FileSystem fileSystem, DirectoryFileTreeFactory directoryFileTreeFactory, FileSystemMirror fileSystemMirror) {
        this.hasher = hasher;
        this.stringInterner = stringInterner;
        this.fileSystem = fileSystem;
        this.fileSystemMirror = fileSystemMirror;
        this.snapshotter = new DefaultGenericFileCollectionSnapshotter(stringInterner, directoryFileTreeFactory, this);
        this.mirrorUpdatingDirectoryWalker = new MirrorUpdatingDirectoryWalker(hasher);
    }

    @Override
    public boolean exists(File file) {
        FileSnapshot snapshot = fileSystemMirror.getFile(file.getAbsolutePath());
        if (snapshot != null) {
            return snapshot.getType() != FileType.Missing;
        }
        return file.exists();
    }

    @Override
    public FileSnapshot snapshotSelf(final File file) {
        // Could potentially coordinate with a thread that is snapshotting an overlapping directory tree
        final String path = file.getAbsolutePath();
        return producingSelfSnapshots.guardByKey(path, new Factory<FileSnapshot>() {
            @Override
            public FileSnapshot create() {
                FileSnapshot snapshot = fileSystemMirror.getFile(path);
                if (snapshot == null) {
                    snapshot = calculateDetails(file);
                    fileSystemMirror.putFile(snapshot);
                }
                return snapshot;
            }
        });
    }

    @Override
    public Snapshot snapshotAll(final File file) {
        // Could potentially coordinate with a thread that is snapshotting an overlapping directory tree
        final String path = file.getAbsolutePath();
        return producingAllSnapshots.guardByKey(path, new Factory<Snapshot>() {
            @Override
            public Snapshot create() {
                Snapshot snapshot = fileSystemMirror.getContent(path);
                if (snapshot == null) {
                    FileCollectionSnapshot fileCollectionSnapshot = snapshotter.snapshot(ImmutableFileCollection.of(file), InputPathNormalizationStrategy.ABSOLUTE, InputNormalizationStrategy.NOT_CONFIGURED);
                    DefaultBuildCacheHasher hasher = new DefaultBuildCacheHasher();
                    fileCollectionSnapshot.appendToHasher(hasher);
                    HashCode hashCode = hasher.hash();
                    snapshot = new HashBackedSnapshot(hashCode);
                    String internedPath = internPath(file);
                    fileSystemMirror.putContent(internedPath, snapshot);
                }
                return snapshot;
            }
        });
    }

    /*
     * For simplicity this only caches trees without includes/excludes. However, if it is asked
     * to snapshot a filtered tree, it will try to find a snapshot for the underlying
     * tree and filter it in memory instead of walking the file system again. This covers the
     * majority of cases, because all task outputs are put into the cache without filters
     * before any downstream task uses them.
     */
    @Override
    public VisitableDirectoryTree snapshotDirectoryTree(final DirectoryFileTree dirTree) {
        // Could potentially coordinate with a thread that is snapshotting an overlapping directory tree
        final String path = dirTree.getDir().getAbsolutePath();
        final PatternSet patterns = dirTree.getPatterns();

        VisitableDirectoryTree snapshot = fileSystemMirror.getDirectoryTree(path);
        if (snapshot != null) {
            return filterSnapshot(snapshot, patterns);
        }
        if (!patterns.isEmpty()) {
            return snapshotWithoutCaching(dirTree);
        }
        return producingTrees.guardByKey(path, new Factory<VisitableDirectoryTree>() {
            @Override
            public VisitableDirectoryTree create() {
                VisitableDirectoryTree snapshot = fileSystemMirror.getDirectoryTree(path);
                if (snapshot == null) {
                    return snapshotAndCache(dirTree);
                } else {
                    return snapshot;
                }
            }
        });
    }

    @Override
    public VisitableDirectoryTree snapshotTree(final FileTreeInternal tree) {
        return new VisitableDirectoryTree() {
            @Override
            public void visit(final PhysicalFileTreeVisitor visitor) {
                tree.visitTreeOrBackingFile(new FileVisitor() {
                    @Override
                    public void visitDir(FileVisitDetails dirDetails) {
                        visitor.visit(dirDetails.getFile().toPath(), internPath(dirDetails.getFile()), dirDetails.getName(), RelativePath.EMPTY_ROOT, DirContentSnapshot.INSTANCE);
                    }

                    @Override
                    public void visitFile(FileVisitDetails fileDetails) {
                        String basePath = internPath(fileDetails.getFile());
                        String relativePath = PATH_JOINER.join(fileDetails.getRelativePath());
                        if (basePath.endsWith(relativePath) && (basePath.charAt(basePath.length() - relativePath.length() - 1) == File.separatorChar)) {
                            basePath =  stringInterner.intern(basePath.substring(0, basePath.length() - relativePath.length() - 1));
                            visitor.visit(fileDetails.getFile().toPath(), basePath, fileDetails.getName(), fileDetails.getRelativePath(), fileSnapshot(fileDetails));
                        } else {
                            visitor.visit(fileDetails.getFile().toPath(), basePath, fileDetails.getName(), RelativePath.EMPTY_ROOT, fileSnapshot(fileDetails));
                        }
                    }
                });
            }

            @Override
            public void accept(HierarchicalFileTreeVisitor visitor) {
                throw new UnsupportedOperationException("Arbitrary FileTreeInternals are not supported yet");
            }
        };
    }

    @SuppressWarnings("Since15")
    private VisitableDirectoryTree snapshotAndCache(DirectoryFileTree directoryTree) {
        final FileSnapshot fileSnapshot = snapshotSelf(directoryTree.getDir());
        final String path = internPath(directoryTree.getDir());
        if (fileSnapshot.getType() == FileType.Missing) {
            return PhysicalSnapshotBackedVisitableTree.EMPTY;
        }
        if (fileSnapshot.getType() == FileType.RegularFile) {
            return new VisitableDirectoryTree() {
                @Override
                public void visit(PhysicalFileTreeVisitor visitor) {
                    visitor.visit(Paths.get(fileSnapshot.getPath()), path, fileSnapshot.getName(), ImmutableList.of(fileSnapshot.getName()), fileSnapshot.getContent());
                }

                @Override
                public void accept(HierarchicalFileTreeVisitor visitor) {
                    visitor.visit(Paths.get(fileSnapshot.getPath()), fileSnapshot.getName(), fileSnapshot.getContent());
                }
            };
        }
        Path rootPath = directoryTree.getDir().toPath();
        ImmutablePhysicalDirectorySnapshot rootDirectory = mirrorUpdatingDirectoryWalker.walkDir(rootPath);
        VisitableDirectoryTree visitableDir = new PhysicalSnapshotBackedVisitableTree(path, rootDirectory);
        fileSystemMirror.putDirectory(path, visitableDir);
        return visitableDir;
    }

    /*
     * We don't reuse code between this and #snapshotAndCache, because we can avoid
     * some defensive copying when the result won't be shared.
     */
    private VisitableDirectoryTree snapshotWithoutCaching(DirectoryFileTree directoryTree) {
        String path = directoryTree.getDir().getAbsolutePath();
        List<FileSnapshot> elements = Lists.newArrayList();
        directoryTree.visit(new FileVisitorImpl(elements));
        return new DirectoryTreeDetails(path, elements);
    }

    private VisitableDirectoryTree filterSnapshot(final VisitableDirectoryTree snapshot, PatternSet patterns) {
        if (patterns.isEmpty()) {
            return snapshot;
        }
        final Spec<FileTreeElement> spec = patterns.getAsSpec();
        return new VisitableDirectoryTree() {
            @Override
            public void visit(final PhysicalFileTreeVisitor visitor) {
                snapshot.visit(new PhysicalFileTreeVisitor() {
                    @Override
                    public void visit(Path path, String basePath, String name, Iterable<String> relativePath, FileContentSnapshot content) {
                        if (spec.isSatisfiedBy(new SnapshotFileTreeElement(FileSnapshotHelper.create(path, relativePath, content), fileSystem))) {
                            visitor.visit(path, basePath, name, relativePath, content);
                        }
                    }
                });
            }

            @Override
            public void accept(final HierarchicalFileTreeVisitor visitor) {
                snapshot.accept(new HierarchicalFileTreeVisitor() {
                    private Deque<String> relativePath = Lists.newLinkedList();

                    @Override
                    public void preVisitDirectory(Path path, String name) {
                        relativePath.addLast(name);
                        visitor.preVisitDirectory(path, name);
                    }

                    @Override
                    public void visit(Path path, String name, FileContentSnapshot content) {
                        if (spec.isSatisfiedBy(new SnapshotFileTreeElement(FileSnapshotHelper.create(path, relativePath, content), fileSystem))) {
                            visitor.visit(path, name, content);
                        }
                    }

                    @Override
                    public void postVisitDirectory() {
                        relativePath.removeLast();
                        visitor.postVisitDirectory();
                    }
                });
            }
        };
    }

    private String internPath(File file) {
        return stringInterner.intern(file.getAbsolutePath());
    }

    private FileSnapshot calculateDetails(File file) {
        String path = internPath(file);
        FileMetadataSnapshot stat = fileSystem.stat(file);
        switch (stat.getType()) {
            case Missing:
                return new MissingFileSnapshot(path, new RelativePath(true, file.getName()));
            case Directory:
                return new DirectoryFileSnapshot(path, new RelativePath(false, file.getName()), true);
            case RegularFile:
                return new RegularFileSnapshot(path, new RelativePath(true, file.getName()), true, fileSnapshot(file, stat));
            default:
                throw new IllegalArgumentException("Unrecognized file type: " + stat.getType());
        }
    }

    private FileHashSnapshot fileSnapshot(FileTreeElement fileDetails) {
        return new FileHashSnapshot(hasher.hash(fileDetails), fileDetails.getLastModified());
    }

    private FileHashSnapshot fileSnapshot(File file, FileMetadataSnapshot fileDetails) {
        return new FileHashSnapshot(hasher.hash(file, fileDetails), fileDetails.getLastModified());
    }

    private static class HashBackedSnapshot implements Snapshot {
        private final HashCode hashCode;

        HashBackedSnapshot(HashCode hashCode) {
            this.hashCode = hashCode;
        }

        @Override
        public void appendToHasher(BuildCacheHasher hasher) {
            hasher.putHash(hashCode);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            HashBackedSnapshot that = (HashBackedSnapshot) o;

            return hashCode.equals(that.hashCode);
        }

        @Override
        public int hashCode() {
            return hashCode.hashCode();
        }
    }

    private class FileVisitorImpl implements FileVisitor {
        private final List<FileSnapshot> fileTreeElements;

        FileVisitorImpl(List<FileSnapshot> fileTreeElements) {
            this.fileTreeElements = fileTreeElements;
        }

        @Override
        public void visitDir(FileVisitDetails dirDetails) {
            fileTreeElements.add(new DirectoryFileSnapshot(internPath(dirDetails.getFile()), dirDetails.getRelativePath(), false));
        }

        @Override
        public void visitFile(FileVisitDetails fileDetails) {
            fileTreeElements.add(new RegularFileSnapshot(internPath(fileDetails.getFile()), fileDetails.getRelativePath(), false, fileSnapshot(fileDetails)));
        }
    }
}
