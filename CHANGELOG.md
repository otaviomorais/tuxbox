# Changelog

Todas as mudanças relevantes do TuxBox serão registradas aqui. O formato é
baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e o
projeto segue [versionamento semântico](https://semver.org/lang/pt-BR/).

## [1.0.0-v3.47] — 2026-09-25

### Primeira release pública

- app Android para rootfs Linux com namespaces, bind mounts e
  `pivot_root/chroot`, sem `systemd-nspawn`;
- instalação e importação de rootfs do linuxcontainers.org;
- gerenciador de containers, reparo de permissões e remoção segura;
- terminal multi-aba com ponte PTY nativa e foreground service;
- opções de namespace de rede e Termux:X11;
- servidor X11 compartilhado com watchdog e recuperação automática;
- instalação de XKB, clientes X11 e gerenciador de janelas;
- diagnóstico de GPU e instalação Mesa/Turnip;
- diagnóstico real das capacidades do kernel/root;
- logs de boot, X11, watchdog, GPU e PTY;
- código de terminal adaptado do Termux sob GPL-3.0-only;
- documentação, avisos de segurança e build reproduzível com Gradle 8.9.

### Segurança

- O modo GUI pode tentar `setenforce 0` em ROMs compatíveis.
- O helper Arch usa `TrustAll` no `pacman.conf` para contornar keyrings
  antigas; isso não restringe a confiança exclusivamente à keyring oficial.
- O instalador Mesa pode extrair assets de terceiro como root e não fixa um
  hash/assinatura independente.
- Consulte [SECURITY.md](SECURITY.md) antes de usar.

[1.0.0-v3.47]: https://github.com/otaviomorais/tuxbox/releases/tag/v1.0.0-v3.47
