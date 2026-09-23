# TuxBox — Interface gráfica (termux-x11) — registro completo

> Documento de registro do desenvolvimento da aba `--gui` do TuxBox
> (PT-BR, root, `sh.tuxbox.root`, container via namespaces, **sem nspawn**).
> Última atualização: v3.9f (APK `5cb397ea`) — rastreio pré-parse + drop do `set -u`.

---

## 1. Objetivo e requisitos

O TuxBox abre um container Linux (chroot + mount namespace + pivot_root) dentro
do Android. O requisito da aba `--gui`:

1. Rodar um servidor X **nativo do Android** (termux-x11) em `:0`, com a tela
   aberta via `am start` e `DISPLAY=:0` exportado para dentro do container.
2. **GPU nativa** (kgsl / Turnip / Vulkan) conforme as regras de driver abaixo.
3. **GUI termux-x11 estável e auto-recuperável**: o servidor X deve
   **sobreviver ao fechamento de um cliente GLX** (ex.: `glxgears` + `^C`) e
   manter a conexão aberta para receber novamente a interface do app — o
   watchdog deve **reiniciar o servidor sozinho** quando ele cair.

### Regras de driver (invariantes)

| Contexto | Comportamento |
|---|---|
| GLX/GL no X11 remoto | **NUNCA** override kgsl (crash). Default: llvmpipe (estável). |
| Vulkan | Sempre Turnip via kgsl (`TU_DEBUG=noconform`, ICD lido direto). |
| GLX/EGL acelerado na GPU (opcional) | zink → Turnip, ativado por `touch /root/.gui_gl_zink` |
| Mesa lfdevs (backend kgsl) | holdada no container; `/dev/ion` recriado no boot (fallback 10:62); `/dev/kgsl` 511:0 |
| Instalação do TuxBox | `pm install -r` (atualiza o app preservando /data) |

### Tema do app

Tema escuro navy/roxo, rótulo "Container", Sobre sem licença/imagem.

---

## 2. Arquitetura do X11

```
Android (host)                                  Container (rootfs)
─────────────────────────────────────────────   ─────────────────────────────
app_process (art) + CmdEntryPoint                /tmp/.X11-unix/X0  (symlink)
  termux-x11 server em :0                                   │
  TMPDIR=$ROOTFS/tmp/termux-x11                  ln -sf ../termux-x11/.X11-unix/X0
  socket real: $ROOTFS/tmp/termux-x11/.X11-unix/X0
  (ou xvfb.sock, versões antigas/app)             DISPLAY=:0  (export via X11_ENV)
        │
x11-watchdog (v3.4, setsid, próprio env)         /usr/local/bin/x11-watchdog
  liveness = listener real em /proc/net/unix      var/lock/tuxbox-x11-watchdog/{pid,heartbeat}
```

- O **socket real** do servidor nasce *dentro* do rootfs, em
  `$ROOTFS/tmp/termux-x11/.X11-unix/X0` (ou `xvfb.sock`). O container resolve
  `:0` via symlink `/tmp/.X11-unix/X0` — **sem bind mounts e sem LD_PRELOAD**.
- O CmdEntryPoint **apaga o TMPDIR na saída**; por isso o scratch é isolado em
  `$ROOTFS/tmp/termux-x11`, preservando o `/tmp` do container.
- `XKB_CONFIG_ROOT=/usr/share/X11/xkb` aponta pro xkb do container (evita
  erros de xkbcomp no servidor).
- `XDG_RUNTIME_DIR=/run/user/0` (criado com 700 no boot e recriado pelo
  watchdog no relaunch) — **preveniu o fatal XKB no relaunch** (ver §4).
- O watchdog roda **SEMPRE** em `--gui` (avaliado mesmo com servidor parado),
  com liveness por **listener real** via `/proc/net/unix`.

---

## 3. História das versões — o que quebrava e por quê

### v1 → v3.2: o servidor nunca reaparecia

Sintomas (verificados **no probe do aparelho**, §4):

- Após `^C` no `glxgears`, o servidor **crashava** — e o watchdog não reagia,
  porque sua checagem de liveness era enganável:
  1. `test -S <socket>` (arquivo `-S`): o **socket stale** permanecia em disco
     após o crash (`.X0-lock` e socket com mtime preservado) → "vivo".
  2. `pgrep -f 'CmdEntryPoint'`: o **pid órfão de uma sessão anterior**
     (cmdline reescrito por `--nice-name=termux-x11`) continuava casando →
     "vivo".
- Resultado: watchdog "vivo" e relinkando socket morto (`lA=0`) enquanto nada
  escutava → `glxgears` falhava.

### v3.2: mudanças que ficaram

- `XDG_RUNTIME_DIR=/run/user/0` no launch do servidor (e recriado pelo
  watchdog no relaunch).
- `pkill` dos 2 padrões (`com.termux.x11.CmdEntryPoint` e `termux-x11`) no
  **restore** + `sleep 1` + `rm -rf "$TX11_TMP" "$CANON/X0"` (remove socket
  stale E `.X0-lock` stale — o X server **recusa subir** com `.X0-lock` stale).
- Relaunch com env, relink e `am start`.

### v3.3: ainda enganável

- Liveness = socket-file + pgrep → **discartada** (provadamente enganável:
  wd.log mostrava `ok lA=0 lB=1` com o órfão `5985` casando o pgrep o tempo
  todo, e o servidor morto).

### v3.4: listener real (a correção final)

- Liveness = **listener real** via `/proc/net/unix`: entrada com o caminho do
  socket no estado `01`. Provado localmente: listener vivo → detectado;
  processo morto → some do `/proc/net/unix` **mesmo com arquivo stale
  presente**.
- Singleton lockdir+PID em `var/lock`: valida `/proc/<pid>/cmdline` contém
  `x11-watchdog` (pid reusado entre boots não bloqueia).
- Restore sempre: pkill 2 padrões + `sleep 1` + `rm -rf $TX11_TMP $CANON/X0`
  + relaunch (env v3.2) + relink + `am start`.
- Saúde (heartbeat) a cada 10s: `ok lA=… lB=… pids=…` no
  `/var/log/termux-x11-wd.log` — `pids=` identifica o processo que enganava.

---

## 4. Evidências forenses (probe do aparelho)

- Após `^C`, o servidor **crashou de verdade**: `.X0-lock` e socket em
  `/tmp/termux-x11/.X11-unix/X0` com mtime 13:23 **preservados** (sem limpeza
  do X server).
- Watchdog v3.3 vivo às 13:37 relinkando socket morto = enganado por
  `-S` + pgrep; `nada escutava`.
