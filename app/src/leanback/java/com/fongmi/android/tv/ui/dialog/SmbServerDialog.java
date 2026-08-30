package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSmbServerBinding;
import com.fongmi.android.tv.smb.SmbServer;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SmbServerDialog extends DialogFragment {

    private DialogSmbServerBinding mBinding;
    private SmbServer editing;
    private OnSave onSave;
    private OnDelete onDelete;

    public interface OnSave {
        void onSave(SmbServer server);
    }

    public interface OnDelete {
        void onDelete(String id);
    }

    public static void show(androidx.fragment.app.FragmentActivity activity, SmbServer server,
                             OnSave onSave, OnDelete onDelete) {
        SmbServerDialog d = new SmbServerDialog();
        d.editing = server;
        d.onSave = onSave;
        d.onDelete = onDelete;
        d.show(activity.getSupportFragmentManager(), "SmbServerDialog");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        mBinding = DialogSmbServerBinding.inflate(LayoutInflater.from(requireActivity()));
        if (editing != null) fill(editing);
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(requireActivity());
        b.setTitle(editing == null ? R.string.smb_add_server : R.string.smb_edit_server);
        b.setView(mBinding.getRoot());
        b.setPositiveButton(android.R.string.ok, (d, w) -> save());
        b.setNegativeButton(android.R.string.cancel, null);
        if (editing != null) b.setNeutralButton(R.string.smb_delete, (d, w) -> {
            if (onDelete != null) onDelete.onDelete(editing.getId());
        });
        return b.create();
    }

    private void fill(SmbServer s) {
        mBinding.name.setText(s.getName());
        mBinding.host.setText(s.getHost());
        if (s.getPort() != 445) mBinding.port.setText(String.valueOf(s.getPort()));
        mBinding.share.setText(s.getShare());
        mBinding.user.setText(s.getUser());
        mBinding.pass.setText(s.getPass());
        mBinding.domain.setText(s.getDomain());
    }

    private void save() {
        SmbServer s = editing != null ? editing : new SmbServer();
        s.setName(mBinding.name.getText().toString().trim());
        s.setHost(mBinding.host.getText().toString().trim());
        String portStr = mBinding.port.getText().toString().trim();
        int port = 445;
        if (!TextUtils.isEmpty(portStr)) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException ignored) {
            }
        }
        s.setPort(port);
        s.setShare(mBinding.share.getText().toString().trim());
        s.setUser(mBinding.user.getText().toString());
        s.setPass(mBinding.pass.getText().toString());
        s.setDomain(mBinding.domain.getText().toString().trim());
        if (TextUtils.isEmpty(s.getHost()) || TextUtils.isEmpty(s.getName())) {
            Notify.show(R.string.smb_invalid);
            return;
        }
        if (onSave != null) onSave.onSave(s);
    }
}
