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

    private static final String KEY_DIAGNOSTICS_EXPANDED = "diagnostics_expanded";

    private TextView tvRootStatus, tvLog, tvHeroName, tvHeroStatus, tvHeroGpu;
    private MaterialButton btnInstall, btnInstallGpu, btnGpuDiag, btnDiagnosticsHeader;
    private View dotHero, diagnosticsContent;
    private boolean diagnosticsExpanded = false;

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
        btnGpuDiag = view.findViewById(R.id.btnGpuDiag);
        btnDiagnosticsHeader = view.findViewById(R.id.btnDiagnosticsHeader);
        diagnosticsContent = view.findViewById(R.id.diagnosticsContent);
        tvHeroName = view.findViewById(R.id.tvHeroName);
        tvHeroStatus = view.findViewById(R.id.tvHeroStatus);
        tvHeroGpu = view.findViewById(R.id.tvHeroGpu);
        dotHero = view.findViewById(R.id.dotHero);

        btnInstall.setOnClickListener(v -> installBinaries());
        btnInstallGpu.setOnClickListener(v -> installMesa());
        btnGpuDiag.setOnClickListener(v -> gpuDiagnostics());
        if (savedInstanceState != null) {
            diagnosticsExpanded = savedInstanceState.getBoolean(KEY_DIAGNOSTICS_EXPANDED, false);
        }
        setDiagnosticsExpanded(diagnosticsExpanded);
        btnDiagnosticsHeader.setOnClickListener(v -> setDiagnosticsExpanded(!diagnosticsExpanded));
    }

    private void setDiagnosticsExpanded(boolean expanded) {
        diagnosticsExpanded = expanded;
        diagnosticsContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
        btnDiagnosticsHeader.setText(getString(R.string.section_diagnostics)
                + (expanded ? "  ⌃" : "  ⌄"));
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_DIAGNOSTICS_EXPANDED, diagnosticsExpanded);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getView() == null) return;
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
                        + " else echo NONE; fi;"
                        // v3.20 — tamanho do lib do Turnip: o do mesa-for-android-container
                        // (backend KGSL) tem ~17,3-17,5 MB; o do pacote da distro
                        // (mesa-vulkan-drivers) tem ~10,3-10,6 MB. Se o apt sobrescreveu,
                        // detectamos aqui e o botão de reinstalar aparece.
                        + " echo '@@TURNIP@@';"
                        + " R=$(" + TuxPaths.LIST_ROOTFS_CMD + " | head -n1);"
                        + " if [ -n \"$R\" ]; then"
                        + "   B=\"" + base + "/$R\";"
                        + "   T=$(cd \"$B\" 2>/dev/null && (dpkg-architecture -qDEB_HOST_MULTIARCH 2>/dev/null || echo $(uname -m)-linux-gnu));"
                        + "   for t in \"$T\" \"$(uname -m)-linux-gnu\" \"\"; do"
                        + "     for l in \"$B/usr/lib/$t/libvulkan_freedreno.so\" \"$B/usr/lib/libvulkan_freedreno.so\"; do"
                        + "       if [ -f \"$l\" ]; then stat -c %s \"$l\" 2>/dev/null && break 2; fi"
                        + "     done"
                        + "   done"
                        + " else echo 0; fi",
                out -> {
                    String first = null, gpu = null, mesa = null, turnip = null;
                    int mode = 0; // 0=nomes, 1=gpu, 2=mesa, 3=turnip
                    for (String line : out.split("\n")) {
                        String l = line.trim();
                        if (l.isEmpty() || l.startsWith("$ ") || l.startsWith("[exit=") || l.startsWith("ERR:")) continue;
                        if (l.equals("@@GPU@@")) { mode = 1; continue; }
                        if (l.equals("@@MESA@@")) { mode = 2; continue; }
                        if (l.equals("@@TURNIP@@")) { mode = 3; continue; }
                        if (mode == 0 && first == null) first = l;
                        else if (mode == 1 && gpu == null) gpu = l;
                        else if (mode == 2 && mesa == null) mesa = l;
                        else if (mode == 3 && turnip == null) turnip = l;
                    }
                    final String f = first, g = gpu, m = mesa, tp = turnip;
                    // v3.10 — requireActivity() sem checar isAdded() estourava
                    // IllegalStateException quando o root demorava e o callback
                    // chegava depois de o fragment ter sido desfeito — era o
                    // "app quebrou" ao voltar pra lista de containers.
                    if (!isAdded()) return;
                    runOnUi(() -> {
                        if (isAdded() && getView() != null) renderHero(f, g, m, tp);
                    });
                });
    }

    /**
     * v3.21 — Diagnóstico da GPU em um toque: mostra em diálogo o que o próprio
     * container enxerga (vulkaninfo/vkmark para Vulkan, glxinfo/eglinfo para
     * GL/EGL), mais o inventário dos arquivos que decidem o que roda na GPU.
     * Antes era preciso abrir um terminal e digitar cada comando na mão — e o
     * erro mais comum (apt sobrescrever o Turnip) só aparecia no
     * `vulkaninfo`, invisível no app.
     */
    private void gpuDiagnostics() {
        final String env = heroEnv;
        if (env == null) { Toast.makeText(requireContext(), R.string.home_ct_none, Toast.LENGTH_SHORT).show(); return; }
        final String base = TuxPaths.ROOTFS_BASE;
        final String B = base + "/" + env;
        btnGpuDiag.setEnabled(false);
        Toast.makeText(requireContext(), R.string.gpu_diag_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String out = RootShell.run(
                    // inventário: quem manda no GL (kgsl) e no Vulkan (Turnip)
                    "echo '== NODES =='; ls -l /dev/kgsl* /dev/ion /dev/dri 2>/dev/null;"
                    + " echo '== ICD VULKAN ==';"
                    + " for i in " + B + "/etc/vulkan/icd.d/*.json " + B + "/usr/share/vulkan/icd.d/*.json; do"
                    + "   [ -f \"$i\" ] && echo \"$(basename \"$i\") -> $(sed -n 's/.*library_path\"[^\\\"]*\"\\([^\\\"]*\\)\".*/\\1/p' \"$i\")\";"
                    + " done;"
                    + " echo '== DRIVERS ==';"
                    + " for l in $(dpkg-architecture -qDEB_HOST_MULTIARCH 2>/dev/null || echo $(uname -m)-linux-gnu); do :; done;"
                    + " T=$(dpkg-architecture -qDEB_HOST_MULTIARCH 2>/dev/null || echo $(uname -m)-linux-gnu);"
                    + " ls -l " + B + "/usr/lib/$T/libvulkan_freedreno.so " + B + "/usr/lib/$T/libvulkan_lvp.so "
                    + B + "/usr/lib/$T/dri/kgsl_dri.so " + B + "/usr/lib/$T/libgallium-26.3.0-devel.so 2>/dev/null;"
                    + " echo '== VULKANINFO ==';"
                    + " if [ -x " + B + "/usr/local/bin/vulkaninfo ]; then chroot " + B + " /usr/local/bin/vulkaninfo --summary 2>&1 | head -20;"
                    + " elif command -v vulkaninfo >/dev/null 2>&1; then chroot " + B + " sh -c 'vulkaninfo --summary' 2>&1 | head -20;"
                    + " else echo '(vulkaninfo nao instalado)'; fi;"
                    + " echo '== VKMARK ==';"
                    + " if [ -x " + B + "/usr/local/bin/vkmark ]; then timeout 60 chroot " + B + " /usr/local/bin/vkmark 2>&1 | head -12;"
                    + " else echo '(vkmark nao instalado)'; fi;"
                    + " echo '== GLXINFO ==';"
                    + " if [ -x " + B + "/usr/local/bin/glxinfo ]; then chroot " + B + " /usr/local/bin/glxinfo -B 2>&1 | head -16;"
                    + " else echo '(glxinfo nao instalado)'; fi;"
                    + " echo '== EGLINFO ==';"
                    + " if [ -x " + B + "/usr/local/bin/eglinfo ]; then chroot " + B + " /usr/local/bin/eglinfo 2>&1 | grep -iE 'renderer|version|vendor' | head -8;"
                    + " else echo '(eglinfo nao instalado)'; fi;");
            runOnUi(() -> {
                if (!isAdded()) return;
                btnGpuDiag.setEnabled(true);
                mesaLog("== GPU DIAG ==\n" + out);
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.gpu_diag_title)
                        .setMessage(out)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }).start();
    }

    /**
     * v3.20 — {@code turnip} é o tamanho (em bytes) do libvulkan_freedreno.so no
     * container. O build do mesa-for-android-container (backend KGSL, o que
     * funciona) tem ~17,3-17,5 MB; o do pacote da distro (mesa-vulkan-drivers)
     * tem ~10,3-10,6 MB. Serve para duas coisas: avisar que o apt sobrescreveu o
     * driver, e manter o botão de instalação/reinstalação sempre acessível —
     * antes o botão sumia assim que havia QUALQUER arquivo `*kgsl*` no
     * usr/lib, ou seja, justamente quando o Turnip precisava ser refeito.
     */
    private void renderHero(String env, String gpu, String mesa, String turnip) {
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
        // v3.20 — o Turnip do container-for-android (backend KGSL) tem ~17,4 MB;
        // o do pacote da distro ~10,5 MB. Se estiver no tamanho da distro, o apt
        // sobrescreveu e o Vulkan cai no lavapipe.
        long turnipBytes = 0;
        try { turnipBytes = turnip == null ? 0 : Long.parseLong(turnip.trim()); } catch (Exception ignored) { }
        boolean turnipOk = turnipBytes >= 15_000_000L;
        boolean turnipStolen = mesaOk && !turnipOk;

        // v3.21 — rótulo único com o estado real das DUAS APIs de GPU.
        //  - GL/GLES: depende do *kgsl*.so (freedreno/kgsl na GPU)
        //  - Vulkan:  depende do lib do Turnip (não do apt ter roubado)
        String state;
        if (!hwGpu) {
            state = getString(R.string.home_mesa_no);
        } else if (!mesaOk) {
            state = getString(R.string.home_mesa_no);
        } else if (turnipOk) {
            state = getString(R.string.home_gpu_ok_both);
        } else if (turnipBytes > 0) {
            state = getString(R.string.home_turnip_stolen);
        } else {
            state = getString(R.string.home_gpu_gl_only);
        }
        tvHeroGpu.setText(gpuLabel + "  ·  " + state);
        tvHeroGpu.setTextColor(getResources().getColor(
                (mesaOk && turnipOk) ? R.color.tux_green : (hwGpu ? R.color.tux_violet_light : R.color.tux_text_secondary)));
        tvHeroGpu.setVisibility(View.VISIBLE);

        // v3.20 — botão SEMPRE disponível quando há GPU nativa: "Instalar Mesa GPU"
        // ainda não instalado, "Reinstalar Mesa GPU" quando já está (inclusive
        // quando o Turnip foi sobrescrito pelo apt). Antes ele sumia assim que
        // existia qualquer *kgsl*.so no usr/lib, prendendo o usuário sem como
        // refazer a instalação.
        btnInstallGpu.setText(mesaOk ? R.string.btn_reinstall_mesa : R.string.btn_install_mesa);
        btnInstallGpu.setVisibility(hwGpu ? View.VISIBLE : View.GONE);
        btnGpuDiag.setVisibility(hwGpu ? View.VISIBLE : View.GONE);
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
                String rootfs = base + "/" + env;
                // Keep the helper/config available even when the container was
                // created before v3.31; this also makes the button usable on
                // Arch without opening a terminal first.
                try {
                    AssetInstaller.install(appCtx);
                    String pacmanHelper = AssetInstaller.binDir(appCtx).getAbsolutePath() + "/ensure-pacman";
                    if (new File(pacmanHelper).isFile()) {
                        RootShell.run(shellQuote(pacmanHelper) + " " + shellQuote(rootfs) + " 2>/dev/null || true");
                    }
                } catch (Exception ignored) { }
                String osrel = RootShell.run(
                        "echo '@@OSBEGIN@@'; cat " + shellQuote(rootfs + "/etc/os-release")
                                + " 2>/dev/null; echo '@@OSEND@@';"
                                + " if [ -x " + shellQuote(rootfs + "/usr/bin/dpkg")
                                + " ] || [ -x " + shellQuote(rootfs + "/usr/bin/apt")
                                + " ]; then echo '@@PKG@@=debian';"
                                + " elif [ -x " + shellQuote(rootfs + "/sbin/apk")
                                + " ]; then echo '@@PKG@@=alpine';"
                                + " elif [ -x " + shellQuote(rootfs + "/usr/bin/pacman")
                                + " ]; then echo '@@PKG@@=arch';"
                                + " elif [ -x " + shellQuote(rootfs + "/usr/bin/dnf")
                                + " ] || [ -x " + shellQuote(rootfs + "/bin/dnf")
                                + " ]; then echo '@@PKG@@=fedora';"
                                + " elif [ -x " + shellQuote(rootfs + "/sbin/xbps-install")
                                + " ]; then echo '@@PKG@@=void'; fi");
                String[] idc = parseOsRelease(osrel);
                mesaLog("distro: id=" + (idc[0] == null ? "?" : canonicalDistro(idc[0]))
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

                // 4) Extração/instalação com checagem real do retorno.
                //
                // Debian/Ubuntu/etc. recebem um tar com os arquivos já no
                // layout do rootfs. O asset Arch é um pacote diferente: um
                // tar externo contendo vários *.pkg.tar.xz. Extraí-lo direto
                // no rootfs deixava apenas os .pkg.tar.xz na raiz, e por isso
                // o status continuava dizendo que o Mesa não estava instalado.
                setMesaStatus(getString(R.string.mesa_extract));
                final String RFS = base + "/" + env;
                String out;
                if ("arch".equals(canonicalDistro(idc[0]))
                        || "arch".equals(canonicalDistro(idc[2]))) {
                    mesaLog("Arch: instalando pacotes Mesa via pacman");
                    out = installArchMesaBundle(tmpPath, RFS);
                } else {
                    String z = info[1].endsWith(".gz") ? "z" : "";
                    out = RootShell.run(
                            "T=$(dpkg-architecture -qDEB_HOST_MULTIARCH 2>/dev/null || echo $(uname -m)-linux-gnu);"
                            + " D=" + RFS + ";"
                            + " for p in \"/usr/lib/$T/libvulkan_freedreno.so\" \"/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json\"; do"
                            + "   if [ -e \"$D$p\" ] || [ -L \"$D$p\" ]; then"
                            + "     chroot " + RFS + " dpkg-divert --add --rename --divert \"$p\" \"$p.dpkg-divert\" >/dev/null 2>&1 || true;"
                            + "   fi;"
                            + " done;"
                            + " tar -x" + z + "f '" + tmpPath + "' -C '" + RFS + "' 2>&1;"
                            + " rc_tar=$?;"
                            + " if [ \"$rc_tar\" = \"0\" ]; then chroot " + RFS + " /sbin/ldconfig 2>/dev/null || chroot " + RFS + " /usr/bin/ldconfig 2>/dev/null || true; fi;"
                            // Diagnóstico: tamanho do lib do Turnip (distro ~10,5 MB,
                            // custom/backend KGSL ~17,4 MB) — entra no log da aba Home.
                            + " echo TURNIP_LIB=$(stat -c %s \"$D/usr/lib/$T/libvulkan_freedreno.so\" 2>/dev/null || stat -c %s \"$D/usr/lib/libvulkan_freedreno.so\" 2>/dev/null || echo 0);"
                            + " rm -f '" + tmpPath + "'; echo \"TAR_RC=$rc_tar\"");
                }
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
        if (getActivity() == null || getView() == null) return;
        getActivity().runOnUiThread(() -> {
            if (isAdded() && getView() != null) r.run();
        });
    }

    private void setMesaStatus(final String s) {
        runOnUi(() -> {
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
        boolean inOs = false;
        for (String line : raw.split("\\r?\\n")) {
            String l = line.trim();
            if (l.equals("@@OSBEGIN@@")) { inOs = true; continue; }
            if (l.equals("@@OSEND@@")) { inOs = false; continue; }
            if (l.startsWith("@@PKG@@=")) {
                pkg = cleanOsValue(l.substring(8));
                continue;
            }
            if (!inOs || l.isEmpty() || l.startsWith("$ ") || l.startsWith("[exit=") || l.startsWith("ERR:")) continue;
            if (l.startsWith("ID_LIKE=")) idLike = cleanOsValue(l.substring(8));
            else if (l.startsWith("ID=")) id = cleanOsValue(l.substring(3));
            else if (l.startsWith("VERSION_CODENAME=")) code = cleanOsValue(l.substring(l.indexOf('=') + 1));
        }
        return new String[]{emptyToNull(id), emptyToNull(idLike), emptyToNull(pkg), emptyToNull(code)};
    }

    private static String cleanOsValue(String value) {
        String s = value == null ? "" : value.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0), last = s.charAt(s.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Candidatos de nome de asset do mesa-for-android-container.
     * Usa ID do os-release, depois ID_LIKE (ex.: centos → fedora), depois o
     * gerenciador de pacotes. Builds oficiais: debian_trixie,
     * ubuntu_{noble,questing,resolute}, archlinux, alpine_3.24, fedora_{43,44}, void.
     */
    private String[] distroCandidates(String[] idc) {
        String id = canonicalDistro(idc[0]);
        String like = idc[1] == null ? "" : idc[1].toLowerCase(Locale.US);

        // ID can be malformed when a host shell concatenates command output
        // (for example "archarm"). Prefer ID_LIKE/package-manager detection
        // whenever it gives a known family.
        if (!isSupportedDistro(id)) {
            if (like.contains("debian") || like.contains("ubuntu")) id = "debian";
            else if (like.contains("rhel") || like.contains("fedora")) id = "fedora";
            else id = canonicalDistro(idc[2]);
        }
        if (!isSupportedDistro(id)) return null;

        String c = idc[3] == null ? "" : cleanOsValue(idc[3]).toLowerCase(Locale.US);
        switch (id) {
            case "debian":
                return new String[]{"debian_trixie"};
            case "ubuntu":
                if (c.equals("resolute")) return new String[]{"ubuntu_resolute", "ubuntu_noble", "ubuntu_questing"};
                if (c.equals("questing")) return new String[]{"ubuntu_questing", "ubuntu_noble", "ubuntu_resolute"};
                return new String[]{"ubuntu_noble", "ubuntu_resolute", "ubuntu_questing"};
            case "arch":
                return new String[]{"archlinux"};
            case "alpine":
                return new String[]{"alpine_3.24"};
            case "fedora":
                if (c.equals("43")) return new String[]{"fedora_43", "fedora_44"};
                return new String[]{"fedora_44", "fedora_43"};
            case "void":
                return new String[]{"void"};
            default:
                return null;
        }
    }

    private static String canonicalDistro(String value) {
        String s = cleanOsValue(value).toLowerCase(Locale.US);
        if (s.startsWith("arch")) return "arch";
        if (s.startsWith("debian")) return "debian";
        if (s.startsWith("ubuntu")) return "ubuntu";
        if (s.startsWith("alpine")) return "alpine";
        if (s.startsWith("fedora") || s.startsWith("centos") || s.startsWith("rhel")
                || s.startsWith("rocky") || s.startsWith("almalinux")) return "fedora";
        if (s.startsWith("void")) return "void";
        return s;
    }

    private static boolean isSupportedDistro(String id) {
        return "debian".equals(id) || "ubuntu".equals(id) || "arch".equals(id)
                || "alpine".equals(id) || "fedora".equals(id) || "void".equals(id);
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

    /**
     * The Arch asset is an outer tar containing makepkg *.pkg.tar.xz files,
     * not a rootfs tree. Install those packages with pacman; direct outer-tar
     * extraction only leaves package files at the root and never creates
     * /usr/lib/dri/kgsl_dri.so.
     */
    private String installArchMesaBundle(String archive, String rootfs) {
        String stage = rootfs + "/.tuxbox-mesa-stage";
        String qRoot = shellQuote(rootfs);
        String qStage = shellQuote(stage);
        String qArchive = shellQuote(archive);
        String pacmanCommand = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                + "if [ -f /.tuxbox-mesa-pacman.conf ]; then "
                + "/usr/bin/pacman --config /.tuxbox-mesa-pacman.conf "
                + "--cachedir /.tuxbox-mesa-cache --disable-sandbox "
                + "-U --noconfirm /.tuxbox-mesa-stage/*.pkg.tar.xz; "
                + "else /usr/bin/pacman --cachedir /.tuxbox-mesa-cache --disable-sandbox "
                + "-U --noconfirm /.tuxbox-mesa-stage/*.pkg.tar.xz; fi";
        String command = "D=" + qRoot + "; root_bound=0; proc_owned=0; dev_bound=0;"
                + " if mount -o bind \"$D\" \"$D\" 2>/dev/null; then root_bound=1; fi;"
                + " if [ -d \"$D/proc\" ] && mount -t proc -o nosuid,nodev,noexec proc \"$D/proc\" 2>/dev/null; then proc_owned=1; fi;"
                + " if [ -d \"$D/dev\" ] && mount -o bind /dev \"$D/dev\" 2>/dev/null; then dev_bound=1; fi;"
                + " dns=''; if [ -r /system/etc/resolv.conf ]; then dns=$(sed -n 's/^[[:space:]]*nameserver[[:space:]]\\+//p' /system/etc/resolv.conf 2>/dev/null || true); fi;"
                + " if [ -z \"$dns\" ]; then for p in net.dns1 net.dns2 dhcp.wlan0.dns1 dhcp.eth0.dns1; do v=$(getprop \"$p\" 2>/dev/null || true); if [ -n \"$v\" ]; then dns=\"$dns $v\"; fi; done; fi;"
                + " [ -n \"$dns\" ] || dns='1.1.1.1 8.8.8.8';"
                + " rm -f \"$D/etc/resolv.conf\"; for n in $dns; do echo \"nameserver $n\"; done > \"$D/etc/resolv.conf\";"
                + " rm -rf " + qStage + " \"$D/.tuxbox-mesa-cache\" \"$D/.tuxbox-mesa-pacman.conf\"; mkdir -p " + qStage + " \"$D/.tuxbox-mesa-cache\";"
                + " if [ -f \"$D/etc/pacman.conf\" ]; then cp \"$D/etc/pacman.conf\" \"$D/.tuxbox-mesa-pacman.conf\" 2>/dev/null || true; "
                + "sed -i 's/^[[:space:]]*SigLevel[[:space:]]*=.*/SigLevel = Required DatabaseOptional TrustAll/' \"$D/.tuxbox-mesa-pacman.conf\" 2>/dev/null || true; "
                + "sed -i 's/^[[:space:]]*LocalFileSigLevel[[:space:]]*=.*/LocalFileSigLevel = Optional/' \"$D/.tuxbox-mesa-pacman.conf\" 2>/dev/null || true; fi;"
                + " tar -xf " + qArchive + " -C " + qStage + " 2>&1; outer_rc=$?; pkg_rc=$outer_rc;"
                + " if [ \"$outer_rc\" = \"0\" ] && [ -x \"$D/usr/bin/pacman\" ]; then"
                + " chroot " + qRoot + " /usr/bin/pacman-key --init >/dev/null 2>&1 || true;"
                + " chroot " + qRoot + " /usr/bin/pacman-key --populate archlinuxarm >/dev/null 2>&1 || true;"
                + " chroot " + qRoot + " /usr/bin/pacman-key --lsign-key 68B3537F39A313B3E574D06777193F152BDBE6A6 >/dev/null 2>&1 || true;"
                + " chroot " + qRoot + " /bin/sh -c " + shellQuote(pacmanCommand) + " 2>&1; pkg_rc=$?;"
                + " else pkg_rc=127; fi;"
                + " if [ \"$pkg_rc\" = \"0\" ]; then chroot " + qRoot + " /sbin/ldconfig 2>/dev/null || chroot " + qRoot + " /usr/bin/ldconfig 2>/dev/null || true; fi;"
                + " if [ \"$dev_bound\" = \"1\" ]; then umount \"$D/dev\" 2>/dev/null || true; fi;"
                + " if [ \"$proc_owned\" = \"1\" ]; then umount \"$D/proc\" 2>/dev/null || true; fi;"
                + " if [ \"$root_bound\" = \"1\" ]; then umount \"$D\" 2>/dev/null || true; fi;"
                + " rm -rf " + qStage + " \"$D/.tuxbox-mesa-cache\" \"$D/.tuxbox-mesa-pacman.conf\"; rm -f " + qArchive + ";"
                + " T=$(dpkg-architecture -qDEB_HOST_MULTIARCH 2>/dev/null || echo $(uname -m)-linux-gnu);"
                + " echo TURNIP_LIB=$(stat -c %s \"$D/usr/lib/$T/libvulkan_freedreno.so\" 2>/dev/null || stat -c %s \"$D/usr/lib/libvulkan_freedreno.so\" 2>/dev/null || echo 0);"
                + " echo \"TAR_RC=$pkg_rc\"";
        return RootShell.run(command);
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
        runOnUi(() -> tvLog.append(s + "\n"));
    }

    private void setRootStatus(String s, boolean ok) {
        runOnUi(() -> {
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
        final android.content.Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            try {
                AssetInstaller.install(appCtx);
                String binDir = AssetInstaller.binDir(appCtx).getAbsolutePath();
                String ensureXkb = binDir + "/ensure-xkb";
                String installXkb = "for d in " + TuxPaths.ROOTFS_BASE + "/*; do "
                        + "[ -d \"$d\" ] || continue; "
                        + "\"" + ensureXkb + "\" \"$d\" || true; "
                        + "done";
                RootShell.run("chmod 755 " + binDir + "/tuxbox " + binDir + "/tuxbox-mini "
                        + binDir + "/getroot " + binDir + "/ensure-xkb " + binDir + "/ptyd 2>&1; "
                        + "mkdir -p " + TuxPaths.ROOTFS_BASE + "; " + installXkb);
                log(getString(R.string.home_installed));
            } catch (Exception e) {
                log("erro install: " + e);
            } finally {
                runOnUi(() -> btnInstall.setEnabled(true));
            }
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}