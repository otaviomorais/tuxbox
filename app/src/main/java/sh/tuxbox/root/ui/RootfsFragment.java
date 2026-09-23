package sh.tuxbox.root.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import sh.tuxbox.root.R;
import sh.tuxbox.root.RootShell;
import sh.tuxbox.root.util.AssetInstaller;
import sh.tuxbox.root.util.TuxPaths;

/** Lists installed rootfs under the base dir and allows delete / open terminal. */
public class RootfsFragment extends Fragment {

    private LinearLayout listRootfs;
    private TextView tvFree, tvNone;
    private MaterialCardView cardNone;
    private MaterialButton btnRefresh;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_rootfs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listRootfs = view.findViewById(R.id.listRootfs);
        tvFree = view.findViewById(R.id.tvFree);
        tvNone = view.findViewById(R.id.tvNone);
        cardNone = view.findViewById(R.id.cardNone);
        btnRefresh = view.findViewById(R.id.btnRefresh);
        btnRefresh.setOnClickListener(v -> refresh());
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        RootShell.runAsync(TuxPaths.LIST_ROOTFS_CMD + "; echo ---; df -h /data/local 2>/dev/null | tail -n 1 | awk '{print $4\" livre de \"$2\" (\"$5\" usado)\"}'", out -> {
            final List<String> names = new ArrayList<>();
            final String[] freeRef = {""};
            boolean afterMarker = false;
            for (String line : out.split("\n")) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("$ ") || l.startsWith("[exit=") || l.startsWith("ERR:")) continue;
                if (l.equals("---")) {
                    afterMarker = true;
                } else if (afterMarker) {
                    freeRef[0] = l;
                } else {
                    names.add(l);
                }
            }
            requireActivity().runOnUiThread(() -> render(names, freeRef[0]));
        });
    }

    private void render(List<String> names, String free) {
        listRootfs.removeAllViews();
        if (names.isEmpty()) {
            cardNone.setVisibility(View.VISIBLE);
        } else {
            cardNone.setVisibility(View.GONE);
            for (String name : names) {
                listRootfs.addView(makeRow(name));
            }
        }
        if (free != null && !free.isEmpty()) {
            tvFree.setText(getString(R.string.rootfs_free, free));
        }
    }

    private View makeRow(String name) {
        MaterialCardView card = new MaterialCardView(requireContext());
        card.setRadius(16);
        card.setCardElevation(0);
        card.setCardBackgroundColor(requireContext().getColor(R.color.tux_surface_alt));
        card.setStrokeColor(requireContext().getColor(R.color.tux_stroke));
        card.setStrokeWidth(dp(1));
        card.setContentPadding(16, 12, 16, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 8);
        card.setLayoutParams(lp);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.addView(row);

        // dot de status: verde = aba aberta, ciano = parado
        View dot = new View(requireContext());
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(requireContext().getColor(tabRunning(name) ? R.color.tux_green : R.color.tux_cyan));
        dot.setBackground(d);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMarginEnd(dp(10));
        row.addView(dot, dotLp);

        TextView tv = new TextView(requireContext());
        tv.setText(name);
        tv.setTextSize(16);
        tv.setTextColor(requireContext().getColor(R.color.tux_text));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tv);

        row.addView(outlinedButton(R.string.rootfs_btn_terminal, R.color.tux_cyan, v -> openTerminal(name)));
        row.addView(outlinedButton(R.string.rootfs_btn_delete, R.color.tux_red, v -> confirmDelete(name)));

        return card;
    }

    /** Botão outline translúcido: borda + texto na cor, sem fundo (estilo do protótipo). */
    private MaterialButton outlinedButton(int textRes, int colorRes, View.OnClickListener onClick) {
        MaterialButton b = new MaterialButton(requireContext());
        b.setText(textRes);
        b.setAllCaps(false);
        b.setBackgroundTintList(ColorStateList.valueOf(0x00000000));
        b.setTextColor(requireContext().getColor(colorRes));
        b.setStrokeColor(ColorStateList.valueOf(requireContext().getColor(colorRes)));
        b.setStrokeWidth(dp(1));
        b.setMinHeight(dp(36));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private boolean tabRunning(String env) {
        return TerminalFragment.isEnvRunning(env);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void openTerminal(String name) {
        // Quick sanity probe before switching tabs: if /bin/sh cannot exec, we show
        // the real reason and offer a one-tap repair instead of a dead terminal.
        final String dst = TuxPaths.ROOTFS_BASE + "/" + name;
        btnRefresh.setEnabled(false);
        RootShell.runAsync(
                "ls -lZ " + dst + "/bin/sh 2>&1; echo ---; grep -E ' /data/local |" + name + "' /proc/mounts 2>/dev/null; echo ---; chroot " + dst + " /bin/sh -c 'echo CHROOT_OK' 2>&1",
                out -> requireActivity().runOnUiThread(() -> {
                    btnRefresh.setEnabled(true);
                    if (out.contains("CHROOT_OK")) {
                        TerminalFragment.setPendingRootfs(name);
                        if (getActivity() instanceof sh.tuxbox.root.MainActivity) {
                            ((sh.tuxbox.root.MainActivity) getActivity()).openTerminalTab();
                        }
                    } else {
                        showRepairDialog(name, out);
                    }
                })
        );
    }

    private void showRepairDialog(String name, String probe) {
        String brief = probe.length() > 900 ? probe.substring(probe.length() - 900) : probe;
        new AlertDialog.Builder(requireContext())
                .setTitle("Container não executa /bin/sh")
                .setMessage("Teste do shell falhou:\n\n" + brief +
                        "\n\nIsso costuma ser exec bits perdidos na instalação ou rótulo SELinux bloqueando a execução. Reparar (chmod + restorecon) e testar de novo?")
                .setPositiveButton("Reparar", (d, w) -> repair(name))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Fix exec/traverse bits and SELinux labels on a broken rootfs, then retest. */
    private void repair(String name) {
        final String dst = TuxPaths.ROOTFS_BASE + "/" + name;
        Toast.makeText(requireContext(), "Reparando…", Toast.LENGTH_SHORT).show();
        RootShell.runAsync(
                "chmod -R u+rwX,go+rX " + dst + " 2>&1; restorecon -RF " + dst + " 2>/dev/null || true; chroot " + dst + " /bin/sh -c 'echo CHROOT_OK' 2>&1",
                out -> requireActivity().runOnUiThread(() -> {
                    if (out.contains("CHROOT_OK")) {
                        Toast.makeText(requireContext(), "Reparado! Abrindo terminal…", Toast.LENGTH_SHORT).show();
                        TerminalFragment.setPendingRootfs(name);
                        if (getActivity() instanceof sh.tuxbox.root.MainActivity) {
                            ((sh.tuxbox.root.MainActivity) getActivity()).openTerminalTab();
                        }
                    } else {
                        String brief = out.length() > 900 ? out.substring(out.length() - 900) : out;
                        Toast.makeText(requireContext(), "Ainda falhando:\n" + brief, Toast.LENGTH_LONG).show();
                    }
                })
        );
    }

    private void confirmDelete(String name) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.rootfs_delete_ok)
                .setMessage(getString(R.string.rootfs_confirm_delete, name))
                .setPositiveButton("Deletar", (d, w) -> delete(name))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void delete(String name) {
        RootShell.runAsync("rm -rf " + TuxPaths.ROOTFS_BASE + "/" + name, out ->
                Toast.makeText(requireContext(), getString(R.string.rootfs_delete_ok, name), Toast.LENGTH_SHORT).show());
        refresh();
    }
}