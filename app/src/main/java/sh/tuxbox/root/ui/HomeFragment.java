package sh.tuxbox.root.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import sh.tuxbox.root.R;
import sh.tuxbox.root.RootShell;
import sh.tuxbox.root.util.AssetInstaller;
import sh.tuxbox.root.util.TuxPaths;

public class HomeFragment extends Fragment {

    private TextView tvRootStatus, tvLog, tvHeroName, tvHeroStatus, tvHeroGpu;
    private MaterialButton btnInstall, btnInstallGpu;
    private View dotHero;

    /** Nome do container mostrado no cartão hero (alvo do botão de instalar Mesa). */
    private String heroEnv = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvRootStatus = view.findViewById(R.id.tvRootStatus);
        tvLog = view.findViewById(R.id.tvLog);
        btnInstall = view.findViewById(R.id.btnInstall);
        btnInstallGpu = view.findViewById(R.id.btnInstallGpu);
        tvHeroName = view.findViewById(R.id.tvHeroName);
        tvHeroStatus = view.findViewById(R.id.tvHeroStatus);
        tvHeroGpu = view.findViewById(R.id.tvHeroGpu);
        dotHero = view.findViewById(R.id.dotHero);

        btnInstall.setOnClickListener(v -> installBinaries());
        btnInstallGpu.setOnClickListener(v -> installMesa());

