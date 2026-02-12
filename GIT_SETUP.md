# Guia de Integração com Git - ODM Tools

## Passo 1: Inicializar o Repositório Git

No diretório do projeto (`/Users/rafaelmarques/Documents/eclipse/ODM_TOOLS`), execute:

```bash
cd /Users/rafaelmarques/Documents/eclipse/ODM_TOOLS
git init
```

## Passo 2: Criar o arquivo .gitignore

Crie um arquivo `.gitignore` para excluir arquivos desnecessários:

```bash
# Arquivos compilados
bin/
*.class

# Arquivos do Eclipse
.classpath
.project
.settings/

# Arquivos do sistema
.DS_Store
*.swp
*.swo
*~

# Logs
*.log

# Arquivos temporários
*.tmp
*.bak

# Dependências (não versionar JARs grandes)
# Descomente se quiser excluir as libs
# lib/
# *.jar

# Configurações locais
config.properties
local.properties

# Arquivos de IDE
.idea/
*.iml
.vscode/
```

## Passo 3: Adicionar os Arquivos ao Git

```bash
# Adicionar todos os arquivos (respeitando o .gitignore)
git add .

# Verificar o que será commitado
git status
```

## Passo 4: Fazer o Primeiro Commit

```bash
git commit -m "Initial commit: ODM Tools - IBM ODM API Client

- Implementação completa de serviços ODM
- REST API com 9 endpoints
- Validação de artefatos
- Documentação completa (README, ARCHITECTURE, API_REFERENCE)
- Suporte para Rules, Decision Tables, Ruleflows, Variables, Operations
- Java 21 + IBM ODM 9.5+"
```

## Passo 5: Criar Repositório no GitHub/GitLab/Bitbucket

### Opção A: GitHub

1. Acesse https://github.com/new
2. Crie um novo repositório (ex: `odm-tools`)
3. **NÃO** inicialize com README (já temos um)
4. Copie a URL do repositório

### Opção B: GitLab

1. Acesse https://gitlab.com/projects/new
2. Crie um novo projeto
3. Copie a URL do repositório

### Opção C: Bitbucket

1. Acesse https://bitbucket.org/repo/create
2. Crie um novo repositório
3. Copie a URL do repositório

## Passo 6: Conectar ao Repositório Remoto

```bash
# Adicionar o remote (substitua pela sua URL)
git remote add origin https://github.com/seu-usuario/odm-tools.git

# Verificar se foi adicionado
git remote -v
```

## Passo 7: Enviar o Código para o Repositório

```bash
# Enviar para a branch main
git push -u origin main

# Ou se o repositório usar 'master':
# git branch -M main
# git push -u origin main
```

## Estrutura de Branches Recomendada

```bash
# Branch principal (produção)
main (ou master)

# Branch de desenvolvimento
git checkout -b develop

# Branches de features
git checkout -b feature/nova-funcionalidade

# Branches de correção
git checkout -b fix/correcao-bug

# Branches de release
git checkout -b release/v1.0.0
```

## Workflow Recomendado (Git Flow)

### 1. Criar uma nova feature

```bash
# A partir da develop
git checkout develop
git pull origin develop
git checkout -b feature/adicionar-endpoint-xyz

# Fazer alterações...
git add .
git commit -m "feat: adicionar endpoint XYZ"

# Enviar para o remoto
git push origin feature/adicionar-endpoint-xyz
```

### 2. Fazer merge da feature

```bash
# Voltar para develop
git checkout develop
git merge feature/adicionar-endpoint-xyz

# Enviar para o remoto
git push origin develop

# Deletar a branch local (opcional)
git branch -d feature/adicionar-endpoint-xyz
```

### 3. Criar uma release

```bash
git checkout develop
git checkout -b release/v1.0.0

# Fazer ajustes finais, atualizar versão...
git commit -m "chore: preparar release v1.0.0"

# Merge na main
git checkout main
git merge release/v1.0.0
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin main --tags

# Merge de volta na develop
git checkout develop
git merge release/v1.0.0
git push origin develop
```

## Convenções de Commit (Conventional Commits)

Use prefixos para organizar seus commits:

```bash
# Nova funcionalidade
git commit -m "feat: adicionar suporte para Decision Operations"

# Correção de bug
git commit -m "fix: corrigir validação de Decision Tables"

# Documentação
git commit -m "docs: atualizar README com exemplos de uso"

# Refatoração
git commit -m "refactor: melhorar estrutura do ODMRuleService"

# Performance
git commit -m "perf: otimizar batch operations em variáveis"

# Testes
git commit -m "test: adicionar testes para ODMArtifactValidator"

# Configuração
git commit -m "chore: atualizar dependências do projeto"

# Estilo/formatação
git commit -m "style: formatar código seguindo padrões"
```

## Arquivo .gitignore Completo

Crie o arquivo `.gitignore` na raiz do projeto:

