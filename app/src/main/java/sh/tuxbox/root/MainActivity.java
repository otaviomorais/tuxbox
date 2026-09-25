package sh.tuxbox.root;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayList;
import java.util.List;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;

import sh.tuxbox.root.ui.PanelFragment;
import sh.tuxbox.root.ui.TerminalFragment;

/**
 * Two-page host: a management Panel and a distraction-free Terminal.
 *
 * Both pages stay added to the Activity. Switching pages only changes their
 * visibility and maximum lifecycle, so panel downloads and terminal sessions
 * are not destroyed or recreated.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG_PANEL = "page_panel";
    private static final String TAG_TERMINAL = "page_terminal";
    private static final String KEY_TERMINAL_VISIBLE = "terminal_visible";
    private static final String KEY_REQUESTED_ROOTFS = "requested_rootfs";

    private PanelFragment panelPage;
    private TerminalFragment terminalPage;
    private String requestedRootfsForNextTerminal;
    private boolean terminalVisible;
    private boolean pendingVisibilityApply;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FragmentManager fm = getSupportFragmentManager();
        removeUnexpectedRestoredFragments(fm);

        Fragment restoredPanel = fm.findFragmentByTag(TAG_PANEL);
        Fragment restoredTerminal = fm.findFragmentByTag(TAG_TERMINAL);
        if (restoredPanel instanceof PanelFragment) panelPage = (PanelFragment) restoredPanel;
        if (restoredTerminal instanceof TerminalFragment) terminalPage = (TerminalFragment) restoredTerminal;
        terminalVisible = savedInstanceState != null
                && savedInstanceState.getBoolean(KEY_TERMINAL_VISIBLE, false);
        requestedRootfsForNextTerminal = savedInstanceState == null
                ? null : savedInstanceState.getString(KEY_REQUESTED_ROOTFS);

        if (panelPage == null) {
            panelPage = new PanelFragment();
            fm.beginTransaction()
                    .add(R.id.fragmentContainer, panelPage, TAG_PANEL)
                    .commitNow();
        }

        applyPageVisibility();
        installBackNavigation();
    }

    /** Remove the old five-tab fragment ("page") when upgrading a live task. */
    private void removeUnexpectedRestoredFragments(FragmentManager fm) {
        List<Fragment> unexpected = new ArrayList<>();
        for (Fragment fragment : fm.getFragments()) {
            String tag = fragment.getTag();
            boolean panel = fragment instanceof PanelFragment && TAG_PANEL.equals(tag);
            boolean terminal = fragment instanceof TerminalFragment && TAG_TERMINAL.equals(tag);
            if (!panel && !terminal) unexpected.add(fragment);
        }
        if (unexpected.isEmpty()) return;
        FragmentTransaction tx = fm.beginTransaction();
        for (Fragment fragment : unexpected) tx.remove(fragment);
        tx.setReorderingAllowed(true).commitNow();
    }

    private void installBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (terminalVisible) {
                    showPanelPage();
                } else {
                    finish();
                }
            }
        });
    }

    private void showTerminalPage(@Nullable String requestedRootfs) {
        dismissKeyboardAndFocus();
        if (terminalPage != null && requestedRootfs != null) {
            terminalPage.requestRootfs(requestedRootfs);
        } else if (terminalPage == null) {
            requestedRootfsForNextTerminal = requestedRootfs;
        }
        terminalVisible = true;
        applyPageVisibility();
    }

    /** Compatibility entry point for callers that do not preselect a container. */
    public void openTerminalTab() {
        showTerminalPage(null);
    }

    /** Opens the Terminal page and preselects the requested installed container. */
    public void openTerminalTab(@Nullable String requestedRootfs) {
        showTerminalPage(requestedRootfs);
    }

    /** Returns from Terminal to the preserved management Panel. */
    public void showPanelPage() {
        dismissKeyboardAndFocus();
        terminalVisible = false;
        applyPageVisibility();
    }

    private void applyPageVisibility() {
        FragmentManager fm = getSupportFragmentManager();
        if (panelPage == null || fm.isStateSaved()) {
            pendingVisibilityApply = true;
            return;
        }

        FragmentTransaction tx = fm.beginTransaction().setReorderingAllowed(true);

        if (terminalVisible) {
            if (terminalPage == null) {
                terminalPage = TerminalFragment.create(requestedRootfsForNextTerminal);
                requestedRootfsForNextTerminal = null;
                tx.add(R.id.fragmentContainer, terminalPage, TAG_TERMINAL);
            }
            tx.setMaxLifecycle(panelPage, Lifecycle.State.STARTED)
                    .hide(panelPage)
                    .setMaxLifecycle(terminalPage, Lifecycle.State.RESUMED)
                    .show(terminalPage);
        } else {
            tx.setMaxLifecycle(panelPage, Lifecycle.State.RESUMED)
                    .show(panelPage);
            if (terminalPage != null) {
                tx.setMaxLifecycle(terminalPage, Lifecycle.State.STARTED)
                        .hide(terminalPage);
            }
        }

        tx.commitNow();
        pendingVisibilityApply = false;
    }

    private void dismissKeyboardAndFocus() {
        View focused = getCurrentFocus();
        if (focused == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (pendingVisibilityApply) applyPageVisibility();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_TERMINAL_VISIBLE, terminalVisible);
        if (requestedRootfsForNextTerminal != null) {
            outState.putString(KEY_REQUESTED_ROOTFS, requestedRootfsForNextTerminal);
        }
    }
}
