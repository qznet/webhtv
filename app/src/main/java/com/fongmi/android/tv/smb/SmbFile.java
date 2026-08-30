package com.fongmi.android.tv.smb;

import com.fongmi.android.tv.impl.Diffable;
import com.hierynomus.smbj.share.File;

/**
 * A single entry inside an SMB share: either a folder or a file.
 * {@code path} is the relative path within the share, e.g. "Movies/Sub" (folder)
 * or "Movies/Sub/clip.mkv" (file).
 */
public class SmbFile implements Diffable<SmbFile> {

    private final String name;
    private final String path;
    private final boolean directory;
    private final long size;

    public SmbFile(String name, String path, boolean directory, long size) {
        this.name = name;
        this.path = path;
        this.directory = directory;
        this.size = size;
    }

    public static SmbFile create(File f, String parentPath) {
        String name = f.getFileName();
        String path = parentPath.isEmpty() ? name : parentPath + "/" + name;
        return new SmbFile(name, path, f.isDirectory(), f.getFileSize());
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }

    @Override
    public boolean isSameItem(SmbFile other) {
        return path.equals(other.path);
    }

    @Override
    public boolean isSameContent(SmbFile other) {
        return path.equals(other.path) && size == other.size && directory == other.directory;
    }
}
