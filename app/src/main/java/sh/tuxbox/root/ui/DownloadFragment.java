package sh.tuxbox.root.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import sh.tuxbox.root.R;
import sh.tuxbox.root.RootShell;
import sh.tuxbox.root.util.AssetInstaller;
import sh.tuxbox.root.util.TuxPaths;

/**
 * Download / import rootfs images.
 * Index: https://images.linuxcontainers.org/meta/1.0/index-system
 * Lines:  distro;release;arch;variant;build;/path/to/tarball/
 * Rootfs URL: <indexUrl base> + path + "rootfs.tar.xz"
 *
 * Download runs in Java (real byte progress), then the completed archive is
 * copied to a persistent root cache and extracted directly as root.  This
 * keeps symlinks (for example bin -> usr/bin) intact and allows a retry to
 * reuse the archive without downloading it again.
 */
public class DownloadFragment extends Fragment {

    private static final String INDEX_URL = "https://images.linuxcontainers.org/meta/1.0/index-system";
    private static final String IMAGES_BASE = "https://images.linuxcontainers.org";
    private static final String KEY_DISTRO = "selected_distro";
    private static final String KEY_RELEASE = "selected_release";
    private static final String KEY_NAME = "container_name";

    private Spinner spinnerDistro, spinnerRelease;
    private TextView tvArch, tvProgress;
    private MaterialButton btnDownload, btnImport;
    private LinearProgressIndicator progress;
    private com.google.android.material.textfield.TextInputEditText editName;

    private final List<String> distros = new ArrayList<>();
    private final Map<String, List<String>> releasesByDistro = new LinkedHashMap<>();
    private String arch = ""; // e.g. arm64
    private String lastRootfsSize = "";
    private String lastRootfsUrl = "";
    private String selectedDistro;
    private String selectedRelease;