- `_listen()` validado localmente: listener vivo → detectado; morto → some do
  `/proc/net/unix` com arquivo stale presente.
- dmesg do aparelho: KernelSU/susfs → `umounting
  '/apex/com.android.art/bin/dex2oat{64,32}' for uid: 10275` — dex2oat frágil
  neste kernel.

### Ciclo de validação final (v3.4, 10:46–10:50)

```
10:46:51  start pid=123  pids_casando=6064 5985 6065   ← 5985 = órfão de sessão anterior
10:47:51  sem listener -> restaurando (arqA=0 arqB=1 …) ← arquivo presente, ninguém escuta
10:47:52  launch app_process TMPDIR=… XKB=…
10:47:54  restaurado (wait=3 lA=0 lB=1)                ← novo servidor em ~1.2s
10:47:58+ ok lA=0 lB=1 pids=6881 6769 6882             ← 5985 SUMIU (morto pelo restore)
10:47:54 → 10:50:40+ saudável SEM interrupção          ← servidor nunca mais caiu
```

- `termux-x11.log` **sem nenhum fatal novo** após o relaunch → o
  `XDG_RUNTIME_DIR` segurou o XKB (`Failed to compile keymap` não recorreu).
- Heartbeat fresco (diferença 0s) em `var/lock/tuxbox-x11-watchdog/heartbeat`.

---

## 5. Design final da v3.4 (referência de manutenção)

Arquivo: `app/src/main/assets/bin/tuxbox`

| Trecho | Linhas (aprox.) |
|---|---|
| Bloco X11 (comentário + launch + watchdog + banner) | 428–634 |
| Heredoc do v3.4 `x11-watchdog` | ~501–579 |
| Banner "watchdog v3.4 ativo" | 611–613 |
| `X11_ENV` exportado no exec final | 624 (set) / 707 (uso) |
| Regras GPU (GUI zink/llvmpipe) | 646–660 |
| Opção `-G, --gui` | 945 (usage), 964–967 (parse) |
| `export … GUI` (Phase 1→2) | 1127 |

Pontos-chave do watchdog (heredoc):

- `TX11_TMP` / `CANON` = `$ROOTFS/tmp/termux-x11` e `/tmp/.X11-unix/X0`.
- `_listen()`: varre `/proc/net/unix` por `X0` no estado `01` (listener).
- Restore: `pkill -f 'com.termux.x11.CmdEntryPoint'` + `pkill -f 'termux-x11'`
  + `sleep 1` + `rm -rf "$TX11_TMP" "$CANON/X0"` + relaunch com env v3.2
  (`TMPDIR XKB_CONFIG_ROOT XDG_RUNTIME_DIR CLASSPATH`; `setsid unshare -m` do
  app_process, log em `/var/log/termux-x11.log`) + relink + `am start`.
- Singleton: `var/lock/tuxbox-x11-watchdog/pid` + validação
  `/proc/<pid>/cmdline` contém `x11-watchdog`.
- Saúde a cada 10s. Deadline de restore quando `lA=0` (sem listener).

Logs do container (ler via `/root/sb` → `local/tuxbox/ubuntu-noble/…`):

- `/var/log/termux-x11-wd.log` — ações/health do watchdog (marcador `[wd …]`).
- `/var/log/termux-x11.log` — stderr do servidor (boots bons não escrevem nada).
- `/var/log/tuxbox-x11-boot.log` — `boot HH:MM:SS ok=… wd=…` por sessão.
- `var/lock/tuxbox-x11-watchdog/{pid,heartbeat}` — singleton/health.
- `tmp/termux-x11/wd-state` — estado interno legível.

---

## 6. Receita Droidspaces (reserva p/ v3.5 — NÃO aplicada)

Se o fatal XKB reaparecer no relaunch (trigger novo), a v3.5 entra com a
receita do Droidspaces (`src/android/x11.c`, ravindu644/Droidspaces-OSS) —
**uma variável por round**:

1. `-Xnoimage-dex2oat` no launch do app_process (kernel SUSFS frágil com
   dex2oat; `unsetenv LD_LIBRARY_PATH/LD_PRELOAD`).
2. `Loader` + socket em disco `$PREFIX/tmp/.X11-unix` bind-montado no
   container + env limpo + daemon único (`x11.xpid`), só se a versão do
   termux-x11 do aparelho tiver a classe `Loader` (validar com
   `dumpsys package com.termux.x11 | grep -E 'versionName|versionCode'`).
3. Alinhamento-Droidspaces (socket em disco, roda como UID termux).

Estado: **adido, não aplicado.** O v3.4 não precisou (XKB segurou).

---

## 7. Como testar (fluxo validado)

```
am force-stop sh.tuxbox.root
pm install -r /sdcard/Download/TuxBox-v1.0.0.apk
pkill -f x11-watchdog
pkill -f CmdEntryPoint
```

- Aba NOVA `--gui` → banner "watchdog v3.4 ativo".
- `glxgears` → `^C` → **aguardar ~10s** → `glxgears` reabre (servidor
  reapareceu sozinho).
- Colar: `tail -40 /var/log/termux-x11-wd.log` + `tail -20 /var/log/termux-x11.log`.

Esperado no wd.log ~1s após `^C`:
`sem listener -> restaurando (arqA=… arqB=… pids=…)` + `restaurado (wait=… lA=… lB=1)`;
saúde a cada 10s `ok lA=… lB=… pids=…`.

---

## 8. Estado atual e pendências

### Estado atual
- **v3.4 validada**: servidor reaparece ~2–4s após o cliente fechar; interface
  reconecta. Requisito atendido.
- APK entregue: `/mnt/storage/Download/TuxBox-v1.0.0.apk`
  MD5 `74b77a99c5cbf25b5a8c6e2548d71626` (asset `7dfc8fc0079045fcb6fa8ffe119be7fe`,
  marcador v3.4×2).
- Watchdog vivo (heartbeat 0s), singleton validado por `/proc`, liveness
  inenganável por listener.
- Boots registrados: 09:13:24 (wd=121), 09:13:53 (wd=123), 09:24:45 (wd=120),
  10:04:03 (wd=121), 10:23:31 (wd=122), 10:41:05 (v3.4, pid 123), 10:46:51
  (v3.4, pid 123 — visto que wd.log salta de 10:46:51 direto pro teste final).
  Pids reusados entre boots → validação `/proc/<pid>/cmdline` no singleton.
