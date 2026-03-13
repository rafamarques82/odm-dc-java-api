# ODM Decision Center Java API

A comprehensive Java toolkit for interacting with IBM Operational Decision Manager (ODM) Decision Center via its remote API. This project provides both a REST API server and service layer for managing ODM artifacts including rules, decision tables, ruleflows, vocabularies, variables, and operations.

## 🚀 Features

- **REST API Server** - HTTP endpoints for all ODM operations
- **Rule Management** - Create, update, and manage Action Rules
- **Decision Tables** - Build complex decision tables from JSON models
- **Ruleflows** - Create and manage decision flows (DRF XML)
- **Variables** - Manage Variable Sets and Variables
- **Operations** - Create Decision Service Operations with parameters
- **Vocabularies** - Query BOM vocabularies
- **Validation** - Automatic artifact validation with detailed reports

## 📋 Requirements

- **Java 21** or higher
- **IBM ODM 9.5+** installation
- Access to Decision Center API
- All required ODM libraries (see `jars/` directory)

## 🏗️ Architecture

```
Client Applications
        ↓
REST API Server (Port 8080)
        ↓
Service Layer
  ├── ODMVocabularyService
  ├── ODMRuleService
  ├── ODMRuleflowService
  ├── ODMDecisionTableService
  ├── ODMVariableService
  └── ODMOperationService
        ↓
ODM Decision Center API
        ↓
Decision Center Repository
```

## 🔧 Configuration

Edit the configuration in `ODMHttpServer.java` and `Main.java`:

```java
private static final String DC_USERNAME = "odmAdmin";
private static final String DC_PASSWORD = "odmAdmin";
private static final String DC_URL = "http://localhost:9060/decisioncenter-api";
private static final String DC_DATASOURCE = "jdbc/ilogDataSource";
```

## 🚀 Quick Start

### Compile

```bash
javac -cp "jars/*:." -d bin src/com/ibm/odm/regras/*.java
```

### Run REST API Server

```bash
java -cp "bin:jars/*" com.ibm.odm.regras.ODMHttpServer
```

Server starts on `http://localhost:8080`

### Run CLI Tool

```bash
java -cp "bin:jars/*" com.ibm.odm.regras.Main "DecisionServiceName" "%current_key"
```

## 📡 REST API Endpoints

### Health Check
```bash
GET /health
```

### Vocabularies
```bash
GET /vocabularies?project=<name>&baseline=<name>
```

### Projects
```bash
POST /projects
DELETE /projects?projectName=<name>
```

### Rules
```bash
POST /rules      # Create rule
GET /rules       # Get rule details
PUT /rules       # Update rule
```

### Ruleflows
```bash
POST /ruleflows  # Create/update ruleflow
GET /ruleflows   # Get ruleflow details
```

### Decision Tables
```bash
POST /decisiontables  # Create decision table
GET /decisiontables   # Get table details
```

### Variables
```bash
POST /variables      # Create variable set with variables
GET /variables       # List variable sets
POST /variablesets   # Create empty variable set
```

### Operations
```bash
POST /operations  # Create/update operation
GET /operations   # Get operation details
```

## 💡 Usage Examples

### Create a Rule

```bash
curl -X POST http://localhost:8080/rules \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "LoanApproval",
    "packageName": "rules",
    "ruleName": "CheckCreditScore",
    "ruleBody": "if the credit score of the applicant is at least 700 then approve the loan;",
    "priority": 10
  }'
```

### Create a Decision Table

```bash
curl -X POST http://localhost:8080/decisiontables \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "LoanApproval",
    "packagePath": "tables",
    "tableName": "LoanDecision",
    "model": {
      "conditions": [
        {
          "title": "Credit Score",
          "statement": "the credit score of the applicant",
          "type": "range:number"
        }
      ],
      "actions": [
        {
          "title": "Decision",
          "statement": "set the decision to <a string>"
        }
      ]
    }
  }'
```

### Create Variables

```bash
curl -X POST http://localhost:8080/variables \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "LoanApproval",
    "packageName": "variables",
    "variableSetName": "LoanVariables",
    "variables": [
      {
        "name": "applicantName",
        "bomType": "java.lang.String",
        "verbalization": "the applicant name",
        "initialValue": ""
      }
    ]
  }'
```

### Create an Operation

