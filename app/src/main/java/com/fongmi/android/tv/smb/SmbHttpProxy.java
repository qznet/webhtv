package com.fongmi.android.tv.smb;

import com.fongmi.android.tv.setting.PlayerSetting;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * Local loopback HTTP proxy for SMB playback.
 *
 * <p>mpv (and, for consistency, ExoPlayer) cannot read {@code smb://} directly on Android TV
 * — mpv falls back to its own broken SMB client and stalls at ~0 throughput. External file
 * managers that "just work" do so by streaming the SMB file through a local HTTP server and
 * handing the player a plain {@code http://127.0.0.1} URL. This class reproduces that exact,
 * proven path using the same pure-Java smbj client already used for browsing.
 *
 * <p>When an SMB file is opened for playback we mint a session id and return
 * {@code http://127.0.0.1:<port>/smb/<id>}. The player (any kernel) reads that URL over
 * loopback HTTP; we serve it with proper {@code Range} support by performing random-access
 * reads against the SMB file via smbj. This is modelled after {@code MpvHlsProxy}.
 */
public class SmbHttpProxy extends NanoHTTPD {

    private static final SmbHttpProxy INSTANCE = new SmbHttpProxy();
    private static final long IDLE_TIMEOUT_MS = 90_000;
    private static final int READ_BLOCK = 1024 * 1024;   // bytes per single SMB READ (1 MiB)
    private static final int MAX_WINDOW = 8 * 1024 * 1024;
    private static final int MAX_SESSIONS = 1000;

    private final ConcurrentHashMap<String, SmbSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final Object startLock = new Object();
    private volatile boolean started;
    private ScheduledExecutorService reaper;

    public static SmbHttpProxy get() {
        return INSTANCE;
    }

    private SmbHttpProxy() {
        super("127.0.0.1", 0);
    }

    /** Mint a loopback HTTP URL that streams the given {@code smb://} file. */
    public synchronized String proxyUrl(String smbUrl) throws IOException {
        ensureStarted();
        String id = String.valueOf(nextId.incrementAndGet());
        sessions.put(id, new SmbSession(id, smbUrl));
        return baseUrl() + "/smb/" + id;
    }

    /** Sample the live read throughput (bytes/sec) for a proxied {@code http://127.0.0.1/.../smb/<id>} URL. */
    public long sampleThroughputBps(String url) {
        SmbSession s = sessions.get(parseId(url));
        return s == null ? 0 : s.sampleThroughputBps();
    }

    private static String parseId(String url) {
        if (url == null) return "";
        int idx = url.indexOf("/smb/");
        if (idx < 0) return "";
        String tail = url.substring(idx + 5);
        int q = tail.indexOf('?');
        return q >= 0 ? tail.substring(0, q) : tail;
    }

    private void ensureStarted() throws IOException {
        synchronized (startLock) {
            if (started) return;
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "smb-proxy-reaper");
                t.setDaemon(true);
                return t;
            });
            reaper.scheduleAtFixedRate(this::reap, 30, 30, TimeUnit.SECONDS);
            started = true;
        }
    }

    /**
     * Frees the SMB connection of idle sessions but <b>keeps the session entry</b>, so a folder
     * playlist can still request episode #40 long after it was minted (it simply reopens lazily).
     * Entries are only evicted once the cache grows past {@link #MAX_SESSIONS}.
     */
    private void reap() {
        if (sessions.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (SmbSession s : sessions.values()) {
            if (now - s.lastAccess() > IDLE_TIMEOUT_MS) s.close();
        }
        int excess = sessions.size() - MAX_SESSIONS;
        if (excess <= 0) return;
        new ArrayList<>(sessions.entrySet()).stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().lastAccess()))
                .limit(excess)
                .forEach(entry -> {
                    sessions.remove(entry.getKey());
                    entry.getValue().close();
                });
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + getListeningPort();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null || !uri.startsWith("/smb/")) {
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "");
        }
        String id = uri.substring(5);
        SmbSession smb = sessions.get(id);
        if (smb == null) {
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "unknown smb session");
        }
        try {
            smb.open();
            long size = smb.getSize();
            Map<String, String> headers = session.getHeaders();
            String range = headers.get("range");
            long start = 0;
            long end = size - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", 2);
                try {
                    if (!parts[0].isEmpty()) start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                }
                if (start < 0 || start > end || start >= size) {
                    Response r = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "");
                    r.addHeader("Content-Range", "bytes */" + size);
                    return r;
                }
                if (end >= size) end = size - 1;
                partial = true;
            }
            long length = end - start + 1;
            String mime = getMimeTypeForFile(smb.fileName());
            if (mime == null) mime = "application/octet-stream";
            InputStream is = new SmbRangeStream(smb, start, length);
            Response res;
            if (partial) {
                res = newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, is, length);
                res.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
            } else {
                res = newFixedLengthResponse(Status.OK, mime, is, length);
            }
            res.addHeader("Accept-Ranges", "bytes");
            res.addHeader("Content-Length", String.valueOf(length));
            res.addHeader("Connection", "close");
            return res;
        } catch (Throwable e) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                    "smb open failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * Streams {@code [start, start+length)} from an opened SMB file through a read-ahead window.
     *
     * <p>The HTTP layer pulls this stream in small (a few KB) chunks; issuing a separate SMB READ
     * per small chunk means one network round-trip per few KB, which collapses to ~5 MB/s on a
     * high-latency NAS. Instead we pull a large block (1 MiB) from SMB into a window and serve the
     * small HTTP reads out of memory — the same read-ahead idea external SMB file managers use.
     */
    private static final class SmbRangeStream extends InputStream {
        private final SmbSession session;
        private final long end;
        private final byte[] window;
        private long pos;
        private long winStart;
        private int winLen;

        SmbRangeStream(SmbSession session, long start, long length) {
            this.session = session;
            this.pos = start;
            this.end = start + length;
            long desired = Math.min(PlayerSetting.getSmbWindowBytes(), MAX_WINDOW);
            long fit = Math.min(desired, Math.max(READ_BLOCK, length));
            this.window = new byte[(int) Math.max(READ_BLOCK, fit)];
        }

        private void fill() throws IOException {
            winStart = pos;
            winLen = 0;
            long want = Math.min(window.length, end - pos);
            while (winLen < want) {
                int need = (int) Math.min(READ_BLOCK, want - winLen);
                int n = session.readRaw(winStart + winLen, window, winLen, need);
                if (n <= 0) break;
                winLen += n;
            }
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= end) return -1;
            if (pos < winStart || pos >= winStart + winLen) fill();
            if (winLen == 0) return -1;
            int inWin = (int) (pos - winStart);
            int n = (int) Math.min(len, Math.min(winLen - inWin, end - pos));
            System.arraycopy(window, inWin, b, off, n);
            pos += n;
            return n;
        }
    }

    /** One SMB file playback session: parsed credentials + lazily opened smbj handle. */
    private static final class SmbSession {
        private final String id;
        private final String smbUrl;
        private final String host;
        private final int port;
        private final String share;
        private final String filePath;
        private final String domain;
        private final String user;
        private final String pass;

        private SMBClient client;
        private Connection conn;
        private Session session;
        private DiskShare ds;
        private File file;
        private long size = -1;
        private volatile long lastAccess = System.currentTimeMillis();

        private final AtomicLong bytesServed = new AtomicLong();
        private long lastSampleBytes;
        private long lastSampleNs = System.nanoTime();

        SmbSession(String id, String smbUrl) {
            this.id = id;
            this.smbUrl = smbUrl;
            String[] parsed = parse(smbUrl);
            this.host = parsed[0];
            this.port = Integer.parseInt(parsed[1]);
            this.share = parsed[2];
            this.filePath = parsed[3];
            this.domain = parsed[4];
            this.user = parsed[5];
            this.pass = parsed[6];
        }

        String fileName() {
            int s = filePath.lastIndexOf('/');
            return s >= 0 ? filePath.substring(s + 1) : filePath;
        }

        long lastAccess() {
            return lastAccess;
        }

        synchronized void open() throws IOException {
            lastAccess = System.currentTimeMillis();
            if (file != null) return;
            SmbConfig config = SmbConfig.builder()
                    .withTimeout(30, TimeUnit.SECONDS)
                    .withSoTimeout(30, TimeUnit.SECONDS)
                    .withDfsEnabled(true)
                    .build();
            client = new SMBClient(config);
            conn = client.connect(host, port);
            AuthenticationContext auth = user.isEmpty()
                    ? new AuthenticationContext("", new char[0], "")
                    : new AuthenticationContext(user, pass.toCharArray(), domain);
            session = conn.authenticate(auth);
            ds = (DiskShare) session.connectShare(share);
            file = ds.openFile(filePath,
                    EnumSet.of(AccessMask.GENERIC_READ, AccessMask.FILE_READ_DATA),
                    EnumSet.noneOf(FileAttributes.class),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(SMB2CreateOptions.class));
            size = file.getFileInformation(FileStandardInformation.class).getEndOfFile();
        }

        long getSize() {
            return size;
        }

        /** Raw SMB read at an arbitrary file offset; counts real network bytes for the OSD. */
        synchronized int readRaw(long offset, byte[] buf, int off, int len) throws IOException {
            lastAccess = System.currentTimeMillis();
            int n = file.read(buf, offset, off, len);
            if (n > 0) bytesServed.addAndGet(n);
            return n;
        }

        long sampleThroughputBps() {
            long now = System.nanoTime();
            long bytes = bytesServed.get();
            long elapsedNs = Math.max(1, now - lastSampleNs);
            long bps = (long) ((bytes - lastSampleBytes) * 1_000_000_000.0 / elapsedNs);
            lastSampleBytes = bytes;
            lastSampleNs = now;
            return bps;
        }

        synchronized void close() {
            safeClose(file);
            safeClose(ds);
            safeClose(session);
            safeClose(conn);
            safeClose(client);
            file = null;
            ds = null;
            session = null;
            conn = null;
            client = null;
        }

        private void safeClose(java.io.Closeable c) {
            if (c == null) return;
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }

        private void safeClose(DiskShare c) {
            if (c == null) return;
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }

        private void safeClose(SMBClient c) {
            if (c == null) return;
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }

        private void safeClose(Connection c) {
            if (c == null) return;
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }

        private void safeClose(Session c) {
            if (c == null) return;
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }

        /** Returns {host, port, share, filePath, domain, user, pass}. */
        private static String[] parse(String url) {
            String s = url.substring("smb://".length());
            int slash = s.indexOf('/');
            String authority = slash >= 0 ? s.substring(0, slash) : s;
            String pathRemainder = slash >= 0 ? s.substring(slash + 1) : "";
            int at = authority.indexOf('@');
            String userinfo = at >= 0 ? authority.substring(0, at) : null;
            String hostport = at >= 0 ? authority.substring(at + 1) : authority;

            String domain = "";
            String user = "";
            String pass = "";
            if (userinfo != null && !userinfo.isEmpty()) {
                String ui = userinfo;
                int semi = ui.indexOf(';');
                if (semi >= 0) {
                    domain = ui.substring(0, semi);
                    ui = ui.substring(semi + 1);
                }
                int colon = ui.indexOf(':');
                if (colon >= 0) {
                    user = ui.substring(0, colon);
                    pass = ui.substring(colon + 1);
                } else {
                    user = ui;
                }
            }

            int colon = hostport.indexOf(':');
            String host = colon >= 0 ? hostport.substring(0, colon) : hostport;
            int port = colon >= 0 ? Integer.parseInt(hostport.substring(colon + 1)) : 445;

            String share = "";
            String filePath = "";
            if (!pathRemainder.isEmpty()) {
                int s2 = pathRemainder.indexOf('/');
                if (s2 < 0) {
                    share = pathRemainder;
                } else {
                    share = pathRemainder.substring(0, s2);
                    filePath = pathRemainder.substring(s2 + 1);
                }
            }
            return new String[]{host, String.valueOf(port), share, filePath, domain, user, pass};
        }
    }
}