- pgrep do health mostra 3 pids por linha; o **estável** de cada janela é o
  servidor vivo; o que muda a cada 10s são os pids efêmeros (app_process re-exec).

### Decisão tuxbox-mini
- **Não implementar `--gui` no `tuxbox-mini`.** Verificado: o app só
  *instala* e *chmod* `bin/tuxbox-mini` (`AssetInstaller.java:59`,
  `HomeFragment.java:515`); **nenhum fluxo o executa** (o `tuxbox` principal é
  o único invocado). Asset vestigial, mantido como está (416 linhas, sem X11).
  Caso alguém queira usá-lo num terminal do aparelho, o bloco X11 v3.4 pode ser
  copiado do `tuxbox` (linhas 428–634) para `tuxbox-mini` em posição pré-pívot
  (antes de `mkdir -p "$ROOTFS/old_root"`), junto com `/dev/ion` (paridade de
  GPU), `-G|--gui` no parse/uso e `GUI` no `export` do Phase 2.

### Pendências opcionais
- Nenhuma bloqueante. A v3.5 (Droidspaces) só se o XKB fatal reaparecer em
  outro trigger.
- Hardening futuro (não urgente): matar órfãos `termux-x11` também no boot
  (hoje só no primeiro restore); wrapper de `pkill` diferenciando cmdline real
  vs. reescrito (`--nice-name`).

---

## 9. Resumo da linha do tempo

| Versão | Problema | Resolução |
|---|---|---|
| v1–v3.2 | Servidor não voltava após `^C` | Diagnóstico: crash real + socket stale + órfão + `--nice-name`. Base do restore (pkill/rm/relaunch/env). |
| v3.2 | XKB fatal no relaunch | `XDG_RUNTIME_DIR=/run/user/0` + recriação no relaunch. |
| v3.3 | Watchdog cego (`-S` + pgrep) | (probe provou) → descartados. |
| v3.4 | **Resolvido** | Liveness por listener `/proc/net/unix` + singleton `/proc/<pid>/cmdline` + saúde 10s + restore cíclico. |

> Diagnóstico final em uma linha: **v1–v3.2** (servidor não reaparecia: socket
> stale enganava `-S`; órfão de sessão anterior enganava `pgrep`;
> `--nice-name` reescrevia cmdline) → **v3.4** (liveness por listener real em
> `/proc/net/unix` + observabilidade a cada 10s + `rm -rf` do socket/lock morto
> no restore).

---

## 10. Visual do menu Terminal (pós v3.4 — APK `729a4e3a`)

Reorganização da UI do cabeçalho da aba Terminal (`fragment_terminal.xml`):

- **Card único** (mesma linguagem visual da aba Container: `tux_surface_alt`,
  raio 18dp, stroke `tux_stroke`) agrupando: título "Terminal", seletor de
  rootfs, opções e ações.
- **Checkboxes reestilizadas** — antes cruas (default Material3), agora com o
  estilo `Widget.TuxBox.CheckBox` (`themes.xml`): texto `tux_text`, marca
  violeta quando marcada (`checkbox_tint.xml` → `tux_violet`) e cinza
  (`tux_text_secondary`) quando não. Empilhadas em largura total (antes 2 na
  mesma linha + 2 botões amontoados).
- **Botões em linha própria** (meio a meio): "Nova aba" filled violeta e
  "Fechar aba" outlined — antes espremidos no fim da linha das checkboxes.
- **Spinner temático**: itens (`spinner_env_item.xml`) e dropdown
  (`spinner_env_dropdown.xml`) nas cores do tema (texto `tux_text`, fundo
  `tux_surface_alt`), em vez do `simple_spinner_dropdown_item` cru.
- Status (`tvTermStatus`) em `tux_text_secondary` 13sp; título "Terminal" 18sp
  bold (alinhado aos cards da Home); padding do fragment 16dp.
- Nenhuma mudança de comportamento/IDs — a UI (`TerminalFragment.java`) segue
  igual, só muda a chamada do adapter do spinner (layouts próprios).

### 10.b — Terminal maior + teclado (APK `63106293`)

- **Menu recolhível**: cabeçalho enxuto sempre visível (título "Terminal" +
  spinner de rootfs + chevron `▾/▴`); o painel de opções (checkbox `--net`/
  `--gui` + botões Nova aba/Fechar aba) fica em `optsPanel`, que **recolhe**
  manualmente pelo chevron devolvendo ~180dp pro terminal (o `+` da barra de
  abas continua abrindo sessão com o painel fechado).
- **Teclado não esconde o texto**: além do `adjustResize` no manifest,
  `ViewCompat.setOnApplyWindowInsetsListener` no root observa os IME insets —
  quando o teclado abre o painel **recolhe sozinho** (e volta a expandir ao
  fechar), mantendo o terminal visível com texto ao vivo; cobre ROMs que
  ignoram `adjustResize`.
- Card mais compacto (raio 16dp, padding 12dp, checkbox 36dp, spinner 40dp,
  status 12sp).

### 10.c — Boot silencioso no terminal (APK `e986b0f3`)

- Todo o diagnóstico informativo de boot (`mqueue`, `kgsl: node/ion/dma_heap`,
  `X11: watchdog/termux-x11/GL/GLX`, `GPU: …/llvmpipe`, `kgsl: criado`) saiu do
  pty e passa pela helper `bootinfo()` → **`/var/log/tuxbox-boot.log`** do
  container (pré-pívot escreve em `$ROOTFS/var/log/tuxbox-boot.log`, pós-pívot
  em `/var/log/tuxbox-boot.log`; a helper cria o diretório quando falta).
- O prompt do terminal entra **limpo**; só erros reais continuam no stderr
  (`aviso bind /dev/dri falhou`, `watchdog nao subiu`, `servidor nao subiu`,
  `app nao instalado`).
- Linha de boot do X11 continua indo só pro `tuxbox-x11-boot.log`
  (`tee … >/dev/null`, já não poluía o pty).
- Marcador `watchdog v3.4` preservado — agora no boot log, não no terminal.

APK entregue: `/mnt/storage/Download/TuxBox-v1.0.0.apk`
MD5 `a24570b4eb9558f76c32c88ef8a4f5d3` (build pós 10.c/10.d v3.7; UI do terminal
intacta).

### 10.d — Multi-aba: UM servidor X compartilhado (APK `a24570b4`)

**Bug 1 (multi-aba)**: abrir a 2ª aba com `--gui` dava
`[tuxbox] X11: servidor termux-x11 nao subiu`. Causa: o modelo antigo era
**um servidor por container** — cada aba nasce com `/tmp` tmpfs próprio, então a
2ª aba não via o socket da 1ª, dava `pkill -f CmdEntryPoint` (matava o servidor
da 1ª) e subia outro em cima → briga de servidores/watchdogs e launch quebrado.

