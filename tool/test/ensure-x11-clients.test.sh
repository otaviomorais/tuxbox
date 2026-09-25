#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 LongQT-sea <long025733@gmail.com>
#
# Testes do ensure-x11-clients (Arch-only).
#
# Roda em Linux comum, sem Android. Dois cenarios:
#   - host mode: <rootfs>  -> o script e' copiado e chamado via chroot real
#   - in-container mode: --in-container -> roda direto (é o caminho do boot)
#
# O "pacman" e' um STUB que grava os argumentos e cria os arquivos que um
# pacote real criaria, respeitando AVAILABLE_PKGS (o que o repo teria) e
# FAIL_SIGLEVEL (simula pacman recusando assinatura).
#
# Uso: sh tool/test/ensure-x11-clients.test.sh

set -u

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
HELPER="$HERE/../../app/src/main/assets/bin/ensure-x11-clients"
HELPER="$(CDPATH= cd -- "$(dirname -- "$HELPER")" && pwd)/$(basename -- "$HELPER")"

PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  FAIL %s\n' "$1"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

[ -f "$HELPER" ] || { printf 'helper nao encontrado: %s\n' "$HELPER"; exit 1; }

# --- pacman stub ----------------------------------------------------------
# STUB_PACMAN <caminho>  -> gera um pacman/pacman-key que honra:
#   AVAILABLE_PKGS  pacotes que existem no repo (default: todos)
#   FAIL_SIGLEVEL   != 0 faz o pacman recusar a assinatura (bug antigo)
make_pacman_stub() {
    _bin="$1"
    mkdir -p "$_bin"
    cat >"$_bin/pacman" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >>"$PACMAN_ARGS"
_pkgs="$*"
case "$_pkgs" in
    *-Si*)
        _p=""
        for a in "$@"; do
            case "$a" in
                -*) continue ;;
                pacman) continue ;;
            esac
            _p="$a"
        done
        for k in $AVAILABLE_PKGS; do
            [ "$k" = "$_p" ] && exit 0
        done
        echo "package '$_p' was not found" >&2
        exit 1
        ;;
esac
# Simula a recusa de assinatura quando o conf nao foi normalizado.
if [ "${FAIL_SIGLEVEL:-0}" != "0" ]; then
    if ! grep -q 'TrustAll' /etc/pacman.conf 2>/dev/null; then
        echo "signature from \"TuxBox\" is marginal trust" >&2
        exit 1
    fi
fi
# Instala: cria libs e binarios.
for a in "$@"; do
    case "$a" in
        -*) continue ;;
        xcb-util-wm|libxcb)
            mkdir -p /usr/lib
            : >/usr/lib/libxcb.so.1
            : >/usr/lib/libxcb-icccm.so.4
            ;;
        openbox) mkdir -p /usr/bin; printf '#!/bin/sh\nexit 0\n' >/usr/bin/openbox; chmod 755 /usr/bin/openbox ;;
        xterm) mkdir -p /usr/bin; printf '#!/bin/sh\nexit 0\n' >/usr/bin/xterm; chmod 755 /usr/bin/xterm ;;
        xorg-xclock) mkdir -p /usr/bin; printf '#!/bin/sh\nexit 0\n' >/usr/bin/xclock; chmod 755 /usr/bin/xclock ;;
    esac
done
exit 0
STUB
    cp "$_bin/pacman" "$_bin/pacman-key"
    chmod 755 "$_bin/pacman" "$_bin/pacman-key"
}

