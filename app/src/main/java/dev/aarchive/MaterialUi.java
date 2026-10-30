package dev.aarchive;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Build;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.*;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.*;
import java.util.function.IntConsumer;

/** The resource-selected Material generation owns colors, shapes and typography. */
final class MaterialUi {
    enum Text { TITLE, BODY, SECONDARY, LABEL }
    private MaterialUi() {}
    static int generation(Context context) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(R.attr.archiveMaterialGeneration, value, true);
        return value.data;
    }
    static void apply(Activity activity) {
        if (generation(activity) >= 3 && Build.VERSION.SDK_INT >= 31) DynamicColors.applyToActivityIfAvailable(activity);
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        // Android 8.0 cannot draw dark navigation icons on a light background.
        window.setNavigationBarColor(Build.VERSION.SDK_INT >= 27 ? Color.TRANSPARENT : Color.BLACK);
        boolean light = ColorUtils.calculateLuminance(background(activity)) > 0.5;
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightStatusBars(light);
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightNavigationBars(Build.VERSION.SDK_INT >= 27 && light);
    }
    static int background(Context context) { return color(context, android.R.attr.colorBackground).getDefaultColor(); }
    private static ColorStateList color(Context context, int attribute) {
        TypedArray values = context.obtainStyledAttributes(new int[]{attribute});
        try { return values.getColorStateList(0); }
        finally { values.recycle(); }
    }
    static void content(Activity activity, View child) {
        FrameLayout viewport = new FrameLayout(activity);
        viewport.setBackgroundColor(background(activity));
        ViewCompat.setOnApplyWindowInsetsListener(viewport, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        viewport.addView(child, new FrameLayout.LayoutParams(-1, -1));
        activity.setContentView(viewport);
        ViewCompat.requestApplyInsets(viewport);
    }
    static TextView text(Context context, String label, Text role) {
        TextView view = new AppCompatTextView(context);
        int attribute;
        if (generation(context) >= 3) {
            attribute = role == Text.TITLE ? com.google.android.material.R.attr.textAppearanceTitleLarge
                    : role == Text.LABEL ? com.google.android.material.R.attr.textAppearanceLabelMedium
                    : role == Text.SECONDARY ? com.google.android.material.R.attr.textAppearanceBodyMedium
                    : com.google.android.material.R.attr.textAppearanceBodyLarge;
        } else if (generation(context) == 2) {
            attribute = role == Text.TITLE ? com.google.android.material.R.attr.textAppearanceHeadline6
                    : role == Text.LABEL ? com.google.android.material.R.attr.textAppearanceCaption
                    : role == Text.SECONDARY ? com.google.android.material.R.attr.textAppearanceBody2
                    : com.google.android.material.R.attr.textAppearanceBody1;
        } else {
            attribute = role == Text.TITLE ? android.R.attr.textAppearanceLarge
                    : role == Text.BODY ? android.R.attr.textAppearanceMedium : android.R.attr.textAppearanceSmall;
        }
        TypedValue appearance = new TypedValue();
        if (context.getTheme().resolveAttribute(attribute, appearance, true)) view.setTextAppearance(appearance.resourceId);
        view.setTextColor(color(context, role == Text.SECONDARY || role == Text.LABEL
                ? android.R.attr.textColorSecondary : android.R.attr.textColorPrimary));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setText(label);
        return view;
    }
    static Button button(Context context, String label, boolean primary) {
        Button button;
        if (generation(context) >= 2) {
            int style = primary ? com.google.android.material.R.attr.materialButtonStyle : androidx.appcompat.R.attr.borderlessButtonStyle;
            button = new MaterialButton(context, null, style);
        } else button = new AppCompatButton(context, null,
                primary ? R.attr.archivePrimaryButtonStyle : androidx.appcompat.R.attr.borderlessButtonStyle);
        button.setText(label);
        return button;
    }
    static CheckBox checkbox(Context context) {
        return generation(context) >= 2 ? new MaterialCheckBox(context) : new AppCompatCheckBox(context);
    }
    static ProgressBar progress(Context context) {
        return generation(context) >= 2 ? new LinearProgressIndicator(context)
                : new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
    }
    static AlertDialog.Builder dialog(Context context) {
        return generation(context) >= 2 ? new MaterialAlertDialogBuilder(context) : new AlertDialog.Builder(context);
    }
    static View card(Context context, View content) {
        CardView card = generation(context) >= 2 ? new MaterialCardView(context) : new CardView(context);
        card.addView(content);
        return card;
    }
    static Toolbar toolbar(Context context, String title, Runnable back) {
        Toolbar toolbar = generation(context) >= 2 ? new MaterialToolbar(context) : new Toolbar(context);
        toolbar.setTitle(title);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationContentDescription("Back");
        toolbar.setNavigationOnClickListener(view -> back.run());
        TypedValue height = new TypedValue();
        if (context.getTheme().resolveAttribute(androidx.appcompat.R.attr.actionBarSize, height, true))
            toolbar.setMinimumHeight(TypedValue.complexToDimensionPixelSize(height.data, context.getResources().getDisplayMetrics()));
        return toolbar;
    }
    static EditText edit(Context context) {
        return generation(context) >= 2 ? new TextInputEditText(context) : new AppCompatEditText(context);
    }
    static View input(Context context, EditText edit, String hint, boolean clear) {
        if (generation(context) < 2) { edit.setHint(hint); return edit; }
        TextInputLayout layout = new TextInputLayout(context, null, com.google.android.material.R.attr.textInputStyle);
        layout.setHint(hint);
        if (clear) layout.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        layout.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        return layout;
    }

    @android.annotation.SuppressLint("ViewConstructor") // Programmatic widget with a required semantic label.
    static final class Dropdown extends FrameLayout {
        private final Spinner spinner;
        private final MaterialAutoCompleteTextView menu;
        private String[] labels = {};
        private int selected;
        private IntConsumer changed;
        Dropdown(Context context, String hint) {
            super(context);
            if (generation(context) < 2) {
                spinner = new AppCompatSpinner(context);
                menu = null;
                LinearLayout column = new LinearLayout(context);
                column.setOrientation(LinearLayout.VERTICAL);
                column.addView(text(context, hint, Text.LABEL));
                column.addView(spinner, new LinearLayout.LayoutParams(-1, -2));
                addView(column);
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    public void onNothingSelected(AdapterView<?> parent) {}
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        if (position == spinner.getSelectedItemPosition()) select(position);
                    }
                });
            } else {
                spinner = null;
                TextInputLayout layout = new TextInputLayout(context, null, R.attr.archiveDropdownStyle);
                layout.setHint(hint);
                menu = new MaterialAutoCompleteTextView(layout.getContext());
                menu.setInputType(android.text.InputType.TYPE_NULL);
                layout.addView(menu, new LinearLayout.LayoutParams(-1, -2));
                addView(layout);
                menu.setOnClickListener(view -> menu.showDropDown());
                menu.setOnItemClickListener((parent, view, position, id) -> select(position));
            }
        }
        private void select(int position) { selected = position; if (changed != null) changed.accept(position); }
        void setItems(String[] labels) {
            this.labels = labels;
            if (spinner != null) spinner.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, labels));
            else menu.setSimpleItems(labels);
        }
        void setSelection(int position) {
            if (position < 0 || position >= labels.length) return;
            selected = position;
            if (spinner != null) spinner.setSelection(position);
            else menu.setText(labels[position], false);
        }
        int getSelectedItemPosition() { return selected; }
        void onSelectionChanged(IntConsumer listener) { changed = listener; }
        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            if (spinner != null) spinner.setEnabled(enabled);
            if (menu != null) { menu.setEnabled(enabled); getChildAt(0).setEnabled(enabled); }
        }
    }

    static final class LevelSlider extends FrameLayout {
        private final SeekBar legacy;
        private final Slider material;
        private IntConsumer changed;
        LevelSlider(Context context) {
            super(context);
            if (generation(context) >= 2) {
                legacy = null;
                material = new Slider(context);
                material.setValueFrom(1); material.setValueTo(9); material.setStepSize(1);
                material.addOnChangeListener((slider, value, fromUser) -> {
                    if (fromUser && changed != null) changed.accept((int) value);
                });
                addView(material, new FrameLayout.LayoutParams(-1, -2));
            } else {
                material = null;
                legacy = new AppCompatSeekBar(context);
                legacy.setMax(8);
                legacy.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar bar) {}
                    public void onStopTrackingTouch(SeekBar bar) {}
                    public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                        if (fromUser && changed != null) changed.accept(value + 1);
                    }
                });
                addView(legacy, new FrameLayout.LayoutParams(-1, -2));
            }
        }
        void value(int value) { if (material != null) material.setValue(value); else legacy.setProgress(value - 1); }
        void onChange(IntConsumer changed) { this.changed = changed; }
        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            if (material != null) material.setEnabled(enabled);
            if (legacy != null) legacy.setEnabled(enabled);
        }
    }
}
