package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterSmbBinding;
import com.fongmi.android.tv.smb.SmbFile;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

public class SmbAdapter extends BaseDiffAdapter<SmbFile, SmbAdapter.ViewHolder> {

    private final OnClickListener listener;

    public SmbAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onItemClick(SmbFile item);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSmbBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SmbFile item = getItem(position);
        holder.binding.icon.setImageResource(item.isDirectory() ? R.drawable.ic_smb_folder : R.drawable.ic_smb_file);
        holder.binding.name.setText(item.getName());
        holder.binding.info.setText(item.isDirectory() ? ResUtil.getString(R.string.smb_folder) : formatSize(item.getSize()));
        holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
        holder.setFocus();
    }

    private String formatSize(long size) {
        if (size <= 0) return "";
        float kb = size / 1024f;
        if (kb < 1024) return String.format("%.1f KB", kb);
        float mb = kb / 1024f;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024f);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        private final AdapterSmbBinding binding;

        public ViewHolder(@NonNull AdapterSmbBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        private void setFocus() {
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start();
                    v.setSelected(true);
                } else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    v.setSelected(false);
                }
            });
        }
    }
}