# --- rootfs falso ---------------------------------------------------------
# make_rootfs <dir> [pacman|nao] [libs] [strict|relaxed]
make_rootfs() {
    _d="$1"; _pm="${2:-nao}"; _libs="${3:-nao}"; _conf="${4:-strict}"
    mkdir -p "$_d/usr/bin" "$_d/usr/lib" "$_d/lib" "$_d/etc" \
             "$_d/var/log" "$_d/proc" "$_d/usr/local/bin" "$_d/etc/pacman.d" \
             "$_d/dev" "$_d/tmp" "$_d/var/lib/pacman" "$_d/var/cache/pacman/pkg"
    # /dev/null: sem isso o `>>"$LOG"` do helper falha com "Directory
    # nonexistent" dentro do chroot (o script usa redirecionamento em /dev/null).
    : >"$_d/dev/null"
    chmod 666 "$_d/dev/null"
    populate_sh "$_d"
    printf 'ID=arch\n' >"$_d/etc/os-release"
    case "$_conf" in
        strict)
            # Conf de FABRICA do Arch ARM: SigLevel estrita, sem DisableSandbox.
            printf '%s\n' '[options]' 'HoldPkg     = pacman glibc' \
                'SigLevel    = Required DatabaseOptional' \
                'LocalFileSigLevel = Optional' >"$_d/etc/pacman.conf"
            ;;
        *)
            printf '[options]\nHoldPkg = pacman glibc\n' >"$_d/etc/pacman.conf"
            ;;
    esac
    if [ "$_libs" = "libs" ]; then
        : >"$_d/usr/lib/libxcb.so.1"
        : >"$_d/usr/lib/libxcb-icccm.so.4"
    fi
    if [ "$_pm" = "pacman" ]; then
        # O stub do host mode: chamado DENTRO do chroot, precisa gravar os args
        # num arquivo do HOST para o teste inspecionar.
        # ATENCAO: o stub grava em /var/log/pacman-args.log (caminho DENTRO do
    # container). Gravar em "$PACMAN_ARGS" nao funciona: essa variavel aponta
    # para um path do HOST, invisivel dentro do chroot.
    cat >"$_d/usr/bin/pacman" <<'STUB'
#!/bin/sh
mkdir -p /var/log 2>/dev/null
printf '%s\n' "$*" >>/var/log/pacman-args.log
case "$*" in
    *-Si*)
        _p=""; for a in "$@"; do case "$a" in -*) continue ;; pacman) continue ;; esac; _p="$a"; done
        for k in $AVAILABLE_PKGS; do [ "$k" = "$_p" ] && exit 0; done
        exit 1 ;;
esac
if [ "${FAIL_SIGLEVEL:-0}" != "0" ] && ! grep -q TrustAll /etc/pacman.conf 2>/dev/null; then
    echo "signature is marginal trust" >&2; exit 1
fi
for a in "$@"; do
    case "$a" in
        -*) continue ;;
        xcb-util-wm|libxcb) mkdir -p /usr/lib; : >/usr/lib/libxcb.so.1; : >/usr/lib/libxcb-icccm.so.4 ;;
        openbox) mkdir -p /usr/bin; printf '#!/bin/sh\nexit 0\n' >/usr/bin/openbox; chmod 755 /usr/bin/openbox ;;
        xterm) mkdir -p /usr/bin; printf '#!/bin/sh\nexit 0\n' >/usr/bin/xterm; chmod 755 /usr/bin/xterm ;;
        xorg-xclock) mkdir -p /usr/bin; printf '#!/bin/sh\nexit 0\n' >/usr/bin/xclock; chmod 755 /usr/bin/xclock ;;
    esac
done
exit 0
STUB
        cp "$_d/usr/bin/pacman" "$_d/usr/bin/pacman-key"
        chmod 755 "$_d/usr/bin/pacman" "$_d/usr/bin/pacman-key"
    fi
}

# Path ABSOLUTO do chroot real. O stub chama "$REAL_CHROOT" (e nao `chroot`)
# senao o PATH re-resolve o proprio stub e o teste entra em recursao ate
# dar "Argument list too long" — bug do harness, nao do helper.
REAL_CHROOT="$(command -v chroot || echo /usr/sbin/chroot)"
[ -x "$REAL_CHROOT" ] || { printf 'chroot real nao encontrado\n'; exit 1; }

