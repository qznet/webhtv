package com.fongmi.android.tv.smb;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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

    private static final String PREFS = "smb_proxy";
    private static final String KEY_PORT = "port";
    private static final int PREFERRED_PORT = 34567;
    private static final long IDLE_TIMEOUT_MS = 90_000;
    /** How long a pooled SMB connection/session/share stays open after last use. */
    private static final long SHARE_IDLE_TIMEOUT_MS = 60_000;
    /**
     * Bytes requested in a single smbj read. Larger requests amortise SMB round-trips on a
     * high-latency link. The server may return fewer bytes (negotiated max read size), but
     * asking for more lets us fill the window in fewer round-trips when the server supports it.
     */
    private static final int READ_BLOCK = 4 * 1024 * 1024;   // 4 MiB
    private static final int MAX_WINDOW = 8 * 1024 * 1024;
    private static final int MAX_SESSIONS = 5000;
    /** How long serve() waits for the (background) SMB open to finish before giving up. */
    private static final long OPEN_TIMEOUT_MS = 40_000;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static volatile SmbHttpProxy instance;

    /** Bounded pool that performs the slow SMB connect+open off the HTTP-serve thread. */
    private static final ExecutorService opener = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "smb-proxy-opener");
        t.setDaemon(true);
        return t;
    });

    /** SMB share connection pool keyed by host/port/share/credentials. Reusing the same
     *  TCP/SMB connection across files removes repeated handshakes and keeps the congestion
     *  window warm — the main reason external managers feed this app faster than our own proxy. */
    private static final ConcurrentHashMap<String, ShareHandle> shares = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, SmbSession> sessions = new ConcurrentHashMap<>();
    private final Object startLock = new Object();
    private volatile boolean started;
    private ScheduledExecutorService reaper;

    public static synchronized SmbHttpProxy get() {
        if (instance == null) instance = new SmbHttpProxy(resolvePort());
        return instance;
    }

    private SmbHttpProxy(int port) {
        super("127.0.0.1", port);
    }

    /**
     * Return the loopback HTTP URL that streams the given {@code smb://} file.
     *
     * <p>The URL is <b>stable across app restarts</b>: a persisted port plus an id derived from the
     * SMB url itself. Playback history is keyed by url, so a changing url would silently break
     * resume-from-last-position.
     */
    public synchronized String proxyUrl(String smbUrl) throws IOException {
        ensureStarted();
        String id = stableId(smbUrl);
        SmbSession session = sessions.computeIfAbsent(id, key -> new SmbSession(key, smbUrl));
        // Warm the SMB connection now, while the player is still starting up, so the first
        // HTTP serve() does not have to perform the whole SMB handshake synchronously.
        session.ensureOpenAsync();
        return baseUrl() + "/smb/" + id;
    }

    /** Like {@link #proxyUrl} but does NOT kick off a background SMB connect. Use for URL
     *  matching (history) and for every playlist entry after the first, to avoid spawning one
     *  background connection per file. The SMB open then happens lazily when the player requests it. */
    public synchronized String proxyUrlLazy(String smbUrl) throws IOException {
        ensureStarted();
        String id = stableId(smbUrl);
        sessions.computeIfAbsent(id, key -> new SmbSession(key, smbUrl));
        return baseUrl() + "/smb/" + id;
    }

    private static String stableId(String smbUrl) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(smbUrl.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(smbUrl.hashCode());
        }
    }

    /** Reuse the previously bound port so URLs stay identical between launches. */
    private static int resolvePort() {
        int saved = prefs().getInt(KEY_PORT, 0);
        if (saved > 0 && isFree(saved)) return saved;
        if (isFree(PREFERRED_PORT)) {
            savePort(PREFERRED_PORT);
            return PREFERRED_PORT;
        }
        return 0;
    }

    private static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void savePort(int port) {
        prefs().edit().putInt(KEY_PORT, port).apply();
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
            savePort(getListeningPort());
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
        reapSessions();
        reapShares();
    }

    private void reapSessions() {
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

    /** Close pooled share connections that have not been used recently. Active playback keeps
     *  the handle touched, so this only reaps truly idle connections. */
    private void reapShares() {
        if (shares.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ShareHandle> e : shares.entrySet()) {
            if (now - e.getValue().lastUsed() > SHARE_IDLE_TIMEOUT_MS) {
                ShareHandle removed = shares.remove(e.getKey());
                if (removed != null) removed.close();
            }
        }
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
     * Streams {@code [start, start+length)} from an opened SMB file, feeding the HTTP layer from a
     * ring buffer that is filled by several background readers issuing SMB reads <b>in parallel</b>.
     *
     * <p>Single-threaded SMB reads cap throughput at ~one READ per round-trip (≈10 MB/s on a typical
     * NAS) no matter how large the block — exactly the ceiling users hit versus CX File Explorer
     * (~18 MB/s), which keeps several reads in flight over a warm connection. We open several SMB
     * file handles on the shared connection and let them read ahead concurrently into a ring, so the
     * HTTP consumer never stalls on a single round-trip and throughput approaches the link's real
     * capacity. If parallel setup fails for any reason we transparently fall back to a single
     * sequential stream, so playback can never get worse than before.
     */
    private static final class SmbRangeStream extends InputStream {
        private final SmbSession session;
        private final long absStart;
        private final long absEnd;          // exclusive
        private final boolean parallel;

        private static final int SEG = 1024 * 1024;            // 1 MB per ring segment
        private static final int NSEG = 24;                     // 24 MB ring
        private static final int PREFETCH = 4;                 // concurrent SMB readers

        private final byte[] ring = new byte[SEG * NSEG];
        private final AtomicIntegerArray segState = new AtomicIntegerArray(NSEG); // 0 unread,1 reading,2 done,3 error
        private final AtomicIntegerArray segLen = new AtomicIntegerArray(NSEG);    // valid bytes in a done segment
        private final AtomicLong nextSeg = new AtomicLong(0);    // next absolute segment to fetch
        private volatile long consumerSeg = 0;                  // current consumer absolute segment
        private long pos;                                       // current absolute read offset
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition();
        private final Condition segReady = lock.newCondition();
        private final List<File> handles = new ArrayList<>();
        private final ExecutorService pool;
        private volatile boolean closed;

        SmbRangeStream(SmbSession session, long start, long length) throws IOException {
            this.session = session;
            this.absStart = start;
            this.absEnd = start + length;
            this.pos = start;
            this.consumerSeg = start / SEG;
            this.parallel = tryInitParallel();
            this.pool = parallel ? buildPool() : null;
            if (parallel) for (int i = 0; i < PREFETCH; i++) pool.submit(new Prefetch(i));
        }

        private boolean tryInitParallel() {
            try {
                for (int i = 0; i < PREFETCH; i++) handles.add(session.openParallel(i));
                nextSeg.set(absStart / SEG);
                return true;
            } catch (IOException | RuntimeException e) {
                for (File f : handles) safeClose(f);
                handles.clear();
                return false;
            }
        }

        private ExecutorService buildPool() {
            return Executors.newFixedThreadPool(PREFETCH, new ThreadFactory() {
                private int c;
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "smb-pre-" + (c++));
                    t.setDaemon(true);
                    return t;
                }
            });
        }

        private final class Prefetch implements Runnable {
            private final int idx;
            Prefetch(int i) { this.idx = i; }
            @Override public void run() {
                File h = handles.get(idx);
                while (!closed) {
                    long g;
                    lock.lock();
                    try {
                        g = nextSeg.get();
                        if (g * SEG >= absEnd) break;
                        if (g - consumerSeg >= NSEG) {
                            try { notFull.await(150, TimeUnit.MILLISECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                            continue;
                        }
                        g = nextSeg.getAndIncrement();
                        if (g * SEG >= absEnd) break;
                        segState.set((int) (g % NSEG), 1);
                    } finally {
                        lock.unlock();
                    }
                    int got;
                    try {
                        int len = (int) Math.min(SEG, absEnd - g * SEG);
                        got = h.read(ring, g * SEG, (int) (g % NSEG) * SEG, len);
                    } catch (Exception e) {
                        got = -1;
                    }
                    lock.lock();
                    try {
                        int slot = (int) (g % NSEG);
                        if (got <= 0) {
                            segState.set(slot, 3);
                            segReady.signalAll();
                            break;
                        }
                        segLen.set(slot, got);
                        segState.set(slot, 2);
                        session.recordServed(got);
                        segReady.signalAll();
                        notFull.signalAll();
                    } finally {
                        lock.unlock();
                    }
                }
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
            if (len == 0) return 0;
            if (pos >= absEnd) return -1;
            if (parallel) {
                lock.lock();
                try {
                    while (true) {
                        long c = pos / SEG;
                        int slot = (int) (c % NSEG);
                        int st = segState.get(slot);
                        if (st == 2) {
                            int inSeg = (int) (pos - c * SEG);
                            int valid = segLen.get(slot);
                            int avail = (int) Math.min((long) len, Math.min((long) valid - inSeg, absEnd - pos));
                            if (avail <= 0) { pos = (c + 1) * SEG; continue; }
                            System.arraycopy(ring, slot * SEG + inSeg, b, off, avail);
                            pos += avail;
                            long nc = pos / SEG;
                            if (nc > consumerSeg) { consumerSeg = nc; notFull.signalAll(); }
                            return avail;
                        } else if (st == 3) {
                            break; // a prefetch error occurred -> fall through to sequential fallback
                        } else {
                            try { segReady.await(300, TimeUnit.MILLISECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return -1; }
                            if (pos >= absEnd) return -1;
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
            // Sequential fallback (parallel disabled or a prefetch error occurred).
            int avail = (int) Math.min((long) len, absEnd - pos);
            if (avail <= 0) return -1;
            int got = session.readRaw(pos, b, off, avail);
            if (got <= 0) return -1;
            pos += got;
            return got;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            lock.lock();
            try { segReady.signalAll(); notFull.signalAll(); } finally { lock.unlock(); }
            if (pool != null) pool.shutdownNow();
            super.close();
        }
    }

    /** One SMB share connection (TCP + SMB session + tree connect) that can be reused across files. */
    private static final class ShareHandle {
        private final String key;
        private final String host;
        private final int port;
        private final String share;
        private final String domain;
        private final String user;
        private final String pass;

        private SMBClient client;
        private Connection conn;
        private Session session;
        private DiskShare ds;
        private volatile long lastUsed = System.currentTimeMillis();
        private volatile boolean opened;
        private final Object lock = new Object();

        ShareHandle(String key, String host, int port, String share, String domain, String user, String pass) {
            this.key = key;
            this.host = host;
            this.port = port;
            this.share = share;
            this.domain = domain;
            this.user = user;
            this.pass = pass;
        }

        long lastUsed() {
            return lastUsed;
        }

        void touch() {
            lastUsed = System.currentTimeMillis();
        }

        void ensureOpen() throws IOException {
            synchronized (lock) {
                if (opened && conn != null && isConnected(conn) && ds != null) {
                    touch();
                    return;
                }
                close();
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
                opened = true;
                touch();
            }
        }

        private boolean isConnected(Connection c) {
            try {
                return c != null && c.isConnected();
            } catch (Throwable e) {
                return false;
            }
        }

        File openFile(String filePath) throws IOException {
            synchronized (lock) {
                touch();
                return ds.openFile(filePath,
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.FILE_READ_DATA),
                        EnumSet.noneOf(FileAttributes.class),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.noneOf(SMB2CreateOptions.class));
            }
        }

        void close() {
            synchronized (lock) {
                opened = false;
                safeClose(ds);
                safeClose(session);
                safeClose(conn);
                safeClose(client);
                ds = null;
                session = null;
                conn = null;
                client = null;
            }
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

        private ShareHandle shareHandle;
        private File file;
        private long size = -1;
        private volatile long lastAccess = System.currentTimeMillis();

        private final Object openLock = new Object();
        private volatile boolean opened;
        private boolean opening;
        private volatile String openError;
        private volatile CountDownLatch readyLatch = new CountDownLatch(1);

        private final AtomicLong bytesServed = new AtomicLong();
        private final List<File> parallelFiles = new ArrayList<>();
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

        /** Start the (slow) SMB connect+open in the background if it has not begun yet. Idempotent. */
        void ensureOpenAsync() {
            synchronized (openLock) {
                if (opened || opening) return;
                opening = true;
                opener.submit(() -> {
                    try {
                        doOpen();
                        opened = true;
                    } catch (Throwable e) {
                        openError = e.getMessage() == null ? e.toString() : e.getMessage();
                    } finally {
                        readyLatch.countDown();
                    }
                });
            }
        }

        /**
         * Open the SMB file, waiting for any in-flight background open.
         *
         * <p>Previously the whole SMB handshake (TCP connect, negotiate, authenticate, tree-connect,
         * open file, query size) ran <b>synchronously</b> inside the first {@code serve()} call. On a
         * high-latency NAS that handshakes in 1–3 s, which can exceed the player's URL-open timeout and
         * surface as "media parse failed" (mpv) or a transient "proxy error" (exo) on the very first
         * file — exactly the "first level fails, later ones work" symptom. The connect is now kicked
         * off in {@link #ensureOpenAsync()} (typically from {@code proxyUrl}, before the player even
         * launches) and this method just waits for it.
         *
         * <p>A pooled SMB share connection is reused across files, so once the first file has warmed
         * the share, subsequent opens are essentially instant. If the background open still fails
         * (transient SMB handshake hiccup), we retry once synchronously before giving up.
         */
        void open() throws IOException {
            lastAccess = System.currentTimeMillis();
            ensureOpenAsync();
            try {
                if (!readyLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw new IOException("smb open timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("smb open interrupted");
            }
            if (!opened) {
                // Transient failure: retry once synchronously. With connection pooling the share
                // may already be warm, so the retry is usually fast.
                synchronized (openLock) {
                    if (opened) return;
                    openError = null;
                    try {
                        doOpen();
                        opened = true;
                    } catch (IOException e) {
                        openError = e.getMessage();
                        throw e;
                    }
                }
            }
        }

        private void doOpen() throws IOException {
            String shareKey = host + ":" + port + "/" + share + "@" + domain + ";" + user;
            shareHandle = shares.computeIfAbsent(shareKey, k -> new ShareHandle(k, host, port, share, domain, user, pass));
            shareHandle.ensureOpen();
            file = shareHandle.openFile(filePath);
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

        /** Open a fresh parallel SMB file handle for concurrent prefetch reads.
         *  Each caller (each {@link SmbRangeStream}) opens its own set so concurrent prefetchers
         *  never share a single smbj {@code File} (its reads are not thread-safe). All handles are
         *  closed together when the session is reaped. */
        synchronized File openParallel(int i) throws IOException {
            File f = shareHandle.openFile(filePath);
            parallelFiles.add(f);
            return f;
        }

        /** Record network bytes served (called by the prefetch threads) for the OSD read-out. */
        void recordServed(long n) {
            if (n > 0) bytesServed.addAndGet(n);
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
            for (File pf : parallelFiles) safeClose(pf);
            parallelFiles.clear();
            safeClose(file);
            file = null;
            if (shareHandle != null) {
                shareHandle.touch(); // keep the pooled share alive
                shareHandle = null;
            }
            opened = false;
            opening = false;
            openError = null;
            readyLatch = new CountDownLatch(1);
        }
    }

    private static void safeClose(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static void safeClose(DiskShare c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static void safeClose(SMBClient c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static void safeClose(Connection c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static void safeClose(Session c) {
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
