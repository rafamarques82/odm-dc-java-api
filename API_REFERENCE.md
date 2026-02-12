# ODM Tools - API Reference

## Base URL
```
http://localhost:8080
```

## Authentication
Currently uses basic authentication configured in the server. Credentials are set in the configuration:
- Username: `odmAdmin`
- Password: `odmAdmin`

---

## Endpoints

### 1. Health Check

#### GET /health
Check if the server is running.

**Response:**
```json
{
  "status": "OK",
  "timestamp": "2026-02-12T19:00:00Z"
}
```

**Status Codes:**
- `200 OK`: Server is healthy

---

### 2. Vocabularies

#### GET /vocabularies
Retrieve BOM vocabularies from a Decision Service baseline.

**Query Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| project | string | Yes | Decision Service name |
| baseline | string | No | Baseline name (default: %current_key) |

**Example Request:**
```bash
curl "http://localhost:8080/vocabularies?project=LoanApproval&baseline=Main"
```

**Response:**
```json
{
  "projectName": "LoanApproval",
  "baselineName": "Main",
  "vocabularies": [
    {
      "decisionService": "LoanApproval",
      "baseline": "Main",
      "locale": "en_US",
      "body": "vocabulary content..."
    }
  ]
}
```

**Status Codes:**
- `200 OK`: Success
- `400 Bad Request`: Missing required parameters
- `404 Not Found`: Project or baseline not found
- `500 Internal Server Error`: Server error

---

### 3. Projects

#### POST /projects
Create a new Decision Service project.

**Request Body:**
```json
{
  "projectName": "MyProject"
}
```

**Response:**
```json
{
  "status": "created",
  "projectName": "MyProject"
}
```

**Status Codes:**
- `200 OK`: Project created or already exists
- `400 Bad Request`: Invalid request body
- `500 Internal Server Error`: Creation failed

---

#### DELETE /projects
Delete a Decision Service project.

**Query Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| projectName | string | Yes | Name of project to delete |

**Example Request:**
```bash
curl -X DELETE "http://localhost:8080/projects?projectName=MyProject"
```

**Response:**
```json
{
  "status": "deleted",
  "projectName": "MyProject"
}
```

**Status Codes:**
- `200 OK`: Project deleted
- `404 Not Found`: Project not found
- `500 Internal Server Error`: Deletion failed

---

### 4. Rules

#### POST /rules
Create a new Action Rule.

**Request Body:**
```json
{
  "projectName": "LoanApproval",
  "packageName": "rules",
  "ruleName": "CheckCreditScore",
  "ruleBody": "if the credit score of 'the applicant' is at least 700 then approve 'the loan';",
  "priority": 10,
  "baselineName": "Main"
}
```

**Field Descriptions:**
- `projectName`: Target Decision Service
- `packageName`: Package path (can be nested: "a.b.c")
- `ruleName`: Unique rule name
- `ruleBody`: IRL (ILOG Rule Language) code
- `priority`: Rule priority (integer, default: 0)
- `baselineName`: Target baseline (optional, default: current)

**Response:**
```json
{
  "status": "created",
  "ruleName": "CheckCreditScore",
  "validation": {
    "valid": true,
    "issues": []
  }
}
```

**Validation Response (with issues):**
```json
{
  "status": "created",
  "ruleName": "CheckCreditScore",
  "validation": {
    "valid": false,
    "issues": [
      {
        "severity": "WARN",
        "message": "Body contains TODO markers",
        "location": "ActionRule.body"
      }
    ]
  }
}
```

**Status Codes:**
- `200 OK`: Rule created
- `400 Bad Request`: Validation failed or invalid parameters
- `500 Internal Server Error`: Creation failed

---

#### GET /rules
Retrieve details of an Action Rule.

**Query Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| projectName | string | Yes | Decision Service name |
| ruleName | string | Yes | Rule name |
| baselineName | string | No | Baseline name |

**Example Request:**
```bash
curl "http://localhost:8080/rules?projectName=LoanApproval&ruleName=CheckCreditScore"
```

**Response:**
```json
{
  "name": "CheckCreditScore",
  "package": "rules",
  "priority": "10",
  "body": "if the credit score of 'the applicant' is at least 700 then approve 'the loan';"
}
```

**Status Codes:**
- `200 OK`: Rule found
- `404 Not Found`: Rule not found
- `500 Internal Server Error`: Query failed

---

#### PUT /rules
Update an existing Action Rule.

**Request Body:**
```json
{
  "projectName": "LoanApproval",
  "ruleName": "CheckCreditScore",
  "newRuleBody": "if the credit score of 'the applicant' is at least 750 then approve 'the loan';",
  "newPriority": 15,
  "newName": "CheckCreditScoreStrict"
}
```

