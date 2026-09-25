package sh.tuxbox.root.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import sh.tuxbox.root.R;
import sh.tuxbox.root.MainActivity;
import sh.tuxbox.root.RootShell;
import sh.tuxbox.root.TerminalService;
import sh.tuxbox.root.util.AssetInstaller;
import sh.tuxbox.root.util.TuxPaths;

/**
 * Embedded terminal (like Termux) running inside Linux containers as root,
 * with multiple parallel sessions ("abas"), each running its own container
 * (ptyd -> tuxbox -> rootfs). Tabs keep running while you switch between them.
 *
 * ptyd (a static bionic helper) is exec'd through {@code su}: it creates a PTY
 * as root, forks/execs the container command and bridges the pty master to its
 * stdio using framed messages. Sessions are kept static so they keep running
 * while the user browses other app tabs or puts the app in the background.
 */
public class TerminalFragment extends Fragment {

    /** Requested by the Rootfs list tab: open a terminal inside this rootfs. */
    private static String pendingRootfs = null;

    public static void setPendingRootfs(String name) {
        pendingRootfs = name;
    }

    /** One open terminal tab = one running container session. */
    static final class TerminalTab {
        final TerminalSession session;
        final Process process;
        final String label;
        final boolean net;
        TerminalTab(TerminalSession session, Process process, String label, boolean net) {
            this.session = session;
            this.process = process;
            this.label = label;
            this.net = net;
        }
    }

    /** All open tabs, kept static so they survive tab switches / activity recreation. */
    private static final List<TerminalTab> sTabs = new ArrayList<>();
    private static int sActiveTab = -1;

    /** True se há uma aba de terminal aberta para o rootfs dado. */
    static boolean isEnvRunning(String env) {
        for (TerminalTab t : sTabs) {
            if (t != null && env.equals(t.label)) return true;
        }
        return false;
    }

    private Spinner spinnerEnv;
    private MaterialCheckBox chkNet;
    private MaterialCheckBox chkGui;
    private MaterialButton btnOpen, btnClose, btnNewTab;
    private ImageButton btnAppMenu;
    private TextView tvTermStatus, btnToggleOpts;
    private LinearLayout optsPanel, extraKeysRow, tabsRow;
    private HorizontalScrollView tabsScroll;
    private TerminalView terminalView;

    /** The single client wired to both the view and the session (sticky modifiers live here). */
    private TerminalClients terminalClients;

    private final List<String> envs = new ArrayList<>();

    /** Painel de opções do menu: expandido por padrão; recolhido (manual ou
     *  automaticamente ao abrir o teclado) devolve espaço pro terminal. */
    private boolean optionsExpanded = true;
    private boolean autoCollapsed = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_terminal, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        spinnerEnv = view.findViewById(R.id.spinnerEnv);
        chkNet = view.findViewById(R.id.chkNet);
        chkGui = view.findViewById(R.id.chkGui);
        btnOpen = view.findViewById(R.id.btnOpen);
        btnClose = view.findViewById(R.id.btnClose);
        btnNewTab = view.findViewById(R.id.btnNewTab);
        btnAppMenu = view.findViewById(R.id.btnAppMenu);
        tvTermStatus = view.findViewById(R.id.tvTermStatus);
        btnToggleOpts = view.findViewById(R.id.btnToggleOpts);
        optsPanel = view.findViewById(R.id.optsPanel);
        terminalView = view.findViewById(R.id.terminalView);
        extraKeysRow = view.findViewById(R.id.extraKeysRow);
        tabsRow = view.findViewById(R.id.tabsRow);
        tabsScroll = view.findViewById(R.id.tabsScroll);