**Bug 2 (subida fria, v3.7)**: na 1ª subida pós-instalação até o servidor dar
`ok=0`: a subida COLD do `app_process` levou ~19s (ART/dex2oat lento com
KernelSU/susfs) e o wait do boot era só 10s → timeout precoce → `DISPLAY` não
setado. O log provou que o servidor subia depois (watchdog `ok lA=0 lB=1`).
Correção: wait **40s** (100×0.4s) + **2ª tentativa de launch** dentro do boot
(`_x11_trylaunch`/`_x11_wait` como helpers); restaurar no watchdog também espera
40s (não mata servidor ainda subindo).

**Correção v3.6 — servidor único, compartilhado entre todas as abas**:

- Scratch fixo **fora dos rootfs**: `XS=/data/local/tuxbox/x11`, com
  `tmp/` (TMPDIR real do servidor), `run/` (XDG_RUNTIME_DIR, chmod 700),
  `lock` (flock de launch/restore), `xkb` (caminho XKB registrado na 1ª subida),
  `termux-x11.log` (log único do servidor).
- Launch protegido por `flock 9` no `$XS/lock` + re-check de **listener real**
  (`/proc/net/unix`, estado `01`): só a 1ª aba sobe o `app_process`; 2ª/3ª abas
  pegam o lock, veem o servidor vivo e **não matam nem relaunçam nada**.
- Cada container expõe o socket no próprio `/tmp` via **bind mount** (pré-pívot):
  `$XS/tmp/.X11-unix` → `/tmp/.X11-unix` e `$XS/tmp` → `/tmp/termux-x11`
  (compat com variantes xvfb.sock). O `X0` aparece direto; novas abas e
  restores aparecem sozinhos no mesmo diretório.
- Watchdog **v3.7 versionado** (`ver` no lockdir `/var/lock/tuxbox-x11-watchdog`):
  o boot mata **só** o watchdog antigo (ex. v3.4 de APK anterior ainda em
  execução) quando a versão diverge — evita ele restaurar TMPDIR antigo e
  brigar com o compartilhado. Monitora os sockets compartilhados; o restore é
  **serializado por flock + re-check**: vários watchdogs podem detectar a
  queda juntos, mas só o primeiro relaunça; os demais desistem ao ver o socket
  de volta.
- Restore limpa **apenas arquivos** de socket/`.X0-lock`
  (`rm -f`, nunca `rm -rf` do diretório) — os bind mounts de outras abas
  dependem do inode do diretório compartilhado continuar vivo.

**Teste validado no fluxo**: 1ª aba `--gui` sobe o servidor; abrir 2ª aba `--gui`
conecta no mesmo servidor (sem "nao subiu"); `^C` no cliente GLX de qualquer aba
derruba o listener → watchdogs de todos os containers restauram um único
servidor em ~2-4s e os binds o expõem em todas as abas.

**Arquivos**: bloco X11 (~linha 445–665) com `flock`+binds+helpers
`_x11_trylaunch`/`_x11_wait`; heredoc do watchdog v3.7 (~535–625;
`echo 3.7 > ver`, restore com flock e wait 40s).

### 10.e — Instrumentação de diagnóstico (APK `96fe4a4c`, v3.8)

Investigações conclusivas: o servidor compartilhado SOBE e fica escutando (wd
`ok lA=0 lB=1` estável) mas o boot declarava `ok=0` mesmo com o servidor no ar.
O superblock (sb) NÃO vê tmpfs (RAM) — `/tmp` do container é invisível por lá,
então o resultado dos binds não é verificável de fora. Para obter a verdade de
campo, o v3.8 instrumenta o boot (tudo indo só pro `tuxbox-boot.log`, terminal
limpo):

- Wait loga `X11: servidor visto no wait (compartilhado ok)` ou
  `X11: wait1 (40s) esgotou…` — distingue falha de DETECÇÃO (wait) de falha de
  PUBLICAÇÃO (bind).
- Binds trocados de `--bind` para `-o bind` (padrão do resto do script) e
  verificados: `X11: bind OK — /tmp/.X11-unix/X0 visivel no container` ou
  `X11: AVISO bind FALHOU — /tmp do container sem X0`.
- Antes do ok-check: `X11: DIAG: sem listener compartilhado pos-waits; -S
  X0=…; proc=N linha(s)` — mostra se o boot enxerga o listener no
  `/proc/net/unix` (N = contagem de linhas com `.X11-unix`).

APK entregue: `/mnt/storage/Download/TuxBox-v1.0.0.apk`
MD5 `96fe4a4c17f952bc01898d49d76d7da2` (build v3.8; asset `tuxbox` == fonte
`27344ce8`).

### 10.f — Causa raiz achada; v3.9 troca bind por symlink (APK `e6558d0b`)

O v3.8 provou em campo (`tuxbox-boot.log`):

```
[tuxbox] X11: servidor visto no wait (compartilhado ok)      <- DETECÇÃO ok
[tuxbox] X11: AVISO bind FALHOU — /tmp do container sem X0   <- PUBLICAÇÃO quebrada
```

**Causa raiz**: bind mounts de /data NÃO funcionam neste SELinux (o próprio
script já tratava outros binds de /data — `/mnt/storage`, `rt_tables`,
`/dev/dri` — como best-effort com `|| true`; o `setenforce 0` é barrado e os
binds são negados). O servidor SEMPRE subiu; o problema era só o `/tmp` do
container ficar sem o socket.

**v3.9**: scratch do servidor movido para DENTRO do rootfs,
`$ROOTFS/var/lib/tuxbox-x11` (= `/var/lib/tuxbox-x11` no container) —
disk-backed, então TODAS as abas desse rootfs compartilham o MESMO socket sem
nenhum mount. Cada aba expõe via **symlink** (mecanismo comprovado do v3.4):
`/tmp/.X11-unix/X0 -> /var/lib/tuxbox-x11/.X11-unix/X0`. Binds removidos do
bloco; lock global (`/data/local/tuxbox/x11/lock`) mantido para serializar
launch/restore; watchdog v3.8 (só comentários/versão — comportamento
env-driven idêntico; boots com ver divergente regravam). Limitação documentada:
compartilhamento é por rootfs (abas do MESMO env); multi-env ainda brigaria pelo
display.

