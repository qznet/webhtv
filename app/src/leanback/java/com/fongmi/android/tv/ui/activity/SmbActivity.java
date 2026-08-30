package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.databinding.ActivitySmbBinding;
import com.fongmi.android.tv.smb.SmbClient;
import com.fongmi.android.tv.smb.SmbFile;
import com.fongmi.android.tv.smb.SmbServer;
import com.fongmi.android.tv.smb.SmbStore;
import com.fongmi.android.tv.ui.adapter.SmbAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.dialog.ChoiceDialog;
import com.fongmi.android.tv.ui.dialog.SmbServerDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class SmbActivity extends BaseActivity implements SmbAdapter.OnClickListener {

    private ActivitySmbBinding mBinding;
    private SmbAdapter mAdapter;
    private List<SmbServer> servers = new ArrayList<>();
    private int selectedIndex = 0;
    private String currentShare = "";
    private String currentPath = "";

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
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(Product.getColumn(), 16));
        mBinding.server.setOnClickListener(v -> openServerChooser());
        mBinding.share.setOnClickListener(v -> promptShare());
        mBinding.add.setOnClickListener(v -> addServer());
        mBinding.refresh.setOnClickListener(v -> listFiles());
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

    @Override
    public void onItemClick(SmbFile item) {
        if (item.isDirectory()) {
            currentPath = item.getPath();
            listFiles();
            return;
        }
        SmbServer s = servers.get(selectedIndex);
        String url = s.getFileUrl(currentShare, item.getPath());
        VideoActivity.start(this, url);
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
