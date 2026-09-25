package sh.tuxbox.root;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import sh.tuxbox.root.ui.AboutFragment;
import sh.tuxbox.root.ui.DownloadFragment;
import sh.tuxbox.root.ui.HomeFragment;
import sh.tuxbox.root.ui.RootfsFragment;
import sh.tuxbox.root.ui.TerminalFragment;

public class MainActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(this::onNavItemSelected);

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_home);
        }

        // v3.13 — a permissão POST_NOTIFICATIONS (Android 13+) NÃO é pedida
        // aqui: no lançamento não há contexto e, negada uma vez, ela fica negada
        // para sempre — a notificação persistente da sessão simplesmente nunca
        // aparecia e o app morria em segundo plano sem aviso. O pedido agora
        // acontece no Terminal, no momento em que a sessão começa
        // (TerminalFragment.ensureSessionNotification), que também avisa e leva
        // às configurações se já estiver bloqueada.
    }

    private boolean onNavItemSelected(@NonNull MenuItem item) {
        Fragment f = null;
        int id = item.getItemId();
        if (id == R.id.nav_home) {
            f = new HomeFragment();
        } else if (id == R.id.nav_download) {
            f = new DownloadFragment();
        } else if (id == R.id.nav_rootfs) {
            f = new RootfsFragment();
        } else if (id == R.id.nav_terminal) {
            f = new TerminalFragment();
        } else if (id == R.id.nav_about) {
            f = new AboutFragment();
        }
        if (f != null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragmentContainer, f, "page")
                    .commit();
        }
        return true;
    }

    /** The terminal tab is reachable from the Rootfs list too; let fragments switch tabs. */
    public void openTerminalTab() {
        bottomNav.setSelectedItemId(R.id.nav_terminal);
    }
}