**Response:**
```json
{
  "status": "updated",
  "ruleName": "CheckCreditScoreStrict"
}
```

**Status Codes:**
- `200 OK`: Rule updated
- `404 Not Found`: Rule not found
- `500 Internal Server Error`: Update failed

---

### 5. Ruleflows

#### POST /ruleflows
Create or update a Ruleflow.

**Request Body:**
```json
{
  "projectName": "LoanApproval",
  "packageName": "flows",
  "ruleflowName": "LoanApprovalFlow",
  "drfBody": "<?xml version=\"1.0\" encoding=\"UTF-8\"?><flow>...</flow>",
  "mainFlowTask": true,
  "baselineName": "Main"
}
```

**Field Descriptions:**
- `projectName`: Target Decision Service
- `packageName`: Package path
- `ruleflowName`: Unique ruleflow name
- `drfBody`: DRF XML content
- `mainFlowTask`: Set as main flow task (boolean)
- `baselineName`: Target baseline (optional)

**Response:**
```json
{
  "status": "created",
  "ruleflowName": "LoanApprovalFlow",
  "validation": {
    "valid": true,
    "issues": []
  }
}
```

**Status Codes:**
- `200 OK`: Ruleflow created/updated
- `400 Bad Request`: Invalid XML or parameters
- `500 Internal Server Error`: Operation failed

---

#### GET /ruleflows
Retrieve Ruleflow details.

**Query Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| projectName | string | Yes | Decision Service name |
| packageName | string | Yes | Package name |
| ruleflowName | string | Yes | Ruleflow name |

**Response:**
```json
{
  "name": "LoanApprovalFlow",
  "package": "flows",
  "mainFlowTask": true,
  "locale": "en_US",
  "bodyPresent": true
}
```

**Status Codes:**
- `200 OK`: Ruleflow found
- `404 Not Found`: Ruleflow not found
- `500 Internal Server Error`: Query failed

---

### 6. Decision Tables

#### POST /decisiontables
Create a Decision Table from JSON model.

**Request Body:**
```json
{
  "projectName": "LoanApproval",
  "packagePath": "tables",
  "tableName": "LoanDecision",
  "baselineName": "Main",
  "locale": "en_US",
  "resetIfExists": false,
  "model": {
    "preconditions": [
      {
        "statement": "the applicant is not null"
      }
    ],
    "conditions": [
      {
        "title": "Credit Score",
        "statement": "the credit score of 'the applicant'",
        "type": "range:number",
        "partitions": [
          {"type": "between", "min": 0, "max": 600},
          {"type": "between", "min": 600, "max": 700},
          {"type": "atLeast", "value": 700}
        ]
      },
      {
        "title": "Income",
        "statement": "the annual income of 'the applicant'",
        "type": "range:number"
      }
    ],
    "actions": [
      {
        "title": "Decision",
        "statement": "set the decision to <a string>",
        "valuesByPath": {
          "[0,0]": "Reject",
          "[1,0]": "Review",
          "[2,0]": "Approve"
        }
      },
      {
        "title": "Interest Rate",
        "statement": "set the interest rate to <a number>",
        "valuesByPath": {
          "[0,0]": 0,
          "[1,0]": 8.5,
          "[2,0]": 5.5
        }
      }
    ]
  }
}
```

**Field Descriptions:**
- `projectName`: Target Decision Service
- `packagePath`: Package path (nested supported)
- `tableName`: Unique table name
- `baselineName`: Target baseline
- `locale`: Locale for verbalization (default: en_US)
- `resetIfExists`: Delete and recreate if exists (boolean)
- `model`: Table structure definition

**Model Structure:**
- `preconditions`: Array of precondition statements
- `conditions`: Array of condition column definitions
  - `title`: Column header
  - `statement`: BOM expression
  - `type`: Column type (range:number, range:string, etc.)
  - `partitions`: Partition definitions
  - `children`: Nested columns (for hierarchical tables)
- `actions`: Array of action column definitions
  - `title`: Column header
  - `statement`: Action template
  - `valuesByPath`: Map of path to value

**Response:**
```json
{
  "status": "created",
  "tableName": "LoanDecision",
  "validation": {
    "valid": true,
    "issues": []
  }
}
```

**Status Codes:**
- `200 OK`: Table created
- `400 Bad Request`: Invalid model or validation failed
- `500 Internal Server Error`: Creation failed

---

#### GET /decisiontables
Retrieve Decision Table details.

**Query Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| projectName | string | Yes | Decision Service name |
| packagePath | string | Yes | Package path |
| tableName | string | Yes | Table name |

