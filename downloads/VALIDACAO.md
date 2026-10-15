# TuxBox 1.1.0-dev — entrega de desenvolvimento

Projeto: https://github.com/otaviomorais/tuxbox
Base: 9e04863. Branch local: codex/tuxbox-management-ui.

## O que mudou

Interface com cards de estado e sessões, ações iniciar/parar e menu de gestão.
Processos e buffers pertencem à aplicação, com encerramento dos descendentes,
códigos de saída reais e views desacopladas. PTY tolera pacotes fragmentados e
backpressure, usando espera por eventos. Os helpers foram reconstruídos para ARM64,
ARMv7 e x86_64.

Perfis CPU/RAM, compartilhamentos somente leitura por padrão, portas e sessões
OverlayFS descartáveis são configuráveis por ambiente. Backup, clone e instalação
usam locks recuperáveis, staging e proteção do rootfs ativo. Modos/ownership dos
arquivos são preservados. Templates cobrem desenvolvimento, XFCE e SSH/Nginx.

Downloads novos recebem verificação SHA-256 e validação de tar. A GUI preserva o
SELinux global. Pacman mantém assinaturas e TrustedOnly. A rede gerenciada usa IPs
livres e remove os recursos da sessão. O serviço foreground suporta versões antigas
do Android e deixa de prometer recuperação de PTYs após morte do processo.

## Evidências

| Verificação | Resultado |
|---|---|
| APK debug, testes Java e lint Gradle | Sucesso |
| Assinatura APK (apksigner) | Válida, chave de desenvolvimento |
| Integridade/validação/configuração Java | 7 testes, 0 falhas/erros |
| PTY real em Linux | 6 testes, 0 falhas |
| Backup, restauração, clone, locks, PID antigo, rootfs ativo, OverlayFS | Passaram |
| Alocação de endereço liberado sem colisão | Passou |
| Script de clientes X11 | 46 verificações, 0 falhas |
| Script de diagnóstico de kernel | 31 verificações, 0 falhas |
| Interface Android 11/API 30, emulador x86_64 | 1 teste instrumentado passou |
| Helpers Android de três ABIs | Reconstruídos; TLS ARM64 alinhado a 64 bytes |

O teste Android cobre renderização, diálogo, salvamento de preferências e recriação
da Activity. As capturas apresentam um card de demonstração `debian-teste`.
Os testes de scripts usam Linux com root/capabilities e fixtures, incluindo chroot
e uma camada OverlayFS real em tmpfs; não comprovam GPU física ou execução de uma
distro no Android.

## Instalação e revisão

A APK é TuxBox Dev, ID `sh.tuxbox.root.dev`, versão 1.1.0-dev/código 28,
Android 10 ou superior. Pode coexistir com a release; ambas usam os rootfs de
`/data/local/tuxbox`. Use um clone para experimentar. A assinatura de desenvolvimento
não substitui a chave privada da release do proprietário.

O ZIP contém o source completo, licenças e helpers. O patch aplica-se à base acima:
`git apply --check tuxbox-alteracoes.patch`, seguido de `git apply tuxbox-alteracoes.patch`.
A documentação de build e uso está em `docs/management.md`. Nesta sessão, o build
usou a fonte original libsu 5.2.2, commit 30da9269455ffd6801b237d19cbfd43b7171f6f4,
pelo mecanismo opcional `-PtuxboxLibsuSource`; o build padrão mantém JitPack.

## Limites materiais

Validação em aparelho ainda necessária para Magisk/KernelSU, GPU/Termux:X11,
SELinux, cgroups, rede Android e páginas de 16 KiB. Perfis e OverlayFS falham
explicitamente quando indisponíveis. Root/namespaces não são garantia de sandbox.
Docker/LXC são diagnosticados como ferramentas presentes; não são backends novos.
Não foi medido ganho numérico de desempenho no celular. Os arquivos de desenvolvimento foram disponibilizados numa branch de downloads no GitHub.
