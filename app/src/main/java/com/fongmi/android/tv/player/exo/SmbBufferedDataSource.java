package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * smbj-backed {@code smb://} DataSource with a sliding read-ahead window.
 *
 * <p>The stock {@code androidx.media3.datasource.SmbDataSource} (fongmi fork) issues one SMB
 * READ per player {@code read()} call and uses the default {@link SMBClient} timeout config. On
 * high-latency NAS that collapses throughput (the player reports a very low transfer speed and
 * eventually times out). This implementation instead pulls large sequential chunks (up to
 * {@link #WINDOW_SIZE}) into a local window so the player's many small reads are served from
 * memory, and connects with explicit, generous timeouts so a slow link does not trip a spurious
 * "connection timeout".
 *
 * <p>User-info of the URI is parsed as {@code [domain;]user:password}; an empty user-info falls
 * back to a guest session. This keeps playback authentication identical to the SMB browser
 * (which already carries the domain).
 */
public final class SmbBufferedDataSource extends BaseDataSource {

    private static final int WINDOW_SIZE = 2 * 1024 * 1024;   // 2 MB read-ahead window
    private static final int READ_CHUNK = 256 * 1024;        // max bytes per single SMB READ

    // Live debug statistics, published to a singleton so the OSD can sample throughput
    // and read-ahead window occupancy without holding a reference to the active source.
    private static volatile long sTotalBytes;
    private static volatile long sLastSampleBytes;
    private static volatile long sLastSampleTs;
    private static volatile float sWindowFill;               // 0..1, read-ahead window occupancy

    private final byte[] window = new byte[WINDOW_SIZE];
    private long windowStart;   // file offset of window[0]
    private long windowEnd;     // file offset just past the last valid byte in the window
    private long fileLength;
    private long readPosition;  // next file offset the player expects
    private long bytesRemaining;

    @Nullable
    private Uri uri;
    private SMBClient smbClient;
    private Connection connection;
    private Session session;
    private DiskShare diskShare;
    private File smbFile;
    private boolean opened;

    public SmbBufferedDataSource() {
        super(true);
    }

    private static AuthenticationContext getAuthentication(Uri uri) {
        String userInfo = uri.getUserInfo();
        if (userInfo == null || userInfo.isEmpty()) {
            return AuthenticationContext.guest();
        }
        String domain = null;
        String userPass = userInfo;
        int semi = userInfo.indexOf(';');
        if (semi >= 0) {
            domain = userInfo.substring(0, semi);
            userPass = userInfo.substring(semi + 1);
        }
        String[] parts = userPass.split(":", 2);
        String username = parts[0];
        char[] password = parts.length > 1 ? parts[1].toCharArray() : new char[0];
        return new AuthenticationContext(username, password, domain);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        resetStats();
        transferInitializing(dataSpec);
        String host = uri.getHost();
        if (host == null) {
            throw new DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        int port = uri.getPort() != -1 ? uri.getPort() : 445;
        String path = uri.getPath();
        if (path != null && path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path == null || !path.contains("/")) {
            throw new DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        String[] parts = path.split("/", 2);
        String shareName = parts[0];
        String filePath = parts[1];

        closeSmb();
        try {
            com.hierynomus.smbj.SmbConfig config = com.hierynomus.smbj.SmbConfig.builder()
                    .withTimeout(60, TimeUnit.SECONDS)
                    .withSoTimeout(60, TimeUnit.SECONDS)
                    .withDfsEnabled(true)
                    .build();
            smbClient = new SMBClient(config);
            connection = smbClient.connect(host, port);
            session = connection.authenticate(getAuthentication(uri));
            diskShare = (DiskShare) session.connectShare(shareName);
            smbFile = diskShare.openFile(filePath,
                    EnumSet.of(AccessMask.GENERIC_READ), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN, null);
            fileLength = smbFile.getFileInformation().getStandardInformation().getEndOfFile();
        } catch (IOException e) {
            closeSmb();
            throw new DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
        }

        readPosition = dataSpec.position;
        bytesRemaining = dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : fileLength - dataSpec.position;
        if (bytesRemaining < 0 || dataSpec.position > fileLength) {
            throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
        }
        windowStart = 0;
        windowEnd = 0;
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining <= 0) {
            return C.RESULT_END_OF_INPUT;
        }
        if (smbFile == null) {
            throw new DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        ensureBuffered(readPosition, readPosition + Math.min(length, bytesRemaining));
        long available = windowEnd - readPosition;
        if (available <= 0) {
            bytesRemaining = 0;
            return C.RESULT_END_OF_INPUT;
        }
        int toCopy = (int) Math.min(length, Math.min(available, bytesRemaining));
        int winOff = (int) (readPosition - windowStart);
        System.arraycopy(window, winOff, buffer, offset, toCopy);
        readPosition += toCopy;
        bytesRemaining -= toCopy;
        bytesTransferred(toCopy);
        record(toCopy);
        return toCopy;
    }

    private void ensureBuffered(long needStart, long needEnd) throws IOException {
        if (needStart >= windowStart && needEnd <= windowEnd) {
            return; // already covered by the window
        }
        if (needStart < windowStart || (needEnd - windowStart) > WINDOW_SIZE) {
            // backward seek, or the request cannot fit contiguously -> reset the window
            windowStart = needStart;
            windowEnd = needStart;
        } else {
            // slide the still-needed tail to the front of the array
            long consumed = needStart - windowStart;
            if (consumed > 0) {
                int tail = (int) (windowEnd - needStart);
                System.arraycopy(window, (int) consumed, window, 0, tail);
                windowStart = needStart;
                windowEnd = needStart + tail;
            }
        }
        long target = Math.min(fileLength, windowStart + WINDOW_SIZE);
        while (windowEnd < target) {
            int toRead = (int) Math.min(READ_CHUNK, target - windowEnd);
            int n = smbFile.read(window, windowEnd, (int) (windowEnd - windowStart), toRead);
            if (n <= 0) {
                break;
            }
            windowEnd += n;
        }
        sWindowFill = (float) (windowEnd - windowStart) / WINDOW_SIZE;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        if (opened) {
            opened = false;
            transferEnded();
        }
        uri = null;
        closeSmb();
    }

    /**
     * Records a successful transfer of {@code n} bytes for the live throughput estimate, and
     * refreshes the read-ahead window occupancy.
     */
    private void record(int n) {
        sTotalBytes += n;
        sWindowFill = bytesRemaining > 0 || windowEnd > windowStart
                ? (float) (windowEnd - windowStart) / WINDOW_SIZE : 0f;
    }

    /** Current SMB read throughput in bytes/second, sampled since the previous call. */
    public static long sampleThroughputBps() {
        long now = System.currentTimeMillis();
        long lastBytes = sLastSampleBytes;
        long lastTs = sLastSampleTs;
        sLastSampleBytes = sTotalBytes;
        sLastSampleTs = now;
        if (lastTs <= 0) return 0;
        long dt = now - lastTs;
        if (dt <= 0) return 0;
        long bytes = sTotalBytes - lastBytes;
        return bytes * 1000 / dt;
    }

    /** Read-ahead window occupancy in the range 0..1. */
    public static float getWindowFill() {
        return Math.max(0f, Math.min(1f, sWindowFill));
    }

    /** Resets the live debug counters (call when a new file is opened or the source is closed). */
    public static void resetStats() {
        sTotalBytes = 0;
        sLastSampleBytes = 0;
        sLastSampleTs = 0;
        sWindowFill = 0f;
    }

    private void closeSmb() {
        closeSilently(smbFile);
        closeSilently(diskShare);
        closeSilently(session);
        closeSilently(connection);
        closeSilently(smbClient);
        smbFile = null;
        diskShare = null;
        session = null;
        connection = null;
        smbClient = null;
        windowStart = 0;
        windowEnd = 0;
    }

    private static void closeSilently(@Nullable AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }
}