**Response:**
```json
{
  "name": "LoanDecision",
  "package": "tables",
  "conditionCount": 2,
  "actionCount": 2,
  "rowCount": 3
}
```

**Status Codes:**
- `200 OK`: Table found
- `404 Not Found`: Table not found
- `500 Internal Server Error`: Query failed

---

### 7. Variables

#### POST /variables
Create a Variable Set with variables.

**Request Body:**
```json
{
  "projectName": "LoanApproval",
  "packageName": "variables",
  "variableSetName": "LoanVariables",
  "baselineName": "Main",
  "clearExisting": false,
  "variables": [
    {
      "name": "applicantName",
      "bomType": "java.lang.String",
      "verbalization": "the applicant name",
      "initialValue": ""
    },
    {
      "name": "creditScore",
      "bomType": "java.lang.Integer",
      "verbalization": "the credit score",
      "initialValue": "0"
    },
    {
      "name": "loanAmount",
      "bomType": "java.lang.Double",
      "verbalization": "the loan amount",
      "initialValue": "0.0"
    }
  ]
}
```

**Field Descriptions:**
- `projectName`: Target Decision Service
- `packageName`: Package path (nested supported)
- `variableSetName`: Unique variable set name
- `baselineName`: Target baseline
- `clearExisting`: Clear existing variables before adding (boolean)
- `variables`: Array of variable definitions
  - `name`: Variable name
  - `bomType`: Java type (fully qualified)
  - `verbalization`: Natural language description
  - `initialValue`: Default value (string representation)

**Response:**
```json
{
  "status": "created",
  "variableSetName": "LoanVariables",
  "variablesAdded": 3
}
```

**Status Codes:**
- `200 OK`: Variables created
- `400 Bad Request`: Invalid parameters
- `500 Internal Server Error`: Creation failed

---

#### GET /variables
List all Variable Sets in a project.

**Query Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| projectName | string | Yes | Decision Service name |
| baselineName | string | No | Baseline name |
| packageName | string | No | Filter by package |

**Example Request:**
```bash
curl "http://localhost:8080/variables?projectName=LoanApproval&packageName=variables"
```

**Response:**
```json
{
  "projectName": "LoanApproval",
  "baselineName": "Main",
  "packageFilter": "variables",
  "variableSets": [
    {
      "name": "LoanVariables",
      "package": "variables",
      "packageQualified": "variables",
      "variables": [
        {
          "name": "applicantName",
          "bomType": "java.lang.String",
          "verbalization": "the applicant name",
          "initialValue": ""
        }
      ],
      "variableCount": 3
    }
  ],
  "variableSetCount": 1
}
```

**Status Codes:**
- `200 OK`: Success
- `500 Internal Server Error`: Query failed

---

### 8. Variable Sets

#### POST /variablesets
Create an empty Variable Set.

**Request Body:**
```json
{
  "projectName": "LoanApproval",
  "packageName": "variables",
  "variableSetName": "EmptyVarSet",
  "baselineName": "Main"
}
```

**Response:**
```json
{
  "status": "created",
  "variableSetName": "EmptyVarSet"
}
```

**Status Codes:**
- `200 OK`: Variable Set created
- `400 Bad Request`: Invalid parameters
- `500 Internal Server Error`: Creation failed

---

### 9. Operations

#### POST /operations
Create or update a Decision Service Operation.

**Request Body:**
```json
{
  "projectName": "LoanApproval",
  "baselineName": "Main",
  "operationName": "approveLoan",
  "description": "Loan approval decision operation",
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
    },
    {
      "name": "loanDetails",
      "direction": "INOUT",
      "bomType": "loan.LoanDetails"
    }
  ],
  "rulesetParameters": {
    "trace.enabled": "true",
    "trace.level": "INFO"
  }
}
```

**Field Descriptions:**
- `projectName`: Target Decision Service
- `baselineName`: Target baseline
- `operationName`: Unique operation name
- `description`: Operation description
- `ruleflowName`: Associated ruleflow (optional)
- `rulesetName`: Ruleset name for deployment
- `parameters`: Array of operation parameters
  - `name`: Parameter name
  - `direction`: IN, OUT, or INOUT
  - `bomType`: BOM type (fully qualified)
- `rulesetParameters`: Key-value pairs for ruleset configuration

**Response:**
```json
{
  "status": "saved",
  "operationName": "approveLoan",
  "approach": "dsm_operation",
  "dsmRuleflowLinked": "LoanApprovalFlow",
  "descriptorUpdatedCount": 2,
  "parametersUpdatedCount": 3,
  "rulesetParametersUpdatedCount": 2
}
```

