# ODM Tools - Architecture Documentation

## System Architecture

### High-Level Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Client Applications                      │
│              (REST API Consumers, CLI Tools)                 │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP/REST
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    ODMHttpServer (Port 8080)                 │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  REST Endpoints Layer                                │   │
│  │  /vocabularies /rules /ruleflows /decisiontables    │   │
│  │  /variables /operations /projects /health           │   │
│  └──────────────────────────────────────────────────────┘   │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐   │
│  │         Request Processing & Validation             │   │
│  │         (JSON parsing, parameter extraction)        │   │
│  └──────────────────────┬──────────────────────────────┘   │
└─────────────────────────┼────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    Service Layer                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Vocabulary   │  │ Rule         │  │ Ruleflow     │      │
│  │ Service      │  │ Service      │  │ Service      │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Decision     │  │ Variable     │  │ Operation    │      │
│  │ Table Svc    │  │ Service      │  │ Service      │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐   │
│  │         ODMArtifactValidator                        │   │
│  │    (Pre/Post validation, Syntax checking)           │   │
│  └──────────────────────┬──────────────────────────────┘   │
└─────────────────────────┼────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│              IBM ODM Decision Center API                     │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  IlrSession (Remote Session Factory)                │   │
│  │  IlrSessionHelper (Utility methods)                 │   │
│  └──────────────────────┬───────────────────────────────┘   │
│                         │                                    │
│  ┌──────────────────────┴───────────────────────────────┐   │
│  │  BRM Package (Business Rule Model)                  │   │
│  │  - IlrRuleProject, IlrBaseline                      │   │
│  │  - IlrActionRule, IlrRuleflow                       │   │
│  │  - IlrDecisionTable, IlrVariableSet                 │   │
│  └──────────────────────┬───────────────────────────────┘   │
└─────────────────────────┼────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│         IBM ODM Decision Center Repository                   │
│              (Database: jdbc/ilogDataSource)                 │
└─────────────────────────────────────────────────────────────┘
```

## Component Details

### 1. REST API Layer (ODMHttpServer)

**Responsibilities:**
- HTTP request handling
- Route management
- Request/response serialization (JSON)
- CORS handling
- Error response formatting

**Key Components:**
- `HttpServer` (Java built-in)
- `ObjectMapper` (Jackson) for JSON
- Context handlers for each endpoint

**Design Patterns:**
- Handler pattern for endpoints
- Singleton for services
- Factory pattern for session creation

### 2. Service Layer

#### 2.1 ODMVocabularyService

**Purpose:** Retrieve BOM vocabularies from Decision Center

**Key Responsibilities:**
- Session management
- Baseline resolution
- Vocabulary querying
- Locale handling

**Data Flow:**
```
Client Request → Open Session → Set Baseline → Query Vocabularies → Close Session → Return Results
```

**Key Classes Used:**
- `IlrVocabulary`
- `IlrBaseline`
- `DataFinder`

#### 2.2 ODMRuleService

**Purpose:** Manage Action Rules lifecycle

**Key Responsibilities:**
- Rule creation/deletion
- Rule body updates (IRL)
- Priority management
- Lock/unlock operations

**Data Flow:**
```
Create: Session → Project → Baseline → Package → Create Rule → Commit
Update: Session → Find Rule → Lock → Update → Commit → Unlock
```

**Key Classes Used:**
- `IlrActionRule`
- `IlrDefinition`
- `IlrCommitableObject`

#### 2.3 ODMRuleflowService

**Purpose:** Manage Ruleflows (DRF XML)

**Key Responsibilities:**
- Ruleflow creation
- DRF body management
- Package association
- MainFlowTask flag setting

**Data Flow:**
```
Create: Session → Project → Package → Create Ruleflow → Set Name → Associate Package → Set Body → Commit
```

**Key Classes Used:**
- `IlrRuleflow`
- `IlrRulePackage`

#### 2.4 ODMDecisionTableService

**Purpose:** Comprehensive Decision Table management

**Key Responsibilities:**
- Table structure creation
- Column management (conditions/actions)
- Row/partition management
- Expression handling

**Data Flow:**
```
Create: Session → Project → Package → Create DT → Add Columns → Add Rows → Set Actions → Persist
```

**Key Classes Used:**
- `IlrDecisionTable`
- `IlrDTController`
- `IlrDTModel`
- `IlrDTPartitionDefinition`
- `IlrDTActionDefinition`

**Complex Operations:**
- Hierarchical partition trees
- Expression instances
- Action sets at leaf nodes

#### 2.5 ODMVariableService

**Purpose:** Variable Set and Variable management

**Key Responsibilities:**
- Variable Set creation
- Variable addition (batch)
- Nested package resolution
- Type management

**Data Flow:**
```
Create: Session → Project → Resolve Package → Create VarSet → Add Variables (batch) → Commit
```

**Key Classes Used:**
- `IlrVariableSet`
- `IlrVariable`
- `IlrCommitableObject`

**Special Features:**
- Reflection-based compatibility
- Batch operations for performance
- Nested package support (a.b.c)

#### 2.6 ODMOperationService

**Purpose:** Decision Service Operation management

**Key Responsibilities:**
- Operation creation (DSM)
- Parameter management
- Ruleflow association
- Ruleset configuration

**Data Flow:**
```
Create: Session → Create Operation → Add Parameters → Link Ruleflow → Set Ruleset → Commit
```

**Key Classes Used:**
- DSM Package (via reflection)
- `IlrElementHandle`
- `IlrElementDetails`
- `IlrCommitableObject`

**Special Features:**
- DSM metamodel access via reflection
- Multi-version compatibility
- Fallback mechanisms

### 3. Validation Layer (ODMArtifactValidator)

**Purpose:** Ensure artifact quality and correctness

**Validation Types:**

#### 3.1 Action Rule Validation
- Body not empty
- No placeholders (`<...>`)
- Balanced delimiters: `()`, `{}`, `[]`
- Even number of quotes
- TODO/FIXME detection (warning)

#### 3.2 Decision Table Validation
- At least one condition column
- Non-empty column titles
- No duplicate titles
- Valid root partition
- At least one expression

#### 3.3 Ruleflow Validation
- Well-formed XML
- Valid root element
- Contains task/flow elements

**Validation Flow:**
```
Pre-validation → Artifact Creation → Post-validation → Report Generation
```

**Severity Levels:**
- `ERROR`: Blocks operation
- `WARN`: Allows operation with warning
- `INFO`: Informational only

### 4. Utility Components

#### 4.1 DTJsonBuilder

**Purpose:** Convert JSON model to Decision Table structure

**Responsibilities:**
- Parse JSON conditions/actions
- Create column definitions
- Build partition hierarchy
- Set action values

#### 4.2 Session Management

**Pattern:** Try-with-resources equivalent (manual)

```java
IlrSession session = null;
try {
    session = openSession();
    // operations
} finally {
    closeSession(session);
}
```

## Data Models

### Request/Response Models

#### Rule Creation Request
```json
{
  "projectName": "string",
  "packageName": "string",
  "ruleName": "string",
  "ruleBody": "string (IRL)",
  "priority": "integer"
}
```

#### Decision Table Request
```json
{
  "projectName": "string",
  "packagePath": "string",
  "tableName": "string",
  "baselineName": "string",
  "locale": "string",
  "model": {
    "preconditions": [],
    "conditions": [
      {
        "title": "string",
        "statement": "string",
        "type": "string",
        "children": {}
      }
    ],
    "actions": [
      {
        "title": "string",
        "statement": "string",
        "valuesByPath": {}
      }
    ]
  }
}
```

#### Variable Set Request
```json
{
  "projectName": "string",
  "packageName": "string",
  "variableSetName": "string",
  "variables": [
    {
      "name": "string",
      "bomType": "string",
      "verbalization": "string",
      "initialValue": "string"
    }
  ]
}
```

#### Operation Request
```json
{
  "projectName": "string",
  "operationName": "string",
  "description": "string",
  "ruleflowName": "string",
  "rulesetName": "string",
  "parameters": [
    {
      "name": "string",
      "direction": "IN|OUT|INOUT",
      "bomType": "string"
    }
  ],
  "rulesetParameters": {}
}
```

### Internal Models

#### ValidationReport
```java
class ValidationReport {
    List<ValidationIssue> issues;
    boolean isValid();
    Map<String, Long> summaryBySeverity();
}
```

#### ValidationIssue
```java
class ValidationIssue {
    Severity severity;  // ERROR, WARN, INFO
    String message;
    String location;
}
```

## Design Patterns

### 1. Service Pattern
Each domain (Rules, Ruleflows, etc.) has a dedicated service class encapsulating all operations.

### 2. Factory Pattern
Session creation uses factory pattern:
```java
IlrRemoteSessionFactory factory = new IlrRemoteSessionFactory();
factory.connect(user, pass, url, datasource);
IlrSession session = factory.getSession();
```

### 3. Builder Pattern
Decision Table construction uses builder-like approach:
```java
addConditionColumn() → addRowToColumn() → addActionColumn() → setActionAtPath()
```

### 4. Strategy Pattern
Validation strategies for different artifact types:
- `validateActionRule()`
- `validateDecisionTable()`
- `validateRuleflow()`

### 5. Template Method Pattern
Common session management pattern across services:
```java
public Result operation() {
    IlrSession session = null;
    try {
        session = openSession();
        // specific operation
        return result;
    } finally {
        closeSession(session);
    }
}
```

## Error Handling Strategy

### Levels of Error Handling

1. **API Level**: HTTP status codes and JSON error responses
2. **Service Level**: Exception translation and logging
3. **ODM API Level**: Native exception handling

### Exception Hierarchy

```
Exception
├── IlrConnectException (connection failures)
├── IlrObjectNotFoundException (artifact not found)
├── IlrApplicationException (ODM API errors)
├── IlrPermissionException (access denied)
└── IllegalArgumentException (validation errors)
```

### Error Response Format

```json
{
  "status": "error",
  "message": "Human-readable error",
  "errorType": "ExceptionClassName",
  "validation": {
    "valid": false,
    "issues": [...]
  }
}
```

## Performance Considerations

### 1. Session Reuse
- Sessions are expensive to create
- Reuse within operation scope
- Always close in finally block

### 2. Batch Operations
- Use `addVariablesToSet()` for multiple variables
- Single commit for batch operations
- Reduces network round-trips

### 3. Baseline Caching
- Set working baseline once per operation
- Avoid repeated baseline lookups

### 4. Query Optimization
- Use specific search criteria
- Limit scope when possible
- Use ELEMENT_DETAILS vs ELEMENT_HANDLE appropriately

## Security Architecture

### Authentication
- Basic authentication to Decision Center
- Credentials in configuration (development)
- Should use environment variables in production

### Authorization
- Relies on ODM's built-in RBAC
- User permissions enforced by Decision Center
- Lock/unlock mechanism for concurrent access

### Data Protection
- No sensitive data logging
- Credentials not exposed in responses
- HTTPS recommended for production

## Scalability Considerations

### Horizontal Scaling
- Stateless REST API design
- No session affinity required
- Can run multiple instances

### Vertical Scaling
- Thread-safe service implementations
- Connection pooling (if implemented)
- Memory management for large operations

### Limitations
- Single Decision Center instance
- Network latency to DC
- ODM API rate limits

## Monitoring and Observability

### Health Check
```
GET /health
```
Returns server status and timestamp.

### Logging
- Console output for operations
- Debug logs for troubleshooting
- Error stack traces

### Metrics (Future Enhancement)
- Request count per endpoint
- Response times
- Error rates
- Validation failure rates

## Extension Points

### Adding New Endpoints
1. Create handler method in `ODMHttpServer`
2. Register context: `server.createContext("/path", handler)`
3. Implement request parsing and response formatting

### Adding New Services
1. Create service class following existing pattern
2. Implement session management
3. Add validation if applicable
4. Expose via REST endpoint

### Custom Validation Rules
1. Extend `ODMArtifactValidator`
2. Add new validation methods
3. Integrate into creation/update flows

## Technology Stack

### Core Technologies
- **Java 21**: Modern Java features
- **IBM ODM 9.5+**: Decision management platform
- **Jackson 2.16**: JSON processing
- **Java HTTP Server**: Built-in HTTP server

### Frameworks
- **Spring Framework 6.2**: Dependency injection (ODM libs)
- **Eclipse EMF**: Model framework (ODM requirement)

### Build Tools
- **Eclipse**: IDE and build system
- **Java Compiler**: javac

## Deployment Architecture

### Development
```
Developer Machine → Eclipse → ODM Tools → Local DC Instance
```

### Production (Recommended)
```
Load Balancer → Multiple ODM Tools Instances → ODM Decision Center Cluster
```

### Container Deployment
```dockerfile
FROM openjdk:21-jdk
COPY lib/ /app/lib/
COPY bin/ /app/bin/
WORKDIR /app
CMD ["java", "-cp", "bin:lib/*", "com.ibm.odm.regras.ODMHttpServer"]
```

## Future Enhancements

1. **Connection Pooling**: Reuse sessions across requests
2. **Async Operations**: Non-blocking API calls
3. **Caching**: Cache vocabularies and metadata
4. **Metrics**: Prometheus/Grafana integration
5. **Authentication**: JWT/OAuth support
6. **Rate Limiting**: Protect against abuse
7. **Batch Endpoints**: Process multiple artifacts
8. **WebSocket**: Real-time updates
9. **GraphQL**: Alternative query interface
10. **OpenAPI**: API documentation generation

---

This architecture supports the current requirements while providing flexibility for future enhancements and scaling needs.