```gitignore
# Compiled class files
*.class
bin/
target/
build/

# Eclipse
.classpath
.project
.settings/
.metadata/

# IntelliJ IDEA
.idea/
*.iml
*.iws
out/

# VS Code
.vscode/

# NetBeans
nbproject/
nbbuild/
dist/
nbdist/

# Mac OS
.DS_Store
.AppleDouble
.LSOverride

# Windows
Thumbs.db
ehthumbs.db
Desktop.ini

# Linux
*~

# Logs
*.log
logs/

# Temporary files
*.tmp
*.bak
*.swp
*.swo
*~.nib

# Package Files
*.jar
*.war
*.nar
*.ear
*.zip
*.tar.gz
*.rar

# Virtual machine crash logs
hs_err_pid*

# Application specific
config.properties
local.properties
secrets.properties

# Test coverage
coverage/
.nyc_output/

# Dependencies (opcional - descomente se não quiser versionar)
# lib/
# libs/
```

## Comandos Git Úteis

### Verificar status
```bash
git status
```

### Ver histórico de commits
```bash
git log --oneline --graph --all
```

### Desfazer alterações não commitadas
```bash
# Arquivo específico
git checkout -- arquivo.java

# Todos os arquivos
git reset --hard HEAD
```

### Atualizar do remoto
```bash
git pull origin main
```

### Ver diferenças
```bash
# Diferenças não staged
git diff

# Diferenças staged
git diff --staged
```

### Criar e aplicar tags
```bash
# Criar tag
git tag -a v1.0.0 -m "Versão 1.0.0"

# Enviar tags
git push origin --tags

# Listar tags
git tag -l
```

## Configuração Inicial do Git (se ainda não fez)

```bash
# Configurar nome e email
git config --global user.name "Seu Nome"
git config --global user.email "seu.email@example.com"

# Configurar editor padrão
git config --global core.editor "code --wait"  # VS Code
# ou
git config --global core.editor "vim"  # Vim

# Configurar branch padrão
git config --global init.defaultBranch main

# Ver configurações
git config --list
```

## Proteger Credenciais

**IMPORTANTE**: Nunca commite credenciais no código!

### Opção 1: Usar variáveis de ambiente

Modifique o código para ler de variáveis de ambiente:

```java
private static final String DC_USERNAME = System.getenv("ODM_USERNAME");
private static final String DC_PASSWORD = System.getenv("ODM_PASSWORD");
private static final String DC_URL = System.getenv("ODM_URL");
```

### Opção 2: Usar arquivo de configuração externo

Crie `config.properties` (adicione ao .gitignore):

```properties
odm.username=odmAdmin
odm.password=odmAdmin
odm.url=http://my-odm.ibm.com:9060/decisioncenter-api
odm.datasource=jdbc/ilogDataSource
```

E crie `config.properties.example` (versione este):

```properties
odm.username=YOUR_USERNAME
odm.password=YOUR_PASSWORD
odm.url=YOUR_ODM_URL
odm.datasource=YOUR_DATASOURCE
```

## Colaboração em Equipe

### Pull Requests / Merge Requests

1. Faça fork do repositório (se não for colaborador direto)
2. Clone seu fork
3. Crie uma branch para sua feature
4. Faça commits
5. Push para seu fork
6. Abra um Pull Request no repositório original

### Code Review

Antes de fazer merge, peça para alguém revisar:
- Código segue os padrões do projeto
- Testes passam
- Documentação está atualizada
- Não há credenciais expostas

## Integração Contínua (CI/CD)

### GitHub Actions (exemplo)

Crie `.github/workflows/build.yml`:

```yaml
name: Build and Test

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 21
      uses: actions/setup-java@v3
      with:
        java-version: '21'
        distribution: 'temurin'
    
    - name: Compile
      run: javac -cp "lib/*:." -d bin src/com/ibm/odm/regras/*.java
    
    - name: Run tests
      run: echo "Add your test commands here"
```

## Backup e Sincronização

```bash
# Fazer backup de todas as branches
git push origin --all

# Fazer backup de todas as tags
git push origin --tags

# Clonar incluindo todas as branches
git clone --mirror https://github.com/seu-usuario/odm-tools.git
```

## Troubleshooting

### Erro: "remote origin already exists"
```bash
git remote remove origin
git remote add origin https://github.com/seu-usuario/odm-tools.git
```

### Erro: "failed to push some refs"
```bash
# Puxar as mudanças primeiro
git pull origin main --rebase
git push origin main
```

### Desfazer último commit (mantendo alterações)
```bash
git reset --soft HEAD~1
```

### Desfazer último commit (descartando alterações)
```bash
git reset --hard HEAD~1
```

## Próximos Passos

1. ✅ Inicializar repositório Git
2. ✅ Criar .gitignore
3. ✅ Fazer primeiro commit
4. ✅ Criar repositório remoto
5. ✅ Conectar e fazer push
6. 📝 Adicionar badges ao README (build status, license, etc.)
7. 📝 Configurar CI/CD
8. 📝 Adicionar testes automatizados
9. 📝 Criar CHANGELOG.md
10. 📝 Adicionar LICENSE

## Recursos Adicionais

- [Git Documentation](https://git-scm.com/doc)
- [GitHub Guides](https://guides.github.com/)
- [Conventional Commits](https://www.conventionalcommits.org/)
- [Git Flow](https://nvie.com/posts/a-successful-git-branching-model/)

---

**Dica**: Faça commits pequenos e frequentes com mensagens descritivas. Isso facilita o rastreamento de mudanças e a reversão se necessário.