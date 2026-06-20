/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, PojavLauncher, Zalith Launcher, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.javalauncher.ui.instance;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.javalauncher.R;
import ca.dnamobile.javalauncher.databinding.ItemLauncherInstanceBinding;
import ca.dnamobile.javalauncher.instance.LauncherInstance;

public final class LauncherInstanceAdapter extends RecyclerView.Adapter<LauncherInstanceAdapter.InstanceViewHolder> {
    public interface Listener {
        void onInstanceSelected(@NonNull LauncherInstance instance);
        void onInstanceQuickPlayRequested(@NonNull LauncherInstance instance);

        void onInstanceDeleteRequested(@NonNull LauncherInstance instance);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final Listener listener;
    private final ArrayList<LauncherInstance> instances = new ArrayList<>();
    @Nullable
    private String selectedInstanceKey;

    public LauncherInstanceAdapter(@NonNull Context context, @NonNull Listener listener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
    }

    public void submitList(@NonNull List<LauncherInstance> newInstances) {
        instances.clear();
        instances.addAll(newInstances);
        notifyDataSetChanged();
    }

    public void setSelectedInstance(@Nullable LauncherInstance instance) {
        this.selectedInstanceKey = instance == null ? null : getSelectionKey(instance);
        notifyDataSetChanged();
    }

    public void clearSelectedInstance() {
        this.selectedInstanceKey = null;
        notifyDataSetChanged();
    }

    public void setSelectedInstanceId(@Nullable String selectedInstanceId) {
        if (selectedInstanceId == null) {
            clearSelectedInstance();
            return;
        }

        for (LauncherInstance instance : instances) {
            if (selectedInstanceId.equals(instance.getId())) {
                setSelectedInstance(instance);
                return;
            }
        }

        clearSelectedInstance();
    }

    @NonNull
    @Override
    public InstanceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemLauncherInstanceBinding binding = ItemLauncherInstanceBinding.inflate(inflater, parent, false);
        return new InstanceViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull InstanceViewHolder holder, int position) {
        LauncherInstance instance = instances.get(position);
        boolean selected = selectedInstanceKey != null
                && selectedInstanceKey.equals(getSelectionKey(instance));

        holder.binding.textInstanceName.setText(instance.getName());
        holder.binding.textInstanceMeta.setText(context.getString(
                instance.isIsolated() ? R.string.instance_meta_value : R.string.instance_meta_shared_value,
                displayLoader(instance.getLoader()),
                instance.getBaseVersionId(),
                displayVersionType(instance.getVersionType()),
                cleanDate(instance.getCreatedAt())
        ));
        holder.binding.textInstanceState.setText(instance.isIsolated()
                ? R.string.version_state_installed
                : R.string.instance_state_shared);

        bindInstanceIcon(holder, instance);

        MaterialCardView card = holder.binding.instanceCard;
        int selectedColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimary);
        int outlineColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOutline);
        card.setStrokeColor(selected ? selectedColor : outlineColor);
        card.setStrokeWidth(selected ? dp(2) : dp(1));

        holder.binding.getRoot().setOnClickListener(view -> {
            setSelectedInstance(instance);
            listener.onInstanceSelected(instance);
        });
        holder.binding.getRoot().setOnLongClickListener(view -> {
            listener.onInstanceDeleteRequested(instance);
            return true;
        });

        holder.binding.buttonDeleteInstance.setImageResource(R.drawable.ic_play_arrow_24);
        holder.binding.buttonDeleteInstance.setContentDescription("Play instance");
        holder.binding.buttonDeleteInstance.setOnClickListener(view -> listener.onInstanceQuickPlayRequested(instance));
    }

    private void bindInstanceIcon(@NonNull InstanceViewHolder holder, @NonNull LauncherInstance instance) {
        holder.binding.imageInstanceIcon.setImageDrawable(null);

        File iconFile = instance.getIconFile();
        if (iconFile != null && iconFile.isFile()) {
            try {
                holder.binding.imageInstanceIcon.setImageURI(Uri.fromFile(iconFile));
                if (holder.binding.imageInstanceIcon.getDrawable() != null) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        holder.binding.imageInstanceIcon.setImageResource(InstanceIconResolver.getDefaultIcon(instance));
    }

    @Override
    public int getItemCount() {
        return instances.size();
    }

    @NonNull
    public List<LauncherInstance> getCurrentItems() {
        return Collections.unmodifiableList(instances);
    }

    @NonNull
    private String displayLoader(@Nullable String loader) {
        if (loader == null || loader.isBlank()) return "Vanilla";
        return loader.substring(0, 1).toUpperCase(Locale.US) + loader.substring(1);
    }

    @NonNull
    private static String displayVersionType(@Nullable String type) {
        if (type == null) return "Unknown";
        switch (type) {
            case "release":
                return "Release";
            case "snapshot":
                return "Snapshot";
            case "old_beta":
                return "Beta";
            case "old_alpha":
                return "Alpha";
            default:
                return type.substring(0, 1).toUpperCase(Locale.US) + type.substring(1).replace('_', ' ');
        }
    }

    @NonNull
    private static String cleanDate(@Nullable String releaseTime) {
        if (releaseTime == null || releaseTime.isBlank()) return "Unknown date";
        int index = releaseTime.indexOf('T');
        return index > 0 ? releaseTime.substring(0, index) : releaseTime;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static final class InstanceViewHolder extends RecyclerView.ViewHolder {
        final ItemLauncherInstanceBinding binding;

        InstanceViewHolder(@NonNull ItemLauncherInstanceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    @NonNull
    public static String getSelectionKey(@NonNull LauncherInstance instance) {
        if (instance.isIsolated()) {
            return "isolated:"
                    + safePath(instance.getRootDirectory())
                    + ":"
                    + nullToEmpty(instance.getName());
        }

        return "shared:"
                + safePath(instance.getGameDirectory())
                + ":"
                + nullToEmpty(instance.getBaseVersionId());
    }

    @NonNull
    private static String safePath(@Nullable File file) {
        if (file == null) return "";
        return file.getAbsoluteFile().getAbsolutePath();
    }

    @NonNull
    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