# Popula o rootfs falso com um /bin/sh que FUNCIONA (o helper roda dentro do
# chroot), copiando o shell e as libs de que ele precisa.
populate_sh() {
    _d="$1"
    mkdir -p "$_d/bin" "$_d/lib" "$_d/lib64" "$_d/usr/bin"
    cp /bin/sh "$_d/bin/sh" 2>/dev/null || true
    # ldd imprime "lib => /caminho" e "/caminho (0x...)" para o loader, entao
    # pegamos o que comeca com "/" em QUALQUER coluna (o loader dinamico vem na
    # $1; sem ele o /bin/sh do rootfs falso nem inicia).
    copy_ldd() {
        _bin="$1"
        # `tr -s ' \t' '\n'` remove o TAB inicial da linha do loader dinamico
        # ("\t/lib/ld-linux-*.so.1 (0x..)") — sem isso o grep '^/' perde o
        # loader e o /bin/sh do rootfs falso nem inicia.
        for _lib in $(ldd "$_bin" 2>/dev/null | tr -s ' \t' '\n' | grep '^/'); do
            [ -f "$_lib" ] || continue
            mkdir -p "$_d$(dirname "$_lib")"
            cp -L "$_lib" "$_d$_lib" 2>/dev/null || true
        done
    }
    copy_ldd /bin/sh
    for _t in sed grep awk cat cp mv rm mkdir chmod ls tail; do
        _p="$(command -v "$_t" 2>/dev/null)" || continue
        mkdir -p "$_d$(dirname "$_p")"
        cp -L "$_p" "$_d$_p" 2>/dev/null || true
        copy_ldd "$_p"
    done
}

# Chroot real para rootfs absolutos e com env propagado.
mk_chroot() {
    _bin="$1"
    mkdir -p "$_bin"
    cat >"$_bin/chroot" <<STUB
#!/bin/sh
# chroot real via caminho absoluto (evita recursao pelo PATH).
exec "$REAL_CHROOT" "\$@"
STUB
    chmod 755 "$_bin/chroot"
}

printf 'ensure-x11-clients\n'

# =========================================================================
# 1) HOST MODE: o caminho que a Home usa.
# =========================================================================

# 1a) Nao-Arch: no-op, sem copiar nada.
R="$TMP/h1"; make_rootfs "$R" nao no
out="$(sh "$HELPER" "$R" 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "host: nao-arch rc 0" || bad "host: nao-arch rc=$rc"
[ "$out" = "skip: nao-arch" ] && ok "host: nao-arch diz skip" || bad "host: nao-arch='$out'"
[ ! -e "$R/usr/local/bin/tuxbox-x11-clients" ] \
    && ok "host: nao-arch nao copia helper" || bad "host: nao-arch copiou helper"

# 1b) Host mode copia o helper e chama --in-container via chroot.
R="$TMP/h2"; make_rootfs "$R" pacman no strict
BIN="$TMP/bin2"; mk_chroot "$BIN"; make_pacman_stub "$TMP/stub2"
: >"$TMP/args2"
out="$(PATH="$BIN:$PATH" PACMAN_ARGS="$TMP/args2" AVAILABLE_PKGS="" \
    sh "$HELPER" "$R" 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "host: instala e rc 0" || bad "host: rc=$rc ($out)"
[ -x "$R/usr/local/bin/tuxbox-x11-clients" ] \
    && ok "host: helper copiado para o container" || bad "host: helper nao copiado"
[ -e "$R/usr/local/bin/.tuxbox-x11-clients.new" ] \
    && bad "host: deixou temporario" || ok "host: sem temporario"
[ -e "$R/usr/lib/libxcb.so.1" ] && ok "host: libxcb instalada" || bad "host: libxcb ausente"
[ -e "$R/usr/lib/libxcb-icccm.so.4" ] && ok "host: libxcb-icccm instalada" || bad "host: ausente"
for needle in -S --needed --noconfirm xcb-util-wm libxcb; do
    case "$(cat "$R/var/log/pacman-args.log" 2>/dev/null)" in
        *"$needle"*) ok "host: pacman recebe '$needle'" ;;
        *) bad "host: pacman sem '$needle'" ;;
    esac
