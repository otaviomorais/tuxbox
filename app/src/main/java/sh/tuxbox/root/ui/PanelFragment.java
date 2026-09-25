// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 LongQT-sea
package sh.tuxbox.root.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.button.MaterialButton;

import sh.tuxbox.root.MainActivity;
import sh.tuxbox.root.R;

/**
 * Single-page dashboard shell.
 *
 * Home/status stays visible at the top. Containers, Download and About are
 * accordion sections that lazily create their child fragments and keep them
 * added after the first expansion, preserving downloads and form state.
 */
public class PanelFragment extends Fragment {

    private static final String KEY_ACTIVE_SECTION = "active_section";
    private static final String KEY_PENDING_SECTION = "pending_section";
    private static final String LEGACY_KEY_SELECTED_TAB = "selected_tab";

    private static final String TAG_HOME = "dashboard_home";
    private static final String TAG_DOWNLOAD = "dashboard_download";
    private static final String TAG_CONTAINERS = "dashboard_containers";
    private static final String TAG_ABOUT = "dashboard_about";

    private static final int PENDING_NONE = -1;
    private static final int SECTION_NONE = 0;
    private static final int SECTION_CONTAINERS = 1;
    private static final int SECTION_DOWNLOAD = 2;
    private static final int SECTION_ABOUT = 3;

    private MaterialButton btnPanelTerminal;
    private MaterialButton btnSectionContainers;
    private MaterialButton btnSectionDownload;
    private MaterialButton btnSectionAbout;
    private FrameLayout sectionContainersContent;
    private FrameLayout sectionDownloadContent;
    private FrameLayout sectionAboutContent;

    private int activeSection = SECTION_NONE;
    private int pendingSection = PENDING_NONE;
    private boolean legacyRemovalPending;

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
        btnPanelTerminal = view.findViewById(R.id.btnPanelTerminal);
        btnSectionContainers = view.findViewById(R.id.btnSectionContainers);
        btnSectionDownload = view.findViewById(R.id.btnSectionDownload);
        btnSectionAbout = view.findViewById(R.id.btnSectionAbout);
        sectionContainersContent = view.findViewById(R.id.sectionContainersContent);
        sectionDownloadContent = view.findViewById(R.id.sectionDownloadContent);
        sectionAboutContent = view.findViewById(R.id.sectionAboutContent);

        removeLegacyChildren();

