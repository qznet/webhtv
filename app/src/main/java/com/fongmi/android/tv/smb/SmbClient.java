package com.fongmi.android.tv.smb;

import com.fongmi.android.tv.App;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the pure-Java smbj SMB client. All network I/O runs on a
 * background thread; results are posted back to the main thread via {@link App#post}.
 */
public class SmbClient {

    private static final SmbClient INSTANCE = new SmbClient();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "smb-client");
        t.setDaemon(true);
        return t;
    });

    public static SmbClient get() {
        return INSTANCE;
    }

    public interface Callback {
        void onResult(List<SmbFile> files);

        void onError(String message);
    }

    public void list(final SmbServer server, final String share, final String parentPath, final Callback callback) {
        executor.execute(() -> {
            SMBClient client = null;
            Connection conn = null;
            try {
                SmbConfig config = SmbConfig.builder()
                        .withTimeout(30, TimeUnit.SECONDS)
                        .withSoTimeout(30, TimeUnit.SECONDS)
                        .build();
                client = new SMBClient(config);
                conn = client.connect(server.getHost());
                AuthenticationContext auth = server.isAnonymous()
                        ? new AuthenticationContext("", new char[0], "")
                        : new AuthenticationContext(server.getUser(), server.getPass().toCharArray(), server.getDomain());
                Session session = conn.authenticate(auth);
                DiskShare ds = (DiskShare) session.connectShare(share);
                String path = parentPath == null ? "" : parentPath;
                List<File> raw = ds.list(path);
                List<SmbFile> files = new ArrayList<>();
                for (File f : raw) {
                    String n = f.getFileName();
                    if (n.equals(".") || n.equals("..")) continue;
                    files.add(SmbFile.create(f, path));
                }
                files.sort((a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                final List<SmbFile> result = files;
                App.post(() -> callback.onResult(result));
            } catch (Throwable e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                App.post(() -> callback.onError(msg));
            } finally {
                closeQuietly(conn);
                closeQuietly(client);
            }
        });
    }

    private void closeQuietly(Connection c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private void closeQuietly(SMBClient c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
