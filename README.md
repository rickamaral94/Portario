# Portário

Catálogo de ports e recomps para Android. Feito para celular, tablet e portátil com controle físico.

## Usar

1. Abra **Explorar** e procure por um jogo ou apelido, como TOTK ou NFSU2.
2. Na ficha, escolha a variante e veja os arquivos do jogo exigidos pelo mantenedor.
3. Toque em **Acompanhar** para consultar releases na Biblioteca. Para fontes novas, cole um link do GitHub em **Adicionar**. Links enviados pelo menu Compartilhar do Android abrem o mesmo fluxo.
4. Na **Biblioteca**, **Buscar atualizações** consulta todas as fontes acompanhadas. **Buscar automaticamente** ativa consultas em segundo plano a cada seis horas, sujeitas à rede e ao agendamento do Android. Autorizar notificações é opcional.
5. Use **Baixar APK** e, ao terminar, **Instalar**. O Android pede confirmação. O Portário confere pacote, ABI, versão e assinatura de atualização, além do SHA-256 quando publicado pelo GitHub. A etiqueta **Nova release** identifica uma publicação que ainda precisa passar por essas verificações.

O catálogo e o estado salvo ficam disponíveis offline. Uma URL de release específica pode ser fixada durante a inclusão. Para editar canal, filtro ou versão fixada, consulte novamente a URL em **Adicionar** e salve as opções avançadas para a mesma fonte.

## Build

Requer JDK 17 ou posterior, Android SDK Platform 36 e Android Build Tools. Defina `ANDROID_HOME` ou `sdk.dir` em `local.properties`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

O GitHub Actions gera o artefato **Portario-debug**. A v0.1.0 usa assinatura de debug. Para distribuição oficial, configure uma chave de release sob controle do mantenedor antes de distribuir a primeira versão de produção.

Com um emulador conectado, `./gradlew connectedDebugAndroidTest` simula dois ciclos do WorkManager e respostas HTTP do GitHub sem consumir a cota real. A suíte verifica o cache e o ETag, a retomada após o limite e a separação entre acesso negado e limite de consultas.

## Catálogo

O arquivo [catalog.json](app/src/main/assets/catalog.json) contém o catálogo de fábrica. Para adicionar uma entrada, mantenha `schema: 1`, crie um ID estável para o jogo e adicione uma variante no array `variants`. Use o ID em minúsculas `dono/repositório` para a variante e `repo` com a grafia da fonte oficial.

- `kind` aceita `RECOMP`, `DECOMP`, `SOURCE` ou `ENGINE`.
- `setup` aceita `DIRECT`, `EXTRA` ou `MANUAL`.
- `channel` aceita `STABLE`, `ALL` ou `BETA`. `filter` é um trecho literal opcional para identificar o asset Android certo, especialmente em releases multiplataforma.
- Anote edição, dados necessários, requisitos, controle, URL do guia, evidências, data de conferência e upstream conforme a documentação da fonte. Use `packageId` apenas quando confirmado no APK ou no manifesto. `verifiedTag` e `verifiedAssets` representam a conferência editorial, enquanto o app consulta as releases atuais.
- Dê preferência a projetos que publiquem APK instalável no repositório do mantenedor. Inclua os passos extras com destaque. No GTA V Recompiled, use o guia para gerar o pacote do usuário a partir dos próprios discos antes de jogar.

A planilha do usuário foi usada para descobrir candidatos. A seleção inicial reúne as fontes com APK Android oficial identificado nas releases consultadas. Isso confirma a publicação do artefato, e a compatibilidade com hardware depende do port e da versão.

## Teste rápido no Odin 2 Portal Pro

1. Instale o APK e abra em modo avião. Confirme que o catálogo e a busca por TOTK e NFSU2 funcionam.
2. Abra BOTW e alterne entre upstream e Amaral. Volte com B e confira se o foco retorna ao jogo.
3. Com internet, compartilhe um link do GitHub para o Portário. Adicione o mesmo projeto pela URL de Releases e confira que há só uma fonte correspondente na Biblioteca.
4. Teste **Buscar atualizações**, ative a busca automática e confira que versões beta e fixadas aparecem identificadas.
5. Em um port com APK ARM64 e variante compatível, baixe, cancele e repita. Ao instalar, confirme a caixa do Android. Depois abra o app pelo Portário e siga a preparação de arquivos indicada no guia.
6. Gire o aparelho ou use-o em paisagem. Navegue entre catálogo e ficha com direcional, A e B. Valide o port separadamente com os dados da edição correta do jogo.
