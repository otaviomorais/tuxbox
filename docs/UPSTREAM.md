# Código de terceiros e proveniência

## Termux terminal

Os componentes em `app/src/main/java/com/termux/` foram adaptados do
projeto [termux/termux-app](https://github.com/termux/termux-app).

A árvore local foi comparada em 25 de setembro de 2026 com o commit
[`084d709fbf23ea83b5cb85fd3d795c775be06676`](https://github.com/termux/termux-app/commit/084d709fbf23ea83b5cb85fd3d795c775be06676).
Alguns arquivos são idênticos ao upstream nesse commit; outros receberam
adaptações para o TuxBox. Como o import inicial não registrou um commit
pai, este commit é a **referência de proveniência verificável**, e não uma
afirmação de que toda a árvore local seja um fork byte a byte.

Principais adaptações do TuxBox incluem:

- integração do terminal com a ponte nativa `ptyd`;
- pooling e timeouts de comandos root;
- adaptations de UI, teclado, ciclos de vida e foreground service;
- pequeños ajustes de compatibilidade Android;
- preservação dos avisos de copyright e referências upstream existentes.

## wcwidth

`WcWidth.java` referencia e adapta
[`termux/wcwidth`](https://github.com/termux/wcwidth), que por sua vez credits
Fredrik Fornwall, Jeff Quast e Markus Kuhn. Consulte
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) para atribuições e
licenças.

## Regra para alterações futuras

Ao atualizar o código do Termux, registrar o commit upstream no histórico
Git e manter separada a lista de adaptações locais. Nunca remover avisos de
copyright, SPDX ou atribuições de terceiros.
