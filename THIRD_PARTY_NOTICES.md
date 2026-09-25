# Notices de terceiros

O TuxBox inclui ou adapta componentes de terceiros. Os textos de licença
completos e as permissões originais estão indicados abaixo.

## Termux

Parte dos componentes de interface e emulação de terminal em
`app/src/main/java/com/termux/` deriva dos projetos:

- [termux/termux-app — `terminal-view`](https://github.com/termux/termux-app/tree/master/terminal-view)
- [termux/termux-app — `terminal-emulator`](https://github.com/termux/termux-app/tree/master/terminal-emulator)

O aplicativo Termux é distribuído sob **GPL-3.0-only**. O Terminal Emulator
for Android usado pelo módulo `terminal-emulator` é uma exceção declarada
pelo projeto Termux e está sob **Apache-2.0**. Consulte
[`LICENSE.md` do Termux](https://github.com/termux/termux-app/blob/master/LICENSE.md)
e o [texto Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0).

O TuxBox é um projeto independente e não é afiliado, endossado ou mantido
pelo Termux.

## wcwidth

`WcWidth.java` adapta a implementação de
[termux/wcwidth](https://github.com/termux/wcwidth), que credits:

- Fredrik Fornwall — copyright de 2016;
- Jeff Quast — copyright de 2014;
- Markus Kuhn — Contributions de 2007.

O projeto `termux/wcwidth` está sob a licença MIT. O texto aplicável está em
[`LICENSE.txt`](https://github.com/termux/wcwidth/blob/master/LICENSE.txt).

## Dependências de build

O projeto obtém, entre outras, estes componentes durante o build:

| Dependência | Projeto | Licença |
|---|---|---|
| AndroidX AppCompat / Fragment | [AndroidX](https://developer.android.com/jetpack/androidx) | Apache-2.0 |
| Material Components for Android | [Material Components](https://github.com/material-components/material-components-android) | Apache-2.0 |
| libsu | [topjohnwu/libsu](https://github.com/topjohnwu/libsu) | Apache-2.0 |
| Apache Commons Compress | [apache/commons-compress](https://github.com/apache/commons-compress) | Apache-2.0 |
| XZ for Java | [tukaani-project/xz-java](https://github.com/tukaani-project/xz-java) | 0BSD / domínio público, conforme o componente |

As dependências são baixadas pelo Gradle durante o build e não são
redistribuídas como código-fonte neste repositório.

## Imagens de containers Linux

O TuxBox **não inclui imagens rootfs**. Em tempo de execução, o aplicativo
pode baixar imagens Linux oficiais por meio do índice do
[linuxcontainers.org](https://linuxcontainers.org/) e armazená-las no
armazenamento do usuário. Essas imagens continuam sob as licenças e os
termos de seus respectivos projetos e mantenedores.