done
# O comando NAO pode levar --cachedir nem --config (era o que quebrava).
case "$(cat "$R/var/log/pacman-args.log" 2>/dev/null)" in
    *--cachedir*|*--config*) bad "host: ainda usa --cachedir/--config" ;;
    *) ok "host: sem --cachedir/--config (o que quebrava v3.43-45)" ;;
esac
# Conf do container normalizado no lugar.
grep -q 'TrustAll' "$R/etc/pacman.conf" && ok "host: conf normalizado com TrustAll" \
    || bad "host: conf SEM TrustAll"
grep -q '^DisableSandbox' "$R/etc/pacman.conf" && ok "host: DisableSandbox injetado" \
    || bad "host: sem DisableSandbox"
[ "$(grep -c '^SigLevel' "$R/etc/pacman.conf")" = "1" ] \
    && ok "host: uma unica linha SigLevel" || bad "host: SigLevel duplicada"

# 1c) Idempotencia: segunda rodada nao chama o pacman.
: >"$TMP/args2b"
rm -f "$R/var/log/pacman-args.log"
out="$(PATH="$BIN:$PATH" AVAILABLE_PKGS="" sh "$HELPER" "$R" 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "host: 2a rodada rc 0" || bad "host: 2a rodada rc=$rc"
# A 2a rodada pode rodar pacman-key (--init/--populate/--lsign-key) — o
# keyring ainda nao existia da 1a vez e o helper normaliza. O que NAO pode
# acontecer e' nova INSTALACAO de pacote (linha com -S).
if grep -q ' -S ' "$R/var/log/pacman-args.log" 2>/dev/null; then
    bad "host: 2a rodada reinstalou: $(tr '\n' '|' < "$R/var/log/pacman-args.log")"
else
    ok "host: 2a rodada nao reinstala pacote"
fi

# 1d) REGRESSAO DO BUG: conf estrito + pacman que recusa assinatura.
#     Antes da v3.45 isso falhava em silencio.
R="$TMP/h3"; make_rootfs "$R" pacman no strict
BIN="$TMP/bin3"; mk_chroot "$BIN"
: >"$TMP/args3"
out="$(PATH="$BIN:$PATH" PACMAN_ARGS="$TMP/args3" AVAILABLE_PKGS="" FAIL_SIGLEVEL=1 \
    sh "$HELPER" "$R" 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "regressao: instala mesmo com conf estrito" \
    || bad "regressao: falhou (rc=$rc) — o bug voltou"
[ -e "$R/usr/lib/libxcb.so.1" ] && ok "regressao: libxcb no lugar" || bad "regressao: sem libxcb"

# 1e) Falha real: pacman quebrado -> mensagem util, log com o comando, rc 1.
R="$TMP/h4"; make_rootfs "$R" pacman no strict
printf '#!/bin/sh\nexit 1\n' >"$R/usr/bin/pacman"; chmod 755 "$R/usr/bin/pacman"
cp "$R/usr/bin/pacman" "$R/usr/bin/pacman-key"
BIN="$TMP/bin4"; mk_chroot "$BIN"
out="$(PATH="$BIN:$PATH" PACMAN_ARGS="$TMP/args4" AVAILABLE_PKGS="" \
    sh "$HELPER" "$R" 2>&1)"; rc=$?
[ "$rc" = "1" ] && ok "pacman quebrado: rc 1" || bad "pacman quebrado: rc=$rc"
case "$out" in
    *FALHOU*) ok "pacman quebrado: saida traz FALHOU" ;;
    *) bad "pacman quebrado: saida='$out'" ;;
esac
case "$out" in
    *xcb-util-wm*) ok "pacman quebrado: mensagem traz o comando" ;;
    *) bad "pacman quebrado: mensagem sem o comando" ;;
esac
case "$out" in
    *tuxbox-x11-clients.log*) ok "pacman quebrado: mensagem traz o log" ;;
    *) bad "pacman quebrado: mensagem sem o log" ;;
