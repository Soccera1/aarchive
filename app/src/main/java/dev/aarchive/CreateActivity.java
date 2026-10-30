package dev.aarchive;

import android.app.*;
import android.content.*;
import androidx.appcompat.app.AppCompatActivity;
import static dev.aarchive.MaterialUi.Text.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class CreateActivity extends AppCompatActivity {
    private static final int FILES = 10, FOLDER = 11, OUTPUT = 12;
    private CreationModel model;
    private Button files, folder, create, cancel;
    private MaterialUi.Dropdown format, codec;
    private MaterialUi.LevelSlider quality;
    private TextView qualityLabel, count, validation, status, empty;
    private ProgressBar progress;
    private SourceAdapter adapter;
    private String[] codecIds = {};
    private boolean rendering;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        MaterialUi.apply(this);
        model = ScreenModels.creation(this);
        buildUi();
        model.changed = this::render;
        render();
    }
    @Override protected void onDestroy() {
        model.changed = null;
        super.onDestroy();
    }
    private void buildUi() {
        LinearLayout root = column();
        root.setPadding(dp(16), dp(10), dp(16), dp(10));
        boolean compact = getResources().getConfiguration().screenHeightDp < 480;
        if (compact) {
            ScrollView scrolling = new ScrollView(this);
            scrolling.setFillViewport(true);
            scrolling.addView(root);
            MaterialUi.content(this, scrolling);
        } else MaterialUi.content(this, root);
        root.addView(MaterialUi.toolbar(this, "Create", this::finish));

        LinearLayout options = row();
        format = new MaterialUi.Dropdown(this, "Format");
        format.setContentDescription("Output format");
        format.setItems(CreationFormat.LABELS);
        codec = new MaterialUi.Dropdown(this, "Compression");
        codec.setContentDescription("Compression codec");
        options.addView(format, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams codecLayout = new LinearLayout.LayoutParams(0, -2, 1);
        codecLayout.setMarginStart(dp(8));
        options.addView(codec, codecLayout);
        root.addView(options, margin(-1, -2, 12));
        format.onSelectionChanged(position -> {
            if (!rendering && !locked() && format.getSelectedItemPosition() == position
                    && !model.format.equals(CreationFormat.IDS[position])) model.setFormat(CreationFormat.IDS[position]);
        });
        codec.onSelectionChanged(position -> {
            if (!rendering && !locked() && position < codecIds.length && codec.getSelectedItemPosition() == position
                    && !model.codec.equals(codecIds[position])) { model.codec = codecIds[position]; render(); }
        });
        qualityLabel = text("", SECONDARY);
        quality = new MaterialUi.LevelSlider(this);
        quality.setContentDescription("Compression level");
        quality.onChange(value -> {
            if (!locked()) { model.level = value; render(); }
        });
        root.addView(qualityLabel);
        root.addView(quality, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout add = row();
        files = button("Add files", false);
        files.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, !model.format.equals("raw"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), FILES));
        folder = button("Add folder", false);
        folder.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), FOLDER));
        add.addView(files, new LinearLayout.LayoutParams(0, dp(48), 1));
        add.addView(folder, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(add, margin(-1, -2, 8));
        count = text("", LABEL);
        root.addView(count, margin(-1, dp(32), 6));

        FrameLayout content = new FrameLayout(this);
        ListView list = new ListView(this);
        adapter = new SourceAdapter();
        list.setAdapter(adapter);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = text("Add files or a folder.", SECONDARY);
        empty.setGravity(Gravity.CENTER);
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, compact ? dp(180) : 0, compact ? 0 : 1));
        validation = text("", BODY);
        root.addView(validation, margin(-1, -2, 8));
        progress = MaterialUi.progress(this);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        status = text("", SECONDARY);
        status.setMaxLines(3);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setOnClickListener(v -> MaterialUi.dialog(this).setTitle("Operation details").setMessage(model.status).setPositiveButton("OK", null).show());
        root.addView(status, margin(-1, -2, 8));
        LinearLayout actions = row();
        cancel = button("Cancel", false);
        cancel.setOnClickListener(v -> { model.cancel(); model.status = getString(R.string.cancelling); render(); });
        actions.addView(cancel, new LinearLayout.LayoutParams(-2, dp(52)));
        create = button("Create", true);
        create.setOnClickListener(v -> {
            model.pickingOutput = true;
            render();
            try {
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(CreationFormat.mime(model.format, model.codec)).putExtra(Intent.EXTRA_TITLE, model.suggestedName())
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), OUTPUT);
            } catch (ActivityNotFoundException error) {
                model.pickingOutput = false;
                model.status = "No document picker is available.";
                render();
            }
        });
        actions.addView(create, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(actions, margin(-1, -2, 8));
    }

    private boolean locked() { return model.busy || model.pickingOutput; }
    private void render() {
        rendering = true;
        format.setSelection(Arrays.asList(CreationFormat.IDS).indexOf(model.format));
        String[] desired = CreationFormat.codecs(model.format);
        if (!Arrays.equals(codecIds, desired)) {
            codecIds = desired;
            String[] labels = new String[desired.length];
            for (int i = 0; i < labels.length; i++) labels[i] = CreationFormat.label(desired[i]);
            codec.setItems(labels);
        }
        codec.setSelection(Arrays.asList(codecIds).indexOf(model.codec));
        quality.value(model.level);
        qualityLabel.setText(getString(R.string.compression_level, model.level));
        qualityLabel.setVisibility(CreationFormat.hasLevel(model.codec) ? View.VISIBLE : View.GONE);
        quality.setVisibility(CreationFormat.hasLevel(model.codec) ? View.VISIBLE : View.GONE);
        quality.setEnabled(!locked());
        format.setEnabled(!locked()); codec.setEnabled(!locked());
        files.setEnabled(!locked()); folder.setEnabled(!locked() && !model.format.equals("raw"));
        count.setText(getResources().getQuantityString(R.plurals.source_count, model.sources.size(), model.sources.size()));
        count.setVisibility(model.sources.isEmpty() ? View.GONE : View.VISIBLE);
        empty.setText(model.format.equals("raw") ? "Add one file to compress." : "Add files or a folder.");
        empty.setVisibility(model.sources.isEmpty() && !model.busy ? View.VISIBLE : View.GONE);
        String error = model.selectionError();
        validation.setText(error);
        validation.setVisibility(!model.sources.isEmpty() && !error.isEmpty() ? View.VISIBLE : View.GONE);
        progress.setVisibility(model.busy ? View.VISIBLE : View.GONE);
        status.setText(model.status);
        status.setVisibility(model.status.isEmpty() ? View.GONE : View.VISIBLE);
        cancel.setVisibility(model.busy ? View.VISIBLE : View.GONE);
        cancel.setEnabled(!model.cancelled);
        create.setEnabled(!locked() && error.isEmpty());
        create.setText(model.format.equals("raw") ? "Compress file" : "Create archive");
        adapter.notifyDataSetChanged();
        rendering = false;
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OUTPUT) model.pickingOutput = false;
        if (result != RESULT_OK || data == null || model.busy) { render(); return; }
        if (request == OUTPUT && data.getData() != null) { model.create(data.getData()); return; }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if ((request == FILES || request == FOLDER) && !uris.isEmpty()) model.add(uris, request == FOLDER);
        else render();
    }

    private final class SourceAdapter extends BaseAdapter {
        public int getCount() { return model.sources.size(); }
        public Object getItem(int position) { return model.sources.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View reused, ViewGroup parent) {
            LinearLayout line = row();
            line.setPadding(0, dp(6), 0, dp(6));
            CreationModel.Source source = model.sources.get(position);
            LinearLayout details = column();
            TextView name = text(source.name, BODY);
            name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            details.addView(name);
            details.addView(text(source.directory ? "Folder" : source.size < 0 ? "Size unknown" : Formatter.formatFileSize(CreateActivity.this, source.size), SECONDARY));
            line.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            Button remove = button("Remove", false);
            remove.setContentDescription("Remove " + source.name);
            remove.setEnabled(!locked());
            remove.setOnClickListener(v -> {
                int index = model.sources.indexOf(source);
                if (index >= 0) model.remove(index);
            });
            line.addView(remove, new LinearLayout.LayoutParams(-2, dp(48)));
            return line;
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout row() { LinearLayout result = new LinearLayout(this); result.setGravity(Gravity.CENTER_VERTICAL); return result; }
    private TextView text(String value, MaterialUi.Text role) { return MaterialUi.text(this, value, role); }
    private Button button(String label, boolean primary) { return MaterialUi.button(this, label, primary); }
    private LinearLayout.LayoutParams margin(int width, int height, int top) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(width, height); result.topMargin = dp(top); return result;
    }
}
