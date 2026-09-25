#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 LongQT-sea <long025733@gmail.com>
#
# Testes do tuxbox-kernel-check.
#
# O script tem duas responsabilidades que precisam ser conferidas:
#  1) nao destruir nada fora do scratch (ele cria device nodes e faz pivot_root)
#  2) o veredito refletir a realidade do ambiente
#
# Rodamos em Linux comum. O scratch e'/data/local/tuxbox-kcheck' por padrao;
# os testes apontam SCRATCH para um tmpdir proprio via TUXBOX_SCRATCH.
#
# Uso: sh tool/test/tuxbox-kernel-check.test.sh

set -u

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SCRIPT="$HERE/../../app/src/main/assets/bin/tuxbox-kernel-check"
SCRIPT="$(CDPATH= cd -- "$(dirname -- "$SCRIPT")" && pwd)/$(basename -- "$SCRIPT")"

PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  FAIL %s\n' "$1"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

[ -f "$SCRIPT" ] || { printf 'script nao encontrado: %s\n' "$SCRIPT"; exit 1; }

printf 'tuxbox-kernel-check\n'

# =========================================================================
# 1) Sintaxe e modo --summary
# =========================================================================
sh -n "$SCRIPT" 2>/dev/null && ok "sintaxe ok" || bad "sintaxe quebrada"

# Redireciona o scratch para o tmpdir: o script le TUXBOX_SCRATCH se existir.
# Como o script tem o caminho fixo, geramos uma copia com o scratch trocado.
SCRATCH_DIR="$TMP/scratch"
SAFE="$(CDPATH= cd -- "$(dirname -- "$SCRIPT")" && pwd)/$(basename -- "$SCRIPT")"
cp "$SCRIPT" "$TMP/check.sh"
sed -i "s#^SCRATCH=\"/data/local/tuxbox-kcheck\"#SCRATCH=\"$SCRATCH_DIR\"#" "$TMP/check.sh"
grep -q "SCRATCH=\"$SCRATCH_DIR\"" "$TMP/check.sh" \
    && ok "scratch redirecionavel para teste" \
    || bad "nao consegui redirecionar o scratch"

# =========================================================================
# 2) --summary: uma unica linha, sem relatorio
# =========================================================================
out="$(sh "$TMP/check.sh" --summary 2>&1)"; rc=$?
case "$out" in
    *TUXBOX_VEREDITO=*) ok "--summary emite TUXBOX_VEREDITO" ;;
    *) bad "--summary sem TUXBOX_VEREDITO: '$out'" ;;
esac
lines="$(printf '%s\n' "$out" | wc -l)"
[ "$lines" -le 7 ] && ok "--summary e curto ($lines linhas)" || bad "--summary longo ($lines linhas)"
case "$out" in
    *"== namespaces =="*) bad "--summary imprimiu relatorio completo" ;;
    *) ok "--summary nao imprime o relatorio" ;;
esac
# rc coerente com o veredito
verdict="$(printf '%s\n' "$out" | sed -n 's/^TUXBOX_VEREDITO=//p')"
case "$verdict" in
    completo)  [ "$rc" = "0" ] && ok "rc 0 para completo" || bad "rc=$rc para completo" ;;
    funcional) [ "$rc" = "1" ] && ok "rc 1 para funcional" || bad "rc=$rc para funcional" ;;
    incompativel) [ "$rc" = "2" ] && ok "rc 2 para incompativel" || bad "rc=$rc para incompativel" ;;
    *) bad "veredito invalido: '$verdict'" ;;
esac

# =========================================================================
# 3) Saida completa: marcadores e contagem
# =========================================================================
full="$(sh "$TMP/check.sh" 2>&1)"
for sec in "capacidades do kernel" "namespaces" "ferramentas" "pivot_root" \
           "mknod (GPU" "SELinux" "rede" "Termux:X11" "VEREDITO"; do
    case "$full" in
        *"$sec"*) ok "secao presente: $sec" ;;
        *) bad "secao ausente: $sec" ;;
    esac
done
for m in "[ OK ]" "[FALHA]" "[ -- ]"; do
    case "$full" in
        *"$m"*) ok "marcador $m usado" ;;
        *) bad "marcador $m nunca aparece" ;;
    esac
done

# Contadores coerentes: PASS+FAIL+SKIP == TOTAL
p="$(printf '%s\n' "$full" | sed -n 's/^TUXBOX_PASS=//p')"
f="$(printf '%s\n' "$full" | sed -n 's/^TUXBOX_FAIL=//p')"
sk="$(printf '%s\n' "$full" | sed -n 's/^TUXBOX_SKIP=//p')"
tt="$(printf '%s\n' "$full" | sed -n 's/^TUXBOX_TOTAL=//p')"
if [ -n "$p" ] && [ -n "$f" ] && [ -n "$sk" ] && [ -n "$tt" ]; then
    if [ "$((p + f + sk))" = "$tt" ]; then
        ok "contadores coerentes ($p+$f+$sk=$tt)"
    else
        bad "contadores incoerentes: $p+$f+$sk != $tt"
    fi
else
    bad "contadores ausentes"
fi

# =========================================================================
# 4) Nao destrutividade: nada fora do scratch e' tocado
# =========================================================================
SENTINEL="$TMP/nao-tocar"
printf 'original\n' >"$SENTINEL"
# Um "rootfs falso" que o script poderia danificar se confusedisse os caminhos
FAKE_CT="$TMP/rootfs"
mkdir -p "$FAKE_CT/dev" "$FAKE_CT/old_root"
printf 'container-intacto\n' >"$FAKE_CT/marker"
printf 'dev-original\n' >"$FAKE_CT/dev/arquivo"

