# ODM Decision Center Java API

Um toolkit Java abrangente para interagir com o IBM Operational Decision Manager (ODM) Decision Center via sua API remota. Este projeto fornece um servidor REST API e camada de serviços para gerenciar artefatos ODM incluindo regras, tabelas de decisão, ruleflows, vocabulários, variáveis e operações.

## 🚀 Funcionalidades

- **Servidor REST API** - Endpoints HTTP para todas as operações ODM
- **Gerenciamento de Regras** - Criar, atualizar e gerenciar Action Rules
- **Tabelas de Decisão** - Construir tabelas de decisão complexas a partir de modelos JSON
- **Ruleflows** - Criar e gerenciar fluxos de decisão (DRF XML)
- **Variáveis** - Gerenciar Variable Sets e Variables
- **Operações** - Criar Decision Service Operations com parâmetros
- **Vocabulários** - Consultar vocabulários BOM
- **Validação** - Validação automática de artefatos com relatórios detalhados

## 📋 Requisitos

- **Java 21** ou superior
- **IBM ODM 9.5+** instalado
- Acesso à API do Decision Center
- Todas as bibliotecas ODM necessárias (veja diretório `jars/`)

## 🏗️ Arquitetura

```
Aplicações Cliente
        ↓
Servidor REST API (Porta 8080)
        ↓
Camada de Serviços
  ├── ODMVocabularyService
  ├── ODMRuleService
  ├── ODMRuleflowService
  ├── ODMDecisionTableService
  ├── ODMVariableService
  └── ODMOperationService
        ↓
API do ODM Decision Center
        ↓
Repositório do Decision Center
```

## 🔧 Configuração

Edite a configuração em `ODMHttpServer.java` e `Main.java`:

```java
private static final String DC_USERNAME = "odmAdmin";
private static final String DC_PASSWORD = "odmAdmin";
private static final String DC_URL = "http://localhost:9060/decisioncenter-api";
private static final String DC_DATASOURCE = "jdbc/ilogDataSource";
```

## 🚀 Início Rápido

### Compilar

```bash
javac -cp "jars/*:." -d bin src/com/ibm/odm/regras/*.java
```

### Executar Servidor REST API

```bash
java -cp "bin:jars/*" com.ibm.odm.regras.ODMHttpServer
```

Servidor inicia em `http://localhost:8080`

### Executar Ferramenta CLI

```bash
java -cp "bin:jars/*" com.ibm.odm.regras.Main "NomeDecisionService" "%current_key"
```

## 📡 Endpoints REST API

### Health Check
```bash
GET /health
```

### Vocabulários
```bash
GET /vocabularies?project=<nome>&baseline=<nome>
```

### Projetos
```bash
POST /projects
DELETE /projects?projectName=<nome>
```

### Regras
```bash
POST /rules      # Criar regra
GET /rules       # Obter detalhes da regra
PUT /rules       # Atualizar regra
```

### Ruleflows
```bash
POST /ruleflows  # Criar/atualizar ruleflow
GET /ruleflows   # Obter detalhes do ruleflow
```

### Tabelas de Decisão
```bash
POST /decisiontables  # Criar tabela de decisão
GET /decisiontables   # Obter detalhes da tabela
```

### Variáveis
```bash
POST /variables      # Criar variable set com variáveis
GET /variables       # Listar variable sets
POST /variablesets   # Criar variable set vazio
```

### Operações
```bash
POST /operations  # Criar/atualizar operação
GET /operations   # Obter detalhes da operação
```

## 💡 Exemplos de Uso

### Criar uma Regra

```bash
curl -X POST http://localhost:8080/rules \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "AprovacaoEmprestimo",
    "packageName": "regras",
    "ruleName": "VerificarScore",
    "ruleBody": "se o score de crédito do solicitante é pelo menos 700 então aprovar o empréstimo;",
    "priority": 10
  }'
```

### Criar uma Tabela de Decisão

```bash
curl -X POST http://localhost:8080/decisiontables \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "AprovacaoEmprestimo",
    "packagePath": "tabelas",
    "tableName": "DecisaoEmprestimo",
    "model": {
      "conditions": [
        {
          "title": "Score de Crédito",
          "statement": "o score de crédito do solicitante",
          "type": "range:number"
        }
      ],
      "actions": [
        {
          "title": "Decisão",
          "statement": "definir a decisão como <uma string>"
        }
      ]
    }
  }'
```

### Criar Variáveis

```bash
curl -X POST http://localhost:8080/variables \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "AprovacaoEmprestimo",
    "packageName": "variaveis",
    "variableSetName": "VariaveisEmprestimo",
    "variables": [
      {
        "name": "nomeSolicitante",
        "bomType": "java.lang.String",
        "verbalization": "o nome do solicitante",
        "initialValue": ""
      }
    ]
  }'
```

### Criar uma Operação

```bash
curl -X POST http://localhost:8080/operations \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "AprovacaoEmprestimo",
    "operationName": "aprovarEmprestimo",
    "description": "Operação de decisão de aprovação de empréstimo",
    "ruleflowName": "FluxoAprovacaoEmprestimo",
    "rulesetName": "ruleset-aprovacao-emprestimo",
    "parameters": [
      {
        "name": "solicitante",
        "direction": "IN",
        "bomType": "emprestimo.Solicitante"
      },
      {
        "name": "decisao",
        "direction": "OUT",
        "bomType": "emprestimo.Decisao"
      }
    ]
  }'
```

