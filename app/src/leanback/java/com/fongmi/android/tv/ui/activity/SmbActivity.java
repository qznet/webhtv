package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.ActivitySmbBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.smb.SmbClient;
import com.fongmi.android.tv.smb.SmbFile;
import com.fongmi.android.tv.smb.SmbHttpProxy;
import com.fongmi.android.tv.smb.SmbServer;
import com.fongmi.android.tv.smb.SmbStore;
import com.fongmi.android.tv.ui.adapter.SmbAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.ChoiceDialog;
import com.fongmi.android.tv.ui.dialog.SmbServerDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SmbActivity extends BaseActivity implements SmbAdapter.OnClickListener {

    private ActivitySmbBinding mBinding;
    private SmbAdapter mAdapter;
    private List<SmbServer> servers = new ArrayList<>();
    private int selectedIndex = 0;
    private String currentShare = "";
    private String currentPath = "";

    /** Keeps the playlist Intent well under the binder transaction limit. */
    private static final int MAX_PLAYLIST = 500;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SmbActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySmbBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        loadServers();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mAdapter = new SmbAdapter(this));
        // Detail-style list: one file per row, easier to read long filenames on a TV.
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        mBinding.server.setOnClickListener(v -> openServerChooser());
        mBinding.share.setOnClickListener(v -> promptShare());
        mBinding.add.setOnClickListener(v -> addServer());
        mBinding.refresh.setOnClickListener(v -> listFiles());
        mBinding.playAll.setOnClickListener(v -> playAll());
    }

    /**
     * Play the folder back-to-back, continuing from where the user left off: the most recently
     * played file goes first (the player restores its saved position) and everything after it
     * follows.
     */
    private void playAll() {
        if (servers.isEmpty()) return;
        List<SmbFile> videos = new ArrayList<>();
        for (SmbFile item : mAdapter.getItems()) {
            if (videos.size() >= MAX_PLAYLIST) break;
            if (!item.isDirectory() && isVideo(item.getName())) videos.add(item);
        }
        if (videos.isEmpty()) {
            Notify.show(R.string.smb_no_video);
            return;
        }
        final List<SmbFile> all = videos;
        Task.execute(() -> {
            int resume = lastWatchedIndex(all);
            List<SmbFile> playlist = resume > 0 ? new ArrayList<>(all.subList(resume, all.size())) : all;
            App.post(() -> startPlaylist(playlist));
        });
    }

    /**
     * Play the clicked file and every video file that follows it in the current folder.
     *
     * <p>Non-video files (subtitles, nfo, ...) still open on their own so clicking one is not a
     * dead end.
     */
    private void playFrom(SmbFile start) {
        if (servers.isEmpty()) return;
        if (!isVideo(start.getName())) {
            playSingle(start);
            return;
        }
        List<SmbFile> items = mAdapter.getItems();
        int from = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getPath().equals(start.getPath())) {
                from = i;
                break;
            }
        }
        List<SmbFile> videos = new ArrayList<>();
        for (int i = from; i < items.size() && videos.size() < MAX_PLAYLIST; i++) {
            SmbFile item = items.get(i);
            if (!item.isDirectory() && isVideo(item.getName())) videos.add(item);
        }
        if (videos.isEmpty()) playSingle(start);
        else startPlaylist(videos);
    }

    private void playSingle(SmbFile item) {
        SmbServer s = servers.get(selectedIndex);
        String url = s.getFileUrl(currentShare, item.getPath());
        // Route through the local loopback HTTP proxy so both the mpv and exo kernels
        // read SMB over plain HTTP (mpv cannot read smb:// directly on Android TV).
        try {
            url = SmbHttpProxy.get().proxyUrl(url);
        } catch (Throwable ignored) {
            // Fall back to the raw smb:// URL (exo's smbj data source) if the proxy is down.
        }
        VideoActivity.start(this, url);
    }

    /**
     * The app already auto-advances ({@code checkEnded -> checkNext}), so a folder playlist is just
     * a TVBox-style multi-episode string: {@code name$url#name$url#...}.
     */
    private void startPlaylist(List<SmbFile> videos) {
        SmbServer s = servers.get(selectedIndex);
        StringBuilder sb = new StringBuilder();
        for (SmbFile v : videos) {
            String url = s.getFileUrl(currentShare, v.getPath());
            try {
                url = SmbHttpProxy.get().proxyUrl(url);
            } catch (Throwable ignored) {
                // Fall back to the raw smb:// URL (exo's smbj data source) if the proxy is down.
            }
            if (sb.length() > 0) sb.append('#');
            sb.append(sanitize(v.getName())).append('$').append(url);
        }
        VideoActivity.start(this, SiteApi.PUSH, sb.toString(), currentPathText());
    }

    private static boolean isVideo(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "mkv":
            case "mp4":
            case "m4v":
            case "mov":
            case "avi":
            case "wmv":
            case "flv":
            case "webm":
            case "ts":
            case "m2ts":
            case "vob":
            case "mpg":
            case "mpeg":
            case "3gp":
            case "asf":
            case "rmvb":
            case "rm":
            case "iso":
            case "dat":
                return true;
            default:
                return false;
        }
    }

    /** '#' and '$' are the playlist separators, so they must not appear inside a title. */
    private static String sanitize(String name) {
        return name.replace('#', '_').replace('$', '_');
    }

    private void loadServers() {
        servers = SmbStore.getAll();
        if (servers.isEmpty()) {
            SmbServerDialog.show(getActivity(), null,
                    s -> {
                        SmbStore.save(s);
                        reloadAndSelect(servers.size());
                    }, null);
            return;
        }
        reloadAndSelect(0);
    }

    private void reloadAndSelect(int index) {
        servers = SmbStore.getAll();
        if (servers.isEmpty()) {
            Notify.show(R.string.smb_no_server);
            finish();
            return;
        }
        selectedIndex = Math.min(index, servers.size() - 1);
        SmbServer s = servers.get(selectedIndex);
        currentShare = s.getShare();
        currentPath = "";
        updateServerButton();
        if (currentShare.isEmpty()) promptShare();
        else listFiles();
    }

    private void updateServerButton() {
        mBinding.server.setText(servers.get(selectedIndex).getDisplay());
        mBinding.share.setVisibility(servers.get(selectedIndex).getShare().isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openServerChooser() {
        ArrayList<CharSequence> items = new ArrayList<>();
        for (SmbServer s : servers) items.add(s.getDisplay());
        items.add("＋ " + ResUtil.getString(R.string.smb_add));
        CharSequence[] arr = items.toArray(new CharSequence[0]);
        ChoiceDialog.showSingle(getActivity(), R.string.smb_server, arr, selectedIndex, index -> {
            if (index == servers.size()) {
                addServer();
            } else {
                selectedIndex = index;
                currentShare = servers.get(index).getShare();
                currentPath = "";
                updateServerButton();
                if (currentShare.isEmpty()) promptShare();
                else listFiles();
            }
        });
    }

    private void addServer() {
        SmbServerDialog.show(getActivity(), null, s -> {
            SmbStore.save(s);
            reloadAndSelect(servers.size());
        }, null);
    }

    private void promptShare() {
        EditText input = new EditText(this);
        input.setHint(R.string.smb_share_name);
        input.setText(currentShare);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.smb_share)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    currentShare = input.getText().toString().trim();
                    currentPath = "";
                    listFiles();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void listFiles() {
        if (servers.isEmpty() || currentShare.isEmpty()) return;
        mBinding.path.setText(currentPathText());
        mBinding.progressLayout.showProgress();
        SmbClient.get().list(servers.get(selectedIndex), currentShare, currentPath, new SmbClient.Callback() {
            @Override
            public void onResult(List<SmbFile> files) {
                mAdapter.setItems(files, () -> mBinding.progressLayout.showContent(true, files.size()));
                mBinding.path.setText(currentPathText());
                refreshWatched(files);
            }

            @Override
            public void onError(String message) {
                Notify.show(message);
                mBinding.progressLayout.showContent(true, 0);
            }
        });
    }

    private String currentPathText() {
        return (currentShare.isEmpty() ? "/" : currentShare) + (currentPath.isEmpty() ? "" : "/" + currentPath);
    }

    /**
     * All PUSH (SMB) history rows for the current cid. A folder play saves one history row per
     * {@code playFrom} session, keyed by the whole playlist string, but the actually-played file's
     * loopback proxy url is stored in {@code episodeUrl}. We match on that url, which is stable
     * across restarts, so a watched file is detected no matter where its playlist started.
     */
    private List<History> pushHistories() {
        try {
            List<History> all = History.get(VodConfig.getCid());
            List<History> push = new ArrayList<>();
            String prefix = SiteApi.PUSH + AppDatabase.SYMBOL;
            for (History h : all) if (h.getKey() != null && h.getKey().startsWith(prefix)) push.add(h);
            return push;
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    /** Look up the playback history of one file (null when it has never been played). */
    private History historyOf(SmbFile item, List<History> push) {
        if (servers.isEmpty()) return null;
        try {
            String url = SmbHttpProxy.get().proxyUrl(servers.get(selectedIndex).getFileUrl(currentShare, item.getPath()));
            for (History h : push) if (url.equals(h.getEpisodeUrl())) return h;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Marks every already-played file so the list can dim it. Runs off the main thread (Room). */
    private void refreshWatched(List<SmbFile> files) {
        List<History> push = pushHistories();
        Task.execute(() -> {
            Set<String> seen = new HashSet<>();
            for (SmbFile item : files) {
                if (item.isDirectory() || !isVideo(item.getName())) continue;
                if (historyOf(item, push) != null) seen.add(item.getPath());
            }
            App.post(() -> {
                mAdapter.setWatched(seen);
                mAdapter.notifyDataSetChanged();
            });
        });
    }

    /**
     * Index to continue from: the most recently played file. If that one was already finished,
     * continue with the next file instead of replaying it.
     */
    private int lastWatchedIndex(List<SmbFile> videos) {
        List<History> push = pushHistories();
        int index = -1;
        long newest = Long.MIN_VALUE;
        for (int i = 0; i < videos.size(); i++) {
            History history = historyOf(videos.get(i), push);
            if (history == null || history.getCreateTime() <= newest) continue;
            newest = history.getCreateTime();
            index = history.isNearEnding() && i + 1 < videos.size() ? i + 1 : i;
        }
        return index;
    }

    @Override
    public void onItemClick(SmbFile item) {
        if (item.isDirectory()) {
            currentPath = item.getPath();
            listFiles();
            return;
        }
        // Play the clicked file and everything after it in the current folder.
        playFrom(item);
    }

    @Override
    protected void onBackInvoked() {
        if (!currentPath.isEmpty()) {
            int idx = currentPath.lastIndexOf('/');
            currentPath = idx < 0 ? "" : currentPath.substring(0, idx);
            listFiles();
        } else {
            super.onBackInvoked();
        }
    }
}
