package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Routes {@code smb://} URIs to a smbj-backed {@link SmbBufferedDataSource} (with read-ahead
 * buffering and explicit timeouts) and delegates every other scheme to the standard
 * {@link DefaultDataSource}.
 *
 * <p>This lets the ExoPlayer engine play files straight off a Windows/Samba share, e.g.
 * {@code smb://user:password@host/share/path/to/movie.mkv}. Credentials are taken from the
 * user-info part of the URI as {@code [domain;]user:password}; anonymous access falls back to a
 * guest session.
 */
public final class SmbSchemeDataSource implements DataSource {

    private final SmbBufferedDataSource smb = new SmbBufferedDataSource();
    private final DataSource base;
    @Nullable
    private DataSource current;

    public SmbSchemeDataSource(Context context, DataSource.Factory httpFactory) {
        this.base = new DefaultDataSource.Factory(context, httpFactory).createDataSource();
    }

    @Override
    public void addTransferListener(@Nullable TransferListener transferListener) {
        smb.addTransferListener(transferListener);
        base.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        current = "smb".equalsIgnoreCase(dataSpec.uri.getScheme()) ? smb : base;
        return current.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return current.read(buffer, offset, length);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return current == null ? null : current.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return current == null ? Collections.emptyMap() : current.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
