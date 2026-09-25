# Segurança

## Antes de usar

O TuxBox é software experimental com acesso **root**. Ele cria namespaces,
montagens, device nodes, regras de rede e, no modo gráfico, pode interacts
com SELinux e com bibliotecas da distro. Faça backup, use um aparelho que
você possa recuperar e leia esta página antes de instalar.

O repositório e as releases **não incluem jogos, ROMs, saves, keys de licença,
imagens rootfs ou outros arquivos de conteúdo**. As imagens Linux são
baixadas em tempo de execução pelo usuário e ficam no armazenamento local.

## Compromissos conhecidos

- **Mesa GPU:** o instalador pode baixar um pacote de desenvolvimento do
  [mesa-for-android-container](https://github.com/lfdevs/mesa-for-android-container)
  e extraí-lo dentro do container como root. O processo não fixa nem verifica
  um hash/assinatura independente do asset. Instale apenas se você confiar
  nesse projeto; de outro modo, não use o botão de Mesa.
- **Arch Linux:** para compatibilidade com kernels Android, o helper de
  `pacman` ativa `DisableSandbox` e `SigLevel = Required DatabaseOptional
  TrustAll`, com `LocalFileSigLevel = Optional`. `TrustAll` exige uma
  assinatura válida, mas não limita a confiança apenas à keyring oficial.
- **Downloader legado:** o helper standalone `getroot` contém uma opção
  `--no-check-certificate` e senha root padrão `1`. O fluxo atual da aba
  **Baixar/Importar** não executa esse script; ele existe como asset legado.
- **SELinux:** o modo `--gui` tenta `setenforce 0` best effort para permitir
  o caminho de sockets/binds do Termux:X11. Em ROMs que bloqueiam a operação,
  a GUI pode ficar indisponível.
- **Bibliotecas da distro:** a instalação Mesa em Debian/Ubuntu pode usar
  `dpkg-divert` para impedir que o gerenciador de pacotes sobrescreva o
  Turnip customizado.
- **Rede:** `--net` cria bridge, namespaces de rede, NAT e regras de firewall
  no aparelho. A rede permanece desligada por padrão.
- **Downloads rootfs:** a aba principal usa HTTPS e o índice público do
  linuxcontainers.org, mas as imagens e seus mantenedores têm seus próprios
  termos e riscos. Confirme distro, release e arquitetura antes de extrair.

Essas escolhas são necessárias para compatibilidade com containers Android,
mas ampliam a superfície de ataque. Não existe garantia de isolamento contra
um kernel comprometido ou um exploit no sistema host.

## Relato privado

Não abra uma issue pública para uma vulnerabilidade explorável. Use
[Security Advisories do repositório](https://github.com/otaviomorais/tuxbox/security/advisories/new)
e inclua:

- versão do TuxBox e do Android;
- modelo/ROM e kernel;
- passos de reprodução;
- impacto e PoC mínimo;
- logs já sanitizados.

O projeto não oferece garantia formal de resposta ou suporte de segurança.