esac

# 1f) Modo full no host: instala programas que existem no repo.
R="$TMP/h5"; make_rootfs "$R" pacman no strict
BIN="$TMP/bin5"; mk_chroot "$BIN"
: >"$TMP/args5"
out="$(PATH="$BIN:$PATH" PACMAN_ARGS="$TMP/args5" AVAILABLE_PKGS="openbox xterm" \
    sh "$HELPER" "$R" full 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "host full: rc 0" || bad "host full: rc=$rc"
[ -x "$R/usr/bin/openbox" ] && ok "host full: openbox" || bad "host full: sem openbox"
[ -x "$R/usr/bin/xterm" ] && ok "host full: xterm" || bad "host full: sem xterm"
[ -x "$R/usr/bin/xclock" ] && bad "host full: xclock apesar de ausente" \
    || ok "host full: xorg-xclock pulado"
grep -q 'ausente no repo' "$R/var/log/tuxbox-x11-clients.log" \
    && ok "host full: log registra pulo" || bad "host full: sem log de pulo"

# 1g) Argumentos invalidos.
sh "$HELPER" >/dev/null 2>&1
[ "$?" = "2" ] && ok "host: sem argumento rc 2" || bad "host: sem argumento rc $?"
sh "$HELPER" /nao/existe >/dev/null 2>&1
[ "$?" = "2" ] && ok "host: rootfs inexistente rc 2" || bad "host: rootfs inexistente rc $?"

# =========================================================================
# 2) IN-CONTAINER MODE: o caminho do boot do --gui.
#    Usa pacman stub num prefixo proprio; o script le /etc/pacman.conf, entao
#    rodamos com um chroot minimo para ter o layout real.
# =========================================================================

# 2a) In-container com conf estrito + recusa de assinatura: tem que normalizar
#     e instalar (e' exatamente o bug que o usuario RELATOU).
R="$TMP/i1"; make_rootfs "$R" pacman no strict
cp "$HELPER" "$R/usr/local/bin/tuxbox-x11-clients"
chmod 755 "$R/usr/local/bin/tuxbox-x11-clients"
: >"$TMP/args1"
out="$("$REAL_CHROOT" "$R" /bin/sh -c \
    "AVAILABLE_PKGS='' FAIL_SIGLEVEL=1 PACMAN_ARGS='$TMP/args1' \
     /bin/sh /usr/local/bin/tuxbox-x11-clients --in-container" 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "in-container: instala com conf estrito" \
    || bad "in-container: falhou rc=$rc ($out)"
[ -e "$R/usr/lib/libxcb.so.1" ] && ok "in-container: libxcb instalada" || bad "in-container: sem libxcb"
grep -q TrustAll "$R/etc/pacman.conf" && ok "in-container: conf TrustAll" || bad "in-container: conf sem TrustAll"
case "$out" in ok:*) ok "in-container: sai com ok:" ;; *) bad "in-container: saida='$out'" ;; esac

# 2b) In-container ja com libs: nao chama pacman, instantaneo.
R="$TMP/i2"; make_rootfs "$R" pacman libs strict
cp "$HELPER" "$R/usr/local/bin/tuxbox-x11-clients"
chmod 755 "$R/usr/local/bin/tuxbox-x11-clients"
: >"$TMP/args2i"
out="$("$REAL_CHROOT" "$R" /bin/sh -c \
    "AVAILABLE_PKGS='' PACMAN_ARGS='$TMP/args2i' \
     /bin/sh /usr/local/bin/tuxbox-x11-clients --in-container" 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "in-container: ja completo rc 0" || bad "in-container: ja completo falhou ($out)"
[ ! -s "$TMP/args2i" ] && ok "in-container: nao chama pacman se ja tem" \
    || bad "in-container: chamou pacman sem necessidade"

