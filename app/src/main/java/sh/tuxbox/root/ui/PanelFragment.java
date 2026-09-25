package sh.tuxbox.root.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import sh.tuxbox.root.MainActivity;
import sh.tuxbox.root.R;

/**
 * Management shell with four internal pages: Home, Download, Containers and About.
 *
 * Child fragments stay added and are switched with show/hide plus max lifecycle.
 * This preserves scroll, form state, the SAF launcher and active downloads.
 */
public class PanelFragment extends Fragment {

    private static final String KEY_SELECTED_TAB = "selected_tab";
    private static final String KEY_PENDING_TAB = "pending_tab";
    private static final String TAG_HOME = "panel_home";
    private static final String TAG_DOWNLOAD = "panel_download";
    private static final String TAG_CONTAINERS = "panel_containers";
    private static final String TAG_ABOUT = "panel_about";

    private TabLayout panelTabs;
    private MaterialButton btnPanelTerminal;
    private int selectedTabId = View.NO_ID;
    private int pendingTabId = View.NO_ID;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_panel, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        panelTabs = view.findViewById(R.id.panelTabs);
        btnPanelTerminal = view.findViewById(R.id.btnPanelTerminal);

        addTab(R.id.nav_home, R.string.tab_home);
        addTab(R.id.nav_download, R.string.tab_download);
        addTab(R.id.nav_rootfs, R.string.tab_rootfs);
        addTab(R.id.nav_about, R.string.tab_about);

        panelTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getId(), true);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });

        btnPanelTerminal.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openTerminalTab();
            }
        });

        int requestedTab = savedInstanceState != null
                ? savedInstanceState.getInt(KEY_SELECTED_TAB, R.id.nav_home)
                : R.id.nav_home;
        if (savedInstanceState != null) {
            pendingTabId = savedInstanceState.getInt(KEY_PENDING_TAB, View.NO_ID);
        }
        if (!isValidTab(requestedTab)) requestedTab = R.id.nav_home;
        selectTabUi(requestedTab);
        showTab(requestedTab, true);
    }

    private void addTab(@IdRes int tabId, int titleRes) {
        TabLayout.Tab tab = panelTabs.newTab();
        tab.setId(tabId);
        tab.setText(titleRes);
        panelTabs.addTab(tab);
    }

    private void selectTabUi(@IdRes int tabId) {
        for (int i = 0; i < panelTabs.getTabCount(); i++) {
            TabLayout.Tab tab = panelTabs.getTabAt(i);
            if (tab != null && tab.getId() == tabId) {
                if (!tab.isSelected()) tab.select();
                return;
            }
        }
    }

    private void showTab(@IdRes int tabId, boolean immediate) {
        if (!isValidTab(tabId)) return;
        FragmentManager fm = getChildFragmentManager();
        if (fm.isStateSaved()) {
            pendingTabId = tabId;
            return;
        }
        if (tabId == selectedTabId) return;

        String targetTag = tagForTab(tabId);
        Fragment target = fm.findFragmentByTag(targetTag);
        FragmentTransaction tx = fm.beginTransaction().setReorderingAllowed(true);

        for (Fragment child : fm.getFragments()) {
            String childTag = child.getTag();
            if (!isPanelTag(childTag)) continue;
            if (targetTag.equals(childTag)) {
                tx.show(child).setMaxLifecycle(child, Lifecycle.State.RESUMED);
            } else {
                tx.hide(child).setMaxLifecycle(child, Lifecycle.State.STARTED);
            }
        }

        if (target == null) {
            target = createFragmentForTab(tabId);
            tx.add(R.id.panelContent, target, targetTag)
                    .setMaxLifecycle(target, Lifecycle.State.RESUMED);
        }

        if (immediate) {
            tx.commitNow();
        } else {
            tx.commit();
        }
        selectedTabId = tabId;
        pendingTabId = View.NO_ID;
    }

    private Fragment createFragmentForTab(@IdRes int tabId) {
        if (tabId == R.id.nav_download) return new DownloadFragment();
        if (tabId == R.id.nav_rootfs) return new RootfsFragment();
        if (tabId == R.id.nav_about) return new AboutFragment();
        return new HomeFragment();
    }

    private boolean isValidTab(@IdRes int tabId) {
        return tabId == R.id.nav_home
                || tabId == R.id.nav_download
                || tabId == R.id.nav_rootfs
                || tabId == R.id.nav_about;
    }

    private boolean isPanelTag(@Nullable String tag) {
        return TAG_HOME.equals(tag)
                || TAG_DOWNLOAD.equals(tag)
                || TAG_CONTAINERS.equals(tag)
                || TAG_ABOUT.equals(tag);
    }

    private String tagForTab(@IdRes int tabId) {
        if (tabId == R.id.nav_download) return TAG_DOWNLOAD;
        if (tabId == R.id.nav_rootfs) return TAG_CONTAINERS;
        if (tabId == R.id.nav_about) return TAG_ABOUT;
        return TAG_HOME;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pendingTabId != View.NO_ID) {
            int tabId = pendingTabId;
            pendingTabId = View.NO_ID;
            selectTabUi(tabId);
            showTab(tabId, true);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TAB, selectedTabId == View.NO_ID
                ? R.id.nav_home : selectedTabId);
        if (pendingTabId != View.NO_ID) {
            outState.putInt(KEY_PENDING_TAB, pendingTabId);
        }
    }
}
