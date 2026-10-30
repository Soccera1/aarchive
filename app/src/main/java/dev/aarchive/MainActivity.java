package dev.aarchive;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.*;
import android.text.format.Formatter;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int OPEN = 1, DESTINATION = 2;
    private static final int INK = 0xff202c27, MUTED = 0xff63716a, GREEN = 0xff285a46, PAPER = 0xfff6f7f2;
    private ArchiveModel model;
    private TextView archiveName, archiveInfo, status, count, empty;
    private Button open, createArchive, password, extract, select, cancel;
    private EditText search;
    private ProgressBar progress;
    private LinearLayout archiveCard, queryControls, selectionControls, bottomActions;
    private EntriesAdapter adapter;
    private final List<Integer> visible = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        model = (ArchiveModel) getLastNonConfigurationInstance();
        if (model == null) model = new ArchiveModel(this);
        buildUi();
        model.changed = this::render;
        render();
        if (state == null && Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null)
            importUri(getIntent().getData());
    }

    @Override public Object onRetainNonConfigurationInstance() { return model; }
    @Override protected void onDestroy() {
        model.changed = null;
        if (isFinishing()) model.close();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(PAPER);
        root.setPadding(dp(22), dp(12), dp(22), dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(22) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(22) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        boolean compact = getResources().getConfiguration().screenHeightDp < 600;
        LinearLayout compactHeader = row();
        open = button("Open file", true);
        createArchive = button("Create", false);
        createArchive.setOnClickListener(v -> startActivity(new Intent(this, CreateActivity.class)));
        if (compact) {
            compactHeader.addView(open, new LinearLayout.LayoutParams(dp(120), dp(48)));
            compactHeader.addView(createArchive, new LinearLayout.LayoutParams(dp(100), dp(48)));
            root.addView(compactHeader);
        } else {
            LinearLayout fileActions = row();
            fileActions.addView(open, new LinearLayout.LayoutParams(0, dp(48), 1));
            fileActions.addView(createArchive, new LinearLayout.LayoutParams(dp(90), dp(48)));
            Button formats = button("Formats", false);
            formats.setOnClickListener(v -> showFormats());
            fileActions.addView(formats, new LinearLayout.LayoutParams(dp(100), dp(48)));
            root.addView(fileActions);
        }
        open.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, OPEN);
        });

        LinearLayout card = column();
        archiveCard = card;
        card.setPadding(dp(14), dp(8), dp(14), dp(8));
        card.setBackground(shape(Color.WHITE, 18));
        archiveName = text("", 18, INK, true);
        archiveName.setMaxLines(2);
        archiveName.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout cardRow = row();
        LinearLayout cardLabels = column();
        cardLabels.addView(archiveName);
        archiveInfo = text("", 12, MUTED, false);
        if (!compact) cardLabels.addView(archiveInfo, spaced(-1, -2, 4));
        cardRow.addView(cardLabels, new LinearLayout.LayoutParams(0, -2, 1));
        password = button("Password", false);
        cardRow.addView(password, new LinearLayout.LayoutParams(-2, dp(48)));
        card.addView(cardRow);
        password.setOnClickListener(v -> passwordDialog());
        if (compact) compactHeader.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
        else root.addView(card, spaced(-1, -2, 12));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("Search paths…");
        search.setContentDescription("Search archive entries");
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(shape(0xffe9ede6, 12));
        queryControls = row();
        queryControls.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(queryControls, spaced(-1, -2, compact ? 4 : 12));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            public void afterTextChanged(Editable s) {}
        });
        LinearLayout selection = row();
        selectionControls = selection;
        count = text("", 12, MUTED, true);
        selection.addView(count, new LinearLayout.LayoutParams(0, dp(48), 1));
        select = button("Select all", false);
        if (compact) queryControls.addView(select, new LinearLayout.LayoutParams(-2, dp(48)));
        else selection.addView(select, new LinearLayout.LayoutParams(-2, dp(48)));
        select.setOnClickListener(v -> {
            if (allSelected()) model.selected.clear();
            else for (int index : visible) if (model.entries.get(index).extractable()) model.selected.add(index);
            render();
        });
        if (!compact) root.addView(selection);

        FrameLayout contents = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        list.setDivider(new android.graphics.drawable.ColorDrawable(0xffe3e7df));
        list.setBackgroundColor(Color.TRANSPARENT);
        adapter = new EntriesAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (model.busy || model.format.startsWith("Compressed file")) return;
            int index = visible.get(position);
            if (!model.entries.get(index).extractable()) return;
            if (!model.selected.remove(index)) model.selected.add(index);
            render();
        });
        contents.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = text("", 17, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(20), dp(20), dp(20));
        contents.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(contents, new LinearLayout.LayoutParams(-1, 0, 1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        status = text("", 12, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setMaxLines(compact ? 2 : 4);
        status.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Operation details")
                .setMessage(model.status).setPositiveButton("OK", null).show());
        root.addView(status, spaced(-1, -2, 10));
        LinearLayout actions = row();
        bottomActions = actions;
        cancel = button("Cancel", false);
        cancel.setOnClickListener(v -> { model.cancelled = true; cancel.setEnabled(false); status.setText(R.string.cancelling); });
        actions.addView(cancel, new LinearLayout.LayoutParams(-2, dp(52)));
        extract = button("Extract all", true);
        actions.addView(extract, new LinearLayout.LayoutParams(0, dp(52), 1));
        extract.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), DESTINATION));
        root.addView(actions, spaced(-1, -2, 10));
    }

    private void passwordDialog() {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setHint("Archive password");
        LinearLayout panel = column();
        panel.setPadding(dp(24), 0, dp(24), 0);
        panel.addView(field);
        new AlertDialog.Builder(this).setTitle("Unlock archive").setMessage("The password stays in memory for this session.")
                .setView(panel).setNegativeButton("Cancel", null)
                .setPositiveButton("Retry", (dialog, which) -> {
                    model.password(field.getText().toString());
                    field.setText("");
                }).show();
    }

    private void render() {
        boolean standalone = model.ready && model.format.startsWith("Compressed file");
        archiveName.setText(model.name);
        archiveInfo.setText(model.ready ? model.format + (standalone ? "" : " · "
                + getResources().getQuantityString(R.plurals.entry_count, model.entries.size(), model.entries.size())) : "");
        archiveInfo.setVisibility(model.ready ? View.VISIBLE : View.GONE);
        archiveCard.setVisibility(model.hasSource() || model.busy ? View.VISIBLE : View.GONE);
        queryControls.setVisibility(model.ready && !standalone ? View.VISIBLE : View.GONE);
        selectionControls.setVisibility(model.ready && !standalone ? View.VISIBLE : View.GONE);
        status.setText(model.status);
        status.setVisibility(model.status.isEmpty() ? View.GONE : View.VISIBLE);
        open.setEnabled(!model.busy);
        createArchive.setEnabled(!model.busy);
        password.setEnabled(!model.busy && model.hasSource());
        password.setVisibility(model.format.startsWith("Compressed file") && model.ready ? View.GONE : View.VISIBLE);
        search.setEnabled(!model.busy && model.ready);
        select.setEnabled(!model.busy && model.ready);
        progress.setVisibility(model.busy ? View.VISIBLE : View.GONE);
        cancel.setVisibility(model.busy ? View.VISIBLE : View.GONE);
        cancel.setEnabled(!model.cancelled);
        extract.setEnabled(!model.busy && model.ready && model.entries.stream().anyMatch(ArchiveEntry::extractable));
        extract.setVisibility(model.ready ? View.VISIBLE : View.GONE);
        bottomActions.setVisibility(model.ready || model.busy ? View.VISIBLE : View.GONE);
        extract.setText(model.selected.isEmpty() ? (model.format.startsWith("Compressed file")
                ? "Decompress file" : "Extract all") : "Extract " + model.selected.size() + " selected");
        filter();
    }

    private void filter() {
        String query = search.getText().toString().toLowerCase(Locale.ROOT);
        visible.clear();
        for (int i = 0; i < model.entries.size(); i++)
            if (model.entries.get(i).path.toLowerCase(Locale.ROOT).contains(query)) visible.add(i);
        count.setText(getString(R.string.selection_count, visible.size(), model.selected.size()));
        select.setText(allSelected() ? "Clear selection" : "Select shown");
        empty.setText(model.busy ? "" : model.ready
                ? (model.entries.isEmpty() ? "This archive is empty." : "No matching paths.")
                : "Open a file to view its contents.");
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private boolean allSelected() {
        boolean found = false;
        for (int index : visible) if (model.entries.get(index).extractable()) {
            found = true;
            if (!model.selected.contains(index)) return false;
        }
        return found;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || model.busy) return;
        if (request == OPEN) importUri(data.getData());
        if (request == DESTINATION && model.ready) model.extract(data.getData());
    }

    private void importUri(Uri uri) {
        model.open(uri);
    }

    private void showFormats() {
        new AlertDialog.Builder(this).setTitle("Supported formats")
                .setMessage("Create\nZIP, 7z, TAR, CPIO, compressed TAR/CPIO, and standalone compressed files. Select files or folders, a codec, and a save location.\n\n"
                        + "Read archives\nZIP, 7z, RAR/RAR5, TAR, CPIO, CAB, ISO, AR/DEB, RPM, XAR, LHA/LZH, WARC and other libarchive containers.\n\n"
                        + "Compressed files\ngzip (.gz), bzip2 (.bz2), XZ, LZMA, Zstandard (.zst), LZ4, lzip (.lz), LZOP (.lzo), Unix compress (.Z), "
                        + "Brotli (.br), Snappy (.sz / .snappy), zlib (.zlib / .zz), raw DEFLATE (.deflate) and uuencode.\n\n"
                        + "Standalone streams become one file; compressed archives show their entries. Brotli and raw DEFLATE need their filename extension. "
                        + "Raw Snappy blocks are limited to 64 MiB.\n\n"
                        + "Some formats still need additional engines: lrzip, grzip, bzip3, ZPAQ, LZFSE and WIM are not supported. "
                        + "Traditional and AES-encrypted ZIP are supported. Encrypted 7z and external dictionaries are not supported.")
                .setPositiveButton("OK", null).show();
    }

    private final class EntriesAdapter extends BaseAdapter {
        public int getCount() { return visible.size(); }
        public Object getItem(int position) { return model.entries.get(visible.get(position)); }
        public long getItemId(int position) { return visible.get(position); }
        public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout view;
            if (recycled == null) {
                view = row();
                view.setPadding(dp(4), dp(8), dp(4), dp(8));
                TextView icon = text("", 12, GREEN, true);
                icon.setGravity(Gravity.CENTER);
                view.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(48)));
                LinearLayout labels = column();
                labels.addView(text("", 14, INK, true));
                labels.addView(text("", 12, MUTED, false));
                view.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                CheckBox check = new CheckBox(MainActivity.this);
                check.setClickable(false);
                check.setFocusable(false);
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                view.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
            } else view = (LinearLayout) recycled;
            int index = visible.get(position);
            ArchiveEntry entry = model.entries.get(index);
            ((TextView) view.getChildAt(0)).setText(entry.link ? "LINK" : entry.directory() ? "DIR" : "FILE");
            LinearLayout labels = (LinearLayout) view.getChildAt(1);
            TextView path = (TextView) labels.getChildAt(0);
            path.setText(entry.path);
            path.setMaxLines(2);
            path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            ((TextView) labels.getChildAt(1)).setText(!entry.extractable() ? "Link or special file · blocked"
                    : entry.directory() ? "Folder" : entry.size < 0 ? "Size unknown" : Formatter.formatFileSize(MainActivity.this, entry.size));
            CheckBox check = (CheckBox) view.getChildAt(2);
            check.setVisibility(model.format.startsWith("Compressed file") ? View.GONE : View.VISIBLE);
            check.setChecked(model.selected.contains(index));
            check.setEnabled(entry.extractable() && !model.busy);
            view.setContentDescription(entry.path + (model.selected.contains(index) ? ", selected" : ", unselected"));
            return view;
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(14); button.setAllCaps(false);
        int[][] states = {new int[]{android.R.attr.state_enabled}, new int[]{}};
        button.setTextColor(new android.content.res.ColorStateList(states,
                new int[]{primary ? Color.WHITE : GREEN, 0xff7b847d}));
        button.setBackgroundTintList(new android.content.res.ColorStateList(states,
                new int[]{primary ? GREEN : 0xffe5ece3, 0xffe4e7df}));
        return button;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private LinearLayout.LayoutParams spaced(int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height); params.topMargin = dp(top); return params;
    }
}