    private ActivityResultLauncher<String[]> importLauncher;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Context appContext;
    private Uri pendingImportUri;
    private int operationGeneration = 0;
    private int indexGeneration = 0;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        appContext = context.getApplicationContext();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::importRootfs);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        spinnerDistro = view.findViewById(R.id.spinnerDistro);
        spinnerRelease = view.findViewById(R.id.spinnerRelease);
        tvArch = view.findViewById(R.id.tvArch);
        tvProgress = view.findViewById(R.id.tvProgress);
        btnDownload = view.findViewById(R.id.btnDownload);
        btnImport = view.findViewById(R.id.btnImport);
        progress = view.findViewById(R.id.progress);
        editName = view.findViewById(R.id.editName);
        if (savedInstanceState != null) {
            selectedDistro = savedInstanceState.getString(KEY_DISTRO);
            selectedRelease = savedInstanceState.getString(KEY_RELEASE);
            String savedName = savedInstanceState.getString(KEY_NAME);
            if (savedName != null) editName.setText(savedName);
        }

        arch = deviceArch();
        tvArch.setText(getString(R.string.dl_arch) + ": " + arch);

        btnImport.setOnClickListener(v -> importLauncher.launch(new String[]{"application/x-xz", "application/x-tar", "*/*"}));

        btnDownload.setOnClickListener(v -> startDownload());
        spinnerDistro.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                if (position >= 0 && position < distros.size()) selectedDistro = distros.get(position);
                populateReleaseSpinner();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        if (pendingImportUri != null) {
            Uri uri = pendingImportUri;
            pendingImportUri = null;
            importRootfs(uri);
        }
        refreshIndex();
    }

    /** Map device ABIs to linuxcontainers.org arch names. */
    static String deviceArch() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            switch (abi) {
                case "arm64-v8a": return "arm64";
                case "armeabi-v7a": return "armhf";
                case "x86_64": return "amd64";
                case "x86": return "i386";
                default: break;
            }
        }
        return "arm64";
    }

    private void postIndexToUi(int generation, @NonNull Runnable action) {
        mainHandler.post(() -> {
            if (generation == indexGeneration && isAdded() && getView() != null) {
                action.run();
            }
        });
    }

    private void postToUi(int generation, @NonNull Runnable action) {
        mainHandler.post(() -> {
            if (generation == operationGeneration && isAdded() && getView() != null) {
                action.run();
            }
        });
    }

    private void refreshIndex() {
        final int generation = ++indexGeneration;
        tvProgress.setText(R.string.dl_fetch_index);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                fetchIndex();
                postIndexToUi(generation, () -> {
                    spinnerDistro.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, distros));
                    if (selectedDistro != null) {
                        int distroIndex = distros.indexOf(selectedDistro);
                        if (distroIndex >= 0) spinnerDistro.setSelection(distroIndex);
                    }
                    tvProgress.setText(R.string.dl_progress);
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                postIndexToUi(generation, () -> {
                    tvProgress.setText(getString(R.string.dl_err, e.getMessage()));
                    progress.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private void fetchIndex() throws Exception {
        URL url = new URL(INDEX_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "TuxBox/1.0");
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            String text = new String(readAll(in), StandardCharsets.UTF_8);
            parseIndex(text);
        } finally {
            conn.disconnect();
        }
    }

    private void parseIndex(String text) {
        distros.clear();
        releasesByDistro.clear();
        for (String line : text.split("\n")) {
            String[] f = line.split(";");
            if (f.length < 6) continue;
            String distro = f[0].trim();
            String release = f[1].trim();
            String a = f[2].trim();
            String variant = f[3].trim();
            String path = f[5].trim();
            if (!a.equals(arch)) continue;
            if (!variant.equals("default")) continue;
            if (path.isEmpty()) continue;
            if (!distros.contains(distro)) distros.add(distro);
            List<String> rels = releasesByDistro.get(distro);
            if (rels == null) {
                rels = new ArrayList<>();
                releasesByDistro.put(distro, rels);
            }
            if (!rels.contains(release)) rels.add(release);
        }
        // put Debian first if present, then Ubuntu, then rest (nice default UX)
        reorder("debian");
        reorder("ubuntu");
    }

    private void reorder(String distro) {
        if (distros.remove(distro)) {
            distros.add(0, distro);
        }
    }

    private void populateReleaseSpinner() {
        if (spinnerDistro.getSelectedItem() == null || distros.isEmpty()) return;
        String distro = spinnerDistro.getSelectedItem().toString();
        List<String> rels = releasesByDistro.get(distro);
        if (rels == null || rels.isEmpty()) {
            selectedRelease = null;
            spinnerRelease.setAdapter(null);
            return;
        }
        int selected = 0;
        String prev = selectedRelease != null ? selectedRelease
                : (spinnerRelease.getSelectedItem() == null ? null : spinnerRelease.getSelectedItem().toString());
        spinnerRelease.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, rels));
        if (prev != null) {
            int idx = rels.indexOf(prev);
            if (idx >= 0) selected = idx;
        } else {
            // Default to latest (LTS/noble etc.) — last entries in index are newest by build date.
            // Prefer a long-term-support-ish default.
            for (String r : rels) {
                if (r.contains("noble") || r.contains("bookworm") || r.contains("latest") || r.contains("stable")) {
                    selected = rels.indexOf(r);
                    break;
                }
            }
        }
        spinnerRelease.setSelection(selected);
        if (selected >= 0 && selected < rels.size()) selectedRelease = rels.get(selected);
    }

    private void startDownload() {
        if (spinnerDistro.getSelectedItem() == null || spinnerRelease.getSelectedItem() == null) return;
        final String distro = spinnerDistro.getSelectedItem().toString();
        final String release = spinnerRelease.getSelectedItem().toString();
        selectedDistro = distro;
        selectedRelease = release;
        final int generation = ++operationGeneration;
        final Context context = appContext != null ? appContext : requireContext().getApplicationContext();
        String requestedName = editName.getText() == null ? "" : editName.getText().toString().trim();
        if (requestedName.isEmpty()) requestedName = distro + (release.isEmpty() ? "" : "-" + release).toLowerCase(Locale.ROOT);
        final String fname = safeContainerName(requestedName);
        final String archiveName = archiveName(distro, release);
        final String stableArchive = stableArchivePath(archiveName);

        btnDownload.setEnabled(false);
        btnImport.setEnabled(false);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            File tmp = null;
            File legacyArchive = null;
            try {
                // A completed archive is deliberately checked before contacting
                // linuxcontainers.org.  This makes retries after a failed
                // extraction completely offline.
                boolean cached = archiveIsReady(stableArchive, archiveName);
                if (cached) {
                    postToUi(generation, () -> tvProgress.setText(
                            "Arquivo já salvo; reutilizando Download/TuxBox…"));
                } else {
                    // v3.28 left completed archives in dl-* when Java
                    // extraction failed. Recover a matching one before
                    // contacting the network.
                    legacyArchive = findLegacyDownload(context, distro, release);
                    if (legacyArchive != null) {
                        postToUi(generation, () -> tvProgress.setText(
                                "Recuperando o archive baixado anteriormente…"));
                        promoteArchive(legacyArchive, stableArchive);
                    } else {
                        String baseUrl = findImageBase(distro, release);
                        String rootfsUrl = baseUrl + "rootfs.tar.xz";
                        tmp = new File(context.getCacheDir(), "dl-" + System.currentTimeMillis());
                        if (!tmp.mkdirs() && !tmp.isDirectory()) {
                            throw new IOException("não foi possível criar o cache temporário");
                        }
                        File tar = new File(tmp, "rootfs.tar.xz");

                        postToUi(generation, () -> tvProgress.setText(getString(R.string.dl_downloading, fname)));
                        download(rootfsUrl, tar, generation);
                        postToUi(generation, () -> tvProgress.setText("Salvando o arquivo .tar.xz…"));
                        promoteArchive(tar, stableArchive);
                    }
                }

                // Always repair the user-visible copy.  This also handles an
                // archive that was completed by an older app version.
                boolean visibleCopy = copyArchiveToDownloads(stableArchive, archiveName);
                postToUi(generation, () -> tvProgress.setText("Extraindo com root…"));
                extractArchiveAsRoot(stableArchive, fname);
                deleteRecursive(tmp);
                if (legacyArchive != null) deleteRecursive(legacyArchive.getParentFile());

                final String archiveHint = visibleCopy
                        ? "Download/TuxBox/" + archiveName
                        : TuxPaths.ARCHIVES_DIR + "/" + archiveName + " (Download indisponível)";
                postToUi(generation, () -> {
                    tvProgress.setText(getString(R.string.dl_done, fname)
                            + "\nArquivo: " + archiveHint);
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                postToUi(generation, () -> {
                    tvProgress.setText(getString(R.string.dl_err, e.getMessage())
                            + "\nO arquivo baixado foi preservado para uma nova tentativa.");
                    progress.setVisibility(View.GONE);
                });
            } finally {
                postToUi(generation, () -> {
                    btnDownload.setEnabled(true);
                    btnImport.setEnabled(true);
                });
            }
        }).start();
    }

    /** Find the image directory path for distro/release/arch from the index. */
    private String findImageBase(String distro, String release) throws Exception {
        URL url = new URL(INDEX_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        String result = null;
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            String text = new String(readAll(in), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String[] f = line.split(";");
                if (f.length < 6) continue;
                if (f[0].trim().equals(distro) && f[1].trim().equals(release) && f[2].trim().equals(arch) && f[3].trim().equals("default")) {
                    result = IMAGES_BASE + f[5].trim();
                    break;
                }
            }
        } finally {
            conn.disconnect();
        }
        if (result == null) throw new IllegalStateException("imagem não encontrada: " + distro + " " + release + " " + arch);
        return result;
    }

    /** Download with real byte-rate progress on the UI thread. */
    private void download(String urlString, File dst, int generation) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "TuxBox/1.0");
        conn.connect();
        int code = conn.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code + " em " + urlString);
        long total = conn.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[65536];
            long done = 0;
            int n;
            long lastUi = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 150) {
                    lastUi = now;
                    final long fdone = done;
                    postToUi(generation, () -> {
                        int pct = total > 0 ? (int) (fdone * 100 / total) : 0;
                        progress.setProgress(pct);
                        tvProgress.setText(String.format(Locale.ROOT, "Baixando… %d%% (%s / %s)", pct, humans(fdone), humans(total)));
                    });
                }
            }
            if (total > 0 && done != total) {
                throw new IOException("download incompleto: " + done + "/" + total + " bytes");
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Find a complete archive left by v3.27/v3.28 in the app cache.  The
     * distro is checked from etc/os-release so an old Debian archive is never
     * silently installed as Arch (or vice versa).
     */
    private File findLegacyDownload(Context context, String distro, String release) {
        File[] dirs = context.getCacheDir().listFiles(file ->
                file != null && file.isDirectory() && file.getName().startsWith("dl-"));
        if (dirs == null || dirs.length == 0) return null;
        Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int limit = Math.min(dirs.length, 12);
        for (int i = 0; i < limit; i++) {
            File archive = new File(dirs[i], "rootfs.tar.xz");
            if (!archive.isFile() || archive.length() <= 0) continue;
            try {
                String osRelease = readOsRelease(archive);
                if (matchesImage(osRelease, distro, release)) return archive;
            } catch (Exception ignored) {
                // Try an older cache entry or download a fresh copy.
            }
        }
        return null;
    }

    /** Read only etc/os-release; no rootfs paths are created in app storage. */
    private String readOsRelease(File archive) throws Exception {
        try (InputStream in = new XZCompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive), 65536));
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("./")) name = name.substring(2);
                if (name.equals("etc/os-release")) {
                    return new String(readAll(tar), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    private static boolean matchesImage(String osRelease, String distro, String release) {
        String id = osReleaseValue(osRelease, "ID").toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return false;
        String expected = distro == null ? "" : distro.toLowerCase(Locale.ROOT);
        if (expected.isEmpty()) return false;
        if (expected.equals("archlinux")) expected = "arch";
        else if (expected.equals("amazonlinux")) expected = "amzn";
        else if (expected.equals("oracle")) expected = "ol";
        if (!id.equals(expected) && !id.startsWith(expected)) return false;

        String wanted = release == null ? "" : release.toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || wanted.equals("current") || wanted.equals("edge")
                || wanted.equals("rolling") || wanted.equals("latest")
                || wanted.equals("unstable")) return true;
        String compactWanted = wanted.replaceAll("[^a-z0-9]", "");
        if (compactWanted.isEmpty()) return true;
        String compactOs = osRelease.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return compactOs.contains(compactWanted);
    }

    private static String osReleaseValue(String text, String key) {
        if (text == null) return "";
        String prefix = key + "=";
        for (String line : text.split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim().replaceAll("^['\\\"]|['\\\"]$", "");
            }
        }
        return "";
    }

    /**
     * Return the persistent name used for a selected linuxcontainers.org image.
     * The name is deliberately independent of the user-facing container name:
     * changing the container label must not trigger another large download.
     */
    private String archiveName(String distro, String release) {
        return "tuxbox-" + safePart(distro) + "-" + safePart(release)
                + "-" + safePart(arch) + ".tar.xz";
    }

    private static String stableArchivePath(String archiveName) {
        return TuxPaths.ARCHIVES_DIR + "/" + archiveName;
    }

    private static String readyPath(String archivePath) {
        return archivePath + ".ready";
    }

    private static String safeContainerName(String value) {
        String safe = value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "-");
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) return "container";
        return safe;
    }

    private static String safePart(String value) {
        String safe = value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "-");
        return safe.isEmpty() ? "unknown" : safe;
    }

    /** Match only a complete output line; RootShell also echoes the command. */
    private static boolean hasMarker(String output, String marker) {
        if (output == null) return false;
        for (String line : output.split("\\r?\\n")) {
            if (line.trim().equals(marker)) return true;
        }
        return false;
    }

    private static String[] downloadDirs() {
        return new String[]{
                "/sdcard/Download/TuxBox",
                "/storage/emulated/0/Download/TuxBox",
                "/sdcard/Download",
                "/storage/emulated/0/Download"
        };
    }

    /**
     * Check the root cache and, if necessary, recover an archive previously
     * copied to Android Downloads.  A .ready marker prevents an interrupted
     * copy from being mistaken for a complete rootfs.
     */
    private boolean archiveIsReady(String stableArchive, String archiveName) {
        String ready = readyPath(stableArchive);
        String check = "if [ -s " + shellQuote(stableArchive)
                + " ] && [ -f " + shellQuote(ready) + " ]; then echo TUXBOX_ARCHIVE_READY; fi";
        try {
            if (hasMarker(RootShell.run(check), "TUXBOX_ARCHIVE_READY")) return true;
            // A process can be killed after the atomic mv and before the
            // marker.  The stable filename is only created after a complete
            // cp, so recover that state instead of downloading again.
            String recover = "if [ -s " + shellQuote(stableArchive)
                    + " ] && [ ! -e " + shellQuote(ready) + " ]; then touch "
                    + shellQuote(ready) + " && echo TUXBOX_ARCHIVE_READY; fi";
            if (hasMarker(RootShell.run(recover), "TUXBOX_ARCHIVE_READY")) return true;
            for (String dir : downloadDirs()) {
                String candidate = dir + "/" + archiveName;
                String restore = "if [ -s " + shellQuote(candidate) + " ]; then "
                        + "if mkdir -p " + shellQuote(TuxPaths.ARCHIVES_DIR)
                        + " && rm -f " + shellQuote(stableArchive + ".part")
                        + " && cp -f " + shellQuote(candidate) + " "
                        + shellQuote(stableArchive + ".part")
                        + " && mv -f " + shellQuote(stableArchive + ".part") + " "
                        + shellQuote(stableArchive) + "; then "
                        + "chmod 0644 " + shellQuote(stableArchive) + " 2>/dev/null || true; "
                        + "touch " + shellQuote(ready)
                        + " && echo TUXBOX_ARCHIVE_READY; fi; fi";
                if (hasMarker(RootShell.run(restore), "TUXBOX_ARCHIVE_READY")) return true;
            }
        } catch (Exception ignored) {
            // A missing root shell is reported by the subsequent operation.
        }
        return false;
    }

    /** Copy a completed download to the root-owned persistent cache atomically. */
    private void promoteArchive(File source, String stableArchive) throws Exception {
        if (source == null || !source.isFile() || source.length() <= 0) {
            throw new IOException("arquivo .tar.xz vazio ou incompleto");
        }
        String part = stableArchive + ".part";
        String ready = readyPath(stableArchive);
        String command = "if mkdir -p " + shellQuote(TuxPaths.ARCHIVES_DIR)
                + " && rm -f " + shellQuote(ready) + " " + shellQuote(part)
                + " && cp -f " + shellQuote(source.getAbsolutePath()) + " " + shellQuote(part)
                + " && mv -f " + shellQuote(part) + " " + shellQuote(stableArchive)
                + "; then chmod 0644 " + shellQuote(stableArchive) + " 2>/dev/null || true; "
                + "restorecon " + shellQuote(stableArchive) + " " + shellQuote(ready)
                + " 2>/dev/null || true; "
                + "touch " + shellQuote(ready) + " && echo TUXBOX_ARCHIVE_SAVED; fi";
        String result = RootShell.run(command);
        if (!hasMarker(result, "TUXBOX_ARCHIVE_SAVED")) {
            throw new IOException("não foi possível salvar o arquivo .tar.xz: " + result);
        }
    }

    /**
     * Keep a user-visible copy in Android Downloads.  The root copy is used
     * for installation, so scoped-storage restrictions do not affect retries.
     */
    private boolean copyArchiveToDownloads(String stableArchive, String archiveName) {
        for (String dir : downloadDirs()) {
            String destination = dir + "/" + archiveName;
            String part = destination + ".part";
            String command = "if [ -d /sdcard ] || [ -d /storage/emulated/0 ]; then "
                    + "if mkdir -p " + shellQuote(dir)
                    + " && cp -f " + shellQuote(stableArchive) + " " + shellQuote(part)
                    + " && mv -f " + shellQuote(part) + " " + shellQuote(destination) + "; then "
                    + "chmod 0644 " + shellQuote(destination) + " 2>/dev/null || true; "
                    + "chown 0:0 " + shellQuote(destination) + " 2>/dev/null || true; "
                    + "restorecon " + shellQuote(destination) + " 2>/dev/null || true; "
                    + "echo TUXBOX_DOWNLOADS_SAVED; fi; fi";
            try {
                if (hasMarker(RootShell.run(command), "TUXBOX_DOWNLOADS_SAVED")) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    /**
     * Extract directly as root.  This is intentionally not done in the app
     * sandbox: Android's private filesystem can reject or mishandle symlink
     * parents such as /bin -> usr/bin, while the root-side tar implementation
     * preserves the archive exactly.
     *
     * If the device tar has no xz support, Java decompresses only the XZ stream
     * to a temporary uncompressed tar and root extracts that.  No Java
     * filesystem extraction is involved, so the original symlink ordering is
     * still handled by tar.
     */
    private void extractArchiveAsRoot(String archive, String name) throws Exception {
        IOException directFailure = tryExtractArchiveAsRoot(archive, name, true);
        if (directFailure == null) return;

        File localDir = null;
        try {
            File localArchive = copyArchiveToAppCache(archive);
            localDir = localArchive.getParentFile();
            File plainTar = decompressXzToTar(localArchive);
            IOException plainFailure = tryExtractArchiveAsRoot(plainTar.getAbsolutePath(), name, false);
            if (plainFailure == null) return;
            directFailure.addSuppressed(plainFailure);
        } catch (Exception fallbackFailure) {
            directFailure.addSuppressed(fallbackFailure);
        } finally {
            deleteRecursive(localDir);
        }
        throw new IOException("não foi possível extrair o container com root; "
                + "tentativa XZ e tar semcompressado falharam", directFailure);
    }

    /** @return null on success, otherwise a diagnostic exception. */
    private IOException tryExtractArchiveAsRoot(String archive, String name, boolean compressed) {
        String destination = TuxPaths.ROOTFS_BASE + "/" + name;
        String[] extractors = compressed
                ? new String[]{
                "tar -xf",
                "/system/bin/tar -xf",
                "toybox tar -xf",
                "/system/bin/toybox tar -xf",
                "busybox tar -xf",
                "/system/bin/busybox tar -xf",
                "tar -xJf",
                "/system/bin/tar -xJf",
                "toybox tar -xJf",
                "/system/bin/toybox tar -xJf",
                "busybox tar -xJf",
                "/system/bin/busybox tar -xJf"
        }
                : new String[]{
                "tar -xf",
                "/system/bin/tar -xf",
                "toybox tar -xf",
                "/system/bin/toybox tar -xf",
                "busybox tar -xf",
                "/system/bin/busybox tar -xf"
        };
        StringBuilder failures = new StringBuilder();
        String lastResult = "";
        for (String extractor : extractors) {
            String command = "rm -rf " + shellQuote(destination)
                    + " && mkdir -p " + shellQuote(destination) + " && "
                    + extractor + " " + shellQuote(archive) + " -C "
                    + shellQuote(destination) + " >/dev/null 2>&1; rc=$?; "
                    + "if [ $rc -eq 0 ] && [ -e " + shellQuote(destination + "/etc")
                    + " ] && [ -e " + shellQuote(destination + "/usr")
                    + " ] && [ -e " + shellQuote(destination + "/bin/sh") + " ]; then "
                    + "chown -R 0:0 " + shellQuote(destination) + " 2>/dev/null || true; "
                    + "chmod -R u+rwX,go+rX,go-w " + shellQuote(destination)
                    + " 2>/dev/null || true; "
                    + "if grep -qs '^_apt:' " + shellQuote(destination + "/etc/passwd")
                    + "; then chown -R _apt:root " + shellQuote(destination + "/var/lib/apt/lists")
                    + " 2>/dev/null || true; fi; "
                    + "restorecon -RF " + shellQuote(destination) + " 2>/dev/null || true; "
                    + "echo TUXBOX_EXTRACT_OK; else rm -rf " + shellQuote(destination)
                    + "; echo TUXBOX_EXTRACT_FAIL; fi";
            String result = RootShell.run(command);
            if (hasMarker(result, "TUXBOX_EXTRACT_OK")) return null;
            lastResult = result;
            failures.append(extractor).append(":falha; ");
        }
        String detail = lastResult.length() > 600 ? lastResult.substring(0, 600) : lastResult;
        return new IOException("tar=" + failures + "\n" + detail);
    }

    /** Copy a root-owned archive back to a temporary app-readable file. */
    private File copyArchiveToAppCache(String stableArchive) throws Exception {
        File dir = new File(appContext.getCacheDir(), "rootfs-src-" + System.currentTimeMillis());
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("não foi possível criar o cache de extração");
        }
        File local = new File(dir, "rootfs.tar.xz");
        String result = RootShell.run(
                "if cp -f " + shellQuote(stableArchive) + " " + shellQuote(local.getAbsolutePath())
                        + " && chmod 0644 " + shellQuote(local.getAbsolutePath())
                        + "; then restorecon " + shellQuote(local.getAbsolutePath())
                        + " 2>/dev/null || true; echo TUXBOX_ARCHIVE_LOCAL; fi");
        if (!hasMarker(result, "TUXBOX_ARCHIVE_LOCAL")) {
            deleteRecursive(dir);
            throw new IOException("não foi possível copiar o archive para o cache: " + result);
        }
        return local;
    }

    /** Decompress XZ to a plain tar without creating any rootfs paths. */
    private File decompressXzToTar(File source) throws Exception {
        File output = new File(source.getParentFile(), "rootfs.tar");
        try (InputStream in = new XZCompressorInputStream(
                new BufferedInputStream(new java.io.FileInputStream(source), 65536));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
            byte[] buffer = new byte[65536];
            int count;
            long total = 0;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
                total += count;
            }
            if (total <= 0) throw new IOException("archive XZ vazio");
        }
        return output;
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // ---- import via SAF ----

    private void importRootfs(Uri uri) {
        if (uri == null) return;
        if (getView() == null) {
            pendingImportUri = uri;
            return;
        }
        startImportRootfs(uri);
    }

    private void startImportRootfs(Uri uri) {
        final int generation = ++operationGeneration;
        final Context context = appContext != null ? appContext : requireContext().getApplicationContext();
        String requestedName = editName.getText() == null ? "" : editName.getText().toString().trim();
        if (requestedName.isEmpty()) requestedName = "importado";
        final String fname2 = safeContainerName(requestedName);
        btnDownload.setEnabled(false);
        btnImport.setEnabled(false);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        tvProgress.setText("Importando arquivo…");
        new Thread(() -> {
            File tmp = null;
            try {
                tmp = new File(context.getCacheDir(), "imp-" + System.currentTimeMillis());
                if (!tmp.mkdirs() && !tmp.isDirectory()) {
                    throw new IOException("não foi possível criar o cache temporário");
                }
                File tar = new File(tmp, "rootfs.tar.xz");
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");

                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(tar))) {
                    if (in == null) throw new IllegalStateException("não foi possível abrir o arquivo");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        digest.update(buf, 0, n);
                    }
                }

                final String archiveName = "tuxbox-import-" + hexDigest(digest.digest()) + ".tar.xz";
                final String stableArchive = stableArchivePath(archiveName);
                postToUi(generation, () -> tvProgress.setText("Salvando o arquivo .tar.xz…"));
                if (!archiveIsReady(stableArchive, archiveName)) {
                    promoteArchive(tar, stableArchive);
                }
                boolean visibleCopy = copyArchiveToDownloads(stableArchive, archiveName);
                postToUi(generation, () -> tvProgress.setText("Extraindo com root…"));
                extractArchiveAsRoot(stableArchive, fname2);
                deleteRecursive(tmp);

                final String archiveHint = visibleCopy
                        ? "Download/TuxBox/" + archiveName
                        : TuxPaths.ARCHIVES_DIR + "/" + archiveName + " (Download indisponível)";
                postToUi(generation, () -> {
                    tvProgress.setText(getString(R.string.dl_done, fname2)
                            + "\nArquivo: " + archiveHint);
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                postToUi(generation, () -> {
                    tvProgress.setText(getString(R.string.dl_err, e.getMessage())
                            + "\nO arquivo importado foi preservado para uma nova tentativa.");
                    progress.setVisibility(View.GONE);
                });
            } finally {
                postToUi(generation, () -> {
                    btnDownload.setEnabled(true);
                    btnImport.setEnabled(true);
                });
            }
        }).start();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (spinnerDistro != null && spinnerDistro.getSelectedItem() != null) {
            outState.putString(KEY_DISTRO, spinnerDistro.getSelectedItem().toString());
        } else if (selectedDistro != null) {
            outState.putString(KEY_DISTRO, selectedDistro);
        }
        if (spinnerRelease != null && spinnerRelease.getSelectedItem() != null) {
            outState.putString(KEY_RELEASE, spinnerRelease.getSelectedItem().toString());
        } else if (selectedRelease != null) {
            outState.putString(KEY_RELEASE, selectedRelease);
        }
        if (editName != null && editName.getText() != null) {
            outState.putString(KEY_NAME, editName.getText().toString());
        }
    }

    // ---- helpers ----

    private static String hexDigest(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    static String humans(long bytes) {
        if (bytes < 0) return "?";
        double b = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (b >= 1024 && u < units.length - 1) {
            b /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, "%.1f %s", b, units[u]);
    }
}