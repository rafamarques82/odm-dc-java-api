# ODM Tools - IBM Operational Decision Manager API Client

## Overview

ODM Tools is a comprehensive Java-based toolkit for interacting with IBM Operational Decision Manager (ODM) Decision Center via its remote API. This project provides a REST API server and service layer for managing ODM artifacts including rules, decision tables, ruleflows, vocabularies, variables, and operations.

## Project Information

- **Language**: Java 21 (JavaSE-21)
- **Build System**: Eclipse Project
- **Target Platform**: IBM ODM 9.5+
- **Architecture**: Service-oriented with HTTP REST API

## Key Features

### 1. **Decision Table Management**
- Create, update, and query decision tables
- Support for complex table structures with conditions and actions
- JSON-based table definition
- Automatic validation of table structure

### 2. **Rule Management**
- Create and modify Action Rules
- Update rule bodies (IRL - ILOG Rule Language)
- Manage rule priorities
- Rule validation with syntax checking

### 3. **Ruleflow Management**
- Create and update ruleflows
- DRF (Decision Rule Flow) XML management
- Associate ruleflows with packages
- Semantic validation of ruleflow XML

### 4. **Vocabulary Services**
- Query BOM (Business Object Model) vocabularies
- Support for multiple locales
- Baseline-aware vocabulary retrieval

### 5. **Variable Management**
- Create and manage Variable Sets
- Add/update variables with BOM types
- Support for nested package structures
- Batch variable operations

### 6. **Operation Services**
- Create Decision Service Operations
- Link operations to ruleflows
- Manage operation parameters (IN/OUT/INOUT)
- Ruleset configuration

### 7. **Artifact Validation**
- Pre and post-creation validation
- Syntax checking for rules and ruleflows
- Decision table structure validation
- Placeholder detection and TODO/FIXME warnings

### 8. **HTTP REST API**
- RESTful endpoints for all services
- JSON request/response format
- Health check endpoint
- CORS support

## Architecture

```
ODM_TOOLS/
├── src/com/ibm/odm/regras/
│   ├── Main.java                      # CLI entry point
│   ├── ODMHttpServer.java             # REST API server (2344 lines)
│   ├── ODMArtifactValidator.java      # Validation engine
│   ├── ODMVocabularyService.java      # Vocabulary operations
│   ├── ODMDecisionTableService.java   # Decision table CRUD
│   ├── ODMRuleService.java            # Rule management
│   ├── ODMRuleflowService.java        # Ruleflow operations
│   ├── ODMVariableService.java        # Variable Set management
│   ├── ODMOperationService.java       # Operation management
│   └── DTJsonBuilder.java             # JSON to DT converter
└── bin/                               # Compiled classes
```

## Core Services

### ODMVocabularyService
Retrieves BOM vocabularies from Decision Center baselines.

**Key Methods:**
- `listBOMVocabularies(decisionServiceName, baselineName)` - Get all vocabularies for a baseline
- Supports baseline resolution (`%current_key`, case-insensitive matching, aliases)

### ODMDecisionTableService
Comprehensive decision table management with support for:
- Column definitions (conditions and actions)
- Row management with hierarchical partitions
- Expression definitions and instances
- Package path resolution

**Key Methods:**
- `createOrUpdateDecisionTable()` - Create/update tables
- `addConditionColumn()` - Add condition columns
- `addActionColumn()` - Add action columns
- `setActionAtPath()` - Set action values at specific paths

### ODMRuleService
Action Rule lifecycle management.

**Key Methods:**
- `createActionRule()` - Create new rules
- `findActionRuleByName()` - Locate existing rules
- `updateActionRuleBody()` - Modify rule IRL
- `updateActionRulePriority()` - Change rule priority
- `deleteProject()` - Remove entire projects

### ODMRuleflowService
Ruleflow creation and management with XML body handling.

**Key Methods:**
- `createOrUpdateRuleflow()` - Create/update ruleflows
- `findRuleflowInPackageByName()` - Locate ruleflows
- `setWorkingBaselineToProject()` - Set baseline context

### ODMVariableService
Variable Set and Variable management with reflection-based compatibility.

**Key Methods:**
- `openOrCreateVariableSet()` - Create/open variable sets
- `addVariablesToSet()` - Batch add variables
- `listAllVariables()` - Query all variables in project
- `resolveOrCreateNestedPackage()` - Handle nested packages

