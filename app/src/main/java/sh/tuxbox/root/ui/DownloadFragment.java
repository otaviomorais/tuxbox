package sh.tuxbox.root.ui;

import android.net.Uri;
import android.os.Bundle;
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

    private ActivityResultLauncher<String[]> importLauncher;

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

        arch = deviceArch();
        tvArch.setText(getString(R.string.dl_arch) + ": " + arch);

        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importRootfs);

        btnImport.setOnClickListener(v -> importLauncher.launch(new String[]{"application/x-xz", "application/x-tar", "*/*"}));

        btnDownload.setOnClickListener(v -> startDownload());
        spinnerDistro.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                populateReleaseSpinner();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

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

    private void refreshIndex() {
        tvProgress.setText(R.string.dl_fetch_index);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                fetchIndex();
                requireActivity().runOnUiThread(() -> {
                    spinnerDistro.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, distros));
                    tvProgress.setText(R.string.dl_progress);
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
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
            spinnerRelease.setAdapter(null);
            return;
        }
        int selected = 0;
        String prev = spinnerRelease.getSelectedItem() == null ? null : spinnerRelease.getSelectedItem().toString();
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
    }

    private void startDownload() {
        if (spinnerDistro.getSelectedItem() == null || spinnerRelease.getSelectedItem() == null) return;
        String distro = spinnerDistro.getSelectedItem().toString();
        String release = spinnerRelease.getSelectedItem().toString();
        btnDownload.setEnabled(false);
        btnImport.setEnabled(false);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                String name = editName.getText() == null ? "" : editName.getText().toString().trim();
                if (name.isEmpty()) name = distro + (release.isEmpty() ? "" : "-" + release).toLowerCase(Locale.ROOT);
                // sanitize
                name = name.replaceAll("[^a-zA-Z0-9._-]", "-");
                final String fname = name;

                String baseUrl = findImageBase(distro, release);
                String rootfsUrl = baseUrl + "rootfs.tar.xz";

                File tmp = new File(requireContext().getCacheDir(), "dl-" + System.currentTimeMillis());
                tmp.mkdirs();
                File tar = new File(tmp, "rootfs.tar.xz");
                File extractDir = new File(tmp, "rootfs");

                requireActivity().runOnUiThread(() -> tvProgress.setText(getString(R.string.dl_downloading, fname)));
                download(rootfsUrl, tar);

                requireActivity().runOnUiThread(() -> tvProgress.setText("Extraindo arquivos…"));
                final int entryCount = extract(tar, extractDir);

                requireActivity().runOnUiThread(() -> tvProgress.setText(R.string.dl_moving));
                moveToMnt(fname, extractDir);

                // cleanup
                deleteRecursive(tmp);

                requireActivity().runOnUiThread(() -> {
                    tvProgress.setText(getString(R.string.dl_done, fname));
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    tvProgress.setText(getString(R.string.dl_err, e.getMessage()));
                    progress.setVisibility(View.GONE);
                });
            } finally {
                requireActivity().runOnUiThread(() -> {
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
    private void download(String urlString, File dst) throws Exception {
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
                    requireActivity().runOnUiThread(() -> {
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

    /** Extract tar.xz in Java (XZ + TarArchiveInputStream), returns number of entries. */
    private int extract(File tarXz, File destDir) throws Exception {
        destDir.mkdirs();
        int entries = 0;
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
                    String linkTarget = entry.getLinkName();
                    java.nio.file.Path link = outFile.toPath();
                    try { java.nio.file.Files.deleteIfExists(link); } catch (Exception ignore) { }
                    try { java.nio.file.Files.createSymbolicLink(link, java.nio.file.Paths.get(linkTarget)); } catch (Exception ignore) {
                        // symlinks may fail on some fs; fall back to writing a dummy file
                        try (OutputStream fos = new FileOutputStream(outFile)) { }
                    }
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
                entries++;
            }
        }
        return entries;
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
    private void moveToMnt(String name, File extractDir) throws Exception {
        String src = extractDir.getAbsolutePath();
        String dst = TuxPaths.ROOTFS_BASE + "/" + name;
        String result = RootShell.run("rm -rf " + dst + "; mkdir -p " + TuxPaths.ROOTFS_BASE + "; cp -a " + src + "/. " + dst + " 2>&1; chown -R 0:0 " + dst
                + "; chmod -R u+rwX,go+rX,go-w " + dst + " 2>&1; restorecon -RF " + dst + " 2>&1 || true; grep -qs '^_apt:' " + dst + "/etc/passwd && chown -R _apt:root " + dst + "/var/lib/apt/lists 2>/dev/null || true; echo DST=$(ls -d " + dst + " 2>&1)");
        if (!result.contains("DST=" + dst)) {
            throw new IllegalStateException("falha ao mover para " + TuxPaths.ROOTFS_BASE + ": " + result);
        }
    }

    // ---- import via SAF ----

    private void importRootfs(Uri uri) {
        if (uri == null) return;
        btnDownload.setEnabled(false);
        btnImport.setEnabled(false);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        tvProgress.setText("Importando arquivo…");
        new Thread(() -> {
            try {
                String name = editName.getText() == null ? "" : editName.getText().toString().trim();
                if (name.isEmpty()) name = "importado";
                name = name.replaceAll("[^a-zA-Z0-9._-]", "-");
                final String fname2 = name;

                File tmp = new File(requireContext().getCacheDir(), "imp-" + System.currentTimeMillis());
                tmp.mkdirs();
                File tar = new File(tmp, "rootfs.tar.xz");
                File extractDir = new File(tmp, "rootfs");

                try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(tar))) {
                    if (in == null) throw new IllegalStateException("não foi possível abrir o arquivo");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                requireActivity().runOnUiThread(() -> tvProgress.setText("Extraindo arquivos…"));
                extract(tar, extractDir);
                moveToMnt(fname2, extractDir);
                deleteRecursive(tmp);
                requireActivity().runOnUiThread(() -> {
                    tvProgress.setText(getString(R.string.dl_done, fname2));
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    tvProgress.setText(getString(R.string.dl_err, e.getMessage()));
                    progress.setVisibility(View.GONE);
                });
            } finally {
                requireActivity().runOnUiThread(() -> {
                    btnDownload.setEnabled(true);
                    btnImport.setEnabled(true);
                });
            }
        }).start();
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