APK entregue: `/mnt/storage/Download/TuxBox-v1.0.0.apk`
MD5 `e6558d0b250348d6b6ad793f9f49f9cd` (build v3.9; UI do terminal intacta;
asset `tuxbox` == fonte `0947b567`).

**v3.9b (APK `8c17cb22`)**: campo mostrou `AVISO symlink FALHOU (_SOCK=var/lib/
tuxbox-x11/.X11-unix/X0)` — ERRADA a validacão, não o mecanismo: pre-pivot o
alvo absoluto `/var/lib/...` não resolve no HOST (só existe dentro do
container), então `[ -S "$ROOTFS/tmp/.X11-unix/X0" ]` falhava sempre. O v3.9b
valida o par `socket real em $_SRV` + `symlink presente ([ -L ])` sem resolver o
alvo — mesmo padrão de pegadinha de contexto de path do sb (tmpfs vs disco,
host vs container).
**v3.9c (APK `5fe3dba7`)**: aba 1 OK, mas aba 2 com `--gui` morria sem abrir o
terminal (sem `--gui` abria). O `termux-x11.log` revelou um relaunch do servidor
com `mkdir(.X11-unix) errno=2` + falha fatal de XKB — o CmdEntryPoint apaga o
TMPDIR na saída. Hardenings: `flock -n 9` (nunca bloqueia — hang de lock era o
suspeito nº1 do "nada carrega"), `9>&-` no servidor lançado (não segura o lock),
`mkdir -p` dentro do trylaunch/restore (cobre TMPDIR apagado), timestamps em
todas as bootinfo, marcador `X11: bloco --gui iniciado` e trap ERR com `LINENO`
(`X11-DIE ... LINHA=N rc=$?`) para apontar a linha exata se algo ainda morrer.

**v3.9d (APK `3ab2d64b`)**: usuário instalou o v3.9c e passou a receber "processo
finalizado" ao abrir aba. Logs (agora com timestamp) mostraram: o servidor X
estava VIVO (`X0` socket + lock pid 99) e as abas 14:13:15/17 (script anterior)
deram ok=1, mas os boots v3.9c (14:13:22/27/49) morriam logo após `kgsl: /dev/ion
criado` — **antes** do bloco X11 (marcador `bloco --gui iniciado` = 0), numa
região com binds/remounts/escrita em /proc/sys **não guardados** que estouram o
`set -e` silenciosamente sob SELinux enforcing (a morte ainda não tinha trap —
o trap v3.9c só cobria o bloco X11). Também descoberto no `wd.log`: dois `start
pid=134` às 14:13:15/16 e depois **zero heartbeats** — o watchdog da era v3.9b
morreu na hora (sem matar o servidor). v3.9d: (1) trap ERR GLOBAL no início da
fase 2 (`P2-DIE LINHA=N rc=$?`) para qualquer abort pre-pivot; (2) guardas
`|| warn`/`|| true` nas linhas frágeis: bind+remount devpts, bind console/tty,
`mask_path`/`readonly_path`, escrita `ping_group_range`, symlinks de /dev —
falha vira aviso e o boot continua até o bloco X11 (onde roda `setenforce 0`);
(3) marcador do bloco mantido. Próximo boot: se algo ainda morrer, o log aponta
a linha exata; o watchdog v3.9 morto é reescrito/respawnado pelo próprio boot.

**v3.9e (APK `30fad75f`)**: usuário confirmou que v3.9b tinha aba 1 100% ok e o
problema original era a 2ª aba; v3.9c/v3.9d regrediram até a aba 1 ("processo
finalizado" com terminal VAZIO e ZERO entradas novas no boot log — nem a 1ª
bootinfo da fase 2). Como as mortes de v3.9c/d acontecem em código idêntico ao
v3.9b (fase 1) e o loop de heartbeat do watchdog parou de repente às 14:13:12
+ SystemUI deu ANR com ~390MB RSS, o culpado mais provável é **LMK/pressão de
memória** (o próprio app já tentava se proteger com `echo -1000 >
/proc/self/oom_score_adj` no joinCmd). v3.9e instrumenta o LAUNCHER, que era o
ponto cego:
1. **ptyd**: loga em `/data/local/tuxbox/ptyd.log` a linha de comando, falha de
   exec (errno) e o status real do filho (127=exec-fail, 137=SIGKILL/LMK...); e
   passa a RETORNAR o status do filho (`return status` — antes sempre 0), então
   o exit code do processo `su -c ptyd` fica visível.
2. **app**: status mostra `processo finalizado (exit=N)`.
3. **tuxbox**: rastreio da FASE 1 — trap `P1-DIE LINHA=...`, bootinfo
   `fase1: args ok` + `fase1: exec unshare`; reforço `oom_score_adj -1000` no
   início da fase 1 e da fase 2.
Teste recomendado: REBOOTAR o aparelho antes (limpa servidor X órfão de 14:13 +
SystemUI travado) e só então `pm install -r` + abrir aba 1 → aba 2. Os logs
(pty-d.log + boot log + X11 logs) dizem exatamente onde/porquê morre.

**v3.9f (APK `5cb397ea`)**: resultado do teste v3.9e — o ptyd.log (a
instrumentação nova) provou: **exec OK (sem EXEC-FAIL) e CHILD EXITED status=1**
nas 3 tentativas (2 com `--gui`, 1 sem), mas **ZERO entradas novas no boot log** —
nem o `fase1: args ok` (linha ~1090) nem o trap P1-DIE. Morte **anterior ao
parse** (`$ROOTFS`) e **silenciosa**, apesar do `set -eu`. Tombstones (±4)
revelaram o agravante ambiental: **`libksud.so` (daemon do KernelSU) crashando**
+ ANRs de SystemUI e do Termux → `su` do aparelho instável = "o app quebrou
todo" é degradação do device, não da build. A fonte-era v3.9b→v3.9e é idêntica
na fase 1 (a morte não é regressão de script). Correção v3.9f:
1. **Rastreio na 1ª linha útil do script** (antes do `set -e`): se o child morre
   pré-parse de novo, `[tuxbox-early pid=$$ args=…]` aparece (a) no terminal
   (stderr do pty) e (b) em `/data/local/tuxbox/boot-early.log` (uid/kernel/sh
   incluídos) — funciona mesmo com rootfs inválida (`$*` cru).
2. **`set -eu` → `set -e`**: desliga o `set -u` (unbound-var tem abort silencioso
   em mksh) — suspeito nº 1 de death pré-parse sem stderr.