        btnPanelTerminal.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openTerminalTab();
            }
        });
        btnSectionContainers.setOnClickListener(v -> toggleSection(SECTION_CONTAINERS));
        btnSectionDownload.setOnClickListener(v -> toggleSection(SECTION_DOWNLOAD));
        btnSectionAbout.setOnClickListener(v -> toggleSection(SECTION_ABOUT));

        int requested = SECTION_NONE;
        if (savedInstanceState != null) {
            requested = savedInstanceState.getInt(KEY_ACTIVE_SECTION, SECTION_NONE);
            pendingSection = savedInstanceState.getInt(KEY_PENDING_SECTION, PENDING_NONE);
            if (!savedInstanceState.containsKey(KEY_ACTIVE_SECTION)) {
                requested = sectionForLegacyTab(
                        savedInstanceState.getInt(LEGACY_KEY_SELECTED_TAB, R.id.nav_home));
            }
            if (!isValidSection(requested)) requested = SECTION_NONE;
        } else {
            requested = activeSection;
        }
        applySection(requested, true);
    }

    /** Drops child fragments left by the former TabLayout shell. */
    private void removeLegacyChildren() {
        FragmentManager fm = getChildFragmentManager();
        if (fm.isStateSaved()) {
            legacyRemovalPending = true;
            return;
        }
        legacyRemovalPending = false;
        FragmentTransaction tx = null;
        for (Fragment child : fm.getFragments()) {
            String tag = child.getTag();
            if (TAG_HOME.equals(tag) || TAG_CONTAINERS.equals(tag)
                    || TAG_DOWNLOAD.equals(tag) || TAG_ABOUT.equals(tag)) continue;
            if (tx == null) tx = fm.beginTransaction();
            tx.remove(child);
        }
        if (tx != null) tx.setReorderingAllowed(true).commitNow();
    }

    private void toggleSection(int section) {
        int effective = pendingSection != PENDING_NONE ? pendingSection : activeSection;
        applySection(effective == section ? SECTION_NONE : section, true);
    }

    private void applySection(int section, boolean immediate) {
        if (!isValidSection(section)) section = SECTION_NONE;
        FragmentManager fm = getChildFragmentManager();
        if (fm.isStateSaved()) {
            pendingSection = section;
            return;
        }

        FragmentTransaction tx = fm.beginTransaction().setReorderingAllowed(true);
        Fragment home = fm.findFragmentByTag(TAG_HOME);
        if (home == null) {
            home = new HomeFragment();
            tx.add(R.id.panelContent, home, TAG_HOME)
                    .setMaxLifecycle(home, Lifecycle.State.RESUMED);
        } else {
            tx.show(home).setMaxLifecycle(home, Lifecycle.State.RESUMED);
        }

        Fragment target = section == SECTION_NONE ? null : fm.findFragmentByTag(tagForSection(section));
        int targetContainer = containerForSection(section);
        for (Fragment child : fm.getFragments()) {
            String tag = child.getTag();
            if (TAG_HOME.equals(tag) || !isDashboardTag(tag)) continue;
            if (tag.equals(tagForSection(section))) {
                tx.show(child).setMaxLifecycle(child, Lifecycle.State.RESUMED);
            } else {
                tx.hide(child).setMaxLifecycle(child, Lifecycle.State.STARTED);
            }
        }
        if (section != SECTION_NONE && target == null) {
            target = createFragmentForSection(section);
            tx.add(targetContainer, target, tagForSection(section))
                    .setMaxLifecycle(target, Lifecycle.State.RESUMED);
        }

        updateSectionViews(section);
        if (immediate) tx.commitNow(); else tx.commit();
        activeSection = section;
        pendingSection = PENDING_NONE;
    }

    private void updateSectionViews(int section) {
        sectionContainersContent.setVisibility(section == SECTION_CONTAINERS ? View.VISIBLE : View.GONE);
        sectionDownloadContent.setVisibility(section == SECTION_DOWNLOAD ? View.VISIBLE : View.GONE);
        sectionAboutContent.setVisibility(section == SECTION_ABOUT ? View.VISIBLE : View.GONE);
        btnSectionContainers.setText(sectionTitle(R.string.section_containers, section == SECTION_CONTAINERS));
        btnSectionDownload.setText(sectionTitle(R.string.section_download, section == SECTION_DOWNLOAD));
        btnSectionAbout.setText(sectionTitle(R.string.section_about, section == SECTION_ABOUT));
    }

    private CharSequence sectionTitle(int titleRes, boolean expanded) {
        return getString(titleRes) + (expanded ? "  ⌃" : "  ⌄");
    }

    private Fragment createFragmentForSection(int section) {
        if (section == SECTION_CONTAINERS) return new RootfsFragment();
        if (section == SECTION_DOWNLOAD) return new DownloadFragment();
        return new AboutFragment();
    }

    private int containerForSection(int section) {
        if (section == SECTION_CONTAINERS) return R.id.sectionContainersContent;
        if (section == SECTION_DOWNLOAD) return R.id.sectionDownloadContent;
        return R.id.sectionAboutContent;
    }

    private String tagForSection(int section) {
        if (section == SECTION_CONTAINERS) return TAG_CONTAINERS;
        if (section == SECTION_DOWNLOAD) return TAG_DOWNLOAD;
        if (section == SECTION_ABOUT) return TAG_ABOUT;
        return TAG_HOME;
    }

    private boolean isDashboardTag(@Nullable String tag) {
        return TAG_CONTAINERS.equals(tag) || TAG_DOWNLOAD.equals(tag) || TAG_ABOUT.equals(tag);
    }

    private boolean isValidSection(int section) {
        return section >= SECTION_NONE && section <= SECTION_ABOUT;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (legacyRemovalPending) removeLegacyChildren();
        if (pendingSection != PENDING_NONE) {
            int section = pendingSection;
            pendingSection = PENDING_NONE;
            applySection(section, true);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_ACTIVE_SECTION, activeSection);
        if (pendingSection != PENDING_NONE) outState.putInt(KEY_PENDING_SECTION, pendingSection);
        // Read by migration tooling from the former tab shell; harmless if absent.
        outState.putInt(LEGACY_KEY_SELECTED_TAB, legacyTabForSection(activeSection));
    }

    private int sectionForLegacyTab(int tabId) {
        if (tabId == R.id.nav_rootfs) return SECTION_CONTAINERS;
        if (tabId == R.id.nav_download) return SECTION_DOWNLOAD;
        if (tabId == R.id.nav_about) return SECTION_ABOUT;
        return SECTION_NONE;
    }

    private int legacyTabForSection(int section) {
        if (section == SECTION_CONTAINERS) return R.id.nav_rootfs;
        if (section == SECTION_DOWNLOAD) return R.id.nav_download;
        if (section == SECTION_ABOUT) return R.id.nav_about;
        return R.id.nav_home;
    }
}
