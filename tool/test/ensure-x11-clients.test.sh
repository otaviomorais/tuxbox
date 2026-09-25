#!/bin/sh
# Testes do helper ensure-x11-clients (Arch-only).
# Roda em Linux comum, sem Android: cria rootfs falsos e um `chroot` stub que
# registra os argumentos e simula a instalacao do pacote.
#
# Uso: sh tool/test/ensure-x11-clients.test.sh

set -u

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
HELPER="$HERE/../../app/src/main/assets/bin/ensure-x11-clients"
HELPER="$(CDPATH= cd -- "$(dirname -- "$HELPER")" && pwd)/$(basename -- "$HELPER")"

PASS=0
FAIL=0

ok()   { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  FAIL %s\n' "$1"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ ! -f "$HELPER" ]; then
    printf 'helper nao encontrado: %s\n' "$HELPER"
    exit 1
fi

# --- fake rootfs -----------------------------------------------------------
# make_rootfs <dir> [pacman] [com-libs]
make_rootfs() {
    _d="$1"; _pacman="${2:-no}"; _libs="${3:-no}"
    mkdir -p "$_d/usr/bin" "$_d/usr/lib" "$_d/lib" "$_d/etc" "$_d/var/log" "$_d/proc"
    printf 'ID=arch\n' >"$_d/etc/os-release"
    [ "$_pacman" = "pacman" ] && {
        printf '#!/bin/sh\nexit 0\n' >"$_d/usr/bin/pacman"
        chmod 755 "$_d/usr/bin/pacman"
        # CONF_MODE=strict reproduz o bug real: /etc/pacman.conf de fabrica do
        # Arch (SigLevel estrita, sem TrustAll, sem DisableSandbox).
        case "${CONF_MODE:-relaxed}" in
            strict) cat >"$_d/etc/pacman.conf" <<'C'
[options]
HoldPkg     = pacman glibc
SigLevel    = Required DatabaseOptional
LocalFileSigLevel = Optional
C
            ;;
            *) printf '[options]\nHoldPkg = pacman glibc\n' >"$_d/etc/pacman.conf" ;;
        esac
    }
    if [ "$_libs" = "libs" ]; then
        : >"$_d/usr/lib/libxcb.so.1"
        : >"$_d/usr/lib/libxcb-icccm.so.4"
    fi
}

# --- stub de chroot --------------------------------------------------------
# fakechroot <bindir>: o stub cria as libs esperadas e grava os args em $ARGS
setup_chroot_stub() {
    _bin="$1"
    mkdir -p "$_bin"
    cat >"$_bin/chroot" <<'STUB'
#!/bin/sh
# stub: chroot <rootfs> <cmd> [args...]
root="$1"; shift
printf '%s\n' "$*" >>"$ARGS_FILE"
# guarda o conf temporario para o teste inspecionar (o helper limpa o stage)
if [ -f "$root/.tuxbox-x11-pacman/pacman.conf" ]; then
    cp "$root/.tuxbox-x11-pacman/pacman.conf" "$ARGS_FILE.conf"
fi
# Sonda de repo (-Si): AVAILABLE_PKGS lista o que existe neste "repo".
# O helper chama `pacman -Si <pacote>`; o nome do pacote e' o ultimo arg.
case " $* " in
    *" -Si "*)
        _probe=""
        for _a in "$@"; do
            case "$_a" in
                -*) continue ;;
                /usr/bin/pacman) continue ;;
            esac
            _probe="$_a"
        done
        for p in $AVAILABLE_PKGS; do
            [ "$p" = "$_probe" ] && exit 0
        done
        exit 1
        ;;
esac
# Instalacao (-S): cria libs e os binarios pedidos.
mkdir -p "$root/usr/lib" "$root/usr/bin"
: >"$root/usr/lib/libxcb.so.1"
: >"$root/usr/lib/libxcb-icccm.so.4"
for a in "$@"; do
    case "$a" in
        -*) continue ;;
    esac
    case "$a" in
        xcb-util-wm|libxcb) ;;
        openbox) : >"$root/usr/bin/openbox" ;;
        xterm) : >"$root/usr/bin/xterm" ;;
        xorg-xclock) : >"$root/usr/bin/xclock" ;;
    esac