3. Mantém tudo da v3.9e (ptyd.log, exit code visível, P1/P2-DIE, `jacke -1000`).
Teste: **REBOOTAR** o aparelho → `pm install -r` → aba 1 `--gui` → reportar o
texto exato do terminal (deve começar com `[tuxbox-early …]` se o script rodar)
+ status; ler `/data/local/tuxbox/boot-early.log` + `ptyd.log` + boot log.

### 10.g — CAUSA RAIZ encontrada + v3.10 (APK `e6de7067`): volta ao comportamento da v3.9b

**`boot-early.log` (v3.9f) provou onde a morte é**: o marcador da 1ª linha
**dispara** (script roda, `uid=0`, args corretos) e o processo morre **logo
depois**, ainda antes do parse. Cruzando com o comportamento por build:

| Build | Linha nova (não-guardada) | Sintoma observado |
|---|---|---|
| v3.9b | — (sem traps) | **aba 1 OK** (`ok=1 wd=134`) |
| v3.9c | `trap … ERR` no bloco X11 | morre pré-X11-bloco |
| v3.9d | `trap … ERR` global na **fase 2** (P2-DIE) | **zero** linhas no boot log (nem a 1ª bootinfo da fase 2) |
| v3.9e/f | `trap … ERR` na **fase 1** (P1-DIE) | `exit=1`, terminal vazio, zero log |

**Causa raiz**: o `/system/bin/sh` do Android é o **mksh**, que **não implementa
a condição `ERR` do trap** (só sinais e `EXIT`/`0`). Logo `trap '…' ERR` devolve
erro — e, por estar sob `set -e`, **encerra o script em silêncio com exit=1**,
antes de qualquer `bootinfo`. Era a **própria instrumentação de diagnóstico**
(v3.9c/d/e) que matava o processo que ela queria medir. Isso explica todas as
regressões, o terminal vazio e o exit=1.

**v3.10** (volta ao comportamento da v3.9b, mantendo o que era defensivo):
1. Os **3 `trap` sem guarda → guardados** (`… ERR 2>/dev/null || true`,
   inclusive o `EXIT` do lock X11): onde o shell tem ERR o diagnóstico
   P1/P2-DIE continua valendo; no mksh são ignorados em vez de matar o boot.
2. **Mantidos** do v3.9c/d: `flock -n`, `mkdir -p` no trylaunch/restore,
   `9>&-`, guardas `|| warn/|| true` do pre-pivô (que consertam a morte da
   v3.9c), proteção OOM, marcador `X11: bloco --gui iniciado`.
3. **Mantidos** do v3.9e/f: `ptyd.log` + exit code no app, marcador
   `tuxbox-early` (+ `boot-early.log`), `P1-DIE`/`P2-DIE` agora guardados.
4. **App — o "quebrou o app / gira na lista de containers"**:
   `RootShell.runAsync` ganhou **timeout de 12s** (libsu 5.2.2 não tem timeout
   nativo) com pool elástico — um `su` pendurado não segura mais as 2 threads
   do pool nem trava a UI; `refreshHero()` trocou o `find … -maxdepth 3` (que
   percorria o `/usr/lib` inteiro a cada abertura) por **globs limitados**; e o
   callback passou a checar `isAdded()` antes de `requireActivity()` — sem isso
   o callback tardio estourava `IllegalStateException` (app quebrando).

### 10.h — v3.11 (APK `85c2ea7e`): CAUSA RAIZ DO `exit=1` + logs fora da base

**1) `set -E` era o assassino (não era o `trap`).** Reproduzido com mksh no host
(mesmo shell do `/system/bin/sh` do Android):

```console
$ mksh -c 'set -E 2>/dev/null || true; echo "chegou depois"'
(nada — nem imprime, rc=1)
$ mksh -c 'set -E || true; echo "chegou depois"'
E: mksh: set: -E: unknown option          rc=1
```

`-E` (errtrace) é **opção desconhecida no mksh**: o shell **aborta imediatamente
com rc=1 e ignora o `|| true`** — a linha nunca chega ao `true`. O `set -E` foi
introduzido na v3.9d (fase 2) e na v3.9e (fase 1), exatamente onde os logs
paravam de aparecer. O `trap … ERR`, esse, **funciona** no mksh (testado: dispara
com `set -e`, e a versão guardada é inofensiva) — foi removido só o `set -E`.
Confirmado rodando o script real com `mksh -e -x`: antes morria em
`TB_BOOT_LOG=…` → `set -E` (exit 1, zero log); agora segue até
`fase1: args ok` → `kgsl: criado` → `fase1: exec unshare`, com o `P1-DIE`
funcionando como diagnóstico.

**2) Os logs estavam DENTRO da base dos containers.** `ptyd.log` e
`boot-early.log` foram criados em `/data/local/tuxbox/`, que é a pasta que o app
lista (`ls -A -1`) para mostrar os containers — então os dois arquivos
apareceram **como containers** na lista (e o hero pegava `boot-early.log`, o
primeiro em ordem alfabética, como "o container"). Correções:
- logs movidos para **`/data/local/tuxbox-logs/`** (ptyd cria o dir com `mkdir`),
  com limpeza única dos arquivos antigos na base;
- **`TuxPaths.LIST_ROOTFS_CMD`**: a lista de containers (aba Container, spinner
  do Terminal e hero da Home) passou a listar **só diretórios com `etc`/`usr`/
  `bin`** — nenhum arquivo solto na base volta a virar container.

### 10.i — v3.12 (APK `9ea22fa8`): lock do X11 apontava pra um diretório morto

Campo (v3.11): sem `--gui` o container abre normal (`root@ubuntu-noble:~#`); com
`--gui` morre em

```
/data/user/0/sh.tuxbox.root/files/bin/tuxbox[803]: can't create
/data/local/tuxbox/x11/lock: No such file or directory
```

**Causa**: a v3.9 moveu o scratch do servidor para dentro do rootfs
(`$ROOTFS/var/lib/tuxbox-x11`), mas o **lock, o xkb e o log do servidor**
continuaram apontando para `/data/local/tuxbox/x11` — o scratch da v3.6/v3.7,
que não existe mais. O `9>"$_XS_LOCK"` falhava com ENOENT; como a mensagem da
redireção sai antes do `2>/dev/null`, ela aparecia no terminal, e o servidor
nunca subia.

**Correção**: estado persistente em `$ROOTFS/var/lib/tuxbox-x11-state`
(`lock`, `xkb`, `termux-x11.log`) — disk-backed, portanto compartilhado por todas
as abas do mesmo rootfs (é o que o lock precisa), e **fora do TMPDIR do
servidor** (`$_SRV`), que o `app_process` apaga na saída — se o lock ficasse
dentro dele, sumiria a cada queda do X. Além disso, o `2>/dev/null` foi movido
para **antes** do `9>`, para que uma futura falha de criação do lock não suje
o terminal.

