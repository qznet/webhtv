package com.fongmi.android.tv.smb;

import com.fongmi.android.tv.App;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;

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
                List<FileIdBothDirectoryInformation> raw = ds.list(path);
                List<SmbFile> files = new ArrayList<>();
                for (FileIdBothDirectoryInformation f : raw) {
                    String n = f.getFileName();
                    if (n.equals(".") || n.equals("..")) continue;
                    files.add(SmbFile.create(f, path));
                }
                files.sort((a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return naturalCompare(a.getName(), b.getName());
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

    /**
     * Natural (numeric-aware) ordering, so {@code S01E2} sorts before {@code S01E10} instead of
     * after it as plain lexicographic ordering would. Folders keep priority in {@link #list}.
     */
    static int naturalCompare(String a, String b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                long na = 0;
                long nb = 0;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    if (na < Long.MAX_VALUE / 10) na = na * 10 + (a.charAt(i) - '0');
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    if (nb < Long.MAX_VALUE / 10) nb = nb * 10 + (b.charAt(j) - '0');
                    j++;
                }
                if (na != nb) return na < nb ? -1 : 1;
            } else {
                int cmp = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
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