done
chmod 755 "$root"/usr/bin/* 2>/dev/null || true
exit 0
STUB
    chmod 755 "$_bin/chroot"
}

printf 'ensure-x11-clients\n'

# 1) Nao-Arch: nao-arch nao tem pacman -> no-op, nao chama chroot.
R="$TMP/r1"; make_rootfs "$R" no no
BIN="$TMP/bin1"; setup_chroot_stub "$BIN"
ARGS_FILE="$TMP/args1" out="$R/../../out1"; : >"$TMP/args1"
out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args1" AVAILABLE_PKGS="" sh "$HELPER" "$R" 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "nao-arch retorna 0" || bad "nao-arch rc=$rc"
[ "$out" = "skip: nao-arch" ] && ok "nao-arch imprime 'skip: nao-arch'" || bad "nao-arch saida='$out'"
[ ! -s "$TMP/args1" ] && ok "nao-arch nao chama chroot" || bad "nao-arch chamou chroot"

# 2) Arch com libs presentes: nao instala nada (idempotente).
R="$TMP/r2"; make_rootfs "$R" pacman libs
BIN="$TMP/bin2"; setup_chroot_stub "$BIN"
: >"$TMP/args2"
out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args2" AVAILABLE_PKGS="" sh "$HELPER" "$R" 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "arch completo retorna 0" || bad "arch completo rc=$rc"
case "$out" in ok:*) ok "arch completo nao reinstala" ;; *) bad "arch completo saida='$out'" ;; esac
[ ! -s "$TMP/args2" ] && ok "arch completo nao chama chroot" || bad "arch completo chamou chroot"

# 3) Arch sem libs: instala xcb-util-wm + libxcb.
R="$TMP/r3"; make_rootfs "$R" pacman no
BIN="$TMP/bin3"; setup_chroot_stub "$BIN"
: >"$TMP/args3"
out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args3" AVAILABLE_PKGS="" sh "$HELPER" "$R" 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "arch sem libs retorna 0" || bad "arch sem libs rc=$rc"
case "$out" in instalado:*) ok "arch sem libs instala" ;; *) bad "arch sem libs saida='$out'" ;; esac
[ -e "$R/usr/lib/libxcb-icccm.so.4" ] && ok "libxcb-icccm presente depois" || bad "libxcb-icccm ausente"
[ -e "$R/usr/lib/libxcb.so.1" ] && ok "libxcb presente depois" || bad "libxcb ausente"

# 3b) Argumentos do pacman: sandbox opt-out + -S --needed --noconfirm + pacotes.
args="$(cat "$TMP/args3")"
for needle in --disable-sandbox -S --needed --noconfirm xcb-util-wm libxcb; do
    case "$args" in
        *"$needle"*) ok "pacman recebe '$needle'" ;;
        *) bad "pacman sem '$needle' (args=$args)" ;;
    esac
done
# O conf temporario precisa ter DisableSandbox injetado (copiado pelo stub).
if [ -f "$TMP/args3.conf" ]; then
    grep -q '^DisableSandbox' "$TMP/args3.conf" \
        && ok "conf do pacman tem DisableSandbox" \
        || bad "conf sem DisableSandbox"
else
    bad "conf temporario nao foi criado"
fi

# 3c) Idempotencia: segunda rodada nao chama chroot.
: >"$TMP/args3b"
out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args3b" AVAILABLE_PKGS="" sh "$HELPER" "$R" 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "segunda rodada retorna 0" || bad "segunda rodada rc=$rc"
[ ! -s "$TMP/args3b" ] && ok "segunda rodada nao reinstala" || bad "segunda rodada reinstalou"

# 3d) Stage temporario e' removido ao final.
[ ! -d "$R/.tuxbox-x11-pacman" ] && ok "stage temporario limpo" || bad "stage temporario sobrou"

# 4) Log sempre escrito no rootfs.
[ -s "$R/var/log/tuxbox-x11-clients.log" ] && ok "log gravado" || bad "log ausente"

# 5) Argumento invalido.
sh "$HELPER" >/dev/null 2>&1
[ "$?" = "2" ] && ok "sem argumento -> rc 2" || bad "sem argumento -> rc $?"

# 6) Imagem Arch REAL (opt-in): extrai usr/bin/pacman + etc/pacman.conf do
#    tarball oficial e roda o helper contra eles. Demonstra que a base Arch
#    nao tem libxcb/libxcb-icccm e que o helper emite o comando pacman certo.
#    Habilite com RUN_ARCH_TAR_TEST=1 (a leitura do .tar.xz e lenta).
ARCH_TAR="${ARCH_TAR:-/mnt/storage/Download/TuxBox/tuxbox-archlinux-current-arm64.tar.xz}"
if [ "${RUN_ARCH_TAR_TEST:-0}" = "1" ] && [ -r "$ARCH_TAR" ]; then
    R="$TMP/r6"
    mkdir -p "$R"
    if tar -xJf "$ARCH_TAR" -C "$R" ./usr/bin/pacman ./etc/pacman.conf 2>/dev/null; then
        BIN="$TMP/bin6"; setup_chroot_stub "$BIN"
        : >"$TMP/args6"
        out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args6" AVAILABLE_PKGS="openbox xterm xorg-xclock" \
            sh "$HELPER" "$R" full 2>&1)"
        rc=$?
        for needle in openbox xterm xorg-xclock; do
            case "$(cat "$TMP/args6")" in
                *"$needle"*) ok "imagem Arch real: sonda $needle" ;;
                *) bad "imagem Arch real: nao sondou $needle" ;;
            esac
        done
        [ "$rc" = "0" ] && ok "imagem Arch real: rc 0" || bad "imagem Arch real: rc=$rc ($out)"
        case "$out" in
            instalado:*) ok "imagem Arch real: detecta libs ausentes e instala" ;;
            *) bad "imagem Arch real: saida='$out'" ;;
        esac
        for needle in --disable-sandbox -S --needed --noconfirm xcb-util-wm libxcb; do
            case "$(cat "$TMP/args6")" in
                *"$needle"*) ok "imagem Arch real: pacman recebe '$needle'" ;;
                *) bad "imagem Arch real: pacman sem '$needle'" ;;
            esac
        done
        grep -q '^DisableSandbox' "$TMP/args6.conf" 2>/dev/null \
            && ok "imagem Arch real: DisableSandbox no conf" \
            || bad "imagem Arch real: conf sem DisableSandbox"
    else
        bad "nao consegui extrair $ARCH_TAR"
    fi
else
    printf '  skip imagem Arch real (RUN_ARCH_TAR_TEST=1 + tarball presente)\n'
fi

# 6b) REGRESSAO do bug real: conf de fabrica (SigLevel estrita, sem
#     DisableSandbox). O helper tem que normalizar a copia, senao o pacman
#     rejeita a assinatura e a instalacao falha em silencio.
R="$TMP/r6b"; CONF_MODE=strict make_rootfs "$R" pacman no
BIN="$TMP/bin6b"; setup_chroot_stub "$BIN"
: >"$TMP/args6b"
out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args6b" AVAILABLE_PKGS="" sh "$HELPER" "$R" 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "conf estrito: rc 0" || bad "conf estrito: rc=$rc ($out)"
if [ -f "$TMP/args6b.conf" ]; then
    grep -q 'TrustAll' "$TMP/args6b.conf" \
        && ok "conf estrito: SigLevel normalizada com TrustAll" \
        || bad "conf estrito: SigLevel SEM TrustAll (pacman rejeitaria a assinatura)"
    grep -q '^DisableSandbox' "$TMP/args6b.conf" \
        && ok "conf estrito: DisableSandbox injetado" \
        || bad "conf estrito: sem DisableSandbox"
    grep -q '^LocalFileSigLevel = Optional' "$TMP/args6b.conf" \
        && ok "conf estrito: LocalFileSigLevel = Optional" \
        || bad "conf estrito: LocalFileSigLevel nao normalizado"
    # Nao pode sobrar a linha estrita duplicada.
    _n="$(grep -c '^SigLevel' "$TMP/args6b.conf")"
    [ "$_n" = "1" ] && ok "conf estrito: uma unica linha SigLevel" \
        || bad "conf estrito: $_n linhas SigLevel"
else
    bad "conf estrito: conf temporario nao criado"
fi
# O conf do container NAO deve ser modificado (so a copia).
grep -q 'TrustAll' "$R/etc/pacman.conf" \
    && bad "conf estrito: alterou /etc/pacman.conf do container" \
    || ok "conf estrito: /etc/pacman.conf do container intacto"

# 6c) keyring ausente: o helper tem que inicializar antes de instalar.
R="$TMP/r6c"; make_rootfs "$R" pacman no
BIN="$TMP/bin6c"; setup_chroot_stub "$BIN"
: >"$TMP/args6c"
out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args6c" AVAILABLE_PKGS="" sh "$HELPER" "$R" 2>&1)"
[ "$?" = "0" ] && ok "sem keyring: rc 0" || bad "sem keyring: falhou"
grep -q 'keyring ausente' "$R/var/log/tuxbox-x11-clients.log" 2>/dev/null \
    && bad "sem keyring: helper tentou pacman-key sem pacman-key no rootfs" \
    || ok "sem keyring: sem pacman-key, helper nao se perde"

# 6d) com pacman-key presente, o keyring DEVE ser inicializado.
R="$TMP/r6d"; make_rootfs "$R" pacman no
printf '#!/bin/sh\nexit 0\n' >"$R/usr/bin/pacman-key"; chmod 755 "$R/usr/bin/pacman-key"
BIN="$TMP/bin6d"; setup_chroot_stub "$BIN"
: >"$TMP/args6d"
out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args6d" AVAILABLE_PKGS="" sh "$HELPER" "$R" 2>&1)"
[ "$?" = "0" ] && ok "com pacman-key: rc 0" || bad "com pacman-key: falhou"
grep -q 'pacman-key --init' "$TMP/args6d" \
    && ok "com pacman-key: inicializa o keyring" \
    || bad "com pacman-key: nao inicializou o keyring"

# 7) Modo "full": libs + programas de X11 (WM e clientes de teste).
R="$TMP/r7"; make_rootfs "$R" pacman no
BIN="$TMP/bin7"; setup_chroot_stub "$BIN"
: >"$TMP/args7"
out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args7" AVAILABLE_PKGS="openbox xterm" \
    sh "$HELPER" "$R" full 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "modo full: rc 0" || bad "modo full: rc=$rc ($out)"
case "$out" in
    *programas*) ok "modo full: instala programas de X11" ;;
    *) bad "modo full: saida='$out'" ;;
esac
[ -x "$R/usr/bin/openbox" ] && ok "modo full: openbox instalado" || bad "modo full: sem openbox"
[ -x "$R/usr/bin/xterm" ] && ok "modo full: xterm instalado" || bad "modo full: sem xterm"
[ -x "$R/usr/bin/xclock" ] && bad "modo full: xclock instalado apesar de ausente no repo" \
    || ok "modo full: xorg-xclock pulado (ausente no repo)"
grep -q 'ausente no repo' "$R/var/log/tuxbox-x11-clients.log" \
    && ok "modo full: log registra pacote pulado" || bad "modo full: sem log de pulo"

# 7b) Modo full e' idempotente.
: >"$TMP/args7b"
out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args7b" AVAILABLE_PKGS="openbox xterm" \
    sh "$HELPER" "$R" full 2>&1)"
[ "$?" = "0" ] && ok "modo full: segunda rodada rc 0" || bad "modo full: segunda rodada falhou"
if grep -q ' -S ' "$TMP/args7b" 2>/dev/null; then
    bad "modo full: reinstalou ($(tr '\n' '|' < "$TMP/args7b"))"
else
    ok "modo full: segunda rodada nao reinstala"
fi

# 8) Modo default (sem 2o arg) NAO instala programas grandes.
R="$TMP/r8"; make_rootfs "$R" pacman no
BIN="$TMP/bin8"; setup_chroot_stub "$BIN"
: >"$TMP/args8"
out="$(PATH="$BIN:$PATH" ARGS_FILE="$TMP/args8" AVAILABLE_PKGS="openbox xterm xorg-xclock" \
    sh "$HELPER" "$R" 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "modo default: rc 0" || bad "modo default: rc=$rc"
[ -x "$R/usr/bin/openbox" ] && bad "modo default instalou openbox" || ok "modo default: so' libs"
grep -q 'openbox' "$TMP/args8" && bad "modo default mandou openbox ao pacman" \
    || ok "modo default: nenhum programa pedido ao pacman"

printf '\n%d ok, %d FAIL\n' "$PASS" "$FAIL"
[ "$FAIL" = "0" ] || exit 1