Verificado no host com mksh + `pm`/`app_process` falsos: o bloco X11 percorre
launch/wait/symlink/watchdog até o fim, com `lock`/`xkb`/`termux-x11.log`
criados no dir novo (antes abortava no `set -e`).

### 10.j — v3.12 VALIDADA em campo (servidor único + multi-aba)

Teste do usuário: **funcionou — duas, três abas com X11**, todas conectando no
mesmo servidor. Evidência no aparelho:

- `tuxbox-x11-boot.log`: `ok=1` nos 3 boots (15:37:51, 15:38:34, 15:42:46) —
  uma entrada por aba, todas com `ok=1`.
- `termux-x11-wd.log`: heartbeats de 10s em `ok lA=0 lB=1` sem interrupção.
- `termux-x11.log`: uma tentativa de 2º launch foi recusada pelo próprio X
  (`server already running` / `Cannot establish any listening sockets`) — ou
  seja, **um único servidor**, com a proteção nativa do X contra duplicata.
- `ptyd.log`: nenhum `EXITED status=1` desde a v3.12; só `SIGNALED sig=9`
  (137) — que é o app **fechando** a aba, não o script morrendo.

Arquitetura final validada: scratch do servidor em
`$ROOTFS/var/lib/tuxbox-x11` (socket, compartilhado por rootfs), estado
persistente em `$ROOTFS/var/lib/tuxbox-x11-state` (lock/xkb/log, fora do
TMPDIR que o X apaga), exposição via symlink `/tmp/.X11-unix/X0`, watchdog
v3.9 com restore serializado por flock.

### 10.k — v3.13 (APK `a476e59c`): notificação persistente (sessão em 2º plano)

A notificação do foreground service (o que segura a sessão com o app em
background) **não aparecia**. Causa: `POST_NOTIFICATIONS` era pedida no
**lançamento** do app, sem contexto; negada uma vez, o Android não pede mais e
o app não tinha como avisar — a sessão ficava vulnerável em silêncio.

- pedido da permissão movido para o Terminal, no momento em que a sessão começa;
- `TerminalService.notificationsVisible()` detecta permissão/canal bloqueados e
  mostra um diálogo com botão que abre as configurações de notificação do app
  (`Toast.setAction` é `@hide` no framework, por isso o diálogo);
