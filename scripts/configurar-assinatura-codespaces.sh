#!/usr/bin/env bash
# Configuracao unica da assinatura Android diretamente no GitHub Codespaces.
# ATENCAO: NUNCA fazer commit do arquivo .jks ou da senha.
set -euo pipefail
umask 077

REPO="thiagolucenaoncity-sketch/Ravo"
SIGNING_DIR="$HOME/.ravo-signing"
KEYSTORE="$SIGNING_DIR/ravo-release.jks"
PASSWORD_FILE="$SIGNING_DIR/senha-ravo.txt"

echo "RAVO: preparando assinatura permanente na nuvem..."

if ! command -v gh >/dev/null 2>&1; then
  echo "ERRO: GitHub CLI (gh) nao encontrada. Abra este repositorio pelo GitHub Codespaces."
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "ERRO: a sessao do GitHub CLI nao esta autenticada. Autentique-se pelo navegador antes de continuar."
  exit 1
fi

# Verifica o acesso a Secrets antes de gerar uma chave privada.
if ! gh api "repos/$REPO/actions/secrets/public-key" --jq .key_id >/dev/null; then
  echo "ERRO: sem acesso aos Secrets deste repositorio. Autorize o GitHub CLI com acesso de administrador e execute novamente."
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "Java nao encontrado; instalando o JDK 17 no Codespaces..."
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jdk-headless
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "ERRO: openssl nao encontrado no ambiente Codespaces."
  exit 1
fi

mkdir -p "$SIGNING_DIR"
chmod 700 "$SIGNING_DIR"

if [[ -e "$KEYSTORE" || -e "$PASSWORD_FILE" ]]; then
  if [[ ! -s "$KEYSTORE" || ! -s "$PASSWORD_FILE" ]]; then
    echo "ERRO: arquivo de assinatura ou senha ausente; nao vamos substituir a chave automaticamente."
    echo "Confira os arquivos no diretorio privado: $SIGNING_DIR"
    exit 1
  fi
  PASSWORD="$(cat "$PASSWORD_FILE")"
  echo "Reutilizando a chave privada ja existente neste Codespaces."
else
  PASSWORD="$(openssl rand -hex 32)"
  # Cria a chave sem exibir a senha no console.
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias ravo -keyalg RSA -keysize 3072 -validity 10000 \
    -storepass "$PASSWORD" -keypass "$PASSWORD" \
    -dname "CN=RAVO, OU=Aplicativo, O=RAVO, L=Sao Paulo, ST=Sao Paulo, C=BR" \
    -noprompt
  printf '%s' "$PASSWORD" > "$PASSWORD_FILE"
  chmod 600 "$KEYSTORE" "$PASSWORD_FILE"
  echo "Nova chave de assinatura criada em pasta privada."
fi

# Envia os segredos diretamente pelo GitHub CLI; nenhum valor confidencial
# vai para um commit, log do GitHub Actions ou texto no terminal.
base64 -w 0 "$KEYSTORE" | gh secret set RAVO_KEYSTORE_BASE64 --repo "$REPO" --app actions
printf '%s' "$PASSWORD" | gh secret set RAVO_KEYSTORE_PASSWORD --repo "$REPO" --app actions
printf '%s' "ravo" | gh secret set RAVO_KEY_ALIAS --repo "$REPO" --app actions
printf '%s' "$PASSWORD" | gh secret set RAVO_KEY_PASSWORD --repo "$REPO" --app actions

echo
echo "SUCESSO: quatro Secrets de assinatura registrados no GitHub Actions."
echo "A chave JKS e a senha estao SOMENTE nesta pasta privada do Codespaces:"
echo "  $SIGNING_DIR"
echo "IMPORTANTE: faca backup seguro dos dois arquivos ANTES de excluir o Codespaces."
echo "Nao publique nem envie esses arquivos para o repositorio ou para conversas."
echo "Proximo passo: integrar o PR #4 e executar o workflow Build APK na branch main."