        checkAll();
        refreshHero();
    }

    // ---------------------------------------------------------------------
    // Cartão "STATUS DO CONTAINER"
    // ---------------------------------------------------------------------

    /**
     * Preenche o cartão hero: primeiro container instalado, status do recipiente
     * e a GPU detectada no host (kgsl/DRM/llvmpipe) + se a Mesa com backend kgsl
     * já está instalada nesse container. Um único comando entrega as três coisas.
     */
    private void refreshHero() {
        String base = TuxPaths.ROOTFS_BASE;
        // v3.11 — lista só containers reais (TuxPaths.LIST_ROOTFS_CMD): o `ls -A`
        // puro pegava QUALQUER arquivo da base (logs de diagnóstico) como o
        // "primeiro container" do hero.
        RootShell.runAsync(
                TuxPaths.LIST_ROOTFS_CMD + "; echo '@@GPU@@';"
                        + " if [ -r /sys/class/kgsl/kgsl-3d0/gpu_model ]; then cat /sys/class/kgsl/kgsl-3d0/gpu_model;"
                        + " elif [ -d /dev/dri ]; then echo DRM;"
                        + " else echo NONE; fi;"
                        + " echo '@@MESA@@';"
                        + " R=$(" + TuxPaths.LIST_ROOTFS_CMD + " | head -n1);"
                        + " if [ -n \"$R\" ]; then"
                        // v3.10 — o `find -maxdepth 3` percorria a árvore INTEIRA do
                        // /usr/lib a cada abertura do app (rootfs grande = segundos,
                        // e com o su instável o card ficava girando). Globs limitados
                        // cobrem os mesmos caminhos do Mesa kgsl com custo O(1).
                        + " L=\"" + base + "/$R/usr/lib\"; K=N;"
                        + " for f in \"$L\"/*kgsl* \"$L\"/dri/*kgsl* \"$L\"/mesa/*kgsl* \"$L\"/*/dri/*kgsl*; do"
                        + " if [ -e \"$f\" ]; then K=Y; break; fi; done;"
                        + " if [ \"$K\" = Y ]; then echo KGSL_YES; else echo KGSL_NO; fi;"
                        + " else echo NONE; fi",
                out -> {
                    String first = null, gpu = null, mesa = null;
                    int mode = 0; // 0=nomes, 1=gpu, 2=mesa
                    for (String line : out.split("\n")) {
                        String l = line.trim();
                        if (l.isEmpty() || l.startsWith("$ ") || l.startsWith("[exit=") || l.startsWith("ERR:")) continue;
                        if (l.equals("@@GPU@@")) { mode = 1; continue; }
                        if (l.equals("@@MESA@@")) { mode = 2; continue; }
                        if (mode == 0 && first == null) first = l;
                        else if (mode == 1 && gpu == null) gpu = l;
                        else if (mode == 2 && mesa == null) mesa = l;
                    }
                    final String f = first, g = gpu, m = mesa;
                    // v3.10 — requireActivity() sem checar isAdded() estourava
                    // IllegalStateException quando o root demorava e o callback
                    // chegava depois de o fragment ter sido desfeito — era o
                    // "app quebrou" ao voltar pra lista de containers.
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (isAdded()) renderHero(f, g, m);
                    });
                });
    }

    private void renderHero(String env, String gpu, String mesa) {
        if (env == null) {
            heroEnv = null;
            tvHeroName.setText(R.string.home_ct_none);
            tvHeroStatus.setText("");
            tvHeroGpu.setVisibility(View.GONE);
            btnInstallGpu.setVisibility(View.GONE);
            setDot(getResources().getColor(R.color.tux_text_secondary));
            return;
        }
        heroEnv = env;
        tvHeroName.setText(env);
        boolean running = TerminalFragment.isEnvRunning(env);
        tvHeroStatus.setText(running ? R.string.home_ct_running : R.string.home_ct_idle);
        setDot(getResources().getColor(running ? R.color.tux_green : R.color.tux_cyan));

        String gpuLabel = prettyGpu(gpu);
        boolean mesaOk = "KGSL_YES".equals(mesa);

        if ("DRM".equals(gpu)) {
            // Kernel com DRM/MSM: o Mesa auto-detecta o driver pelo /dev/dri;
            // o build kgsl (e o botão de instalar) não se aplica a este caminho.
            tvHeroGpu.setText(gpuLabel);
            tvHeroGpu.setTextColor(getResources().getColor(R.color.tux_violet_light));
            tvHeroGpu.setVisibility(View.VISIBLE);
            btnInstallGpu.setVisibility(View.GONE);
            return;
        }

        boolean hwGpu = !"NONE".equals(gpu);
        tvHeroGpu.setText(gpuLabel + "  ·  " + getString(mesaOk ? R.string.home_mesa_ok : R.string.home_mesa_no));
        tvHeroGpu.setTextColor(getResources().getColor(
                mesaOk ? R.color.tux_green : (hwGpu ? R.color.tux_violet_light : R.color.tux_text_secondary)));
        tvHeroGpu.setVisibility(View.VISIBLE);

        // Só oferece instalação quando há GPU nativa utilizável e a Mesa ainda não está lá.
        btnInstallGpu.setVisibility((!mesaOk && hwGpu) ? View.VISIBLE : View.GONE);
    }

    /** Transforma a saída bruta do host numa etiqueta legível ("Adreno650v3" → "Adreno 650 v3 (kgsl)"). */
    private String prettyGpu(String raw) {
        if (raw == null) return getString(R.string.home_gpu, "?");
        String m = raw.trim();
        if (m.isEmpty()) return getString(R.string.home_gpu, "?");
        if (m.equals("DRM")) return getString(R.string.home_gpu, "freedreno (DRM)");
        if (m.equals("NONE")) return getString(R.string.home_gpu, getString(R.string.home_gpu_sw));
        m = m.replaceAll("^(Adreno)(\\d+)(.*)$", "$1 $2$3");
        m = m.replaceAll("^(Adreno \\d+)v(\\d+)$", "$1 v$2");
        String kind = m.toLowerCase(Locale.US).contains("mali") ? "panfrost" : "kgsl";
        return getString(R.string.home_gpu, m + " (" + kind + ")");
    }

    private void setDot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dotHero.setBackground(d);
    }

    // ---------------------------------------------------------------------
    // Instalação automática da Mesa GPU (backend kgsl/Turnip) no container
    // ---------------------------------------------------------------------

    private TextView dlgMesaText;
    private AlertDialog dlgMesa;

    private void installMesa() {
        final String env = heroEnv;
        if (env == null) return;
        btnInstallGpu.setEnabled(false);
        showMesaProgress();
        final android.content.Context appCtx = requireContext();
        new Thread(() -> {
            File tmp = null;
            try {
                final String base = TuxPaths.ROOTFS_BASE;
                mesaLog("→ Mesa GPU em '" + env + "'");

                // 1) Detecção da distro: ID / ID_LIKE / VERSION_CODENAME do
                // os-release + fallback por gerenciador de pacotes.
                setMesaStatus(getString(R.string.mesa_detect));
                String osrel = RootShell.run(
                        "for k in ID ID_LIKE VERSION_CODENAME; do v=$(sed -n \"s/^$k=//p\" '" + base + "/" + env
                                + "/etc/os-release' 2>/dev/null | tr -d '\\\"' | head -n1); echo \"$k=$v\"; done;"
                                + " if [ -x '" + base + "/" + env + "/usr/bin/dpkg' ] || [ -x '" + base + "/" + env + "/usr/bin/apt' ]; then echo PKG=debian;"
                                + " elif [ -x '" + base + "/" + env + "/sbin/apk' ]; then echo PKG=alpine;"
                                + " elif [ -x '" + base + "/" + env + "/usr/bin/pacman' ]; then echo PKG=arch;"
                                + " elif [ -x '" + base + "/" + env + "/usr/bin/dnf' ] || [ -x '" + base + "/" + env + "/bin/dnf' ]; then echo PKG=fedora;"
                                + " elif [ -x '" + base + "/" + env + "/sbin/xbps-install' ]; then echo PKG=void; fi");
                String[] idc = parseOsRelease(osrel);
                mesaLog("distro: id=" + (idc[0] == null ? "?" : idc[0])
                        + " id_like=" + (idc[1] == null ? "" : idc[1])
                        + " pkg=" + (idc[2] == null ? "" : idc[2]));
                String[] candidates = distroCandidates(idc);
                if (candidates == null) {
                    throw new IOException(getString(R.string.mesa_unsupported,
                            idc[0] != null ? idc[0] : (idc[2] != null ? idc[2] : "?")));
                }

                // 2) Pega o release "mesa-*" mais novo que tenha asset para a distro.
                String[] info = null;
                try {
                    info = resolveMesaAsset(candidates);
                } catch (Exception e) {
                    mesaLog("API GitHub indisponível (" + e.getMessage() + ") — usando release fixo");
                    info = null;
                }
                if (info == null) info = hardcodedMesa(candidates);
                mesaLog("asset: " + info[1]);

                // 3) Download com progresso. O download roda no processo do app
                // (sem root): grava no cache do próprio app, NUNCA em /data/local/tmp,
                // que o app não consegue escrever (SELinux / permissões).
                setMesaStatus(getString(R.string.mesa_dl));
                File cacheDir = new File(appCtx.getCacheDir(), "mesa-gpu");
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    throw new IOException("não foi possível criar " + cacheDir);
                }
                tmp = new File(cacheDir, info[1]);
                downloadTo(info[2], tmp);
                String tmpPath = tmp.getAbsolutePath();

                // 4) Extração com checagem real do retorno do tar (o shell root
                // consegue ler o cache do app — mesmo padrão do fluxo de download).
                setMesaStatus(getString(R.string.mesa_extract));
                String z = info[1].endsWith(".gz") ? "z" : "";
                String out = RootShell.run("tar -x" + z + "f '" + tmpPath + "' -C '" + base + "/" + env + "' 2>&1;"
                        + " rc_tar=$?;"
                        + " if [ \"$rc_tar\" = \"0\" ] && [ -x '" + base + "/" + env + "/sbin/ldconfig' ]; then chroot '" + base + "/" + env + "' /sbin/ldconfig 2>/dev/null || true; fi;"
                        + " rm -f '" + tmpPath + "'; echo \"TAR_RC=$rc_tar\"");
                tmp = null;
                int rc = parseTarRc(out);
                if (rc != 0) {
                    throw new IOException("tar rc=" + rc + "\n" + tail(out, 500));
                }

                runOnUi(() -> {
                    if (dlgMesa != null && dlgMesa.isShowing()) dlgMesa.dismiss();
                    Toast.makeText(requireContext(), R.string.mesa_done, Toast.LENGTH_LONG).show();
                    btnInstallGpu.setEnabled(true);
                    refreshHero();
                });
            } catch (Exception e) {
                if (tmp != null && tmp.exists()) tmp.delete();
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                mesaLog("FALHA: " + msg);
                runOnUi(() -> {
                    if (dlgMesa != null && dlgMesa.isShowing()) {
                        dlgMesaText.setText(getString(R.string.mesa_fail_title) + ":\n" + msg);
                        dlgMesa.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.mesa_close), (d, w) -> d.dismiss());
                    }
                    Toast.makeText(requireContext(), getString(R.string.mesa_fail, msg), Toast.LENGTH_LONG).show();
                    btnInstallGpu.setEnabled(true);
                });
            }
        }).start();
    }

    /** Escreve uma linha no log "Saída" e espelha no diálogo de progresso. */
    private void mesaLog(final String s) {
        runOnUi(() -> {
            tvLog.append(s + "\n");
            if (dlgMesaText != null) dlgMesaText.setText(s);
        });
    }

    private void runOnUi(Runnable r) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(r);
    }

    private void setMesaStatus(final String s) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (dlgMesaText != null) dlgMesaText.setText(s);
        });
    }

    private AlertDialog showMesaProgress() {
        LinearLayout ll = new LinearLayout(requireContext());
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        ll.setPadding(pad, pad, pad, pad);
        ProgressBar pb = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        ll.addView(pb);
        dlgMesaText = new TextView(requireContext());
        dlgMesaText.setTextColor(requireContext().getColor(R.color.tux_text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        ll.addView(dlgMesaText, lp);
        dlgMesa = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.mesa_title)
                .setView(ll)
                .setCancelable(false)
                .create();
        dlgMesa.show();
        return dlgMesa;
    }

    /** Lê os campos ID / ID_LIKE / VERSION_CODENAME / PKG da saída do host. */
    private String[] parseOsRelease(String raw) {
        String id = null, idLike = null, code = null, pkg = null;
        for (String line : raw.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("$ ") || l.startsWith("[exit=") || l.startsWith("ERR:")) continue;
            if (l.startsWith("PKG=")) pkg = l.substring(4).trim();
            else if (l.startsWith("ID_LIKE=")) idLike = l.substring(8).trim();
            else if (l.startsWith("ID=")) id = l.substring(3).trim();
            else if (l.startsWith("VERSION_CODENAME=")) code = l.substring(l.indexOf('=') + 1).trim();
        }
        return new String[]{emptyToNull(id), emptyToNull(idLike), emptyToNull(pkg), emptyToNull(code)};
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * Candidatos de nome de asset do mesa-for-android-container.
     * Usa ID do os-release, depois ID_LIKE (ex.: centos → fedora), depois o
     * gerenciador de pacotes. Builds oficiais: debian_trixie,
     * ubuntu_{noble,questing,resolute}, archlinux, alpine_3.24, fedora_{43,44}, void.
     */
    private String[] distroCandidates(String[] idc) {
        String id = idc[0];
        if (id == null && idc[1] != null) {
            String like = idc[1].toLowerCase(Locale.US);
            if (like.contains("debian") || like.contains("ubuntu")) id = "debian";
            else if (like.contains("rhel") || like.contains("fedora")) id = "fedora";
        }
        if (id == null) id = idc[2]; // fallback pelo gerenciador de pacotes
        if (id == null) return null;
        String c = idc[3] == null ? "" : idc[3].toLowerCase(Locale.US);
        switch (id.trim().toLowerCase(Locale.US)) {
            case "debian":
                if (c.equals("trixie")) return new String[]{"debian_trixie"};
                return new String[]{"debian_trixie"};
            case "ubuntu":
                if (c.equals("resolute")) return new String[]{"ubuntu_resolute", "ubuntu_noble", "ubuntu_questing"};
                if (c.equals("questing")) return new String[]{"ubuntu_questing", "ubuntu_noble", "ubuntu_resolute"};
                if (c.equals("noble")) return new String[]{"ubuntu_noble", "ubuntu_resolute", "ubuntu_questing"};
                return new String[]{"ubuntu_noble", "ubuntu_resolute", "ubuntu_questing"};
            case "arch":
                return new String[]{"archlinux"};
            case "alpine":
                return new String[]{"alpine_3.24"};
            case "fedora":
            case "centos":
            case "rhel":
            case "rocky":
            case "almalinux":
                if (c.equals("43")) return new String[]{"fedora_43", "fedora_44"};
                return new String[]{"fedora_44", "fedora_43"};
            case "void":
                return new String[]{"void"};
            default:
                return null;
        }
    }

    /**
     * Consulta a API do GitHub e devolve {tag, asset, url} do release "mesa-*"
     * mais novo que tenha asset para a nossa distro (o release "turnip-*" é só
     * Vulkan e fica de fora — queremos o Mesa completo com backend kgsl para GL).
     */
    private String[] resolveMesaAsset(String[] candidates) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.github.com/repos/lfdevs/mesa-for-android-container/releases?per_page=10").openConnection();
        conn.setRequestProperty("User-Agent", "TuxBox");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                mesaLog("API GitHub: HTTP " + status);
                return null;
            }
            JSONArray arr = new JSONArray(readAll(conn.getInputStream()));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.getJSONObject(i);
                String tag = rel.optString("tag_name", "");
                if (!tag.startsWith("mesa-")) continue;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets == null) continue;
                for (int a = 0; a < assets.length(); a++) {
                    JSONObject as = assets.getJSONObject(a);
                    String name = as.optString("name", "");
                    if (!name.contains("arm64")) continue;
                    for (String cand : candidates) {
                        if (name.contains(cand)) {
                            return new String[]{tag, name, as.optString("browser_download_url", "")};
                        }
                    }
                }
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    /** Fallback para quando a API está inacessível: release conhecido (26.3.0 devel). */
    private String[] hardcodedMesa(String[] candidates) {
        String cand = candidates[0];
        String ext = cand.equals("archlinux") ? ".tar" : ".tar.gz";
        String tag = "mesa-26.3.0-devel-20260824";
        String asset = "mesa-for-android-container_26.3.0-devel-20260824_" + cand + "_arm64" + ext;
        return new String[]{tag, asset,
                "https://github.com/lfdevs/mesa-for-android-container/releases/download/" + tag + "/" + asset};
    }

    /** Baixa o tarball com progresso no diálogo (atualiza a cada ~1 MB). */
    private void downloadTo(String urlStr, File dst) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "TuxBox");
        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status + " em " + new URL(urlStr).getHost());
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                long done = 0, last = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0 && done - last > 1024 * 1024) {
                        last = done;
                        final long pct = done * 100 / total;
                        setMesaStatus(getString(R.string.mesa_dl) + " " + pct + "%");
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Pega o rc do tar na última marca "TAR_RC=" (a primeira é o eco do próprio comando). */
    private int parseTarRc(String out) {
        int idx = out.lastIndexOf("TAR_RC=");
        if (idx < 0) return -1;
        int end = out.indexOf('\n', idx);
        String v = (end < 0 ? out.substring(idx + 7) : out.substring(idx + 7, end)).trim();
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    // ---------------------------------------------------------------------
    // Binários base
    // ---------------------------------------------------------------------

    private void log(String s) {
        requireActivity().runOnUiThread(() -> tvLog.append(s + "\n"));
    }

    private void setRootStatus(String s, boolean ok) {
        requireActivity().runOnUiThread(() -> {
            tvRootStatus.setText(s);
            tvRootStatus.setTextColor(getResources().getColor(ok ? R.color.tux_green : R.color.tux_red));
        });
    }

    private void checkAll() {
        setRootStatus("checando root…", false);
        new Thread(() -> {
            try {
                if (!RootShell.isRoot()) {
                    setRootStatus(getString(R.string.root_blocked), false);
                } else {
                    setRootStatus(getString(R.string.root_ok), true);
                }
            } catch (Exception e) {
                setRootStatus("BLOQUEADO: " + e.getMessage(), false);
            }
        }).start();
    }

    private void installBinaries() {
        btnInstall.setEnabled(false);
        new Thread(() -> {
            try {
                AssetInstaller.install(requireContext());
                String binDir = AssetInstaller.binDir(requireContext()).getAbsolutePath();
                RootShell.run("chmod 755 " + binDir + "/tuxbox " + binDir + "/tuxbox-mini " + binDir + "/getroot " + binDir + "/ptyd 2>&1; mkdir -p " + TuxPaths.ROOTFS_BASE);
                log(getString(R.string.home_installed));
            } catch (Exception e) {
                log("erro install: " + e);
            } finally {
                requireActivity().runOnUiThread(() -> btnInstall.setEnabled(true));
            }
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}