# Assinatura permanente do APK do RAVO

As atualizações Android precisam usar o mesmo `applicationId`, um `versionCode` maior e **a mesma chave de assinatura**. O `assembleDebug` do GitHub Actions criava um certificado temporário em cada execução. Sem assinatura persistente, instalar o APK novo por cima do anterior pode falhar.

## Configuração mais fácil: 100% nuvem, sem instalar nada no Windows

1. Abra este repositório no navegador, selecione a branch com a correção (ou `main` depois do merge do PR #4) e clique em **Code → Codespaces → Create codespace**.
2. No editor do Codespaces, clique em **Terminal → New Terminal**.
3. Execute **um único comando**, diretamente na máquina virtual do GitHub:

   ```bash
   bash scripts/configurar-assinatura-codespaces.sh
   ```

4. O script cria a chave PKCS12/JKS com senha aleatória forte, guarda os arquivos fora do repositório, verifica o acesso e usa `gh secret set` para enviar os quatro Secrets ao **GitHub Actions**. Ele não mostra a senha nem coloca a chave no código.
5. Antes de apagar o Codespaces, **guarde backup privado** dos arquivos `~/.ravo-signing/ravo-release.jks` e `~/.ravo-signing/senha-ravo.txt` (por exemplo, baixando-os pelo explorador de arquivos do Codespaces e guardando em um cofre criptografado). Nunca compartilhe esses arquivos. O armazenamento do Codespaces **não é um backup permanente**.
6. Após integrar o PR #4 à `main`, vá em **Actions → Build APK → Run workflow**, escolha `main` e baixe o APK com nome `RAVO_atualizavel_...`.

Se `gh secret set` reclamar de autenticação/permissões, a conta do Codespaces precisa de autorização para modificar os Secrets do repositório. Nesse caso, interrompa o processo: o script **não** deve ser alterado para imprimir chaves ou senhas no terminal. Consulte o acesso do GitHub CLI e tente novamente. O script reutiliza a chave já criada no mesmo Codespaces, sem gerar outra a cada tentativa.

A primeira migração do antigo APK debug ainda exige desinstalar uma vez. Depois, todas as versões assinadas com a mesma chave poderão atualizar por cima, desde que o `versionCode` aumente.

---

## Configuração alternativa (Windows, PowerShell)

**Importante:** a chave privada é um documento essencial do aplicativo. Nunca adicione `.jks`, a senha ou a versão Base64 ao GitHub como arquivo público. Faça backup seguro da chave e das senhas.

1. Instale um JDK (Java 17 ou superior) em seu computador. Abra o PowerShell em uma pasta privada e execute:

   ```powershell
   keytool -genkeypair -v -keystore ravo-release.jks -storetype PKCS12 -alias ravo -keyalg RSA -keysize 3072 -validity 10000
   ```

   Escolha uma senha forte e guarde-a. Para PKCS12, a senha da chave pode ser a mesma senha do arquivo.

2. Copie o conteúdo Base64 da chave para a área de transferência:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path (Get-Location) "ravo-release.jks"))) | Set-Clipboard
   ```

3. No GitHub do projeto: **Settings → Secrets and variables → Actions → New repository secret**. Configure:
   - `RAVO_KEYSTORE_BASE64`: cole o Base64 copiado, como **Secret**, nunca no código;
   - `RAVO_KEYSTORE_PASSWORD`: senha do arquivo `.jks`;
   - `RAVO_KEY_ALIAS`: `ravo`;
   - `RAVO_KEY_PASSWORD`: senha da chave (normalmente igual à senha do arquivo).

4. Faça uma nova execução do workflow **Build APK** (ou um push depois da configuração). Baixe o artefato `ravo-apk`, contendo `RAVO_atualizavel_v...`. Esse é o APK assinado para futuras atualizações.

Se os Secrets ainda não estiverem configurados, o workflow continuará gerando um APK para desenvolvimento com nome **RAVO_DEV_NAO_ATUALIZAVEL**. Esse arquivo não oferece atualizações consistentes por cima de versões anteriores.

## Primeira migração

Se o aplicativo instalado foi assinado pela antiga chave debug, **a primeira instalação com a chave permanente não atualiza diretamente**. Antes de desinstalar, registre/exporte os dados importantes do aplicativo. Em instalações atuais, desinstalar normalmente exclui dados internos, favoritos e preferências. Desinstale a versão antiga apenas depois do backup e instale o novo APK assinado.

A partir dessa primeira instalação, todas as próximas versões assinadas com **a mesma chave**, mesmo pacote e `versionCode` superior poderão ser atualizadas tocando no APK, sem desinstalar.

**Nunca gere uma nova chave a cada atualização.** Se perder a chave, não haverá como assinar atualizações compatíveis com a instalação já existente.

## Verificação

No workflow, a etapa `Gerar APK atualizavel com assinatura permanente` deve aparecer como executada (não ignorada). Se a etapa de desenvolvimento for executada, faltam Secrets. Teste uma atualização real entre dois APKs assinados com a mesma chave antes de distribuir.