- manifest: `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (exigida no Android 14+ para FGS
  `specialUse`; sem ela o `startForeground` é recusado);
- `startForegroundService`/`startForeground` em try-catch — notificação nunca
  derruba a sessão;
- `START_NOT_STICKY` → **`START_STICKY`**: serviço é reiniciado se o LMK matar.

### 10.l — v3.14 (APK `c92763cf`): gerenciador de janelas automático

Antes era preciso digitar `openbox --sm-disable &` em **cada** aba. Agora o WM
sobe junto com o X, no mesmo namespace/ciclo de vida (se a aba que subiu o X
fechar, o WM morre junto e a próxima aba o religa).

| Estado | Comportamento |
|---|---|
| `/root/.wm` com comando (`i3`, `fluxbox`, …) | usa esse WM |
| `/root/.wm` vazio ou `none` | não sobe WM |
| sem `/root/.wm` | `openbox --sm-disable` se estiver instalado |

Implementação: `setsid chroot "$ROOTFS" /usr/bin/env -i DISPLAY=:0 … /usr/bin/openbox`
— o `chroot` dá as libs do container e o `setsid` desliga do shell da aba.
Log do WM em `/var/log/x11-wm.log`; status no `tuxbox-boot.log`
(`X11: gerenciador de janelas iniciado: … (pid N)` / `já ativo` / `ausente`).

Dois bugs encontrados testando o bloco com mksh + socket X simulado no host:

1. **singleton falso positivo** — casava `/openbox` em qualquer lugar da
   cmdline (até no `bash -c` do teste), declarando WM ativo indevidamente.
   Corrigido para casar pelo **argv[0]** (o binário do WM).
2. **caminho relativo no `chroot`** — `usr/bin/env` relativo falhava com ENOENT
   (depois do chroot o CWD não existe mais). Corrigido para caminho **absoluto**
   dentro do chroot.

### 10.m — v3.16 (APK `66c21f64`): container NOVO (Debian) — teste do zero

Teste do usuário: baixou um segundo container (`debian-trixie`), instalou Mesa e
a aba `--gui` **trava e morre com `exit=1`**, enquanto `ubuntu-noble` funciona.
O `P2-DIE` apontou `LINHA=614` e o `X11: wait1 (40s) esgotou sem listener`.
**Dois defeitos que só aparecem em imagem nova:**

1. **`/var/lock` é symlink** — em Debian/usrmerge, `/var/lock → /run/lock`.
   Path **absoluto**, que pré-pivô resolve no **HOST** → ENOENT/EEXIST. Havia
   **dois** `mkdir -p` não guardados nele: o da lista do bloco X11 (matava o
   boot antes do X) e o do lockdir do watchdog (era a linha 614 do `exit=1`).
   Diagnóstico de campo: `ls var/lock` pelo `sb` dá ENOENT, mas o diretório-pai
   lista como `d` (symlink pendente) — enquanto no Ubuntu é diretório real.
   → lockdir do watchdog movido para `$_STD/watchdog` (dir de estado que criamos
   nós), `mkdir` da lista sem `/var/lock` e com `|| true`, e o bloco do watchdog
   agora **degrada** (aviso + segue sem auto-recuperação) em vez de matar o boot.
2. **Imagem mínima sem `xkeyboard-config`** — sem `/usr/share/X11/xkb` o X morre
   com `Failed to activate virtual core keyboard` (log do servidor). Agora:
   usa o xkb do próprio rootfs; se faltar, **reaproveita o path de um container
   que tem** (cache global em `/data/local/tuxbox-x11/xkb-path`, fora da base de
   containers, com validação de existência); senão avisa com o comando de
   instalação (`apt install -y xkb-data`) e segue.
3. **Corrida do relaunch** — o `CmdEntryPoint` **apaga o TMPDIR ao sair**; o
   `mkdir` do trylaunch rodava antes do servidor morto terminar de limpar, e o
   novo caía em `mkdir ENOENT` / `Cannot establish any listening sockets`.
   → `sleep 1` entre o `pkill` e o `mkdir` (o v3.2 já fazia isso; o refactor
   v3.6+ tinha perdido).

Nota de arquitetura (já documentada, reconfirmada aqui): o display `:0` é único
no aparelho, então **dois rootfs com `--gui` disputam o mesmo servidor X** — o
`pkill` do trylaunch é global. Abas do **mesmo** rootfs compartilham; rootfs
diferentes alternam o dono do display a cada boot.

### 10.n — v3.17 (APK `8d69d802`): display :0 com dono estrangeiro — falha rápida

Campo (v3.16): o Debian deixou de morrer (`exit=1` resolvido), mas avisou
`X11: servidor termux-x11 nao subiu` depois de ~64s. Os logs provaram a causa:

```
17:59:06  Debian: "AVISO sem xkeyboard-config neste container"
17:59:07  pkill → matou o servidor do Ubuntu → lança o do Debian
18:00:40  watchdog do Ubuntu: "wd start pid=145 tmp=…/ubuntu-noble/…"  ← RECLAIM
18:00:56  boot do Ubuntu: ok=1
```

Socket do Ubuntu: presente. Socket do Debian: ausente. **Não é bug: o `:0` é
único na tela.** O watchdog do outro container sempre recupera o display, e os
dois se atropelam — o perdedor espera 40s+24s e só então falha com mensagem
genérica.

**v3.17** troca a espera por **detecção + mensagem útil**:
- antes de lançar, varre `/proc/net/unix` por um listener X de **outro**
  container (exclui o próprio `$_SRV`); se achar, **não mata ninguém**, não
  espera 64s, e avisa no terminal qual é o dono + como resolver;
- escape hatch: `/root/.x11_takeover` assume o display (comportamento antigo,
  útil para trocar de container de propósito);
- o cache global de xkb (`/data/local/tuxbox-x11/xkb-path`) já é gravado pelo
  primeiro container que tem xkb, então o Debian passa a funcionar sozinho
  assim que o Ubuntu rodar uma vez com a v3.16+ — mas, pelo display único, só
  um container por vez.

### 10.o — v3.18 (APK `8e099be5`): GL na GPU — modo `kgsl` + leitura no container

Campo: "o Turnip não funcionou, glmark2 subiu com llvmpipe". **Diagnóstico:**

1. **`glmark2` é OpenGL, não Vulkan.** O padrão do projeto é GL/GLX em
   **llvmpipe** (software) e **Vulkan na GPU via Turnip/kgsl** — por isso o
   llvmpipe no glmark2 é o resultado **esperado**, não defeito do Turnip.
2. **O Turnip está instalado.** O `mesa-for-android-container` (o pacote que o
   app instala em "Instalar Mesa GPU") **traz o Turnip junto**: no
   `mesa-26.3.0-devel-20260824` há `libvulkan_freedreno.so` (**17.420.912
   bytes** — exatamente o tamanho presente no container),
   `usr/share/vulkan/icd.d/freedreno_icd.aarch64.json` (api 1.4.359) e
   `usr/share/drirc.d/00-turnip-defaults.conf`. O release `turnip-*` (3,4 MB) é
   só a variante "Turnip sozinho" (lib de 17.340.312 bytes) — redundante.
3. **Bug de usabilidade**: o script lia `/root/.gui_gl_zink` **no host** (o
   bloco X roda antes do `pivot_root`), então o `touch` feito no terminal do
   container nunca ativava nada.

**v3.18**:
- novo modo **`.gui_gl_kgsl`** → `MESA_LOADER_DRIVER_OVERRIDE=kgsl`, o uso
  oficial do `mesa-for-android-container` (backend KGSL do Freedreno:
  OpenGL/GLES **e** Vulkan na GPU, "eliminando a necessidade de Zink");
- os dois marcadores (`.gui_gl_kgsl`, `.gui_gl_zink`) passam a ser lidos **no
  host E dentro do container** (`$ROOTFS/root/…`);
- o resumo de GPU no boot log passa a dizer o modo GL ativo explicitamente.

Como validar:
```bash
vulkaninfo --summary               # deve listar Turnip/MESA_TURNIP + Adreno
touch /root/.gui_gl_kgsl           # GL na GPU (reabra a aba --gui: lido no boot)
glxinfo -B | grep -i renderer      # deve mostrar kgsl/zink, não llvmpipe
```

### 10.p — v3.19 (APK `e8e3b780`): o APT rouba o Turnip (Debian) — dpkg-divert

Campo: no `debian-trixie`, `vulkaninfo --summary` mostrava **lavapipe/llvmpipe**
em vez de Turnip. Comparação campo a campo dos dois containers:

| arquivo | Ubuntu (funciona) | Debian (não) |
|---|---|---|
| `libvulkan_freedreno.so` (Turnip) | **17.420.912** | **10.632.136** ← da distro |
| `libvulkan_lvp.so` (lavapipe) | 11.687.144 | 10.375.464 |
| `libgallium-26.3.0-devel.so` (custom) | 28.308.768 ✅ | 28.370.320 ✅ |
| `dri/kgsl_dri.so` → `libdril_dri.so` | ✅ | ✅ |
| ICD `freedreno_icd.aarch64.json` | ✅ idêntico | ✅ idêntico |
| `00-turnip-defaults.conf` | ✅ | ✅ |

O tarball `…_debian_trixie` traz `libvulkan_freedreno.so` com **17.483.184**;
o container tinha **10.632.136** = `mesa-vulkan-drivers` da distro (Deb13
25.0.7). O `libgallium-26.3.0-devel.so` batia **exatamente** com o tarball →
a extração do app foi completa; **um `apt install/upgrade` posterior sobrescreveu
o Turnip custom** (o apt é dono daquele caminho). Resultado: o loader Vulkan
carrega o ICD, o driver init falha/só oferece lavapipe → **llvmpipe**.

**Correção imediata**: reinstalar pelo app ("Instalar Mesa GPU" na aba Home) —
a extração sobrescreve a lib da distro e o `ldconfig` roda em seguida.

**v3.19 — proteção durável no APK**: antes de extrair, o app roda
`dpkg-divert --add --rename --divert` em
`/usr/lib/<triplet>/libvulkan_freedreno.so` e
`/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json` (multiarch detectado por
`dpkg-architecture -qDEB_HOST_MULTIARCH`), tirando esses caminhos do domínio do
apt. Também passa a logar `TURNIP_LIB=<bytes>` na aba Home (distro ≈ 10,5 MB,
custom/KGSL ≈ 17,4 MB) para dar pra ver de imediato se o driver certo está no
lugar.