### ODMOperationService
Decision Service Operation management with DSM (Decision Service Model) integration.

**Key Methods:**
- `createOrUpdateServiceOperation()` - Create operations
- `saveOperation()` - Persist operation with parameters
- `viewOperationMulti()` - Query operation details

### ODMArtifactValidator
Validation engine for ODM artifacts.

**Validation Types:**
- **Action Rules**: Body content, placeholders, delimiter balancing, TODO markers
- **Decision Tables**: Column structure, titles, expressions, root partition
- **Ruleflows**: XML well-formedness, semantic structure

**Key Methods:**
- `validateActionRule()` - Validate rule structure
- `validateDecisionTable()` - Validate table structure
- `validateRuleflow()` - Validate ruleflow XML
- `preValidateRuleBody()` - Pre-creation validation

## REST API Endpoints

### Health Check
```
GET /health
Response: {"status": "OK", "timestamp": "..."}
```

### Vocabularies
```
GET /vocabularies?project=<name>&baseline=<name>
Response: List of vocabulary info with locale and body
```

### Projects
```
POST /projects
Body: {"projectName": "MyProject"}
Response: {"status": "created", "projectName": "MyProject"}

DELETE /projects?projectName=<name>
Response: {"status": "deleted"}
```

### Rules
```
POST /rules
Body: {
  "projectName": "MyProject",
  "packageName": "rules",
  "ruleName": "MyRule",
  "ruleBody": "if ... then ...",
  "priority": 5
}

GET /rules?projectName=<name>&ruleName=<name>
Response: Rule details with body and priority
```

### Ruleflows
```
POST /ruleflows
Body: {
  "projectName": "MyProject",
  "packageName": "flows",
  "ruleflowName": "MyFlow",
  "drfBody": "<xml>...</xml>",
  "mainFlowTask": true
}
```

### Decision Tables
```
POST /decisiontables
Body: {
  "projectName": "MyProject",
  "packagePath": "tables",
  "tableName": "MyTable",
  "model": {
    "conditions": [...],
    "actions": [...]
  }
}
```

### Variables
```
POST /variables
Body: {
  "projectName": "MyProject",
  "packageName": "vars",
  "variableSetName": "MyVarSet",
  "variables": [
    {
      "name": "var1",
      "bomType": "java.lang.String",
      "verbalization": "Variable 1",
      "initialValue": "default"
    }
  ]
}

GET /variables?projectName=<name>&packageName=<name>
Response: List of variable sets with variables
```

### Operations
```
POST /operations
Body: {
  "projectName": "MyProject",
  "operationName": "MyOperation",
  "description": "Operation description",
  "ruleflowName": "MyFlow",
  "rulesetName": "MyRuleset",
  "parameters": [
    {
      "name": "input1",
      "direction": "IN",
      "bomType": "java.lang.String"
    }
  ]
}
```

## Configuration

Edit the configuration constants in `ODMHttpServer.java` and `Main.java`:

```java
private static final String DC_USERNAME = "odmAdmin";
private static final String DC_PASSWORD = "odmAdmin";
private static final String DC_URL = "http://my-odm.ibm.com:9060/decisioncenter-api";
private static final String DC_DATASOURCE = "jdbc/ilogDataSource";
```

## Dependencies

The project requires IBM ODM libraries (see `.classpath` for complete list):

### Core ODM Libraries
- `jrules-teamserver.jar` - Team Server API
- `jrules-engine.jar` - Rule engine
- `jrules-model-*.jar` - Model APIs
- `bdsl-dtx-core.jar` - Decision table support

### Spring Framework
- Spring Core, Context, Web, WS (6.2.12)
- Spring OXM, AOP

### Jakarta/XML
- Jakarta XML Bind API (4.0.2)
- JAXB Runtime (4.0.5)
- SAAJ Implementation (3.0.4)

### Utilities
- Jackson (2.16.0) - JSON processing
- Apache Commons (IO, Logging, Codec)
- SLF4J (1.7.25)
- Guava (32.0.1)

### Eclipse EMF
- EMF Core, Ecore, Common (2.31.0+)

## Building and Running

### Prerequisites
1. Java 21 JDK
2. IBM ODM 9.5+ installation
3. Access to Decision Center API

### Compile
```bash
# Using Eclipse: Import as existing project
# Or compile manually:
javac -cp "lib/*:." -d bin src/com/ibm/odm/regras/*.java
```

