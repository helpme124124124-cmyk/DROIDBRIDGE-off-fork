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

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.javalauncher.R;
import ca.dnamobile.javalauncher.launcher.InstanceLaunchSettings;
import ca.dnamobile.javalauncher.renderer.RendererInterface;
import ca.dnamobile.javalauncher.renderer.Renderers;
import ca.dnamobile.javalauncher.settings.MemoryAllocationUtils;

/**
 * CreateInstanceDialog-style editor for per-instance launch overrides.
 *
 * The action buttons live inside the custom Material card rather than using the
 * default AlertDialog button bar. That keeps Save/Cancel/Reset visible in
 * landscape and prevents showFullscreenSafeDialog-style OnShowListener
 * replacement from breaking the button callbacks.
 */
public final class PerInstanceSettingsDialog {
    private final Activity activity;
    private final String settingsKey;
    private final ArrayList<String> aliasKeys;
    @Nullable
    private final Runnable onDismiss;

    private AlertDialog dialog;

    private final ArrayList<RendererInterface> renderers = new ArrayList<>();
    private final ArrayList<String> rendererLabels = new ArrayList<>();
    private int selectedRendererIndex;
    private int selectedRuntimeIndex;

    private MaterialAutoCompleteTextView rendererDropdown;
    private MaterialAutoCompleteTextView runtimeDropdown;
    private TextInputEditText jvmArgsInput;
    private SwitchMaterial customRamSwitch;
    private TextInputEditText ramInput;
    private TextInputLayout ramInputLayout;
    private Slider ramSlider;
    private TextView ramSummary;
    private TextView ramRangeText;

    private int minRamMb;
    private int maxRamMb;
    private int sliderMaxRamMb;
    private int ramStepMb;
    private int selectedRamMb;
    private boolean updatingRamText;

    public PerInstanceSettingsDialog(
            @NonNull Activity activity,
            @NonNull String settingsKey,
            @Nullable List<String> aliasKeys,
            @Nullable Runnable onDismiss
    ) {
        this.activity = activity;
        this.settingsKey = settingsKey;
        this.aliasKeys = new ArrayList<>();
        addAlias(settingsKey);
        if (aliasKeys != null) {
            for (String key : aliasKeys) addAlias(key);
        }
        this.onDismiss = onDismiss;
    }

    public void show() {
        InstanceLaunchSettings.Settings settings = InstanceLaunchSettings.load(activity, settingsKey);
        prepareRendererChoices(settings);
        prepareRamBounds(settings);

        FrameLayout outer = new FrameLayout(activity);
        outer.setPadding(dp(4), dp(4), dp(4), dp(4));

        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(dp(26));
        card.setCardElevation(dp(8));
        card.setUseCompatPadding(true);
        card.setPreventCornerOverlap(true);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(14));
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        content.addView(createHeader(), matchWrap());