## 🔍 Funcionalidades de Validação

Toda criação de artefatos inclui validação automática:

- **Pré-validação**: Verificação de sintaxe antes da criação
- **Pós-validação**: Verificação de estrutura após a criação
- **Relatório de Validação**: Problemas detalhados com níveis de severidade (ERROR, WARN, INFO)

### Verificações de Validação

**Action Rules:**
- Corpo não vazio
- Sem marcadores de placeholder (`<...>`)
- Delimitadores balanceados `()`, `{}`, `[]`
- Número par de aspas
- Detecção de TODO/FIXME (aviso)

**Tabelas de Decisão:**
- Pelo menos uma coluna de condição
- Títulos de coluna não vazios
- Sem títulos duplicados
- Partição raiz válida
- Pelo menos uma expressão

**Ruleflows:**
- XML bem formado
- Elemento raiz válido
- Contém elementos task/flow

## 📦 Estrutura do Projeto

```
ODM_TOOLS/
├── src/com/ibm/odm/regras/
│   ├── Main.java                      # Ponto de entrada CLI
│   ├── ODMHttpServer.java             # Servidor REST API
│   ├── ODMArtifactValidator.java      # Motor de validação
│   ├── ODMVocabularyService.java      # Operações de vocabulário
│   ├── ODMDecisionTableService.java   # CRUD de tabelas de decisão
│   ├── ODMRuleService.java            # Gerenciamento de regras
│   ├── ODMRuleflowService.java        # Operações de ruleflow
│   ├── ODMVariableService.java        # Gerenciamento de Variable Set
│   ├── ODMOperationService.java       # Gerenciamento de operações
│   └── DTJsonBuilder.java             # Conversor JSON para DT
├── jars/                              # Bibliotecas ODM e dependências
├── bin/                               # Classes compiladas
└── README.md                          # Este arquivo
```

## 🔐 Notas de Segurança

⚠️ **Importante**: Este é um toolkit de desenvolvimento.

- Credenciais são armazenadas em texto simples (apenas desenvolvimento)
- Use variáveis de ambiente para produção
- Implemente autenticação/autorização adequada
- Use HTTPS para implantações em produção
- Sempre teste em ambientes não produtivos primeiro

## 🐛 Solução de Problemas

### Conexão Recusada
- Verifique se `DC_URL` está correto
- Verifique se o Decision Center está em execução
- Verifique a conectividade de rede

### Falha de Autenticação
- Verifique `DC_USERNAME` e `DC_PASSWORD`
- Verifique se o usuário tem permissões apropriadas

### Artefato Não Encontrado
- Verifique se o nome da baseline está correto
- Verifique se o projeto existe
- Certifique-se de que a baseline de trabalho está definida

### Erros de Validação
- Revise o relatório de validação na resposta
- Verifique marcadores de placeholder
- Verifique a sintaxe de IRL/DRF

## 🚢 Configuração Docker (Opcional)

Para testar com ODM baseado em Docker:

```bash
# Baixar imagem ODM
docker pull icr.io/cpopen/odm-k8s/odm:9.5.0

# Executar container ODM
docker run -d \
  --name odm-local \
  -p 9060:9060 \
  -e LICENSE=accept \
  -e SAMPLE=true \
  icr.io/cpopen/odm-k8s/odm:9.5.0

# Acessar Decision Center
# URL: http://localhost:9060/decisioncenter
# Usuário: odmAdmin / Senha: odmAdmin
```

## 📚 Principais Dependências

- Bibliotecas IBM ODM 9.5+ (jrules-teamserver, jrules-engine, etc.)
- Spring Framework 6.2.12
- Jackson 2.16.0 (processamento JSON)
- Jakarta XML Bind API 4.0.2
- Eclipse EMF 2.31.0+
- Bibliotecas Apache Commons

## 🤝 Contribuindo

Ao adicionar novos recursos:
1. Siga os padrões de serviço existentes
2. Adicione validação quando apropriado
3. Inclua tratamento de erros
4. Atualize endpoints REST API
5. Documente novos métodos

## 📄 Licença

Este projeto é para fins de integração com IBM ODM. Certifique-se de estar em conformidade com os termos de licenciamento do IBM ODM.

## 📞 Suporte

Para problemas relacionados a:
- **API ODM**: Consulte a [Documentação IBM ODM](https://www.ibm.com/docs/en/odm)
- **Este toolkit**: Revise comentários no código-fonte e exemplos
- **Decision Center**: Verifique recursos de suporte IBM

## 🔗 Links Úteis

- [Documentação IBM ODM](https://www.ibm.com/docs/en/odm)
- [Guia da API do Decision Center](https://www.ibm.com/docs/en/odm/9.0.0?topic=center-decision-api)
- [Referência da Linguagem IRL](https://www.ibm.com/docs/en/odm/9.0.0?topic=language-ilog-rule-reference)

---

**Versão**: 1.0  
**Java**: 21  
**Compatibilidade ODM**: 9.5+

Feito com ❤️ para automação IBM ODM