### Run HTTP Server
```bash
java -cp "bin:lib/*" com.ibm.odm.regras.ODMHttpServer
# Server starts on port 8080
```

### Run CLI Tool
```bash
java -cp "bin:lib/*" com.ibm.odm.regras.Main "DecisionServiceName" "%current_key"
```

## Usage Examples

### Create a Rule
```bash
curl -X POST http://localhost:8080/rules \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "LoanApproval",
    "packageName": "rules",
    "ruleName": "CheckCreditScore",
    "ruleBody": "if the credit score of the applicant is at least 700 then approve the loan",
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
    "baselineName": "Main",
    "locale": "en_US",
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

### Query Vocabularies
```bash
curl "http://localhost:8080/vocabularies?project=LoanApproval&baseline=Main"
```

## Validation Features

### Automatic Validation
All artifact creation/modification operations include automatic validation:

1. **Pre-validation**: Checks syntax before creation
2. **Post-validation**: Verifies structure after creation
3. **Validation Report**: Returns issues with severity levels (INFO, WARN, ERROR)

### Validation Checks

**Action Rules:**
- Non-empty body
- No placeholder markers (`<...>`)
- Balanced delimiters `()`, `{}`, `[]`
- Even number of quotes
- TODO/FIXME markers (warning)

**Decision Tables:**
- At least one condition column
- Non-empty column titles
- No duplicate titles
- Valid root partition
- At least one expression in rows

**Ruleflows:**
- Well-formed XML
- Valid root element
- Contains task/flow elements

## Error Handling

The API returns structured error responses:

```json
{
  "status": "error",
  "message": "Error description",
  "validation": {
    "valid": false,
    "issues": [
      {
        "severity": "ERROR",
        "message": "Body contains placeholders '<...>'",
        "location": "ActionRule.body"
      }
    ]
  }
}
```

## Advanced Features

### Baseline Resolution
Supports multiple baseline reference formats:
- `%current_key` - Current baseline
- `Main` - Main baseline
- Case-insensitive matching
- Alias support (`main`, `master`, `develop`)

### Nested Package Support
Automatically creates nested package structures:
```java
// Creates: com -> ibm -> rules
resolveOrCreateNestedPackage(session, "com.ibm.rules")
```

### Reflection-Based Compatibility
Uses reflection to support multiple ODM versions and fixpacks:
- Dynamic method resolution
- Feature detection
- Fallback mechanisms

### Batch Operations
Efficient batch processing for variables:
```java
addVariablesToSet(session, vset, variableList, branch)
// Single commit for all variables
```

## Troubleshooting

### Common Issues

1. **Connection Refused**
   - Verify DC_URL is correct
   - Check Decision Center is running
   - Verify network connectivity

2. **Authentication Failed**
   - Check DC_USERNAME and DC_PASSWORD
   - Verify user has appropriate permissions

3. **Artifact Not Found**
   - Verify baseline name is correct
   - Check project exists
   - Ensure working baseline is set

4. **Validation Errors**
   - Review validation report in response
   - Check for placeholder markers
   - Verify syntax of IRL/DRF

## Performance Considerations

- Use batch operations for multiple variables
- Reuse sessions when possible
- Set working baseline once per operation
- Commit in batches for large operations

## Security Notes

- Credentials are stored in plain text (development only)
- Use environment variables for production
- Implement proper authentication/authorization
- Use HTTPS for production deployments

## Contributing

When adding new features:
1. Follow existing service patterns
2. Add validation where appropriate
3. Include error handling
4. Update REST API endpoints
5. Document new methods

## License

This project is for IBM ODM integration purposes. Ensure compliance with IBM ODM licensing terms.

## Support

For issues related to:
- **ODM API**: Consult IBM ODM documentation
- **This toolkit**: Review source code comments and examples
- **Decision Center**: Check IBM support resources

## Version History

- **Current**: Java 21, ODM 9.5+ compatible
- Supports multiple ODM fixpack versions through reflection
- REST API with comprehensive validation

## References

- [IBM ODM Documentation](https://www.ibm.com/docs/en/odm)
- [Decision Center API Guide](https://www.ibm.com/docs/en/odm/9.0.0?topic=center-decision-api)
- [IRL Language Reference](https://www.ibm.com/docs/en/odm/9.0.0?topic=language-ilog-rule-reference)

---

**Note**: This is a development toolkit. Always test thoroughly in a non-production environment before deploying to production systems.