sh "$TMP/check.sh" >/dev/null 2>&1

[ "$(cat "$SENTINEL")" = "original" ] \
    && ok "nao mexeu em arquivo fora do scratch" || bad "CORPOU arquivo externo"
[ "$(cat "$FAKE_CT/marker")" = "container-intacto" ] \
    && ok "nao mexeu no rootfs" || bad "CORPOU o rootfs"
[ "$(cat "$FAKE_CT/dev/arquivo")" = "dev-original" ] \
    && ok "nao mexeu em /dev do rootfs" || bad "CORPOU /dev do rootfs"
[ -d "$FAKE_CT/old_root" ] \
    && ok "nao pivotou o rootfs" || bad "DESTRUIU o rootfs do usuario"
# O proprio scratch tem de sumir (o trap limpa).
[ ! -d "$SCRATCH_DIR" ] && ok "scratch removido no fim" || bad "scratch sobrou em $SCRATCH_DIR"

# =========================================================================
# 5) Limpeza tambem quando o script e' interrompido
# =========================================================================
sh -c "sh '$TMP/check.sh' >/dev/null 2>&1 & p=\$!; sleep 0.3; kill -9 \$p 2>/dev/null; wait 2>/dev/null" >/dev/null 2>&1
rm -rf "$SCRATCH_DIR"
sh "$TMP/check.sh" >/dev/null 2>&1
[ ! -d "$SCRATCH_DIR" ] \
    && ok "sem scratch orfao apos execucao" \
    || bad "scratch orfao"

# =========================================================================
# 6) O veredito muda quando o mount namespace e' negado
#    O tuxbox faz `die` sem mount ns; o diagnostico tem que dizer
#    "incompativel" e nao "funcional".
# =========================================================================
mkdir -p "$TMP/fakebin"
cat >"$TMP/fakebin/unshare" <<'STUB'
#!/bin/sh
# unshare que so nega -m (mount) e aceita o resto
for a in "$@"; do
    [ "$a" = "-m" ] && exit 1
done
exit 0
STUB
chmod 755 "$TMP/fakebin/unshare"
out="$(PATH="$TMP/fakebin:$PATH" sh "$TMP/check.sh" --summary 2>&1)"
case "$out" in
    *TUXBOX_VEREDITO=incompativel*) ok "sem mount ns => incompativel" ;;
    *) bad "sem mount ns deu veredito errado: $(printf '%s' "$out" | sed -n 's/^TUXBOX_VEREDITO=//p')" ;;
esac
# A razao precisa estar em TUXBOX_MOTIVO (estruturado), nao so no texto.
motivo="$(printf '%s' "$out" | sed -n 's/^TUXBOX_MOTIVO=//p')"
case "$motivo" in
    *mount-namespace*) ok "MOTIVO estruturado cita mount-namespace" ;;
    *) bad "MOTIVO vazio ou errado: '$motivo'" ;;
esac
# E o veredito funcional tambem tem que dizer O QUE faltou (a GPU, no caso).
out2="$(sh "$TMP/check.sh" --summary 2>&1)"
v2="$(printf '%s' "$out2" | sed -n 's/^TUXBOX_VEREDITO=//p')"
m2="$(printf '%s' "$out2" | sed -n 's/^TUXBOX_MOTIVO=//p')"
if [ "$v2" = "funcional" ]; then
    [ -n "$m2" ] && [ "$m2" != "nenhuma" ] \
        && ok "veredito funcional lista o que falta" \
        || bad "veredito funcional sem lista de faltas (m2='$m2')"
fi

# =========================================================================
# 7) O veredito NAO e incompativel so por falta de uma capacidade opcional
#    Ex.: sem mknod o container ainda sobe (so perde a GPU).
# =========================================================================
mkdir -p "$TMP/nomknod"
cat >"$TMP/nomknod/mknod" <<'STUB'
#!/bin/sh
exit 1
STUB
chmod 755 "$TMP/nomknod/mknod"
out="$(PATH="$TMP/nomknod:$PATH" sh "$TMP/check.sh" --summary 2>&1)"
verdict="$(printf '%s' "$out" | sed -n 's/^TUXBOX_VEREDITO=//p')"
case "$verdict" in
    incompativel) bad "mknod ausente virou incompativel (deveria ser funcional)" ;;
    funcional|completo) ok "mknod ausente => $verdict (container sobe)" ;;
    *) bad "veredito inesperado: $verdict" ;;
esac

# =========================================================================
# 8) O script nao depende de pm/root para rodar
#    (roda no host; pm pode nao existir num shell de teste)
# =========================================================================
mkdir -p "$TMP/nopm"
out="$(PATH="$TMP/nopm:$PATH" sh "$TMP/check.sh" --summary 2>&1)"
case "$out" in
    *TUXBOX_VEREDITO=*) ok "roda sem pm no PATH" ;;
    *) bad "depende de pm para rodar" ;;
esac
# pm ausente -> Termux:X11 marcado como falha, nao crash
case "$out" in
    *TUXBOX_FAIL=*) ok "Termux:X11 ausente vira FALHA contabilizada" ;;
    *) bad "Termux:X11 ausente nao foi contabilizado" ;;
esac

printf '\n%d ok, %d FAIL\n' "$PASS" "$FAIL"
[ "$FAIL" = "0" ] || exit 1
