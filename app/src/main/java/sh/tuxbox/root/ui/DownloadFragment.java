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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * Download runs in Java (real byte progress), extraction is done in Java with
 * commons-compress (no dependency on device tar/curl), then everything is moved
 * into /data/local/tuxbox/<name> as root with cp -a.
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

    private static final class SymlinkSpec {
        final String path;
        final String target;
        SymlinkSpec(String path, String target) {
            this.path = path;
            this.target = target;
        }
    }

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
        final String fname = requestedName.replaceAll("[^a-zA-Z0-9._-]", "-");

        btnDownload.setEnabled(false);
        btnImport.setEnabled(false);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                String baseUrl = findImageBase(distro, release);
                String rootfsUrl = baseUrl + "rootfs.tar.xz";
                File tmp = new File(context.getCacheDir(), "dl-" + System.currentTimeMillis());
                tmp.mkdirs();
                File tar = new File(tmp, "rootfs.tar.xz");
                File extractDir = new File(tmp, "rootfs");

                postToUi(generation, () -> tvProgress.setText(getString(R.string.dl_downloading, fname)));
                download(rootfsUrl, tar, generation);
                postToUi(generation, () -> tvProgress.setText("Extraindo arquivos…"));
                List<SymlinkSpec> symlinks = extract(tar, extractDir);
                postToUi(generation, () -> tvProgress.setText(R.string.dl_moving));
                moveToMnt(fname, extractDir, symlinks);
                deleteRecursive(tmp);
                postToUi(generation, () -> {
                    tvProgress.setText(getString(R.string.dl_done, fname));
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                postToUi(generation, () -> {
                    tvProgress.setText(getString(R.string.dl_err, e.getMessage()));
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
        } finally {
            conn.disconnect();
        }
    }

    /** Extract tar.xz in Java and return symlinks that must be recreated as root. */
    private List<SymlinkSpec> extract(File tarXz, File destDir) throws Exception {
        destDir.mkdirs();
        List<SymlinkSpec> symlinks = new ArrayList<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new XZCompressorInputStream(new BufferedInputStream(new FileInputStream(tarXz), 65536)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("/")) name = name.substring(1);
                if (name.contains("..")) name = name.replace("..", "_");
                File outFile = new File(destDir, name);
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    applyMode(outFile, entry.getMode());
                } else if (entry.isSymbolicLink()) {
                    File parent = outFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    symlinks.add(new SymlinkSpec(name, entry.getLinkName()));
                    // Defer symlink(2) until all regular entries are written.
                    // This prevents an early /bin -> usr/bin link from making
                    // a later parent lookup follow a not-yet-created directory.
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile))) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = tar.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    applyMode(outFile, entry.getMode());
                }
            }
        }
        // Create links only after extraction; if Android's app sandbox still
        // rejects symlink(2), moveToMnt repairs them as root in the destination.
        for (SymlinkSpec link : symlinks) {
            File linkFile = new File(destDir, link.path);
            File parent = linkFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try {
                java.nio.file.Files.deleteIfExists(linkFile.toPath());
            } catch (Exception ignore) { }
            try {
                java.nio.file.Files.createSymbolicLink(linkFile.toPath(),
                        java.nio.file.Paths.get(link.target));
            } catch (Exception ignore) { }
        }
        return symlinks;
    }

    /**
     * Apply the tar entry mode (owner/group/other rwx) to an extracted file or dir.
     * Setuid/setgid/sticky are intentionally dropped (the container runs as root).
     * Always prefers exact POSIX perms so files are never left world-writable
     * (that is what made /etc/sudo.conf trigger "world writable" in sudo).
     */
    private static void applyMode(File f, int mode) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = new java.util.HashSet<>();
            if ((mode & 0400) != 0) perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            java.nio.file.Files.setPosixFilePermissions(f.toPath(), perms);
        } catch (Exception e1) {
            // Fallback for filesystems without POSIX perms: never add write for
            // group/other, only for the owner.
            try {
                f.setReadable(true, false);
                f.setWritable((mode & 0200) != 0, true);
                f.setExecutable((mode & 0100) != 0, false);
            } catch (Exception ignored) { }
        }
    }

    /** Move extracted rootfs into /data/local/tuxbox/<name> as root, preserving attributes (cp -a).
     *  Afterwards enforce exec/traverse bits and fix SELinux labels: files that come from
     *  the app's sandbox can arrive without +x or with app_data_file labels that block
     *  exec by the su domain (symptom: "chroot: can't execute Permission denied"). */
    private void moveToMnt(String name, File extractDir, List<SymlinkSpec> symlinks) throws Exception {
        String src = extractDir.getAbsolutePath();
        String dst = TuxPaths.ROOTFS_BASE + "/" + name;
        StringBuilder repair = new StringBuilder();
        for (SymlinkSpec link : symlinks) {
            String targetPath = dst + "/" + link.path;
            String parent = new File(targetPath).getParent();
            repair.append("mkdir -p ").append(shellQuote(parent)).append("; ");
            repair.append("rm -rf ").append(shellQuote(targetPath)).append("; ");
            repair.append("ln -s ").append(shellQuote(link.target)).append(" ")
                    .append(shellQuote(targetPath)).append(" 2>/dev/null || true; ");
        }
        String result = RootShell.run("rm -rf " + shellQuote(dst)
                + "; mkdir -p " + shellQuote(TuxPaths.ROOTFS_BASE)
                + "; cp -a " + shellQuote(src + "/.") + " " + shellQuote(dst) + " 2>&1; "
                + repair
                + "chown -R 0:0 " + shellQuote(dst)
                + "; chmod -R u+rwX,go+rX,go-w " + shellQuote(dst) + " 2>&1; "
                + "restorecon -RF " + shellQuote(dst) + " 2>&1 || true; "
                + "grep -qs '^_apt:' " + shellQuote(dst + "/etc/passwd") + " && chown -R _apt:root "
                + shellQuote(dst + "/var/lib/apt/lists") + " 2>/dev/null || true; "
                + "echo DST=$(ls -d " + shellQuote(dst) + " 2>&1)");
        if (!result.contains("DST=" + dst)) {
            throw new IllegalStateException("falha ao mover para " + TuxPaths.ROOTFS_BASE + ": " + result);
        }
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
        final String fname2 = requestedName.replaceAll("[^a-zA-Z0-9._-]", "-");
        btnDownload.setEnabled(false);
        btnImport.setEnabled(false);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        tvProgress.setText("Importando arquivo…");
        new Thread(() -> {
            try {
                File tmp = new File(context.getCacheDir(), "imp-" + System.currentTimeMillis());
                tmp.mkdirs();
                File tar = new File(tmp, "rootfs.tar.xz");
                File extractDir = new File(tmp, "rootfs");

                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(tar))) {
                    if (in == null) throw new IllegalStateException("não foi possível abrir o arquivo");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                postToUi(generation, () -> tvProgress.setText("Extraindo arquivos…"));
                List<SymlinkSpec> symlinks = extract(tar, extractDir);
                moveToMnt(fname2, extractDir, symlinks);
                deleteRecursive(tmp);
                postToUi(generation, () -> {
                    tvProgress.setText(getString(R.string.dl_done, fname2));
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                postToUi(generation, () -> {
                    tvProgress.setText(getString(R.string.dl_err, e.getMessage()));
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