        // Menu global compacto: revela temporariamente Início/Baixar/Container/
        // Terminal/Sobre sem voltar a consumir a altura do Terminal por padrão.
        btnAppMenu.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showTerminalNavigation();
            }
        });

        // Painel de opções recolhível: o terminal ganha espaço e o teclado não
        // esconde o texto. O painel fecha sozinho quando o teclado abre.
        btnToggleOpts.setOnClickListener(v -> setOptionsExpanded(!optionsExpanded));
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (imeVisible && optionsExpanded) {
                autoCollapsed = true; // lembra: volta a expandir quando o teclado fechar
                setOptionsExpanded(false);
            } else if (!imeVisible && autoCollapsed) {
                autoCollapsed = false;
                setOptionsExpanded(true);
            }
            return insets;
        });

        terminalClients = new TerminalClients(requireContext(), this::onTerminalSessionFinished, terminalView);
        terminalView.setTerminalViewClient(terminalClients);
        terminalView.setTextSize(24); // initialize renderer (Termux behavior)
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);

        btnOpen.setOnClickListener(v -> newTab());
        btnNewTab.setOnClickListener(v -> newTab());
        btnClose.setOnClickListener(v -> {
            if (sActiveTab >= 0) closeTab(sActiveTab);
        });

        spinnerEnv.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        setupExtraKeys();
        refreshEnvironments();
        attachExistingSessionIfAny();
    }

    /** Expande/recolhe o painel de opções (checkbox + ações) do menu do terminal. */
    private void setOptionsExpanded(boolean expanded) {
        if (optionsExpanded == expanded) return;
        optionsExpanded = expanded;
        optsPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        btnToggleOpts.setText(expanded ? "\u25be" : "\u25b4"); // ▾ / ▴
    }

    // ---------------------------------------------------------------------
    // Tabs
    // ---------------------------------------------------------------------

    /** Re-render the tab chips from the static tab list and highlight the active one. */
    private void rebuildTabs() {
        tabsRow.removeAllViews();
        for (int i = 0; i < sTabs.size(); i++) {
            final int index = i;
            TerminalTab t = sTabs.get(i);
            boolean active = i == sActiveTab;

            LinearLayout chip = new LinearLayout(requireContext());
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(10), dp(3), dp(5), dp(3));

            TextView label = new TextView(requireContext());
            label.setText(t.label);
            label.setTextSize(13);
            label.setTextColor(active ? 0xFF0A1226 : 0xFFE6EAF4);
            label.setPadding(0, 0, dp(6), 0);
            chip.addView(label);

            TextView close = new TextView(requireContext());
            close.setText("\u00d7"); // ×
            close.setTextSize(14);
            close.setTextColor(active ? 0xFF0A1226 : 0xFF93A1C0);
            close.setPadding(dp(4), 0, 0, 0);
            close.setOnClickListener(v -> closeTab(index));
            chip.addView(close);

            chip.setOnClickListener(v -> switchTab(index));

            GradientDrawable g = new GradientDrawable();
            g.setColor(active ? 0xFF7FD8F0 : 0xFF141B2E);
            g.setCornerRadius(dp(14));
            g.setStroke(dp(1), active ? 0xFF7FD8F0 : 0xFF3A4A6E);
            chip.setBackground(g);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            tabsRow.addView(chip, lp);
        }
        scrollActiveChipIntoView();
    }

    /** Keep the active chip visible inside the scrollable tab bar. */
    private void scrollActiveChipIntoView() {
        if (sActiveTab < 0 || sActiveTab >= tabsRow.getChildCount()) return;
        tabsScroll.post(() -> {
            View chip = tabsRow.getChildAt(sActiveTab);
            if (chip == null) return;
            int left = chip.getLeft();
            int right = chip.getRight();
            int sx = tabsScroll.getScrollX();
            int width = tabsScroll.getWidth();
            if (left < sx) {
                tabsScroll.scrollTo(left, 0);
            } else if (right > sx + width) {
                tabsScroll.scrollTo(right - width, 0);
            }
        });
    }

    /** Activate a tab (tap on its chip). */
    private void switchTab(int index) {
        if (index < 0 || index >= sTabs.size()) return;
        if (index == sActiveTab) {
            TerminalClients.showSoftInput(requireContext(), terminalView);
            return;
        }
        sActiveTab = index;
        attachActiveTab();
    }

    /**
     * Attach the active tab's session to the terminal view. The session's
     * TerminalSessionClient is rewired to the current TerminalView so that
     * onTextChanged() renders into the view that is really on screen.
     */
    private void attachActiveTab() {
        if (sActiveTab < 0 || sActiveTab >= sTabs.size()) return;
        TerminalTab tab = sTabs.get(sActiveTab);
        terminalClients = new TerminalClients(requireContext(), this::onTerminalSessionFinished, terminalView);
        terminalView.setTerminalViewClient(terminalClients);
        tab.session.updateTerminalSessionClient(terminalClients);
        terminalView.attachSession(tab.session);
        terminalView.onScreenUpdated();
        tvTermStatus.setText(getString(R.string.term_status_run) + "  ·  " + tab.label
                + " (aba " + (sActiveTab + 1) + "/" + sTabs.size() + ")");
        selectEnvInSpinner(tab.label);
        terminalView.requestFocus();
        rebuildTabs();
    }

    private void selectEnvInSpinner(String label) {
        if (label != null && envs.contains(label)) {
            int pos = envs.indexOf(label);
            if (spinnerEnv.getSelectedItemPosition() != pos) spinnerEnv.setSelection(pos);
        }
    }

    /** Close a tab: finish its session and remove the chip. */
    private void closeTab(int index) {
        if (index < 0 || index >= sTabs.size()) return;
        TerminalTab tab = sTabs.remove(index);
        new Thread(() -> {
            try {
                if (tab.session != null) tab.session.finishIfRunning();
            } catch (Exception ignored) { }
            try {
                if (tab.process != null) tab.process.destroy();
            } catch (Exception ignored) { }
        }).start();
        if (sTabs.isEmpty()) {
            sActiveTab = -1;
            terminalView.attachSession(null);
            tvTermStatus.setText("\u2014");
            TerminalService.stop(requireContext());
            rebuildTabs();
        } else {
            if (index < sActiveTab) sActiveTab--;
            else if (sActiveTab >= sTabs.size()) sActiveTab = sTabs.size() - 1;
            attachActiveTab();
        }
    }

    /** Called by the session's client when the container process exits. */
    private void onTerminalSessionFinished(TerminalSession finished) {
        if (getActivity() == null) return;
        requireActivity().runOnUiThread(() -> {
            int index = -1;
            for (int i = 0; i < sTabs.size(); i++) {
                if (sTabs.get(i).session == finished) { index = i; break; }
            }
            if (index < 0) return;
            TerminalTab tab = sTabs.get(index);
            int exitCode = -1;
            try { exitCode = tab.process.exitValue(); } catch (Exception ignored) { }
            sTabs.remove(index);
            if (sTabs.isEmpty()) {
                sActiveTab = -1;
                terminalView.attachSession(null);
                tvTermStatus.setText(getString(R.string.term_status_err,
                        "processo finalizado (exit=" + exitCode + ")"));
                TerminalService.stop(requireContext());
                rebuildTabs();
            } else {
                if (index < sActiveTab) sActiveTab--;
                else if (sActiveTab >= sTabs.size()) sActiveTab = sTabs.size() - 1;
                attachActiveTab();
            }
        });
    }

    /** If sessions are still running (from a previous visit of this tab, or after
     *  the activity was recreated in the background), re-attach them and the tab bar. */
    private void attachExistingSessionIfAny() {
        if (sTabs.isEmpty() || sActiveTab < 0 || sActiveTab >= sTabs.size()) {
            rebuildTabs();
            return;
        }
        attachActiveTab();
        terminalView.requestFocus();
        terminalView.postDelayed(() -> {
            if (getView() != null) TerminalClients.showSoftInput(requireContext(), terminalView);
        }, 300);
    }

    /** Open a new tab: a brand-new container on the selected rootfs. */
    private void newTab() {
        String env = spinnerEnv.getSelectedItem() == null ? null : spinnerEnv.getSelectedItem().toString();
        if (env == null || env.equals(getString(R.string.term_no_rootfs))) {
            Toast.makeText(requireContext(), R.string.term_no_rootfs, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean useNet = chkNet.isChecked();
        boolean useGui = chkGui.isChecked();

        btnOpen.setEnabled(false);
        tvTermStatus.setText(R.string.term_status_launch);

        new Thread(() -> {
            try {
                AssetInstaller.install(requireContext());
                String ptyd = AssetInstaller.ptydPath(requireContext()).getAbsolutePath();
                String binDir = AssetInstaller.binDir(requireContext()).getAbsolutePath();

                List<String> argv = new ArrayList<>();
                argv.add(ptyd);
                argv.add("--stdio");
                argv.add(binDir + "/tuxbox");
                if (useNet) argv.add("--net");
                if (useGui) argv.add("--gui");
                argv.add(TuxPaths.ROOTFS_BASE + "/" + env);

                String suPath = findSu();

                ProcessBuilder pb = new ProcessBuilder(suPath, "-c", joinCmd(argv));
                pb.environment().put("TERM", "xterm-256color");
                pb.redirectErrorStream(false);
                Process proc = pb.start();

                // Create the session and attach on the UI thread (Handler inside
                // TerminalSession requires the main looper, and attachSession must
                // run on the main thread).
                requireActivity().runOnUiThread(() -> {
                    try {
                        TerminalSession sess = new TerminalSession(proc.getInputStream(), proc.getOutputStream(), 2000, null);
                        TerminalTab tab = new TerminalTab(sess, proc, env, useNet);
                        sTabs.add(tab);
                        sActiveTab = sTabs.size() - 1;
                        attachActiveTab();
                        TerminalService.start(requireContext(), env);
                        // v3.13 — a notificação persistente é o que segura a
                        // sessão em segundo plano. Se a permissão/canal estiver
                        // desligado, ela some e o app morre sem aviso: pide em
                        // contexto (aqui) e, se já negada, leva às configurações.
                        ensureSessionNotification(env);
                        terminalView.requestFocus();
                        TerminalClients.showSoftInput(requireContext(), terminalView);
                    } catch (Exception e2) {
                        tvTermStatus.setText(getString(R.string.term_status_err, e2.getMessage()));
                        try { proc.destroy(); } catch (Exception ignored) { }
                    } finally {
                        btnOpen.setEnabled(true);
                    }
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    tvTermStatus.setText(getString(R.string.term_status_err, e.getMessage()));
                    btnOpen.setEnabled(true);
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------------
    // v3.13 — Notificação persistente (o que segura a sessão em 2º plano)
    // ---------------------------------------------------------------------

    /**
     * Garante que a notificação do foreground service possa aparecer:
     * 1. Android 13+: pede POST_NOTIFICATIONS **aqui** (no momento em que a
     *    sessão começa), e não no lançamento do app — pedir no start é pedir
     *    sem contexto, e uma negagem lá fica negada para sempre.
     * 2. Se ainda assim estiver bloqueada (negada 2x ou canal silenciado),
     *    mostra um toast com botão que abre as configurações do app: sem isso
     *    o usuário só vê a sessão morrer em segundo plano, sem explicação.
     */
    private void ensureSessionNotification(String env) {
        Context ctx = requireContext();
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        if (TerminalService.notificationsVisible(ctx)) return;
        // v3.13 — Toast.setAction é @hide no framework (não existe no SDK
        // público), então o atalho para as configurações vai num diálogo.
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.term_warn_notif)
                .setMessage(R.string.term_warn_notif_msg)
                .setPositiveButton(R.string.term_warn_notif_action,
                        (d, w) -> TerminalService.openNotificationSettings(requireActivity()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------------
    // Extra keys: a single horizontally scrollable row built in code, with
    // sticky CTRL/ALT modifiers that also apply to the soft keyboard (the
    // TerminalView already consults readControlKey()/readAltKey()).
    // ---------------------------------------------------------------------

    private void setupExtraKeys() {
        addKey(getString(R.string.term_btn_esc), () -> writeBytes(new byte[]{0x1b}));
        addKey(getString(R.string.term_btn_tab), () -> writeBytes(new byte[]{0x09}));

        MaterialButton btnCtrl = addKey(getString(R.string.term_btn_ctrl), () -> toggleModifier("mod-ctrl"));
        btnCtrl.setTag("mod-ctrl");
        btnCtrl.setTag(R.id.extraKeyDefaultColor, btnCtrl.getCurrentTextColor());
        MaterialButton btnAlt = addKey(getString(R.string.term_btn_alt), () -> toggleModifier("mod-alt"));
        btnAlt.setTag("mod-alt");
        btnAlt.setTag(R.id.extraKeyDefaultColor, btnAlt.getCurrentTextColor());

        addKey(getString(R.string.term_btn_slash), () -> writeChar('/'));
        addKey(getString(R.string.term_btn_pipe), () -> writeChar('|'));
        addKey(getString(R.string.term_btn_tilde), () -> writeChar('~'));
        addKey(getString(R.string.term_btn_minus), () -> writeChar('-'));

        // Navigation keys as ANSI escape sequences (ESC [ A..D/H/F, ESC [ 5~/6~),
        // same as a real keyboard: works in shells, readline, vim, less, etc.
        addKey(getString(R.string.term_btn_home), () -> escSeq('H'));
        addKey(getString(R.string.term_btn_up), () -> escSeq('A'));
        addKey(getString(R.string.term_btn_end), () -> escSeq('F'));
        addKey(getString(R.string.term_btn_pgup), () -> writeBytes(new byte[]{0x1b, 0x5b, '5', '~'}));
        addKey(getString(R.string.term_btn_pgdn), () -> writeBytes(new byte[]{0x1b, 0x5b, '6', '~'}));
        addKey(getString(R.string.term_btn_left), () -> escSeq('D'));
        addKey(getString(R.string.term_btn_down), () -> escSeq('B'));
        addKey(getString(R.string.term_btn_right), () -> escSeq('C'));
        addKey(getString(R.string.term_btn_del), () -> writeBytes(new byte[]{0x7f}));
        addKey(getString(R.string.term_btn_ctrlc), () -> writeBytes(new byte[]{0x03}));
        addKey(getString(R.string.term_btn_ctrld), () -> writeBytes(new byte[]{0x04}));
    }

    private void toggleModifier(String which) {
        if ("mod-ctrl".equals(which)) terminalClients.setCtrlHeld(!terminalClients.isCtrlHeld());
        if ("mod-alt".equals(which)) terminalClients.setAltHeld(!terminalClients.isAltHeld());
        refreshExtraKeysState();
    }

    private void refreshExtraKeysState() {
        int n = extraKeysRow.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = extraKeysRow.getChildAt(i);
            if (!(v instanceof MaterialButton)) continue;
            Object tag = v.getTag();
            if (!"mod-ctrl".equals(tag) && !"mod-alt".equals(tag)) continue;
            boolean active = "mod-ctrl".equals(tag) ? terminalClients.isCtrlHeld() : terminalClients.isAltHeld();
            ((MaterialButton) v).setTextColor(active ? 0xFF7FD8F0 : (Integer) v.getTag(R.id.extraKeyDefaultColor));
        }
    }

    /** Add a key button to the scrollable row. */
    private MaterialButton addKey(String label, Runnable action) {
        MaterialButton b = new MaterialButton(requireContext());
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        extraKeysRow.addView(b, lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Write bytes to the ACTIVE tab's session (no-op if none). */
    private void writeBytes(byte[] b) {
        if (sActiveTab < 0 || sActiveTab >= sTabs.size()) return;
        TerminalSession sess = sTabs.get(sActiveTab).session;
        if (sess != null && sess.isRunning()) sess.write(b, 0, b.length);
    }

    /** Send a navigation key as ESC [ <char> (A=up, B=down, C=right, D=left, H=home, F=end). */
    private void escSeq(char c) {
        writeBytes(new byte[]{0x1b, 0x5b, (byte) c});
    }

    /**
     * Send a printable character, honoring the sticky CTRL/ALT states:
     * ctrl+letter -> control byte, alt+letter -> ESC + letter. The sticky
     * modifiers are released after one use (Termux behavior).
     */
    private void writeChar(char c) {
        boolean ctrl = terminalClients != null && terminalClients.readControlKey();
        boolean alt = terminalClients != null && terminalClients.readAltKey();
        if (terminalClients != null) {
            terminalClients.clearControlKey();
            terminalClients.clearAltKey();
        }
        if (ctrl) {
            int code;
            if (c >= 'a' && c <= 'z') code = c - 96;
            else if (c >= 'A' && c <= 'Z') code = c - 64;
            else if (c == ' ' || c == '2') code = 0;
            else if (c == '[' || c == '3') code = 27;
            else if (c == '\\' || c == '4') code = 28;
            else if (c == ']' || c == '5') code = 29;
            else if (c == '^' || c == '6' || c == '~') code = 30;
            else if (c == '_' || c == '7' || c == '/') code = 31;
            else if (c == '8') code = 127;
            else code = c & 0x1f;
            writeBytes(new byte[]{(byte) code});
        } else if (alt) {
            writeBytes(new byte[]{0x1b, (byte) c});
        } else {
            writeBytes(new byte[]{(byte) c});
        }
        refreshExtraKeysState();
    }

    // ---------------------------------------------------------------------
    // Session/process plumbing
    // ---------------------------------------------------------------------

    /** Refresh the rootfs dropdown from the base dir (rootfs names only, no host shell). */
    private void refreshEnvironments() {
        RootShell.runAsync(TuxPaths.LIST_ROOTFS_CMD, out -> {
            envs.clear();
            for (String line : out.split("\n")) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("$ ") || l.startsWith("[exit=") || l.startsWith("ERR:")) continue;
                envs.add(l);
            }
            requireActivity().runOnUiThread(() -> {
                boolean hasRootfs = !envs.isEmpty();
                if (!hasRootfs) envs.add(getString(R.string.term_no_rootfs));
                ArrayAdapter<String> envAdapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_env_item, envs);
                envAdapter.setDropDownViewResource(R.layout.spinner_env_dropdown);
                spinnerEnv.setAdapter(envAdapter);
                btnOpen.setEnabled(hasRootfs);
                if (hasRootfs) {
                    if (pendingRootfs != null && envs.contains(pendingRootfs)) {
                        spinnerEnv.setSelection(envs.indexOf(pendingRootfs));
                    } else if (sActiveTab >= 0 && sActiveTab < sTabs.size()) {
                        selectEnvInSpinner(sTabs.get(sActiveTab).label);
                    }
                }
                pendingRootfs = null;
            });
        });
    }

    /** Locate a usable su binary (Magisk, KernelSU, or classic). */
    private String findSu() {
        String[] candidates = {"su"};
        try {
            com.topjohnwu.superuser.Shell.Result res = com.topjohnwu.superuser.Shell.cmd("command -v su").exec();
            if (res.getOut().size() > 0) {
                String p = res.getOut().get(0).trim();
                if (!p.isEmpty()) return p;
            }
        } catch (Throwable ignored) { }
        // fall back to plain "su" resolved through app PATH
        for (String c : candidates) {
            File f = new File(c);
            if (!f.isAbsolute()) return c;
        }
        return "su";
    }

    /** Build a single "prog arg1 arg2" string suitable for `su -c`. Paths are safe (no spaces).
     *  Lowers the OOM score before exec so the ptyd -> tuxbox -> container tree inherits
     *  the protection (oom_score_adj survives fork/exec); the `;` keeps going even if the
     *  write is denied, and the redirect keeps stdout (the framed ptyd stream) clean. */
    private String joinCmd(List<String> argv) {
        StringBuilder sb = new StringBuilder("echo -1000 > /proc/self/oom_score_adj; exec");
        for (String a : argv) {
            sb.append(' ').append(a);
        }
        return sb.toString();
    }
}