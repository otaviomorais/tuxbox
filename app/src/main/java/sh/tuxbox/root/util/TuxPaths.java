package sh.tuxbox.root.util;

/** Single source of truth for the container base directory (rootfs location). */
public final class TuxPaths {

    /**
     * Real-storage base for container images. /data/local survives reboots and
     * does not consume RAM. Downloads/imports install directly here.
     */
    public static final String ROOTFS_BASE = "/data/local/tuxbox";

    /**
     * v3.11 — comando único que lista SÓ containers reais: diretórios da base
     * que tenham etc/usr/bin. Antes era `ls -A -1 ROOTFS_BASE` puro, que
     * enumerava QUALQUER arquivo solto na base e o app tratava como container
     * (os logs de diagnóstico ptyd.log/boot-early.log apareceram como
     * "containers" e quebraram a lista). Os logs agora vivem fora da base, em
     * {@link #LOGS_DIR}, mas o filtro protege contra qualquer arquivo futuro.
     */
    public static final String LIST_ROOTFS_CMD =
            "for d in " + ROOTFS_BASE + "/*; do"
            + " [ -d \"$d\" ] || continue;"
            + " if [ -e \"$d/etc\" ] || [ -e \"$d/usr\" ] || [ -e \"$d/bin\" ]; then"
            + " echo \"${d##*/}\";"
            + " fi;"
            + " done 2>/dev/null";

    /**
     * v3.11 — logs de diagnóstico FORA de {@link #ROOTFS_BASE}: dentro da base
     * qualquer arquivo vira "container" na lista do app.
     */
    public static final String LOGS_DIR = "/data/local/tuxbox-logs";

    private TuxPaths() { }
}