**Status Codes:**
- `200 OK`: Operation created/updated
- `400 Bad Request`: Invalid parameters
- `500 Internal Server Error`: Operation failed

---

#### GET /operations
Retrieve Operation details.

**Query Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| projectName | string | Yes | Decision Service name |
| operationName | string | Yes | Operation name |
| baselineName | string | No | Baseline name |

**Response:**
```json
{
  "projectName": "LoanApproval",
  "baselineName": "Main",
  "operationName": "approveLoan",
  "descriptor": {
    "ruleflow.package": "flows",
    "ruleflow.name": "LoanApprovalFlow",
    "ruleset.name": "loan-approval-ruleset"
  },
  "parameters": [
    {
      "name": "applicant",
      "direction": "IN",
      "bomType": "loan.Applicant"
    }
  ],
  "rulesetParameters": {
    "trace.enabled": "true"
  }
}
```

**Status Codes:**
- `200 OK`: Operation found
- `404 Not Found`: Operation not found
- `500 Internal Server Error`: Query failed

---

## Common Response Patterns

### Success Response
```json
{
  "status": "success|created|updated|deleted",
  "message": "Optional success message",
  "data": {}
}
```

### Error Response
```json
{
  "status": "error",
  "message": "Error description",
  "errorType": "ExceptionClassName",
  "stackTrace": "Stack trace (in debug mode)"
}
```

### Validation Response
```json
{
  "validation": {
    "valid": true|false,
    "issues": [
      {
        "severity": "ERROR|WARN|INFO",
        "message": "Issue description",
        "location": "Artifact.field"
      }
    ],
    "summary": {
      "ERROR": 0,
      "WARN": 1,
      "INFO": 0
    }
  }
}
```

---

## HTTP Status Codes

| Code | Meaning | Usage |
|------|---------|-------|
| 200 | OK | Successful operation |
| 400 | Bad Request | Invalid parameters or validation failed |
| 404 | Not Found | Resource not found |
| 500 | Internal Server Error | Server or ODM API error |

---

## Rate Limiting

Currently no rate limiting is implemented. Consider implementing rate limiting for production use.

---

## CORS

CORS headers are included in responses to allow cross-origin requests from web applications.

---

## Best Practices

### 1. Baseline Management
- Use `%current_key` for current baseline
- Specify baseline explicitly for production operations
- Use consistent baseline names across operations

### 2. Naming Conventions
- Use descriptive names for artifacts
- Avoid special characters in names
- Use consistent casing (camelCase or snake_case)

### 3. Validation
- Always check validation results
- Address ERROR severity issues before deployment
- Review WARN issues for potential problems

### 4. Error Handling
- Implement retry logic for transient failures
- Log errors for troubleshooting
- Handle 404 errors gracefully

### 5. Performance
- Use batch operations when possible
- Reuse connections where applicable
- Avoid unnecessary queries

---

## Examples

### Complete Workflow: Create Rule with Validation

```bash
# 1. Create project
curl -X POST http://localhost:8080/projects \
  -H "Content-Type: application/json" \
  -d '{"projectName": "MyProject"}'

# 2. Create rule
curl -X POST http://localhost:8080/rules \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "MyProject",
    "packageName": "rules",
    "ruleName": "MyRule",
    "ruleBody": "if true then print \"Hello\";",
    "priority": 5
  }'

# 3. Verify rule
curl "http://localhost:8080/rules?projectName=MyProject&ruleName=MyRule"
```

### Create Decision Table with Multiple Conditions

```bash
curl -X POST http://localhost:8080/decisiontables \
  -H "Content-Type: application/json" \
  -d '{
    "projectName": "Insurance",
    "packagePath": "tables",
    "tableName": "PremiumCalculation",
    "model": {
      "conditions": [
        {
          "title": "Age",
          "statement": "the age of the customer",
          "type": "range:number"
        },
        {
          "title": "Risk Level",
          "statement": "the risk level",
          "type": "string"
        }
      ],
      "actions": [
        {
          "title": "Premium",
          "statement": "set the premium to <a number>"
        }
      ]
    }
  }'
```

---

## Troubleshooting

### Common Issues

**Issue**: "Project not found"
- **Solution**: Verify project name is correct and exists in Decision Center

**Issue**: "Validation failed with ERROR"
- **Solution**: Check validation.issues in response for specific problems

**Issue**: "Connection refused"
- **Solution**: Verify Decision Center URL and ensure DC is running

**Issue**: "Authentication failed"
- **Solution**: Check credentials in server configuration

---

## Version Information

- **API Version**: 1.0
- **ODM Compatibility**: 9.5+
- **Java Version**: 21

---

For more information, see [README.md](README.md) and [ARCHITECTURE.md](ARCHITECTURE.md).