        ScrollView formScroll = new ScrollView(activity);
        formScroll.setFillViewport(false);
        formScroll.setClipToPadding(false);
        formScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        formScroll.setPadding(0, 0, 0, dp(8));

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, 0, 0, dp(8));
        formScroll.addView(form, matchWrap());

        form.addView(createRendererSection(settings), matchWrap());
        form.addView(createRuntimeSection(settings), matchWrap());
        form.addView(createJvmArgsSection(settings), matchWrap());
        form.addView(createRamSection(settings), matchWrap());

        // Critical: the form takes remaining space only. The action row stays outside
        // the ScrollView, so Save/Cancel/Reset can never be pushed below the screen.
        content.addView(formScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        content.addView(createActionsSection(), matchWrap());

        outer.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        dialog = new MaterialAlertDialogBuilder(activity)
                .setView(outer)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
                int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
                int targetWidth = Math.min(screenWidth - dp(24), dp(760));
                int availableHeight = screenHeight - dp(24);
                if (availableHeight < dp(260)) {
                    availableHeight = screenHeight - dp(8);
                }
                availableHeight = Math.max(1, availableHeight);
                int targetHeight = Math.min(availableHeight, dp(640));
                window.setLayout(targetWidth, targetHeight);
            }
        });
        dialog.setOnDismissListener(dialogInterface -> {
            if (onDismiss != null) onDismiss.run();
        });
        dialog.show();
    }

    private View createHeader() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(16));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));

        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setCornerRadius(dp(18));
        iconBackground.setColor(0xFF20242B);
        icon.setBackground(iconBackground);
        row.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(16), 0, 0, 0);

        TextView title = new TextView(activity);
        title.setText("Per Instance Settings");
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        textColumn.addView(title, matchWrap());

        TextView subtitle = new TextView(activity);
        subtitle.setText("Renderer, Java runtime, JVM arguments, and RAM for this instance only.");
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(4), 0, 0);
        textColumn.addView(subtitle, matchWrap());

        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View createRendererSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, "Renderer", "Use a specific renderer for this instance, or keep the launcher default.");

        rendererDropdown = new MaterialAutoCompleteTextView(activity);
        rendererDropdown.setInputType(InputType.TYPE_NULL);
        rendererDropdown.setSingleLine(true);
        rendererDropdown.setOnClickListener(view -> rendererDropdown.showDropDown());
        rendererDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, rendererLabels));
        selectedRendererIndex = resolveRendererSelectionIndex(settings.rendererIdentifier);
        rendererDropdown.setText(rendererLabels.get(selectedRendererIndex), false);
        rendererDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedRendererIndex = Math.max(0, Math.min(position, rendererLabels.size() - 1));
            rendererDropdown.setText(rendererLabels.get(selectedRendererIndex), false);
        });

        TextInputLayout layout = createDropdownLayout("Renderer");
        layout.addView(rendererDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createRuntimeSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, "Java runtime", "Default automatically picks the runtime for the Minecraft version.");

        String[] runtimeLabels = InstanceLaunchSettings.getRuntimeDisplayLabels();
        runtimeDropdown = new MaterialAutoCompleteTextView(activity);
        runtimeDropdown.setInputType(InputType.TYPE_NULL);
        runtimeDropdown.setSingleLine(true);
        runtimeDropdown.setOnClickListener(view -> runtimeDropdown.showDropDown());
        runtimeDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, runtimeLabels));
        selectedRuntimeIndex = InstanceLaunchSettings.runtimeIndexForName(settings.runtimeName);
        if (selectedRuntimeIndex < 0 || selectedRuntimeIndex >= runtimeLabels.length) selectedRuntimeIndex = 0;
        runtimeDropdown.setText(runtimeLabels[selectedRuntimeIndex], false);
        runtimeDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedRuntimeIndex = Math.max(0, Math.min(position, runtimeLabels.length - 1));
            runtimeDropdown.setText(runtimeLabels[selectedRuntimeIndex], false);
        });

        TextInputLayout layout = createDropdownLayout("Java runtime");
        layout.addView(runtimeDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createJvmArgsSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, "Custom JVM arguments", "Optional extra JVM flags. Memory and classpath flags are ignored by the launcher.");

        jvmArgsInput = new TextInputEditText(activity);
        jvmArgsInput.setSingleLine(false);
        jvmArgsInput.setMinLines(2);
        jvmArgsInput.setMaxLines(4);
        jvmArgsInput.setGravity(Gravity.TOP | Gravity.START);
        jvmArgsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        jvmArgsInput.setHint("-Dexample=true");
        jvmArgsInput.setText(settings.customJvmArgs == null ? "" : settings.customJvmArgs);

        TextInputLayout layout = createOutlinedLayout("Custom JVM arguments");
        layout.addView(jvmArgsInput, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createRamSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, "RAM", "Leave disabled to use the launcher-wide RAM value.");

        customRamSwitch = new SwitchMaterial(activity);
        customRamSwitch.setText("Use custom RAM for this instance");
        customRamSwitch.setChecked(settings.hasRamOverride());
        section.addView(customRamSwitch, matchWrap());

        ramInput = new TextInputEditText(activity);
        ramInput.setSingleLine(true);
        ramInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        ramInput.setSelectAllOnFocus(true);
        ramInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) applyTypedRamValue();
        });
        ramInput.setOnEditorActionListener((view, actionId, event) -> {
            applyTypedRamValue();
            return false;
        });

        ramInputLayout = createOutlinedLayout("RAM in MB");
        ramInputLayout.addView(ramInput, matchWrap());
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.setMargins(0, dp(8), 0, 0);
        section.addView(ramInputLayout, inputParams);

        ramSlider = new Slider(activity);
        ramSlider.setValueFrom(minRamMb);
        ramSlider.setValueTo(sliderMaxRamMb);
        ramSlider.setStepSize(ramStepMb);
        ramSlider.setValue(clampRamToSliderRange(selectedRamMb));
        ramSlider.setLabelFormatter(value -> Math.round(value) + " MB");
        ramSlider.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        ramSlider.addOnChangeListener((slider, value, fromUser) -> {
            selectedRamMb = Math.round(value);
            updateRamViews();
        });
        LinearLayout.LayoutParams sliderParams = matchWrap();
        sliderParams.setMargins(0, dp(8), 0, 0);
        section.addView(ramSlider, sliderParams);

        ramSummary = new TextView(activity);
        ramSummary.setTextSize(13);
        ramSummary.setPadding(0, dp(4), 0, 0);
        section.addView(ramSummary, matchWrap());

        ramRangeText = new TextView(activity);
        ramRangeText.setTextSize(12);
        ramRangeText.setPadding(0, dp(4), 0, 0);
        section.addView(ramRangeText, matchWrap());

        customRamSwitch.setOnCheckedChangeListener((buttonView, checked) -> updateRamViews());
        updateRamViews();
        return section;
    }

    private View createActionsSection() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setPadding(0, dp(12), 0, 0);

        MaterialButton reset = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        reset.setText("Reset");
        reset.setAllCaps(false);
        reset.setOnClickListener(view -> {
            clearAllAliases();
            Toast.makeText(activity, "Per-instance settings reset.", Toast.LENGTH_SHORT).show();
            dismiss();
        });
        row.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton cancel = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText(android.R.string.cancel);
        cancel.setAllCaps(false);
        cancel.setOnClickListener(view -> dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.setMargins(dp(8), 0, 0, 0);
        row.addView(cancel, cancelParams);

        MaterialButton save = new MaterialButton(activity);
        save.setText("Save");
        save.setAllCaps(false);
        save.setOnClickListener(view -> saveAndDismiss());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        saveParams.setMargins(dp(8), 0, 0, 0);
        row.addView(save, saveParams);

        return row;
    }

    private void prepareRendererChoices(@NonNull InstanceLaunchSettings.Settings settings) {
        Renderers.reload(activity);
        renderers.clear();
        renderers.addAll(Renderers.getCompatibleRenderers(activity));
        rendererLabels.clear();
        rendererLabels.add("Default launcher renderer");
        for (RendererInterface renderer : renderers) {
            rendererLabels.add(renderer.getRendererName() + (renderer.isExternalPlugin() ? "  •  Plugin" : ""));
        }
        if (rendererLabels.isEmpty()) rendererLabels.add("Default launcher renderer");
    }

    private void prepareRamBounds(@NonNull InstanceLaunchSettings.Settings settings) {
        maxRamMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(activity);
        minRamMb = MemoryAllocationUtils.getMinimumMemoryMb(maxRamMb);
        ramStepMb = Math.max(1, MemoryAllocationUtils.RAM_STEP_MB);
        int stepCount = Math.max(1, (maxRamMb - minRamMb) / ramStepMb);
        sliderMaxRamMb = minRamMb + (stepCount * ramStepMb);
        selectedRamMb = settings.hasRamOverride()
                ? settings.ramMb
                : MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
        selectedRamMb = clampRamToSliderRange(selectedRamMb);
    }

    private void saveAndDismiss() {
        InstanceLaunchSettings.Settings settings = InstanceLaunchSettings.load(activity, settingsKey);

        if (selectedRendererIndex > 0 && selectedRendererIndex - 1 < renderers.size()) {
            settings.rendererIdentifier = renderers.get(selectedRendererIndex - 1).getUniqueIdentifier();
        } else {
            settings.rendererIdentifier = InstanceLaunchSettings.RENDERER_DEFAULT;
        }

        settings.runtimeName = InstanceLaunchSettings.runtimeNameForIndex(selectedRuntimeIndex);
        settings.customJvmArgs = jvmArgsInput != null && jvmArgsInput.getText() != null
                ? jvmArgsInput.getText().toString().trim()
                : "";

        boolean customRam = customRamSwitch != null && customRamSwitch.isChecked();
        if (customRam) {
            applyTypedRamValue();
            settings.ramMb = clampRamToSliderRange(selectedRamMb);
        } else {
            settings.ramMb = InstanceLaunchSettings.RAM_DEFAULT;
        }

        saveAllAliases(settings);
        Toast.makeText(activity, "Per-instance settings saved.", Toast.LENGTH_SHORT).show();
        dismiss();
    }

    private void saveAllAliases(@NonNull InstanceLaunchSettings.Settings settings) {
        for (String key : aliasKeys) {
            InstanceLaunchSettings.save(activity, key, settings);
        }
    }

    private void clearAllAliases() {
        for (String key : aliasKeys) {
            InstanceLaunchSettings.clear(activity, key);
        }
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    private void applyTypedRamValue() {
        if (ramInput == null || ramInput.getText() == null) return;
        String raw = ramInput.getText().toString().trim();
        if (raw.isEmpty()) {
            selectedRamMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
        } else {
            try {
                selectedRamMb = Integer.parseInt(raw);
            } catch (Throwable ignored) {
                selectedRamMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
            }
        }
        selectedRamMb = clampRamToSliderRange(selectedRamMb);
        if (ramSlider != null && Math.round(ramSlider.getValue()) != selectedRamMb) {
            ramSlider.setValue(selectedRamMb);
        }
        updateRamViews();
    }

    private void updateRamViews() {
        boolean custom = customRamSwitch != null && customRamSwitch.isChecked();
        int globalMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);

        if (ramInput != null) {
            ramInput.setEnabled(custom);
            ramInput.setAlpha(custom ? 1f : 0.55f);
        }
        if (ramInputLayout != null) {
            ramInputLayout.setEnabled(custom);
        }
        if (ramSlider != null) {
            ramSlider.setEnabled(custom);
            ramSlider.setAlpha(custom ? 1f : 0.45f);
        }

        int displayMb = custom ? selectedRamMb : globalMb;
        setRamInputText(displayMb);

        if (ramSummary != null) {
            if (custom) {
                ramSummary.setText("Custom RAM: " + selectedRamMb + " MB (" + formatGb(selectedRamMb) + " GB)");
            } else {
                ramSummary.setText("Using launcher default: " + globalMb + " MB (" + formatGb(globalMb) + " GB)");
            }
        }
        if (ramRangeText != null) {
            ramRangeText.setText("Range: " + minRamMb + " MB - " + sliderMaxRamMb + " MB · Step: " + ramStepMb + " MB");
        }
    }

    private void setRamInputText(int memoryMb) {
        if (ramInput == null || updatingRamText) return;
        String value = String.valueOf(memoryMb);
        String current = ramInput.getText() == null ? "" : ramInput.getText().toString();
        if (value.equals(current)) return;
        updatingRamText = true;
        ramInput.setText(value);
        ramInput.setSelection(ramInput.length());
        updatingRamText = false;
    }

    private int clampRamToSliderRange(int memoryMb) {
        int clamped = MemoryAllocationUtils.clampToAllowedRam(activity, memoryMb);
        clamped = Math.max(minRamMb, Math.min(sliderMaxRamMb, clamped));
        int offset = clamped - minRamMb;
        int roundedSteps = Math.round(offset / (float) ramStepMb);
        int rounded = minRamMb + roundedSteps * ramStepMb;
        return Math.max(minRamMb, Math.min(sliderMaxRamMb, rounded));
    }

    private int resolveRendererSelectionIndex(@Nullable String selectedRendererId) {
        if (selectedRendererId == null || selectedRendererId.trim().isEmpty()) return 0;
        for (int i = 0; i < renderers.size(); i++) {
            if (selectedRendererId.equals(renderers.get(i).getUniqueIdentifier())) {
                return i + 1;
            }
        }
        return 0;
    }

    private LinearLayout createSection() {
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(8), 0, dp(10));
        return section;
    }

    private void addSectionTitle(@NonNull LinearLayout root, @NonNull String titleText, @NonNull String summaryText) {
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextSize(15);
        root.addView(title, matchWrap());

        TextView summary = new TextView(activity);
        summary.setText(summaryText);
        summary.setTextSize(12);
        summary.setPadding(0, dp(2), 0, dp(8));
        root.addView(summary, matchWrap());
    }

    private TextInputLayout createOutlinedLayout(@NonNull String hint) {
        TextInputLayout layout = new TextInputLayout(activity);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxCornerRadii(dp(14), dp(14), dp(14), dp(14));
        return layout;
    }

    private TextInputLayout createDropdownLayout(@NonNull String hint) {
        TextInputLayout layout = createOutlinedLayout(hint);
        layout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        return layout;
    }

    private int calculateMaxFormHeight() {
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        return Math.min(dp(440), Math.max(dp(250), height - dp(310)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @NonNull
    private String formatGb(int memoryMb) {
        return String.format(Locale.US, "%.1f", memoryMb / 1024f);
    }

    private void addAlias(@Nullable String rawKey) {
        if (rawKey == null) return;
        String key = InstanceLaunchSettings.resolveInstanceKey(rawKey, rawKey);
        if (key.trim().isEmpty()) return;
        if (!aliasKeys.contains(key)) aliasKeys.add(key);
    }
}
