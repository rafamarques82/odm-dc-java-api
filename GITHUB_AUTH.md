# Autenticação GitHub com SSO Google

Como você usa SSO do Google para acessar o GitHub, precisa criar um **Personal Access Token** para usar no Git via linha de comando.

## Passo a Passo Rápido

### 1. Criar Personal Access Token

1. Acesse: https://github.com/settings/tokens
2. Clique em **"Generate new token"** → **"Generate new token (classic)"**
3. Preencha:
   - **Note**: `ODM Tools - Git Access`
   - **Expiration**: `90 days` (ou `No expiration` se preferir)
   - **Scopes**: Marque apenas `repo` (acesso completo aos repositórios)
4. Clique em **"Generate token"**
5. **COPIE O TOKEN IMEDIATAMENTE** (você só verá uma vez!)
   - Exemplo: `ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`

### 2. Fazer o Push

Abra o Terminal e execute:

```bash
cd /Users/rafaelmarques/Documents/eclipse/ODM_TOOLS
git push -u origin main
```

Quando pedir credenciais:
- **Username**: `rafamarques82`
- **Password**: Cole o token que você copiou (não a senha do Google!)

### 3. Salvar Credenciais (Opcional)

Para não precisar digitar toda vez:

```bash
# Salvar credenciais no keychain do macOS
git config --global credential.helper osxkeychain
```

Depois do primeiro push com o token, o macOS vai salvar automaticamente.

## Alternativa: GitHub Desktop

Se preferir interface gráfica:

1. Baixe: https://desktop.github.com/
2. Faça login com sua conta Google (SSO)
3. File → Add Local Repository
4. Selecione: `/Users/rafaelmarques/Documents/eclipse/ODM_TOOLS`
5. Clique em "Push origin"

O GitHub Desktop cuida da autenticação SSO automaticamente!

## Alternativa: VS Code

Se estiver usando VS Code:

1. Abra o projeto no VS Code
2. Vá em Source Control (Ctrl+Shift+G)
3. Clique em "Publish Branch"
4. O VS Code vai abrir o navegador para autenticar via SSO
5. Autorize e pronto!

## Verificar se funcionou

Depois do push, acesse:
https://github.com/rafamarques82/odm-java-tools

Você deve ver todos os arquivos lá! 🎉

---

**Recomendação**: Use GitHub Desktop ou VS Code para facilitar com SSO!