```bash
curl -X POST http://localhost:8080/operations \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "LoanApproval",
    "operationName": "approveLoan",
    "description": "Loan approval decision",
    "ruleflowName": "LoanApprovalFlow",
    "rulesetName": "loan-approval-ruleset",
    "parameters": [
      {
        "name": "applicant",
        "direction": "IN",
        "bomType": "loan.Applicant"
      },
      {
        "name": "decision",
        "direction": "OUT",
        "bomType": "loan.Decision"
      }
    ]
  }'
```

## 🔍 Validation Features

All artifact creation includes automatic validation:

- **Pre-validation**: Syntax checking before creation
- **Post-validation**: Structure verification after creation
- **Validation Report**: Detailed issues with severity levels (ERROR, WARN, INFO)

### Validation Checks

**Action Rules:**
- Non-empty body
- No placeholder markers (`<...>`)
- Balanced delimiters `()`, `{}`, `[]`
- Even number of quotes
- TODO/FIXME detection (warning)

**Decision Tables:**
- At least one condition column
- Non-empty column titles
- No duplicate titles
- Valid root partition
- At least one expression

**Ruleflows:**
- Well-formed XML
- Valid root element
- Contains task/flow elements

## 📦 Project Structure

```
ODM_TOOLS/
├── src/com/ibm/odm/regras/
│   ├── Main.java                      # CLI entry point
│   ├── ODMHttpServer.java             # REST API server
│   ├── ODMArtifactValidator.java      # Validation engine
│   ├── ODMVocabularyService.java      # Vocabulary operations
│   ├── ODMDecisionTableService.java   # Decision table CRUD
│   ├── ODMRuleService.java            # Rule management
│   ├── ODMRuleflowService.java        # Ruleflow operations
│   ├── ODMVariableService.java        # Variable Set management
│   ├── ODMOperationService.java       # Operation management
│   └── DTJsonBuilder.java             # JSON to DT converter
├── jars/                              # ODM and dependency libraries
├── bin/                               # Compiled classes
└── README.md                          # This file
```

## 🔐 Security Notes

⚠️ **Important**: This is a development toolkit.

- Credentials are stored in plain text (development only)
- Use environment variables for production
- Implement proper authentication/authorization
- Use HTTPS for production deployments
- Always test in non-production environments first

## 🐛 Troubleshooting

### Connection Refused
- Verify `DC_URL` is correct
- Check Decision Center is running
- Verify network connectivity

### Authentication Failed
- Check `DC_USERNAME` and `DC_PASSWORD`
- Verify user has appropriate permissions

### Artifact Not Found
- Verify baseline name is correct
- Check project exists
- Ensure working baseline is set

### Validation Errors
- Review validation report in response
- Check for placeholder markers
- Verify syntax of IRL/DRF

## 🚢 Docker Setup (Optional)

For testing with Docker-based ODM:

```bash
# Pull ODM image
docker pull icr.io/cpopen/odm-k8s/odm:9.5.0

# Run ODM container
docker run -d \
  --name odm-local \
  -p 9060:9060 \
  -e LICENSE=accept \
  -e SAMPLE=true \
  icr.io/cpopen/odm-k8s/odm:9.5.0

# Access Decision Center
# URL: http://localhost:9060/decisioncenter
# User: odmAdmin / Pass: odmAdmin
```

## 📚 Key Dependencies

- IBM ODM 9.5+ libraries (jrules-teamserver, jrules-engine, etc.)
- Spring Framework 6.2.12
- Jackson 2.16.0 (JSON processing)
- Jakarta XML Bind API 4.0.2
- Eclipse EMF 2.31.0+
- Apache Commons libraries

## 🤝 Contributing

When adding new features:
1. Follow existing service patterns
2. Add validation where appropriate
3. Include error handling
4. Update REST API endpoints
5. Document new methods

## 📄 License

This project is for IBM ODM integration purposes. Ensure compliance with IBM ODM licensing terms.

## 📞 Support

For issues related to:
- **ODM API**: Consult [IBM ODM Documentation](https://www.ibm.com/docs/en/odm)
- **This toolkit**: Review source code comments and examples
- **Decision Center**: Check IBM support resources

## 🔗 Useful Links

- [IBM ODM Documentation](https://www.ibm.com/docs/en/odm)
- [Decision Center API Guide](https://www.ibm.com/docs/en/odm/9.0.0?topic=center-decision-api)
- [IRL Language Reference](https://www.ibm.com/docs/en/odm/9.0.0?topic=language-ilog-rule-reference)

---

**Version**: 1.0  
**Java**: 21  
**ODM Compatibility**: 9.5+

Made with ❤️ for IBM ODM automation