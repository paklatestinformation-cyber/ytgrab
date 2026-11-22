package com.zyvo.ytgrab;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;
import com.yausername.youtubedl_android.YoutubeDL;
import com.zyvo.ytgrab.databinding.ActivityMainBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements DownloadBus.Listener {

    private static final int REQ_WRITE = 11;
    private static final int REQ_NOTIF = 12;

    private ActivityMainBinding b;
    private final List<HistoryStore.Item> items = new ArrayList<>();
    private HistoryAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int mode = 0;          // 0 = video, 1 = audio
    private int quality = 0;       // 0 = best, else 480/720/1080
    private String currentId;
    private String lastFetched = "";
    private String pendingUrl;
    private Runnable debounce;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        adapter = new HistoryAdapter(items, new HistoryAdapter.Listener() {
            @Override
            public void onOpen(HistoryStore.Item item) {
                openItem(item);
            }

            @Override
            public void onDelete(HistoryStore.Item item) {
                confirmDelete(item);
            }
        });
        b.rvRecent.setLayoutManager(new LinearLayoutManager(this));
        b.rvRecent.setAdapter(adapter);

        b.btnMore.setOnClickListener(this::showMenu);
        b.btnPaste.setOnClickListener(v -> pasteFromClipboard());
        b.btnOpenFolder.setOnClickListener(v -> openFolder());
        b.btnDownload.setOnClickListener(v -> startDownload());
        b.btnCancel.setOnClickListener(v -> cancelDownload());

        b.segVideo.setOnClickListener(v -> setMode(0));
        b.segAudio.setOnClickListener(v -> setMode(1));

        b.chipBest.setOnClickListener(v -> setQuality(0));
        b.chip1080.setOnClickListener(v -> setQuality(1080));
        b.chip720.setOnClickListener(v -> setQuality(720));
        b.chip480.setOnClickListener(v -> setQuality(480));

        b.etUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                final String t = s.toString().trim();
                if (debounce != null) handler.removeCallbacks(debounce);
                if (t.isEmpty()) return;
                debounce = () -> {
                    if (t.equals(lastFetched)) return;
                    if (!UrlUtil.isYouTube(t)) return;
                    b.chipSource.setText("LINK");
                    fetch(t);
                };
                handler.postDelayed(debounce, 800);
            }
        });

        b.chipSource.setText("LINK");
        setMode(0);
        setQuality(0);
        loadHistory();
        warmUpEngine();
        handleIntent(getIntent());
    }

    // ---------------------------------------------------------------- intents

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) {
                ClipData cd = intent.getClipData();
                if (cd != null && cd.getItemCount() > 0) {
                    CharSequence cs = cd.getItemAt(0).getText();
                    if (cs != null) text = cs.toString();
                }
            }
            String url = UrlUtil.firstUrl(text);
            if (url != null) {
                if (!UrlUtil.isYouTube(url)) {
                    snack("That shared text has no YouTube link");
                    return;
                }
                setUrl(url, true);
                return;
            }
            snack("No link found in the shared text");
        }
    }

    // ---------------------------------------------------------------- input

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            snack("Clipboard is empty");
            return;
        }
        CharSequence txt = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        String url = UrlUtil.firstUrl(String.valueOf(txt));
        if (url == null) {
            snack("No link found in the clipboard");
            return;
        }
        if (!UrlUtil.isYouTube(url)) {
            snack("That is not a YouTube link");
            return;
        }
        setUrl(url, false);
    }

    private void setUrl(String url, boolean shared) {
        b.etUrl.setText(url);
        b.etUrl.setSelection(b.etUrl.getText().length());
        b.chipSource.setText(shared ? "SHARED" : "LINK");
        fetch(url);
    }

    private void fetch(String url) {
        final String id = UrlUtil.videoId(url);
        currentId = id;
        if (id == null) {
            b.tvTitle.setText("Not a YouTube video link");
            b.tvSub.setText("Paste a link like youtube.com/watch?v=…");
            b.imgThumb.setImageDrawable(null);
            return;
        }
        lastFetched = url;
        ThumbLoader.load(b.imgThumb, UrlUtil.thumbUrl(id));
        b.tvTitle.setText("Fetching video info…");
        b.tvSub.setText("youtube.com");
        VideoInfoLoader.load(id, new VideoInfoLoader.Callback() {
            @Override
            public void onInfo(String title, String author) {
                if (!id.equals(currentId)) return;
                b.tvTitle.setText(title);
                String who = (author == null || author.isEmpty()) ? "YouTube" : author;
                b.tvSub.setText(who + " · YouTube");
            }

            @Override
            public void onFail() {
                if (!id.equals(currentId)) return;
                b.tvTitle.setText("YouTube video");
                b.tvSub.setText("Ready to download");
            }
        });
    }

    // ---------------------------------------------------------------- options

    private void setMode(int m) {
        mode = m;
        int on = ContextCompat.getColor(this, R.color.text_primary);
        int off = ContextCompat.getColor(this, R.color.text_secondary);
        if (m == 0) {
            b.segVideo.setBackgroundResource(R.drawable.bg_seg_on);
            b.segVideo.setTextColor(on);
            b.segAudio.setBackgroundColor(0x00000000);
            b.segAudio.setTextColor(off);
            b.qualityLabel.setVisibility(View.VISIBLE);
            b.chipsRow.setVisibility(View.VISIBLE);
        } else {
            b.segAudio.setBackgroundResource(R.drawable.bg_seg_on);
            b.segAudio.setTextColor(on);
            b.segVideo.setBackgroundColor(0x00000000);
            b.segVideo.setTextColor(off);
            b.qualityLabel.setVisibility(View.GONE);
            b.chipsRow.setVisibility(View.GONE);
        }
    }

    private void setQuality(int q) {
        quality = q;
        TextView[] chips = {b.chipBest, b.chip1080, b.chip720, b.chip480};
        int[] values = {0, 1080, 720, 480};
        for (int i = 0; i < chips.length; i++) {
            boolean selected = values[i] == q;
            chips[i].setBackgroundResource(selected ? R.drawable.bg_chip_on : R.drawable.bg_chip);
            chips[i].setTextColor(ContextCompat.getColor(this,
                    selected ? R.color.danger : R.color.text_secondary));
        }
    }

    // ---------------------------------------------------------------- download

    private void startDownload() {
        String url = b.etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            snack("Paste a YouTube link first");
            return;
        }
        if (!UrlUtil.isYouTube(url)) {
            snack("Only YouTube links are supported");
            return;
        }
        if (DownloadBus.running) {
            snack("A download is already running");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingUrl = url;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        enqueue(url);
    }

    private void enqueue(String url) {
        Intent i = new Intent(this, DownloadService.class);
        i.putExtra(DownloadService.EXTRA_URL, url);
        i.putExtra(DownloadService.EXTRA_AUDIO, mode == 1);
        i.putExtra(DownloadService.EXTRA_QUALITY, quality);
        i.putExtra(DownloadService.EXTRA_TITLE, b.tvTitle.getText().toString());
        ContextCompat.startForegroundService(this, i);
        showProgress(b.tvTitle.getText().toString());
    }

    private void cancelDownload() {
        try {
            YoutubeDL.getInstance().destroyProcessById(DownloadService.PROC_ID);
        } catch (Exception ignored) {
        }
        snack("Cancelling…");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingUrl != null) {
                enqueue(pendingUrl);
            } else {
                snack("Storage permission is needed to save files");
            }
            pendingUrl = null;
        }
    }

    // ---------------------------------------------------------------- bus

    @Override
    protected void onResume() {
        super.onResume();
        DownloadBus.add(this);
        if (DownloadBus.running) {
            showProgress(DownloadBus.title.isEmpty() ? "Downloading…" : DownloadBus.title);
            onProgress(DownloadBus.percent, DownloadBus.detail);
        } else {
            hideProgress();
            loadHistory();
        }
    }

    @Override
    protected void onPause() {
        DownloadBus.remove(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (debounce != null) handler.removeCallbacks(debounce);
        super.onDestroy();
    }

    @Override
    public void onStarted(String title) {
        showProgress(title);
    }

    @Override
    public void onProgress(int percent, String detail) {
        b.progressBar.setProgress(percent);
        b.tvPercent.setText(percent + "%");
        if (detail != null && !detail.isEmpty()) {
            String d = detail;
            if (d.length() > 110) d = d.substring(0, 110) + "…";
            b.tvDetail.setText(d);
        }
    }

    @Override
    public void onDone(HistoryStore.Item item) {
        hideProgress();
        loadHistory();
        snack("Saved · " + item.meta);
    }

    @Override
    public void onError(String message) {
        hideProgress();
        showError(message);
    }

    // ---------------------------------------------------------------- ui

    private void showProgress(String title) {
        b.cardProgress.setVisibility(View.VISIBLE);
        b.tvFileName.setText(title == null || title.isEmpty() ? "Downloading…" : title);
        b.tvPercent.setText("0%");
        b.tvDetail.setText("Connecting…");
        b.progressBar.setProgress(0);
        b.btnDownload.setEnabled(false);
        b.btnDownload.setAlpha(0.55f);
        b.scroll.post(() -> b.scroll.smoothScrollTo(0, Math.max(0, b.cardProgress.getTop() - 40)));
    }

    private void hideProgress() {
        b.cardProgress.setVisibility(View.GONE);
        b.btnDownload.setEnabled(true);
        b.btnDownload.setAlpha(1f);
    }

    private void loadHistory() {
        items.clear();
        items.addAll(HistoryStore.load(this));
        adapter.notifyDataSetChanged();
        boolean empty = items.isEmpty();
        b.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        b.rvRecent.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void snack(String msg) {
        Snackbar.make(b.getRoot(), msg, Snackbar.LENGTH_LONG).show();
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setTitle("Download failed")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNeutralButton("Update engine", (d, w) -> updateEngine())
                .show();
    }

    // ---------------------------------------------------------------- history

    private void openItem(HistoryStore.Item it) {
        try {
            Uri uri;
            String mime = (it.mime == null || it.mime.isEmpty())
                    ? (it.audio ? "audio/mp4" : "video/mp4") : it.mime;
            if (it.uri != null) {
                uri = Uri.parse(it.uri);
            } else if (it.path != null) {
                File f = new File(it.path);
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            } else {
                snack("File is no longer available");
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open with"));
        } catch (Exception e) {
            snack("No app can open this file");
        }
    }

    private void confirmDelete(HistoryStore.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("Delete download?")
                .setMessage(it.title + "\n\nThe file will also be removed from your phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> deleteItem(it))
                .show();
    }

    private void deleteItem(HistoryStore.Item it) {
        try {
            if (it.uri != null) {
                getContentResolver().delete(Uri.parse(it.uri), null, null);
            } else if (it.path != null) {
                //noinspection ResultOfMethodCallIgnored
                new File(it.path).delete();
            }
        } catch (Exception ignored) {
        }
        HistoryStore.remove(this, it);
        loadHistory();
        snack("Deleted");
    }

    // ---------------------------------------------------------------- menu

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Update downloader engine");
        pm.getMenu().add(0, 2, 1, "Open downloads folder");
        pm.getMenu().add(0, 3, 2, "Clear history");
        pm.setOnMenuItemClickListener(mi -> {
            int id = mi.getItemId();
            if (id == 1) {
                updateEngine();
                return true;
            }
            if (id == 2) {
                openFolder();
                return true;
            }
            if (id == 3) {
                confirmClear();
                return true;
            }
            return false;
        });
        pm.show();
    }

    private void updateEngine() {
        snack("Updating downloader engine…");
        new Thread(() -> {
            String result;
            try {
                YtDlp.ensureInit(getApplicationContext());
                YoutubeDL.UpdateStatus st = YoutubeDL.getInstance()
                        .updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
                if (st == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
                    result = "Engine already up to date";
                } else {
                    result = "Engine updated · " + safeVersion();
                }
            } catch (Exception e) {
                String m = e.getMessage();
                result = "Update failed" + (m == null ? "" : ": " + m);
            }
            final String msg = result;
            runOnUiThread(() -> {
                if (!isFinishing()) snack(msg);
            });
        }).start();
    }

    private String safeVersion() {
        try {
            String v = YoutubeDL.getInstance().version(this);
            return v == null ? "latest" : v;
        } catch (Exception e) {
            return "latest";
        }
    }

    private void warmUpEngine() {
        b.tvHint.setText("Preparing download engine…");
        new Thread(() -> {
            boolean ok;
            String version = null;
            try {
                YtDlp.ensureInit(getApplicationContext());
                version = safeVersion();
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean ready = ok;
            final String v = version;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (ready) {
                    b.tvHint.setText("Saves to Movies / YT Grab"
                            + (v == null || v.isEmpty() ? "" : " · yt-dlp " + v));
                } else {
                    b.tvHint.setText("Engine offline — tap ⋮ to update it");
                }
            });
        }).start();
    }

    private void openFolder() {
        File dir = new File(getExternalFilesDir(null), "downloads");
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", dir);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "resource/folder");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Where are my files?")
                    .setMessage("Videos → Movies / YT Grab\nSongs → Music / YT Grab\n\n"
                            + "They show up in your Gallery and Music app as soon as the download finishes.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("This only clears the list. Your downloaded files stay on the phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    HistoryStore.clear(this);
                    loadHistory();
                })
                .show();
    }
}
