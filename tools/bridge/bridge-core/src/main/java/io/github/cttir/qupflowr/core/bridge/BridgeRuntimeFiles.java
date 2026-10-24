/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Discovery files of one bridge instance below a user-private runtime directory:
 *
 * <pre>
 *   runtime_dir/                  (0700, owned by the current user)
 *     instances/                  (0700)
 *       &lt;instance_id&gt;.json       manifest (0600), no secrets
 *       &lt;instance_id&gt;.token      bearer token (0600)
 * </pre>
 *
 * Files are written to a temporary file in the same directory, flushed to disk and moved atomically
 * into place, so readers never observe partial content. Symbolic links for the directories are
 * refused. On file systems without POSIX attributes (Windows) permissions are not enforced here.
 */
public final class BridgeRuntimeFiles {

    /** Name of the instances subdirectory. */
    public static final String DIR_INSTANCES = "instances";

    private static final Set<PosixFilePermission> DIR_PERMS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS =
            PosixFilePermissions.fromString("rw-------");

    private final Path instancesDir;
    private final Path manifestPath;
    private final Path tokenPath;
    private final boolean posix;

    private BridgeRuntimeFiles(Path instancesDir, String instanceId, boolean posix) {
        this.instancesDir = instancesDir;
        this.manifestPath = instancesDir.resolve(instanceId + ".json");
        this.tokenPath = instancesDir.resolve(instanceId + ".token");
        this.posix = posix;
    }

    /**
     * Creates (if needed) and verifies the private runtime directories for an instance.
     *
     * @param runtimeDir base runtime directory
     * @param instanceId instance UUID in canonical lower-case form
     * @return helper for this instance
     * @throws IOException if a directory cannot be created, is a symbolic link, is not owned by the
     *     current user or cannot be made private
     */
    public static BridgeRuntimeFiles prepare(Path runtimeDir, String instanceId)
            throws IOException {
        Objects.requireNonNull(runtimeDir, "runtimeDir");
        if (!UUID.fromString(instanceId).toString().equals(instanceId)) {
            throw new IllegalArgumentException("Instance id must be a canonical UUID");
        }
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Path base = runtimeDir.toAbsolutePath().normalize();
        ensurePrivateDirectory(base, posix);
        Path instances = base.resolve(DIR_INSTANCES);
        ensurePrivateDirectory(instances, posix);
        return new BridgeRuntimeFiles(instances, instanceId, posix);
    }

    private static void ensurePrivateDirectory(Path dir, boolean posix) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new IOException("Runtime directory must not be a symbolic link: " + dir);
        }
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (posix) {
                Path parent = dir.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try {
                    Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(DIR_PERMS));
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // created concurrently; verified below
                }
            } else {
                Files.createDirectories(dir);
            }
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime path is not a directory: " + dir);
        }
        if (posix) {
            UserPrincipal owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS);
            if (!owner.equals(currentUser(dir))) {
                throw new IOException("Runtime directory is not owned by the current user: " + dir);
            }
            Files.setPosixFilePermissions(dir, DIR_PERMS);
        }
    }

    /**
     * The owner of a file this process creates, i.e. the effective user (not the user.name
     * property).
     */
    private static UserPrincipal currentUser(Path dir) throws IOException {
        Path probe =
                Files.createTempFile(
                        dir,
                        ".owner-probe-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(FILE_PERMS));
        try {
            return Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    /**
     * Writes the token file atomically with mode 0600.
     *
     * @param token token
     * @throws IOException on write failure
     */
    void writeToken(BridgeToken token) throws IOException {
        writeAtomically(tokenPath, token.value() + "\n");
    }

    /**
     * Writes the manifest atomically with mode 0600.
     *
     * @param json manifest JSON; must not contain secrets
     * @throws IOException on write failure
     */
    void writeManifest(String json) throws IOException {
        writeAtomically(manifestPath, json);
    }

    private void writeAtomically(Path target, String content) throws IOException {
        String prefix = "." + target.getFileName() + ".";
        Path tmp =
                posix
                        ? Files.createTempFile(
                                instancesDir,
                                prefix,
                                ".tmp",
                                PosixFilePermissions.asFileAttribute(FILE_PERMS))
                        : Files.createTempFile(instancesDir, prefix, ".tmp");
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (posix) {
                Files.setPosixFilePermissions(tmp, FILE_PERMS);
            }
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Deletes the manifest first, so the instance stops being discoverable.
     *
     * @return true if a file was removed
     */
    boolean deleteManifest() {
        return deleteQuietly(manifestPath);
    }

    /**
     * Deletes the token file.
     *
     * @return true if a file was removed
     */
    boolean deleteToken() {
        return deleteQuietly(tokenPath);
    }

    private static boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns the manifest path.
     *
     * @return absolute manifest path
     */
    public Path manifestPath() {
        return manifestPath;
    }

    /**
     * Returns the token file path.
     *
     * @return absolute token file path
     */
    public Path tokenPath() {
        return tokenPath;
    }
}