# 2c) In-container full: instala os extras disponiveis.
R="$TMP/i3"; make_rootfs "$R" pacman no strict
cp "$HELPER" "$R/usr/local/bin/tuxbox-x11-clients"
chmod 755 "$R/usr/local/bin/tuxbox-x11-clients"
: >"$TMP/args3i"
out="$("$REAL_CHROOT" "$R" /bin/sh -c \
    "AVAILABLE_PKGS='openbox xterm' PACMAN_ARGS='$TMP/args3i' \
     /bin/sh /usr/local/bin/tuxbox-x11-clients --in-container full" 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "in-container full: rc 0" || bad "in-container full: falhou ($out)"
[ -x "$R/usr/bin/openbox" ] && ok "in-container full: openbox" || bad "in-container full: sem openbox"
[ -x "$R/usr/bin/xterm" ] && ok "in-container full: xterm" || bad "in-container full: sem xterm"
[ -x "$R/usr/bin/xclock" ] && bad "in-container full: xclock instalado sem estar no repo" \
    || ok "in-container full: xclock pulado"

# 2d) In-container sem pacman (nao-Arch): no-op.
R="$TMP/i4"; make_rootfs "$R" nao no
cp "$HELPER" "$R/usr/local/bin/tuxbox-x11-clients"
chmod 755 "$R/usr/local/bin/tuxbox-x11-clients"
out="$("$REAL_CHROOT" "$R" /bin/sh /usr/local/bin/tuxbox-x11-clients --in-container 2>&1)"
rc=$?
[ "$rc" = "0" ] && ok "in-container: sem pacman rc 0" || bad "in-container: sem pacman falhou"
[ "$out" = "skip: nao-arch" ] && ok "in-container: sem pacman = skip" || bad "saida='$out'"

# =========================================================================
# 3) Imagem Arch REAL (opt-in): conf de fabrica do tarball oficial.
# =========================================================================
ARCH_TAR="${ARCH_TAR:-}"
if [ "${RUN_ARCH_TAR_TEST:-0}" = "1" ] && [ -r "$ARCH_TAR" ]; then
    R="$TMP/r6"
    mkdir -p "$R"
    if tar -xJf "$ARCH_TAR" -C "$R" ./etc/pacman.conf 2>/dev/null; then
        grep -q 'SigLevel    = Required DatabaseOptional$' "$R/etc/pacman.conf" \
            && ok "imagem real: conf de fabrica tem SigLevel estrita (o bug)" \
            || bad "imagem real: conf inesperado"
        # Com o conf real, o helper TEM que normalizar (TrustAll + DisableSandbox).
        sh "$HELPER" --in-container >/dev/null 2>&1 || true
        cp "$HELPER" "$R/usr/local/bin/tuxbox-x11-clients" 2>/dev/null
        cp "$R/etc/pacman.conf" "$TMP/real-conf-before"
        mkdir -p "$R/usr/bin"
        printf '#!/bin/sh\nmkdir -p /usr/lib\n: >/usr/lib/libxcb.so.1\n: >/usr/lib/libxcb-icccm.so.4\nexit 0\n' >"$R/usr/bin/pacman"
        chmod 755 "$R/usr/bin/pacman"
        out="$("$REAL_CHROOT" "$R" /bin/sh /usr/local/bin/tuxbox-x11-clients --in-container 2>&1)"
        grep -q TrustAll "$R/etc/pacman.conf" \
            && ok "imagem real: helper normaliza o conf de fabrica" \
            || bad "imagem real: helper NAO normalizou o conf de fabrica"
        grep -q DisableSandbox "$R/etc/pacman.conf" \
            && ok "imagem real: DisableSandbox no conf real" \
            || bad "imagem real: sem DisableSandbox no conf real"
    else
        bad "nao consegui extrair $ARCH_TAR"
    fi
else
    printf '  skip imagem Arch real (RUN_ARCH_TAR_TEST=1 + tarball presente)\n'
fi

printf '\n%d ok, %d FAIL\n' "$PASS" "$FAIL"
[ "$FAIL" = "0" ] || exit 1
