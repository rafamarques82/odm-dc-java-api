package com.ibm.odm.regras;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// ==== Decision Table (DT) ====
// ==== BRM / Team Server (regras, vocabularies, ruleflows) ====
import ilog.rules.teamserver.brm.*;
import ilog.rules.teamserver.model.*;
import ilog.rules.dt.IlrDTController;
import ilog.rules.dt.IlrDTExpressionManager;
import ilog.rules.dt.model.*;
import ilog.rules.dt.model.expression.*;
import ilog.rules.dt.model.helper.IlrDTPropertyHelper;
import ilog.rules.dt.model.helper.IlrDTHelper;
// ==== XML parsing for ruleflow semantic validation ====
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
/**
 * Servidor HTTP unificado para:
 * - Decision Tables (/decisiontables)
 * - Vocabularies (/vocabularies)
 * - Projects (/projects)
 * - Rules (/rules)
 * - Ruleflows (/ruleflows)
 * - Health (/health)
 *
 * Com validações:
 * - Pós-criação/edição (Action Rule / Ruleflow / Decision Table) via ODMArtifactValidator
 * - Pré-validação semântica dos statements contra o vocabulary/BOM (DT) e para Rules/Ruleflows
 */
public class ODMHttpServer {
    // ===========================
    // CONFIG (credenciais e DC)
    // ===========================
    private static final String DC_USERNAME = "odmAdmin";
    private static final String DC_PASSWORD = "odmAdmin";
    private static final String DC_URL = "http://my-odm.ibm.com:9060/decisioncenter-api";
    private static final String DC_DATASOURCE = "jdbc/ilogDataSource";
    private static final ObjectMapper mapper = new ObjectMapper();

    // ===========================
    // Baseline helper
    // ===========================
    /** Se a baseline/branch for "main" (qualquer caixa), usa "%current_key". */
    private static String normalizeBaseline(String baseline) {
        if (baseline == null) return "%current_key"; // default
        String t = baseline.trim();
        return "main".equalsIgnoreCase(t) ? "%current_key" : t;
    }

    // ===========================
    // Validador
    // ===========================
    private static final ODMArtifactValidator VALIDATOR = new ODMArtifactValidator();
    private static List<Map<String, Object>> toIssueList(ODMArtifactValidator.ValidationReport rep) {
        List<Map<String, Object>> arr = new ArrayList<>();
        if (rep != null) {
            for (ODMArtifactValidator.ValidationIssue i : rep.getIssues()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("severity", i.getSeverity().name());
                m.put("message", i.getMessage());
                m.put("location", i.getLocation());
                arr.add(m);
            }
        }
        return arr;
    }

    // ===========================
    // MAIN: registra TODOS os contexts
    // ===========================
    public static void main(String[] args) throws IOException {
        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/vocabularies", ODMHttpServer::handleVocabularies);
        server.createContext("/projects", ODMHttpServer::handleProjects);
        server.createContext("/rules", ODMHttpServer::handleRules);
        server.createContext("/health", ODMHttpServer::handleHealth);
        server.createContext("/ruleflows", ODMHttpServer::handleRuleflows);
        server.createContext("/decisiontables", ODMHttpServer::handleDecisionTables);
        server.createContext("/variables", ODMHttpServer::handleVariables);
        server.createContext("/operations", ODMHttpServer::handleOperations);

        // >>> NOVO ENDPOINT: Test Suites
        server.createContext("/testsuites", ODMHttpServer::handleTestSuites);
        
        // >>> NOVO ENDPOINT: Variable Sets
        server.createContext("/variablesets", ODMHttpServer::handleVariableSets);


        server.setExecutor(null);
        System.out.println("Servidor HTTP iniciado na porta " + port);
        server.start();
    }


    
// ============================================================
    // ====================== DECISION TABLES =====================
    // ============================================================
    private static void handleDecisionTables(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        try {
            switch (method) {
                case "POST": handleCreateOrUpdateDecisionTable(ex, q, true); break;
                case "PUT": handleCreateOrUpdateDecisionTable(ex, q, false); break;
                case "GET": handleViewDecisionTable(ex, q); break;
                default: {
                    Map<String,String> m = mapOf("error", "Método não suportado");
                    sendDecisionTablesJson(ex, 405, castMap(m));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Map<String,Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getName());
            err.put("message", e.getMessage());
            sendDecisionTablesJson(ex, 500, err);
        }
    }

    private static void handleCreateOrUpdateDecisionTable(HttpExchange ex, Map<String, String> q, boolean creating) throws Exception {
        String bodyRaw = readBodyAsString(ex);
        JsonNode node = safeParseJson(bodyRaw);
        String projectName = text(node, "projectName");
        String packageName = text(node, "packageName");
        String tableName = text(node, "tableName");
        String baselineName= normalizeBaseline(textOr("Main", node, "baselineName"));
        String localeStr = textOr("en-US", node, "locale");
        boolean resetIfExists = node != null && node.has("resetIfExists") && node.get("resetIfExists").asBoolean(false);
        JsonNode modelNode = (node != null ? node.get("model") : null);
        // --- PRÉ-VALIDAÇÃO SEMÂNTICA (BOM/Vocabulary) ---
        List<String> stmts = collectDTStatements(modelNode);
        if (!stmts.isEmpty()) {
            Set<String> vocabTokens;
            try {
                vocabTokens = loadVocabularyTokens(projectName, baselineName);
            } catch (Exception e) {
                Map<String,Object> err = new LinkedHashMap<>();
                err.put("error", "Falha ao carregar vocabulário do BOM");
                err.put("message", e.getMessage() == null ? "<sem mensagem>" : e.getMessage());
                sendDecisionTablesJson(ex, 500, err);
                return;
            }
            Map<String, Object> invalids = new LinkedHashMap<>();
            for (String s : stmts) {
                List<String> missing = validateStatementAgainstVocabulary(s, vocabTokens);
                if (!missing.isEmpty()) {
                    invalids.put(s, missing);
                }
            }
            if (!invalids.isEmpty()) {
                Map<String,Object> resp = new LinkedHashMap<>();
                resp.put("error", "Statements não compatíveis com a verbalização do BOM da baseline");
                resp.put("details", invalids);
                resp.put("hint", "Ajuste os termos para casar com o vocabulary do Decision Service/baseline (verbalização do BOM).");
             //   sendDecisionTablesJson(ex, 422, resp);
            //    return; // aborta criação/atualização da DT
            }
        }
        // --- FIM PRÉ-VALIDAÇÃO ---
        ODMDecisionTableService service = new ODMDecisionTableService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);
        IlrSession session = null;
        try {
            session = service.openSession();
            IlrDecisionTable table = service.openOrCreateDecisionTable(
                session, projectName, packageName, tableName, baselineName,
                Locale.forLanguageTag(localeStr), resetIfExists
            );
            Locale reqLocale = Locale.forLanguageTag(localeStr);
            IlrDTController ctrl = service.getDTController(session, table, reqLocale);
            IlrDTModel dtModel = ctrl.getDTModel();
            List<List<List<String>>> partitionsPerColumn = new ArrayList<>();
            if (modelNode != null && !modelNode.isNull()) {
                // 1) Reset hard + persist + reopen
                service.hardResetDecisionTable(dtModel);
                service.persistDT(session, table, ctrl);
                ctrl = service.getDTController(session, table, reqLocale);
                dtModel = ctrl.getDTModel();
                // 2) Precondições (opcional)
                JsonNode preconds = modelNode.get("preconditions");
                if (preconds != null && preconds.isArray() && preconds.size() > 0) {
                    service.setPreconditions(dtModel, text(preconds.get(0), "statement"));
                }
                // 3) Colunas de condições + partitions
                List<IlrDTPartitionDefinition> columns = new ArrayList<>();
                partitionsPerColumn.clear();
                JsonNode conds = modelNode.get("conditions");
                if (conds != null && conds.isArray()) {
                    for (JsonNode cond : conds) {
                        IlrDTPartitionDefinition colDef = service.addConditionColumn(
                            dtModel,
                            text(cond, "title"),
                            text(cond, "statement")
                        );
                        columns.add(colDef);
                        List<List<String>> colPartitions = new ArrayList<>();
                        JsonNode parts = cond.get("partitions");
                        if (parts != null && parts.isArray()) {
                            for (JsonNode part : parts) {
                                colPartitions.add(extractParams(part));
                            }
                        }
                        partitionsPerColumn.add(colPartitions);
                    }
                }
                // Persistir e reabrir após criar colunas
                service.persistDT(session, table, ctrl);
                ctrl = service.getDTController(session, table, reqLocale);
                dtModel = ctrl.getDTModel();
                // 4) Validação de títulos e contagem
                int expected = columns.size();
                int found = dtModel.getPartitionDefinitionCount();
                List<String> foundTitles = new ArrayList<>();
                for (int i = 0; i < found; i++) {
                    foundTitles.add(IlrDTPropertyHelper.getDefinitionTitle(dtModel.getPartitionDefinition(i)));
                }
                if (found != expected) {
                    Set<String> expectedTitles = new HashSet<>();
                    for (IlrDTPartitionDefinition d : columns) {
                        String t = IlrDTPropertyHelper.getDefinitionTitle(d);
                        if (t != null) expectedTitles.add(t);
                    }
                    List<IlrDTPartitionDefinition> toRemove = new ArrayList<>();
                    for (int i = 0; i < dtModel.getPartitionDefinitionCount(); i++) {
                        IlrDTPartitionDefinition def = dtModel.getPartitionDefinition(i);
                        String t = IlrDTPropertyHelper.getDefinitionTitle(def);
                        if (t == null || !expectedTitles.contains(t)) toRemove.add(def);
                    }
                    for (IlrDTPartitionDefinition def : toRemove) dtModel.removePartitionDefinition(def);
                    service.persistDT(session, table, ctrl);
                    ctrl = service.getDTController(session, table, reqLocale);
                    dtModel = ctrl.getDTModel();
                    found = dtModel.getPartitionDefinitionCount();
                    foundTitles.clear();
                    for (int i = 0; i < found; i++) {
                        foundTitles.add(IlrDTPropertyHelper.getDefinitionTitle(dtModel.getPartitionDefinition(i)));
                    }
                    if (found != expected) {
                        Map<String,Object> resp = new LinkedHashMap<>();
                        resp.put("error", "Estrutura inconsistente após remoção de colunas residuais");
                        resp.put("expectedConditionColumnCount", expected);
                        resp.put("foundConditionColumnCount", found);
                        resp.put("foundConditionColumnTitles", foundTitles);
                        sendDecisionTablesJson(ex, 409, resp);
                        return;
                    }
                }
            }
            // 5) Bootstrap da raiz: criar célula [0,0] se não existe
            if (dtModel.getRoot() == null) {
                IlrDTHelper.createPartitionItemAt(dtModel, /*row*/ 0, /*column*/ 0);
                service.persistDT(session, table, ctrl);
                ctrl = service.getDTController(session, table, reqLocale);
                dtModel = ctrl.getDTModel();
            }
            // 6) Primeira regra se houver colunas/partições
            if (!partitionsPerColumn.isEmpty() && dtModel.getPartitionDefinitionCount() > 0) {
                IlrDTPartition root = dtModel.getRoot();
                IlrDTPartitionItem firstItem = root.getPartitionItem(0);
                IlrDTExpressionManager em = dtModel.getExpressionManager();
                IlrDTExpressionDefinition firstColDefExpr = dtModel.getPartitionDefinition(0).getExpressionDefinition();
                List<List<List<String>>> combinations = cartesianProduct(partitionsPerColumn);
                if (!combinations.isEmpty()) {
                    List<String> params0 = combinations.get(0).get(0);
                    IlrDTExpressionInstance inst0 = em.newExpressionInstance(firstColDefExpr, params0);
                    firstItem.setExpression(inst0);
                    IlrDTPartitionItem current = firstItem;
                    for (int i = 1; i < dtModel.getPartitionDefinitionCount(); i++) {
                        current = service.addRowToNonRootColumn(
                            dtModel, dtModel.getPartitionDefinition(i), current, 0, null, combinations.get(0).get(i)
                        );
                    }
                    for (int k = 1; k < combinations.size(); k++) {
                        int pos = dtModel.getRoot().getPartitionItemCount();
                        IlrDTPartitionItem cur = service.addRowToColumn(
                            dtModel, dtModel.getPartitionDefinition(0), pos, null, combinations.get(k).get(0)
                        );
                        for (int i = 1; i < dtModel.getPartitionDefinitionCount(); i++) {
                            cur = service.addRowToNonRootColumn(
                                dtModel, dtModel.getPartitionDefinition(i), cur, 0, null, combinations.get(k).get(i)
                            );
                        }
                    }
                }
            }
            // 7) Persistir antes das ações + reabrir
            service.persistDT(session, table, ctrl);
            ctrl = service.getDTController(session, table, reqLocale);
            dtModel = ctrl.getDTModel();
            // 8) Ações e valuesByPath
            List<String> expectedActionTitles = new ArrayList<>();
            JsonNode acts = modelNode != null ? modelNode.get("actions") : null;
            if (acts != null && acts.isArray()) {
                for (JsonNode act : acts) {
                    String actionTitle = text(act, "title");
                    if (actionTitle != null && !actionTitle.isBlank()) expectedActionTitles.add(actionTitle);
                    IlrDTActionDefinition actionDef = service.addActionColumn(
                        dtModel,
                        actionTitle,
                        text(act, "statement")
                    );
                    JsonNode values = act.get("valuesByPath");
                    if (values != null && values.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> it = values.fields();
                        while (it.hasNext()) {
                            Map.Entry<String, JsonNode> entry = it.next();
                            int[] path = parsePath(entry.getKey());
                            List<String> params = Collections.singletonList(entry.getValue().asText());
                            service.setActionAtPath(dtModel, path, actionDef, params);
                        }
                    }
                }
            }
            if (!expectedActionTitles.isEmpty()) {
                Set<String> expectedLower = new HashSet<>();
                for (String t : expectedActionTitles) expectedLower.add(t.toLowerCase());
                List<IlrDTActionDefinition> toRemove = new ArrayList<>();
                for (int i = 0; i < dtModel.getActionDefinitionCount(); i++) {
                    IlrDTActionDefinition ad = dtModel.getActionDefinition(i);
                    String t = IlrDTPropertyHelper.getDefinitionTitle(ad);
                    String tLower = (t == null ? "" : t.toLowerCase());
                    if (!expectedLower.contains(tLower)) {
                        toRemove.add(ad);
                    }
                }
                for (IlrDTActionDefinition ad : toRemove) {
                    dtModel.removeActionDefinition(ad);
                }
            }
            // >>> Validação da Decision Table
            ODMArtifactValidator.ValidationReport rep = VALIDATOR.validateDecisionTable(dtModel);
            if (!rep.isValid()) {
                Map<String,Object> resp = new LinkedHashMap<>();
                resp.put("error", "Validação da decision table falhou");
                resp.put("summary", rep.summaryBySeverity());
                resp.put("issues", toIssueList(rep));
       //        sendDecisionTablesJson(ex, 422, resp);
              //  return;
            }
            // 9) Persistência final + resposta
            service.persistDT(session, table, ctrl);
            Map<String, Object> result = summarizeDecisionTable(
                session, ctrl, dtModel, projectName, baselineName, packageName, tableName
            );
            result.put("operation", creating ? "create" : "update");
            sendDecisionTablesJson(ex, 200, result);
        } finally {
            closeQuietly(new ODMDecisionTableService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD), session);
        }
    }

    private static void handleViewDecisionTable(HttpExchange ex, Map<String, String> q) throws Exception {
        String projectName = q.get("projectName");
        String packageName = q.get("packageName");
        String tableName = q.get("tableName");
        String baselineName= normalizeBaseline(q.getOrDefault("baselineName", "Main"));
        ODMDecisionTableService service = new ODMDecisionTableService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);
        IlrSession session = null;
        try {
            session = service.openSession();
            IlrDecisionTable table = service.findDecisionTable(session, projectName, packageName, tableName);
            if (table == null) {
                Map<String,String> m = mapOf("error", "Decision Table não encontrada");
                sendDecisionTablesJson(ex, 404, castMap(m));
                return;
            }
            IlrDTController ctrl = service.getDTController(session, table);
            IlrDTModel model = ctrl.getDTModel();
            Map<String, Object> result = summarizeDecisionTable(
                session, ctrl, model, projectName, baselineName, packageName, tableName
            );
            sendDecisionTablesJson(ex, 200, result);
        } finally {
            closeQuietly(service, session);
        }
    }

    // ============================================================
    // ====================== VOCABULARIES ========================
    // ============================================================
    private static void handleVocabularies(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Método não permitido"));
            return;
        }
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        String decisionServiceName = params.get("decisionServiceName");
        String baselineName = normalizeBaseline(params.get("baselineName"));
        if (decisionServiceName == null || baselineName == null) {
            sendResponse(exchange, 400, jsonError("Informe decisionServiceName e baselineName"));
            return;
        }
        ODMVocabularyService vocabService = new ODMVocabularyService(DC_USERNAME, DC_PASSWORD, DC_URL, DC_DATASOURCE);
        try {
            List<ODMVocabularyService.VocabularyInfo> vocabularies =
                vocabService.listBOMVocabularies(decisionServiceName, baselineName);
            Map<String,Object> payload = new LinkedHashMap<>();
            payload.put("decisionService", decisionServiceName);
            payload.put("baseline", baselineName);
            payload.put("vocabularies", vocabularies);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            sendResponse(exchange, 200, json);
        } catch (Exception e) {
            sendResponse(exchange, 500, jsonError(e.getMessage()));
        }
    }

    // ============================================================
    // ========================== PROJECTS ========================
    // ============================================================
    private static void handleProjects(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Método não permitido"));
            return;
        }
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        String projectName = params.get("name");
        if (projectName == null) {
            sendResponse(exchange, 400, jsonError("Informe name"));
            return;
        }
        ODMRuleService ruleService = new ODMRuleService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);
        IlrSession session = null;
        try {
            session = ruleService.openSession();
            IlrRuleProject project = ruleService.createProjectIfNotExists(session, projectName);
            sendResponse(exchange, 200, jsonMessage("Projeto criado ou existente: " + project.getName()));
        } catch (Exception e) {
            sendResponse(exchange, 500, jsonError(e.getMessage()));
        } finally {
            ruleService.closeSession(session);
        }
    }
// ============================================================================
// ======================= VARIABLE SETS (CREATE ONLY) ========================
// ============================================================================
// POST /variablesets { projectName, packageName, variableSetName, baselineName? }
private static void handleVariableSets(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, jsonError("Método não suportado; use POST"));
        return;
    }

    Map<String,String> q = parseQuery(exchange.getRequestURI().getRawQuery());
    String ctype = getContentTypeLower(exchange);
    String body  = readBodyAsString(exchange);
    com.fasterxml.jackson.databind.JsonNode node = null;
    if (ctype.contains("application/json") && body != null && !body.isBlank()) {
        node = safeParseJson(body);
    }

    String projectName     = stringOr(q.get("projectName"),     node, "projectName");
    String packageName     = stringOr(q.get("packageName"),     node, "packageName");
    String variableSetName = stringOr(q.get("variableSetName"), node, "variableSetName");
    String baselineName    = stringOr(q.get("baselineName"),    node, "baselineName"); // opcional

    List<String> missing = new ArrayList<>();
    if (isBlank(projectName))     missing.add("projectName");
    if (isBlank(packageName))     missing.add("packageName");
    if (isBlank(variableSetName)) missing.add("variableSetName");
    if (!missing.isEmpty()) {
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("error", "Parâmetros obrigatórios ausentes");
        resp.put("missing", missing);
        sendResponse(exchange, 400, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resp));
        return;
    }

    IlrSession session = null;
    try {
        // 0) Sessão
        ilog.rules.teamserver.model.IlrSessionFactory factory =
                new ilog.rules.teamserver.client.IlrRemoteSessionFactory();
        factory.connect(DC_USERNAME, DC_PASSWORD, DC_URL, DC_DATASOURCE);
        session = factory.getSession();

        // 1) Projeto + baseline (mesmo padrão que você usa em regras/ruleflows)
        IlrRuleProject project = (IlrRuleProject) IlrSessionHelper.getProjectNamed(session, projectName);
        if (project == null) {
            sendResponse(exchange, 404, jsonError("Projeto não encontrado: " + projectName));
            return;
        }
        String baselineNorm = normalizeBaseline(isBlank(baselineName) ? "Main" : baselineName);
        IlrBaseline baseline = "%current_key".equalsIgnoreCase(baselineNorm)
                ? IlrSessionHelper.getCurrentBaseline(session, project)
                : IlrSessionHelper.getBaselineNamed(session, project, baselineNorm);
        if (baseline == null) {
            baseline = IlrSessionHelper.getBaselineNamed(session, project, "Main");
            if (baseline == null) baseline = IlrSessionHelper.getCurrentBaseline(session, project);
        }
        session.setWorkingBaseline(baseline);

        // 2) Pacote ancorado no projeto (helper já existente e confiável)
        IlrRulePackage pkg = resolveOrCreateNestedPackageForRuleflow(session, project, packageName);
        pkg = (IlrRulePackage) session.getElementDetails(pkg); // materializa (apenas o pacote)

        // 3) 409 se já existir um set homônimo nesse pacote
        IlrVariableSet existing = findVariableSetByNameInPackage(session, pkg, variableSetName);
        if (existing != null) {
            Map<String,Object> resp = new LinkedHashMap<>();
            resp.put("error", "Variable Set já existe no pacote");
            resp.put("projectName", projectName);
            resp.put("baselineName", baseline.getName());
            resp.put("packageName", packageName);
            resp.put("variableSetName", variableSetName);
            sendResponse(exchange, 409, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resp));
            return;
        }

        // 4) *** CRIA direto no pacote *** (padrão igual ao de criar regra)
        //    -> use o overload com pkg e name para já nascer “no lugar certo”
        IlrVariableSet vset = null;
        Object tmp;
        tmp = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", session, pkg, variableSetName);
        if (tmp instanceof IlrVariableSet) vset = (IlrVariableSet) tmp;
        if (vset == null) {
            tmp = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", pkg, variableSetName);
            if (tmp instanceof IlrVariableSet) vset = (IlrVariableSet) tmp;
        }
        if (vset == null) {
            // fallback para drops antigos
            vset = tryCreateVariableSetAnyOverload(session, pkg, variableSetName);
        }
        if (vset == null) {
            sendResponse(exchange, 500, jsonError("Nenhum overload de createVariableSet(...) disponível neste runtime."));
            return;
        }

        // 5) ***** COMMIT DO ARTEFATO (vset) — igual à criação de regra *****
        //    Não re-materialize o VS nem reforce 'name' aqui para não perder o container.
        session.commit(vset);

        // 6) OK — sem introspecção de pacote (no seu drop, isso que estava travando)
        Map<String,Object> ok = new LinkedHashMap<>();
        ok.put("message", "Variable Set criado com sucesso");
        ok.put("projectName", projectName);
        ok.put("baselineName", baseline.getName());
        ok.put("packageName", packageName);
        ok.put("variableSetName", variableSetName);
        sendResponse(exchange, 200, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ok));

    } catch (Exception e) {
        e.printStackTrace();
        sendResponse(exchange, 500, jsonError(e.getMessage() == null ? e.getClass().getName() : e.getMessage()));
    } finally {
        if (session != null) try { session.close(); } catch (Exception ignore) {}
    }
}







// ============================================================
    // ============================ RULES =========================
    // ============================================================

 /** Localiza um Variable Set por nome dentro de um pacote específico. */

private static void handleRules(HttpExchange exchange) throws IOException {
    Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
    String action = params.get("action");
    if (action == null) {
        sendResponse(exchange, 400, jsonError("Informe action=create ou edit"));
        return;
    }

    String ctype = getContentTypeLower(exchange);
    String bodyRaw = readBodyAsString(exchange);
    JsonNode node = null;
    if (ctype.contains("application/json") && bodyRaw != null && !bodyRaw.isBlank()) {
        node = safeParseJson(bodyRaw);
    }

    // Cria o serviço com ORDEM CORRETA dos parâmetros (serverUrl, datasource, login, password)
    ODMRuleService ruleService = new ODMRuleService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);

    // Logs auxiliares para diagnosticar conexão com o DC
    System.out.println("[/rules] DC_URL=" + DC_URL + " | DC_DATASOURCE=" + DC_DATASOURCE + " | DC_USERNAME=" + DC_USERNAME);

    IlrSession session = null;
    try {
        session = ruleService.openSession(); // abre sessão no Decision Center

        if ("create".equalsIgnoreCase(action)) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, jsonError("Método não permitido para action=create"));
                return;
            }

            String projectName = params.get("projectName");
            String packageName = params.get("packageName");
            String ruleName   = params.get("ruleName");
            String body       = params.get("body");
            String baselineName = params.get("baselineName");

            if (node != null) {
                projectName  = stringOr(projectName,  node, "projectName");
                packageName  = stringOr(packageName,  node, "packageName");
                ruleName     = stringOr(ruleName,     node, "ruleName");
                body         = stringOr(body,         node, "body");
                baselineName = stringOr(baselineName, node, "baselineName");
            }

            if (projectName == null || packageName == null || ruleName == null || body == null) {
                sendResponse(exchange, 400, jsonError("Informe projectName, packageName, ruleName e body"));
                return;
            }

            IlrRuleProject project = (IlrRuleProject) IlrSessionHelper.getProjectNamed(session, projectName);
            if (project == null) {
                sendResponse(exchange, 404, jsonError("Projeto não encontrado: " + projectName));
                return;
            }

            ruleService.setWorkingBaselineToProject(session, project);

            IlrRulePackage pkg = findRulePackageByName(session, packageName);
            if (pkg == null) {
                pkg = IlrSessionHelper.createRulePackage(session, null, packageName);
                session.commit(pkg);
            }

            // Criação da Action Rule (sem pré-validação semântica)
            ruleService.createActionRule(session, pkg, ruleName, body);

            // Validação estrutural pós-criação (mantida, mas sem bloquear a resposta)
            ODMArtifactValidator.ValidationReport rep =
                    VALIDATOR.validateActionRule(findActionRuleInPackageByName(session, pkg, ruleName));
            // (Opcional: inspecionar 'rep' e alterar resposta se desejar)

            sendResponse(exchange, 200, jsonMessage("Regra criada: " + ruleName + " (pacote: " + packageName + ")"));
        }
        else if ("edit".equalsIgnoreCase(action)) {
            if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, jsonError("Método não permitido para action=edit"));
                return;
            }

            String projectName  = params.get("projectName");
            String packageName  = params.get("packageName");
            String ruleName     = params.get("ruleName");
            String newBody      = params.get("newBody");
            String priorityStr  = params.get("priority");
            String newName      = params.get("newName");
            String baselineName = params.get("baselineName");

            if (node != null) {
                projectName  = stringOr(projectName,  node, "projectName");
                packageName  = stringOr(packageName,  node, "packageName");
                ruleName     = stringOr(ruleName,     node, "ruleName");
                newBody      = stringOr(newBody,      node, "newBody");
                priorityStr  = stringOr(priorityStr,  node, "priority");
                newName      = stringOr(newName,      node, "newName");
                baselineName = stringOr(baselineName, node, "baselineName");
            }

            if (projectName == null || packageName == null || ruleName == null) {
                sendResponse(exchange, 400, jsonError("Informe projectName, packageName e ruleName"));
                return;
            }

            int priority = 0;
            try {
                if (priorityStr != null && !priorityStr.isBlank()) {
                    priority = Integer.parseInt(priorityStr);
                }
            } catch (NumberFormatException nfe) {
                sendResponse(exchange, 400, jsonError("priority inválido: " + priorityStr));
                return;
            }

            IlrRuleProject project = (IlrRuleProject) IlrSessionHelper.getProjectNamed(session, projectName);
            if (project == null) {
                sendResponse(exchange, 404, jsonError("Projeto não encontrado: " + projectName));
                return;
            }

            ruleService.setWorkingBaselineToProject(session, project);

            IlrRulePackage pkg = findRulePackageByName(session, packageName);
            if (pkg == null) {
                sendResponse(exchange, 404, jsonError("Pacote não encontrado: " + packageName));
                return;
            }

            IlrActionRule rule = findActionRuleInPackageByName(session, pkg, ruleName);
            if (rule == null) {
                sendResponse(exchange, 404, jsonError("Regra não encontrada no pacote: " + ruleName));
                return;
            }

            session.lockElement(rule);
            try {
                // Edição sem pré-validação semântica
                if (newBody != null && !newBody.isBlank()) {
                    ruleService.updateActionRuleBody(session, rule, newBody);
                }
                if (priority > 0) {
                    ruleService.updateActionRulePriority(session, rule, priority);
                }
                if (newName != null && !newName.isBlank()) {
                    ruleService.renameActionRule(session, rule, newName);
                }
            } finally {
                session.unlockElement(rule);
            }

            // Validação estrutural pós-edição (mantida, mas sem bloquear a resposta)
            ODMArtifactValidator.ValidationReport rep = VALIDATOR.validateActionRule(rule);
            // (Opcional: inspecionar 'rep' e alterar resposta se desejar)

            sendResponse(exchange, 200, jsonMessage("Regra editada com sucesso no pacote: " + packageName));
        }
        else {
            sendResponse(exchange, 400, jsonError("Action inválida"));
        }

    } catch (Exception e) {
        // Erro genérico no fluxo (inclui falhas de conexão ao DC)
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) msg = e.getClass().getName();
        sendResponse(exchange, 500, jsonError(msg));
    } finally {
        ruleService.closeSession(session);
    }
}




//============================================================================
//=========================== VARIABLES ENDPOINT =============================
//============================================================================
private static void handleVariables(HttpExchange exchange) throws IOException {
  String method = exchange.getRequestMethod();
  Map<String,String> q = parseQuery(exchange.getRequestURI().getRawQuery());
  String ctype   = getContentTypeLower(exchange);
  String bodyRaw = readBodyAsString(exchange);

  JsonNode node = null;
  if (ctype.contains("application/json") && bodyRaw != null && !bodyRaw.isBlank()) {
      node = safeParseJson(bodyRaw);
  }

  ODMVariableService service = new ODMVariableService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);
  IlrSession session = null;

  try {
      switch (method) {
          // ==============================================================================
          // GET → retorna TODOS os Variable Sets OU UM set (sem efeitos colaterais)
          // ==============================================================================
          case "GET": {
              String projectName = q.getOrDefault("projectName", null);
              String packageName = q.getOrDefault("packageName", null);
              String setName     = q.getOrDefault("variableSetName", null);
              String baselineName= q.getOrDefault("baselineName", null);

              if (isBlank(projectName)) {
                  sendResponse(exchange, 400, jsonError("Informe projectName"));
                  return;
              }

              session = service.openSession();

              // GET de UM Variable Set
              if (setName != null && !setName.isBlank()) {
                  Map<String,Object> dump = new LinkedHashMap<>();
                  IlrRuleProject project = service.getProjectOrThrow(session, projectName);
                  IlrBaseline baseline = service.resolveBaseline(session, project, baselineName);
                  session.setWorkingBaseline(baseline);

                  if (isBlank(packageName)) {
                      sendResponse(exchange, 400, jsonError("Para consultar 1 Variable Set, informe packageName"));
                      return;
                  }

                  // Navega "a.b.c" SEM criar nada
                  IlrRulePackage pkg = resolveExistingNestedPackage(session, packageName);
                  if (pkg == null) {
                      sendResponse(exchange, 404, jsonError("Pacote não encontrado: " + packageName));
                      return;
                  }

                  IlrVariableSet vset = service.findVariableSetInPackage(session, pkg, setName);
                  if (vset == null) {
                      sendResponse(exchange, 404, jsonError("Variable Set não encontrado: " + setName));
                      return;
                  }

                  dump.put("projectName",  projectName);
                  dump.put("baselineName", baseline.getName());
                  dump.put("packageName",  packageName);
                  dump.put("set",          service.summarize(vset));

                  String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dump);
                  sendResponse(exchange, 200, json);
                  return;
              }

              // DUMP GLOBAL: TODAS AS VARIÁVEIS (filtrando opcionalmente por packageName)
              Map<String,Object> dump = service.listAllVariables(projectName, baselineName, packageName);
              String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dump);
              sendResponse(exchange, 200, json);
              return;
          }

          // ==============================================================================
          // POST → UPSERT de Variable Set e Variáveis (lote) — SEM lock explícito
          // ==============================================================================
          case "POST": {
              String projectName = stringOr(q.get("projectName"),   node, "projectName");
              String packageName = stringOr(q.get("packageName"),   node, "packageName");
              String setName     = stringOr(q.get("variableSetName"), node, "variableSetName");
              String baselineName= stringOr(q.get("baselineName"),  node, "baselineName");

              boolean resetIfExists = (node != null && node.has("resetIfExists"))
                      ? node.get("resetIfExists").asBoolean(false)
                      : false;

              if (isBlank(projectName) || isBlank(setName)) {
                  sendResponse(exchange, 400, jsonError("Informe projectName e variableSetName"));
                  return;
              }
              
              // packageName agora é opcional - se não informado, usa string vazia
              if (isBlank(packageName)) {
                  packageName = "";
              }

              // (Opcional mas recomendado) Validação dos nomes antes do processamento
              if (node != null && node.has("variables") && node.get("variables").isArray()) {
                  List<String> missing = new ArrayList<>();
                  for (JsonNode vn : node.get("variables")) {
                      String vname = text(vn, "name");
                      if (vname == null || vname.isBlank()) missing.add("<name vazio>");
                  }
                  if (!missing.isEmpty()) {
                      sendResponse(exchange, 400, jsonError("Toda variável precisa de 'name' não vazio"));
                      return;
                  }
              }

              session = service.openSession();

              // 1) Baseline consistente definida no handler
              IlrRuleProject project = service.getProjectOrThrow(session, projectName);
              IlrBaseline baseline = service.resolveBaseline(session, project, baselineName);
              session.setWorkingBaseline(baseline);

              // 2) Abrir/criar set SEM alterar baseline internamente
              IlrVariableSet vset = service.openOrCreateVariableSet(session, projectName, packageName, setName);

              // 3) Reset opcional — SEM lock: service.clearVariables faz commit(vset)
              if (resetIfExists) {
                  vset = (IlrVariableSet) session.getElementDetails(vset);
                  service.clearVariables(session, vset);   // commit(vset)
                  vset = (IlrVariableSet) session.getElementDetails(vset);
              }

              // 4) Inserção/atualização em lote — SEM lock: commits no container (vset)
              if (node != null && node.has("variables") && node.get("variables").isArray()) {
                  vset = (IlrVariableSet) session.getElementDetails(vset); // materializa

                  for (JsonNode vn : node.get("variables")) {
                      String vname = text(vn, "name");
                      if (isBlank(vname)) continue;

                      String vtype = text(vn, "bomType");
                      String vverb = text(vn, "verbalization");
                      String vinit = text(vn, "initialValue");

                      IlrVariable existing = service.findVariableByName(vset, vname);
                      if (existing == null) {
                          // cria e comita o container dentro do service
                          service.addVariable(session, vset, vname, vtype, vverb, vinit); // commit(vset)
                      } else {
                          // materializa e atualiza; commit também no container
                          existing = (IlrVariable) session.getElementDetails(existing);
                          service.updateVariableInSet(session, vset, existing, vname, vtype, vverb, vinit); // commit(vset)
                      }

                      // manter o vset sincronizado após cada commit
                      vset = (IlrVariableSet) session.getElementDetails(vset);
                  }
              }

              Map<String,Object> out = service.summarize(vset);
              out.put("projectName",  projectName);
              out.put("baselineName", baseline.getName());
              out.put("operation",    "createOrUpdate");

              String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
              sendResponse(exchange, 200, json);
              return;
          }

          // ==============================================================================
          // PUT → atualizar UMA variável específica — SEM lock explícito
          // ==============================================================================
          case "PUT": {
              String projectName = stringOr(q.get("projectName"),   node, "projectName");
              String packageName = stringOr(q.get("packageName"),   node, "packageName");
              String setName     = stringOr(q.get("variableSetName"), node, "variableSetName");
              String varName     = stringOr(q.get("name"),          node, "name");
              String baselineName= stringOr(q.get("baselineName"),  node, "baselineName");

              if (isBlank(projectName) || isBlank(packageName) || isBlank(setName) || isBlank(varName)) {
                  sendResponse(exchange, 400, jsonError("Informe projectName, packageName, variableSetName e name"));
                  return;
              }

              String newName     = stringOr(q.get("newName"),       node, "newName");
              String newBomType  = stringOr(q.get("bomType"),       node, "bomType");
              String newVerbal   = stringOr(q.get("verbalization"), node, "verbalization");
              String newInitial  = stringOr(q.get("initialValue"),  node, "initialValue");

              session = service.openSession();

              // Baseline consistente
              IlrRuleProject project = service.getProjectOrThrow(session, projectName);
              IlrBaseline baseline = service.resolveBaseline(session, project, baselineName);
              session.setWorkingBaseline(baseline);

              // Abre/cria o set
              IlrVariableSet vset = service.openOrCreateVariableSet(session, projectName, packageName, setName);

              // Localiza variável
              IlrVariable var = service.findVariableByName(vset, varName);
              if (var == null) {
                  sendResponse(exchange, 404, jsonError("Variável não encontrada: " + varName));
                  return;
              }

              // Materializa e atualiza SEM lock; commit acontece no container no service
              vset = (IlrVariableSet) session.getElementDetails(vset);
              var  = (IlrVariable)   session.getElementDetails(var);

              service.updateVariableInSet(session, vset, var, newName, newBomType, newVerbal, newInitial); // commit(vset)

              // Recarrega se necessário
              vset = (IlrVariableSet) session.getElementDetails(vset);

              Map<String,Object> res = service.summarize(vset);
              res.put("projectName",  projectName);
              res.put("baselineName", baseline.getName());
              res.put("operation",    "editVariable");

              String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(res);
              sendResponse(exchange, 200, json);
              return;
          }

          default:
              sendResponse(exchange, 405, jsonError("Método não suportado para /variables"));
              return;
      }
  } catch (Exception e) {
      e.printStackTrace();
      sendResponse(exchange, 500, jsonError(e.getMessage()));
  } finally {
      service.closeSession(session);
  }
}




//============================================================================
//============================= OPERATIONS ===================================
//============================================================================
private static void handleOperations(com.sun.net.httpserver.HttpExchange ex) throws java.io.IOException {
  String method = ex.getRequestMethod();
  Map<String,String> q = parseQuery(ex.getRequestURI().getRawQuery());
  String actionFromQuery = q.getOrDefault("action", "").toLowerCase(Locale.ROOT);
  String action = actionFromQuery.isBlank() ? defaultActionForMethod(method) : actionFromQuery;

  String contentType = getContentTypeLower(ex);
  String rawBody = readBodyAsString(ex);
  com.fasterxml.jackson.databind.JsonNode body =
          (contentType.contains("application/json") && rawBody != null && !rawBody.isBlank())
                  ? safeParseJson(rawBody)
                  : null;

  // Campos principais
  String projectName   = stringOr(q.get("projectName"),   body, "projectName");
  String baselineName  = normalizeBaseline(stringOr(q.get("baselineName"), body, "baselineName"));
  String operationName = stringOr(q.get("operationName"), body, "operationName");

  // Registry (listas de VS) — aceita em "registry" ou diretamente no root
  java.util.List<String> descSets  = new java.util.ArrayList<>();
  java.util.List<String> paramSets = new java.util.ArrayList<>();
  java.util.List<String> rsSets    = new java.util.ArrayList<>();
  if (body != null) {
      com.fasterxml.jackson.databind.JsonNode reg = body.has("registry") && body.get("registry").isObject()
              ? body.get("registry")
              : body; // fallback
      if (reg.has("descriptorSets") && reg.get("descriptorSets").isArray()) {
          reg.get("descriptorSets").forEach(n -> { if (n.isTextual()) descSets.add(n.asText()); });
      }
      if (reg.has("paramsSets") && reg.get("paramsSets").isArray()) {
          reg.get("paramsSets").forEach(n -> { if (n.isTextual()) paramSets.add(n.asText()); });
      }
      if (reg.has("rulesetParamSets") && reg.get("rulesetParamSets").isArray()) {
          reg.get("rulesetParamSets").forEach(n -> { if (n.isTextual()) rsSets.add(n.asText()); });
      }
  }

  try {
      ODMOperationService svc = new ODMOperationService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);

      // ====== VIEW ======
      if ("GET".equalsIgnoreCase(method) && "view".equals(action)) {
          if (isBlank(projectName) || isBlank(operationName)) {
              sendResponse(ex, 400, jsonError("Informe projectName e operationName"));
              return;
          }
          Map<String,Object> out = svc.viewOperationMulti(
                  projectName, baselineName, operationName,
                  descSets.isEmpty() ? null : descSets,
                  paramSets.isEmpty()? null : paramSets,
                  rsSets.isEmpty() ? null : rsSets
          );
          sendResponse(ex, 200, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
          return;
      }

      // ====== CREATE / UPDATE ======
      if ( ("POST".equalsIgnoreCase(method) && "create".equals(action))
        || ("PUT" .equalsIgnoreCase(method) && "update".equals(action)) ) {

          if (isBlank(projectName) || isBlank(operationName)) {
              sendResponse(ex, 400, jsonError("Informe projectName e operationName"));
              return;
          }

          // ruleflow (opcional)
          String rfPackage = null, rfName = null;
          if (body != null && body.has("ruleflow")) {
              com.fasterxml.jackson.databind.JsonNode rf = body.get("ruleflow");
              if (rf.isObject()) {
                  rfPackage = text(rf, "packageName");
                  rfName    = text(rf, "ruleflowName");
              } else if (rf.isTextual()) {
                  rfName = rf.asText();
              }
          }

          // rulesetName (runtime-only), mas passaremos ao service se houver overload (design-time opcional)
          String rulesetName = null;
          if (body != null) {
              if (body.has("descriptor") && body.get("descriptor").isObject()) {
                  rulesetName = text(body.get("descriptor"), "rulesetName");
              }
              if (isBlank(rulesetName)) {
                  rulesetName = text(body, "rulesetName");
              }
          }
          if (isBlank(rulesetName)) {
              rulesetName = q.get("rulesetName"); // fallback
          }

          // parameters (contrato)
          java.util.List<java.util.Map<String,String>> params = new java.util.ArrayList<>();
          if (body != null && body.has("parameters") && body.get("parameters").isArray()) {
              for (com.fasterxml.jackson.databind.JsonNode p : body.get("parameters")) {
                  java.util.Map<String,String> m = new java.util.LinkedHashMap<>();
                  m.put("name",      text(p, "name"));
                  m.put("direction", text(p, "direction"));
                  m.put("bomType",   text(p, "bomType"));
                  params.add(m);
              }
          }

          // rulesetParameters (pares extras, opcionais)
          java.util.Map<String,String> rsParams = new java.util.LinkedHashMap<>();
          if (body != null && body.has("rulesetParameters") && body.get("rulesetParameters").isObject()) {
              java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it =
                      body.get("rulesetParameters").fields();
              while (it.hasNext()) {
                  java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e = it.next();
                  rsParams.put(e.getKey(), e.getValue().asText());
              }
          }

          // packagePath e description (opcionais)
          String packagePath = text(body, "packagePath");
          String description = text(body, "description");
          
          // variableSetName (opcional) — nome do Variable Set para parâmetros da Operation
          String variableSetName = text(body, "variableSetName");

          // ==== Chamada reflexiva ao service.saveOperation(...) (tolerante a variações) ====
          Map<String,Object> out;

          // Tentativa 1 — 15 parâmetros (com rulesetName e variableSetName)
          try {
              Object[] args15 = new Object[]{
                      projectName,
                      baselineName,
                      operationName,
                      rfPackage,
                      rfName,
                      /* parameters */ params,
                      /* rulesetParameters */ rsParams,
                      /* registryRootPackageIgnored */ null,
                      descSets.isEmpty()  ? null : descSets,
                      paramSets.isEmpty() ? null : paramSets,
                      rsSets.isEmpty()    ? null : rsSets,
                      packagePath,
                      description,
                      rulesetName,
                      variableSetName
              };
              java.lang.reflect.Method m15 = findCompatibleMethod(
                      svc.getClass(), "saveOperation", classesOf(args15));
              if (m15 != null) {
                  @SuppressWarnings("unchecked")
                  Map<String,Object> tmp = (Map<String,Object>) m15.invoke(svc, args15);
                  out = tmp;
              } else {
                  throw new NoSuchMethodException("saveOperation(15) não disponível");
              }
          } catch (Throwable ignore15) {
              // Tentativa 2 — 14 parâmetros (com rulesetName, sem variableSetName)
              try {
                  Object[] args14 = new Object[]{
                          projectName,
                          baselineName,
                          operationName,
                          rfPackage,
                          rfName,
                          /* parameters */ params,
                          /* rulesetParameters */ rsParams,
                          /* registryRootPackageIgnored */ null,
                          descSets.isEmpty()  ? null : descSets,
                          paramSets.isEmpty() ? null : paramSets,
                          rsSets.isEmpty()    ? null : rsSets,
                          packagePath,
                          description,
                          rulesetName
                  };
                  java.lang.reflect.Method m14 = findCompatibleMethod(
                          svc.getClass(), "saveOperation", classesOf(args14));
                  if (m14 != null) {
                      @SuppressWarnings("unchecked")
                      Map<String,Object> tmp = (Map<String,Object>) m14.invoke(svc, args14);
                      out = tmp;
                  } else {
                      throw new NoSuchMethodException("saveOperation(14) não disponível");
                  }
              } catch (Throwable ignore14) {
                  // Tentativa 3 — 13 parâmetros (sem rulesetName) — compat com código anterior
                  try {
                  Object[] args13 = new Object[]{
                          projectName,
                          baselineName,
                          operationName,
                          rfPackage,
                          rfName,
                          /* parameters */ params,
                          /* rulesetParameters */ rsParams,
                          /* registryRootPackageIgnored */ null,
                          descSets.isEmpty()  ? null : descSets,
                          paramSets.isEmpty() ? null : paramSets,
                          rsSets.isEmpty()    ? null : rsSets,
                          packagePath,
                          description
                  };
                  java.lang.reflect.Method m13 = findCompatibleMethod(
                          svc.getClass(), "saveOperation", classesOf(args13));
                  if (m13 != null) {
                      @SuppressWarnings("unchecked")
                      Map<String,Object> tmp = (Map<String,Object>) m13.invoke(svc, args13);
                      out = tmp;
                  } else {
                      throw new NoSuchMethodException("saveOperation(13) não disponível");
                  }
              } catch (Throwable ignore13) {
                  // Tentativa 4 — 11 parâmetros (sem packagePath/description)
                  Object[] args11 = new Object[]{
                          projectName,
                          baselineName,
                          operationName,
                          rfPackage,
                          rfName,
                          /* parameters */ params,
                          /* rulesetParameters */ rsParams,
                          /* registryRootPackageIgnored */ null,
                          descSets.isEmpty()  ? null : descSets,
                          paramSets.isEmpty() ? null : paramSets,
                          rsSets.isEmpty()    ? null : rsSets
                  };
                      java.lang.reflect.Method m11 = findCompatibleMethod(
                              svc.getClass(), "saveOperation", classesOf(args11));
                      if (m11 == null) {
                          sendResponse(ex, 500, jsonError(
                                  "Nenhum overload compatível de saveOperation(...) encontrado (15/14/13/11 parâmetros)"));
                          return;
                      }
                      @SuppressWarnings("unchecked")
                      Map<String,Object> tmp = (Map<String,Object>) m11.invoke(svc, args11);
                      out = tmp;
                  }
              }
          }

          // Anexa valor runtime (não persistido) apenas para conferência da chamada
          out.put("runtimeRulesetName", rulesetName);
          out.put("operation", "create".equals(action) ? "create" : "update");

          sendResponse(ex, 200, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
          return;
      }

      sendResponse(ex, 405, jsonError("Método ou ação não suportados para /operations"));
  } catch (Exception e) {
      e.printStackTrace();
      sendResponse(ex, 500, jsonError(e.getMessage() == null ? e.getClass().getName() : e.getMessage()));
  }
}

    // ============================================================
    // ====================== TEST SUITES =========================
    // ============================================================
    /**
     * Handler para Test Suites:
     * - POST /testsuites - Cria um test suite vazio
     * - GET /testsuites - Lista test suites de um projeto
     *
     * IMPORTANTE:
     * - Test Suites ficam no projeto (não em pacotes)
     * - Test Suite está associado a uma Decision Operation
     * - Cenários de teste devem ser importados via Excel no Decision Center
     *
     * Payload POST exemplo:
     * {
     *   "projectName": "MyProject",
     *   "testSuiteName": "MySuite",
     *   "operationName": "MyOperation",
     *   "serverName": "Test and Simulation Execution",
     *   "baselineName": "Main"
     * }
     *
     * Query GET exemplo:
     * /testsuites?projectName=MyProject&baselineName=Main
     */
    private static void handleTestSuites(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        
        try {
            switch (method) {
                case "POST":
                    handleCreateTestSuite(ex, q);
                    break;
                case "GET":
                    handleListTestSuites(ex, q);
                    break;
                default:
                    sendResponse(ex, 405, jsonError("Método não suportado. Use POST ou GET"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(ex, 500, jsonError(e.getMessage() == null ? e.getClass().getName() : e.getMessage()));
        }
    }

    /**
     * POST /testsuites - Cria um test suite vazio
     * Os cenários devem ser importados via Excel no Decision Center
     */
    private static void handleCreateTestSuite(HttpExchange ex, Map<String, String> q) throws Exception {
        String bodyRaw = readBodyAsString(ex);
        JsonNode node = safeParseJson(bodyRaw);
        
        // Extrai parâmetros
        String projectName = text(node, "projectName");
        String testSuiteName = text(node, "testSuiteName");
        String operationName = text(node, "operationName");
        String serverName = textOr("", node, "serverName");
        String baselineName = normalizeBaseline(textOr("Main", node, "baselineName"));
        
        // Valida parâmetros obrigatórios
        if (isBlank(projectName)) {
            sendResponse(ex, 400, jsonError("projectName é obrigatório"));
            return;
        }
        if (isBlank(testSuiteName)) {
            sendResponse(ex, 400, jsonError("testSuiteName é obrigatório"));
            return;
        }
        if (isBlank(operationName)) {
            sendResponse(ex, 400, jsonError("operationName é obrigatório"));
            return;
        }
        
        // Cria o serviço e executa
        ODMTestSuiteService service = new ODMTestSuiteService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);
        IlrSession session = null;
        
        try {
            session = service.openSession();
            IlrRuleProject project = service.getProjectOrThrow(session, projectName);
            service.setWorkingBaseline(session, project, baselineName);
            
            IlrElementDetails testSuite = service.createTestSuite(
                session, project, testSuiteName, operationName, serverName
            );
            
            // Monta resposta
            String jsonResponse = String.format(
                "{\"success\":true,\"message\":\"Test Suite criado com sucesso. Importe os cenários via Excel no Decision Center.\",\"testSuiteName\":\"%s\",\"projectName\":\"%s\",\"operationName\":\"%s\"}",
                escapeJson(testSuiteName), escapeJson(projectName), escapeJson(operationName)
            );
            sendResponse(ex, 200, jsonResponse);
            
        } finally {
            service.closeSession(session);
        }
    }

    /**
     * GET /testsuites - Lista test suites de um projeto
     * Query params: projectName (obrigatório), baselineName (opcional)
     */
    private static void handleListTestSuites(HttpExchange ex, Map<String, String> q) throws Exception {
        String projectName = q.get("projectName");
        String baselineName = normalizeBaseline(q.getOrDefault("baselineName", "Main"));
        
        if (isBlank(projectName)) {
            sendResponse(ex, 400, jsonError("projectName é obrigatório"));
            return;
        }
        
        ODMTestSuiteService service = new ODMTestSuiteService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);
        
        try {
            List<Map<String, Object>> testSuites = service.listTestSuites(projectName, baselineName);
            
            try {
                String jsonResponse = mapper.writeValueAsString(Map.of(
                    "projectName", projectName,
                    "baselineName", baselineName,
                    "testSuiteCount", testSuites.size(),
                    "testSuites", testSuites
                ));
                sendResponse(ex, 200, jsonResponse);
            } catch (Exception jsonEx) {
                sendResponse(ex, 500, jsonError("Erro ao gerar JSON: " + jsonEx.getMessage()));
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(ex, 500, jsonError(e.getMessage() == null ? e.getClass().getName() : e.getMessage()));
        }
    }

    // ============================================================
    // =========================== HEALTH =========================
    // ============================================================
    private static void handleHealth(HttpExchange ex) throws IOException {
        sendText(ex, 200, "OK");
    }

    // ============================================================
    // ========================= RULEFLOWS ========================
    // ============================================================
    private static void handleRuleflows(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String actionFromQuery = q.getOrDefault("action", "").toLowerCase(Locale.ROOT);
        String action = actionFromQuery.isBlank() ? defaultActionForMethod(method) : actionFromQuery;
        try {
            if ("GET".equals(method)) {
                if ("view".equals(action)) {
                    handleViewRuleflow(ex, q);
                } else {
                    sendRuleflowsJson(ex, 400, castMap(mapOf("error", "Use GET (view) ou informe action=view")));
                }
            } else if ("POST".equals(method)) {
                if ("create".equals(action)) {
                    handleCreateOrUpdateRuleflow(ex, q, true);
                } else {
                    sendRuleflowsJson(ex, 400, castMap(mapOf("error", "Use POST (create) ou informe action=create")));
                }
            } else if ("PUT".equals(method)) {
                if ("update".equals(action)) {
                    handleCreateOrUpdateRuleflow(ex, q, false);
                } else {
                    sendRuleflowsJson(ex, 400, castMap(mapOf("error", "Use PUT (update) ou informe action=update")));
                }
            } else {
                sendRuleflowsJson(ex, 405, castMap(mapOf("error", "Método não suportado: " + method)));
            }
        } catch (Exception e) {
            e.printStackTrace();
            Map<String,Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getName());
            err.put("message", e.getMessage() == null ? "<no-message>" : e.getMessage());
            sendRuleflowsJson(ex, 500, err);
        }
    }

    private static String defaultActionForMethod(String method) {
        if ("GET".equalsIgnoreCase(method)) return "view";
        if ("POST".equalsIgnoreCase(method)) return "create";
        if ("PUT".equalsIgnoreCase(method)) return "update";
        return "";
    }

    private static void handleViewRuleflow(HttpExchange ex, Map<String, String> q) throws Exception {
        String projectName = q.get("projectName");
        String packageName = q.get("packageName");
        String ruleflowName= q.get("ruleflowName");
        String baselineName= normalizeBaseline(q.getOrDefault("baselineName", "Main"));
        String ctype = getContentTypeLower(ex);
        String bodyRaw = readBodyAsString(ex);
        if (ctype.contains("application/json") && bodyRaw != null && !bodyRaw.isBlank()) {
            JsonNode node = safeParseJson(bodyRaw);
            if (node != null) {
                projectName = stringOr(projectName, node, "projectName");
                packageName = stringOr(packageName, node, "packageName");
                ruleflowName= stringOr(ruleflowName, node, "ruleflowName");
                baselineName= normalizeBaseline(stringOr(baselineName, node, "baselineName"));
            }
        }
        if (isBlank(projectName) || isBlank(packageName) || isBlank(ruleflowName)) {
            sendRuleflowsJson(ex, 400, castMap(mapOf("error", "Informe projectName, packageName e ruleflowName (query ou JSON)")));
            return;
        }
        ODMRuleflowService service = new ODMRuleflowService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);
        IlrSession session = null;
        try {
            session = service.openSession();
            IlrRuleProject project = service.getProjectOrThrow(session, projectName);
            IlrBaseline baseline = resolveBaseline(session, project, baselineName);
            session.setWorkingBaseline(baseline);
            IlrRulePackage pkg = resolveOrCreateNestedPackageForRuleflow(session, project, packageName);
            IlrRuleflow rf = service.findRuleflowInPackageByName(session, pkg, ruleflowName);
            if (rf == null) {
                sendRuleflowsJson(ex, 404, castMap(mapOf("error", "Ruleflow não encontrado no pacote informado.")));
                return;
            }
            String locale = rf.getLocale() == null ? "" : rf.getLocale();
            String body = rf.getBody();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("projectName", projectName);
            result.put("baselineName", baseline.getName());
            result.put("packageName", packageName);
            result.put("ruleflowName", ruleflowName);
            result.put("mainFlowTask", rf.isMainFlowTask());
            result.put("locale", locale);
            result.put("bodyLength", body == null ? 0 : body.length());
            result.put("bodyPreview", body == null ? "" : body.substring(0, Math.min(body.length(), 1000)));
            result.put("describe", service.describeRuleflow(rf));
            sendRuleflowsJson(ex, 200, result);
        } finally {
            service.closeSession(session);
        }
    }

    private static void handleCreateOrUpdateRuleflow(HttpExchange ex, Map<String, String> q, boolean creating) throws Exception {
        String ctype = getContentTypeLower(ex);
        String bodyRaw = readBodyAsString(ex);
        String projectName = null;
        String packageName = null;
        String ruleflowName= null;
        String baselineName= "Main";
        boolean mainFlowTask = true;
        String bodyDRF = null;
        if (ctype.contains("application/json") && bodyRaw != null && !bodyRaw.isBlank()) {
            JsonNode node = safeParseJson(bodyRaw);
            if (node != null) {
                bodyDRF = text(node, "bodyDRF");
                projectName = text(node, "projectName");
                packageName = text(node, "packageName");
                ruleflowName = text(node, "ruleflowName");
                baselineName = normalizeBaseline(textOr("Main", node, "baselineName"));
                mainFlowTask = boolOr(true, node, "mainFlowTask");
                if (isBlank(bodyDRF)) bodyDRF = text(node, "xml");
            }
        }
        if (isBlank(projectName)) projectName = q.get("projectName");
        if (isBlank(packageName)) packageName = q.get("packageName");
        if (isBlank(ruleflowName)) ruleflowName = q.get("ruleflowName");
        if (!isBlank(q.get("baselineName"))) baselineName = normalizeBaseline(q.get("baselineName"));
        if (!isBlank(q.get("mainFlowTask"))) mainFlowTask = parseBoolean(q.get("mainFlowTask"));
        if (isBlank(bodyDRF)) {
            String bodyDRFFromParam = q.get("bodyDRF");
            if (!isBlank(bodyDRFFromParam)) {
                bodyDRF = bodyDRFFromParam;
            } else if (!isBlank(bodyRaw) && !ctype.contains("application/json")) {
                bodyDRF = bodyRaw;
            }
        }
        List<String> faltando = new ArrayList<>();
        if (isBlank(projectName)) faltando.add("projectName");
        if (isBlank(packageName)) faltando.add("packageName");
        if (isBlank(ruleflowName)) faltando.add("ruleflowName");
        if (isBlank(bodyDRF)) faltando.add("bodyDRF(XML)");
        if (!faltando.isEmpty()) {
            Map<String,Object> resp = new LinkedHashMap<>();
            resp.put("error", "Parâmetros obrigatórios ausentes");
            resp.put("missing", faltando);
            sendRuleflowsJson(ex, 400, resp);
            return;
        }
        // --- PRÉ-VALIDAÇÃO SEMÂNTICA PARA RULEFLOW ---
        try {
            Set<String> vocabTokens = loadVocabularyTokens(projectName, baselineName);
            List<String> missingTokens = validateXmlAgainstVocabulary(bodyDRF, vocabTokens);
            if (!missingTokens.isEmpty()) {
                Map<String,Object> resp = new LinkedHashMap<>();
                resp.put("error", "XML do ruleflow contém termos não compatíveis com a verbalização do BOM da baseline");
                resp.put("missingTokens", missingTokens);
                resp.put("hint", "Ajuste nomes/labels/tarefas do ruleflow para casar com o vocabulary do Decision Service/baseline.");
               // sendRuleflowsJson(ex, 422, resp);
              //  return; // aborta criação/atualização
            }
        } catch (Exception e) {
            Map<String,Object> err = new LinkedHashMap<>();
            err.put("error", "Falha ao carregar vocabulário do BOM para validação semântica do ruleflow");
            err.put("message", e.getMessage() == null ? "<no-message>" : e.getMessage());
            sendRuleflowsJson(ex, 500, err);
            return;
        }
        // --- FIM PRÉ-VALIDAÇÃO SEMÂNTICA ---

        ODMRuleflowService service = new ODMRuleflowService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);
        IlrSession session = null;
        try {
            session = service.openSession();
            IlrRuleProject project = service.getProjectOrThrow(session, projectName);
            IlrBaseline baseline = resolveBaseline(session, project, baselineName);
            session.setWorkingBaseline(baseline);
            IlrRulePackage pkg = resolveOrCreateNestedPackageForRuleflow(session, project, packageName);
            IlrRuleflow rf = service.createOrUpdateRuleflow(session, project, pkg, ruleflowName, bodyDRF, mainFlowTask);
            rf = service.findRuleflowInPackageByName(session, pkg, ruleflowName);
            // >>> Validação estrutural do ruleflow
            ODMArtifactValidator.ValidationReport rep = VALIDATOR.validateRuleflow(rf);
            if (!rep.isValid()) {
                Map<String,Object> resp = new LinkedHashMap<>();
                resp.put("error", "Validação do ruleflow falhou");
                resp.put("summary", rep.summaryBySeverity());
                resp.put("issues", toIssueList(rep));
         //       sendRuleflowsJson(ex, 422, resp);
               // return;
            }
            String locale = rf.getLocale() == null ? "" : rf.getLocale();
            String persisted= rf.getBody();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("operation", creating ? "create" : "update");
            result.put("projectName", projectName);
            result.put("baselineName", baseline.getName());
            result.put("packageName", packageName);
            result.put("ruleflowName", ruleflowName);
            result.put("mainFlowTask", rf.isMainFlowTask());
            result.put("locale", locale);
            result.put("bodyLength", persisted == null ? 0 : persisted.length());
            result.put("bodyPreview", persisted == null ? "" : persisted.substring(0, Math.min(persisted.length(), 1000)));
            result.put("describe", service.describeRuleflow(rf));
            sendRuleflowsJson(ex, 200, result);
        } finally {
            service.closeSession(session);
        }
    }

    // ===========================
    // Helpers usados em /ruleflows
    // ===========================
    private static IlrBaseline resolveBaseline(IlrSession session, IlrRuleProject project, String baselineName) throws Exception {
        if (baselineName == null || baselineName.isBlank()) {
            return IlrSessionHelper.getCurrentBaseline(session, project);
        }
        if ("%current_key".equalsIgnoreCase(baselineName)) {
            return IlrSessionHelper.getCurrentBaseline(session, project);
        }
        IlrBaseline b = IlrSessionHelper.getBaselineNamed(session, project, baselineName);
        if (b != null) return b;
        if ("Main".equalsIgnoreCase(baselineName)) {
            b = IlrSessionHelper.getBaselineNamed(session, project, "Main");
            if (b != null) return b;
        }
        return IlrSessionHelper.getCurrentBaseline(session, project);
    }

    private static void sendRuleflowsJson(HttpExchange ex, int code, Map<String, ?> obj) throws IOException {
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        sendResponse(ex, code, json);
    }

    private static String getContentTypeLower(HttpExchange ex) {
        String ctype = ex.getRequestHeaders().getFirst("Content-Type");
        if (ctype == null) ctype = "";
        return ctype.toLowerCase(Locale.ROOT);
    }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static boolean boolOr(boolean def, JsonNode node, String field) {
        return node != null && node.has(field) && !node.get(field).isNull() ? node.get(field).asBoolean() : def;
    }
    private static String stringOr(String current, JsonNode node, String field) {
        String v = text(node, field);
        return isBlank(v) ? current : v;
    }

    // ===========================
    // PRÉ-VALIDAÇÃO DE VOCABULARY
    // ===========================
    private static List<String> collectDTStatements(JsonNode modelNode) {
        List<String> stmts = new ArrayList<>();
        if (modelNode == null || modelNode.isNull()) return stmts;
        JsonNode conds = modelNode.get("conditions");
        if (conds != null && conds.isArray()) {
            for (JsonNode c : conds) {
                String s = text(c, "statement");
                if (s != null && !s.isBlank()) stmts.add(s);
            }
        }
        JsonNode acts = modelNode.get("actions");
        if (acts != null && acts.isArray()) {
            for (JsonNode a : acts) {
                String s = text(a, "statement");
                if (s != null && !s.isBlank()) stmts.add(s);
            }
        }
        return stmts;
    }

    private static Set<String> loadVocabularyTokens(String decisionServiceName, String baselineName) throws Exception {
        ODMVocabularyService vocabService = new ODMVocabularyService(DC_USERNAME, DC_PASSWORD, DC_URL, DC_DATASOURCE);
        List<ODMVocabularyService.VocabularyInfo> vocabularies =
            vocabService.listBOMVocabularies(decisionServiceName, baselineName);
        Set<String> tokens = new HashSet<>();
        for (ODMVocabularyService.VocabularyInfo v : vocabularies) {
            String body = v.getBody();
            if (body != null) {
                String lower = body.toLowerCase(Locale.ROOT);
                String[] parts = lower.split("[^\\p{L}\\p{N}]+");
                for (String p : parts) {
                    if (!p.isBlank()) tokens.add(p);
                }
                tokens.add(lower); // guarda corpo completo para matching por contains
            }
        }
        return tokens;
    }

    private static List<String> validateStatementAgainstVocabulary(String stmt, Set<String> vocabTokens) {
        List<String> missing = new ArrayList<>();
        if (stmt == null || stmt.isBlank()) return missing;
        String[] words = stmt.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        Set<String> stop = new HashSet<>(Arrays.asList("de","do","da","e","em","o","a","os","as","um","uma","no","na","nos","nas","que","se","entao","então"));
        for (String w : words) {
            if (w.isBlank() || stop.contains(w)) continue;
            if (!vocabTokens.contains(w)) {
                boolean found = false;
                for (String t : vocabTokens) {
                    if (t.length() > 20 && t.contains(w)) { found = true; break; }
                }
                if (!found) missing.add(w);
            }
        }
        return missing;
    }

    private static List<String> validateXmlAgainstVocabulary(String xml, Set<String> vocabTokens) throws Exception {
        List<String> missing = new ArrayList<>();
        if (xml == null || xml.isBlank()) return missing;
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setValidating(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Set<String> wordsToCheck = new LinkedHashSet<>();
        collectXmlTokens(doc, wordsToCheck);
        Set<String> stop = new HashSet<>(Arrays.asList("de","do","da","e","em","o","a","os","as","um","uma","no","na","nos","nas","que","se"));
        for (String w : wordsToCheck) {
            String ww = w.toLowerCase(Locale.ROOT);
            if (ww.isBlank() || stop.contains(ww)) continue;
            if (!vocabTokens.contains(ww)) {
                boolean found = false;
                for (String t : vocabTokens) {
                    if (t.length() > 20 && t.contains(ww)) { found = true; break; }
                }
                if (!found) missing.add(w);
            }
        }
        return missing;
    }

    private static void collectXmlTokens(Node node, Set<String> out) {
        if (node == null) return;
        if (node.getNodeType() == Node.TEXT_NODE) {
            String text = node.getNodeValue();
            addWords(text, out);
        }
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            NamedNodeMap attrs = node.getAttributes();
            if (attrs != null) {
                for (int i = 0; i < attrs.getLength(); i++) {
                    Node a = attrs.item(i);
                    String name = a.getNodeName().toLowerCase(Locale.ROOT);
                    if (name.contains("name") || name.contains("label") || name.contains("title") || name.contains("display")) {
                        addWords(a.getNodeValue(), out);
                    }
                }
            }
        }
        NodeList children = node.getChildNodes();
        if (children != null) {
            for (int i = 0; i < children.getLength(); i++) collectXmlTokens(children.item(i), out);
        }
    }

    private static void addWords(String text, Set<String> out) {
        if (text == null) return;
        String[] parts = text.split("[^\\p{L}\\p{N}]+");
        for (String p : parts) {
            if (p != null && !p.isBlank()) out.add(p);
        }
    }
 // Resolve um pacote aninhado EXISTENTE ("a.b.c") sem criar nada.
 // Retorna null se qualquer nível não existir.
 private static IlrRulePackage resolveExistingNestedPackage(IlrSession session, String fullPackageName) throws Exception {
     if (fullPackageName == null || fullPackageName.isBlank()) return null;

     String[] parts = Arrays.stream(fullPackageName.split("\\."))
             .filter(p -> p != null && !p.isBlank())
             .toArray(String[]::new);

     if (parts.length == 0) return null;

     IlrRulePackage current = findRulePackageByName(session, parts[0]); // método existente
     if (current == null) return null;

     for (int i = 1; i < parts.length; i++) {
         IlrRulePackage child = findChildPackageForRuleflow(session, current, parts[i]); // método existente
         if (child == null) return null;
         current = child;
     }
     return current;
 }
    // ===========================
    // Utilitários HTTP/JSON e gerais
    // ===========================
    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
/*
    private static String jsonError(String msg) {
        return "{\"\"error\"\":\"" + escapeJson(msg) + "\"}";
    }

    private static String jsonMessage(String msg) {
        return "{\"\"message\"\":\"" + escapeJson(msg) + "\"}";
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
*/
    private static boolean parseBoolean(String s) {
        return "true".equalsIgnoreCase(s)
            || "1".equals(s)
            || "yes".equalsIgnoreCase(s);
    }

    private static void sendText(HttpExchange ex, int code, String msg) throws IOException {
        byte[] resp = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, resp.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
    }

    private static void sendDecisionTablesJson(HttpExchange ex, int code, Map<String, ?> obj) throws IOException {
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static Map<String, Object> summarizeDecisionTable(
        IlrSession session, IlrDTController ctrl, IlrDTModel model,
        String projectName, String baselineName, String packageName, String tableName) throws Exception {
        List<String> conditionTitles = new ArrayList<>();
        for (int i = 0; i < model.getPartitionDefinitionCount(); i++) {
            conditionTitles.add(IlrDTPropertyHelper.getDefinitionTitle(model.getPartitionDefinition(i)));
        }
        List<String> actionTitles = new ArrayList<>();
        for (int i = 0; i < model.getActionDefinitionCount(); i++) {
            actionTitles.add(IlrDTPropertyHelper.getDefinitionTitle(model.getActionDefinition(i)));
        }
        String storable = IlrSessionHelper.dtControllerToStorableString(session, ctrl);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectName", projectName);
        result.put("baselineName", baselineName);
        result.put("packageName", packageName);
        result.put("tableName", tableName);
        result.put("conditionColumns", conditionTitles);
        result.put("actionColumns", actionTitles);
        result.put("bodyPreview", storable == null ? "" : storable.substring(0, Math.min(storable.length(), 1000)));
        return result;
    }

    private static String toJson(Map<String, ?> obj) {
        return "{" + obj.entrySet().stream()
            .map(e -> "\"" + escapeJson(e.getKey()) + "\":" + toJsonValue(e.getValue()))
            .collect(Collectors.joining(",")) + "}";
    }

    @SuppressWarnings("unchecked")
    private static String toJsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof Map) return toJson((Map<String, ?>) v);
        if (v instanceof Collection<?> col) {
            return "[" + col.stream().map(ODMHttpServer::toJsonValue).collect(Collectors.joining(",")) + "]";
        }
        String s = String.valueOf(v);
        return "\"" + escapeJson(s) + "\"";
    }

    private static Map<String, String> mapOf(String k1, String v1) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k1, v1);
        return m;
    }

    private static Map<String, Object> castMap(Map<String, String> in) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<String,String> e : in.entrySet()) out.put(e.getKey(), e.getValue());
        return out;
    }

    private static void closeQuietly(ODMDecisionTableService service, IlrSession session) {
        try { if (service != null) service.closeSession(session); } catch (Exception ignore) { }
    }

    // ===========================
    // Arrays/strings helpers
    // ===========================
    private static List<JsonNode> arrayOrEmpty(JsonNode node, String field) {
        JsonNode arr = node != null ? node.get(field) : null;
        if (arr != null && arr.isArray()) {
            List<JsonNode> list = new ArrayList<>();
            arr.forEach(list::add);
            return list;
        }
        return Collections.emptyList();
    }

    private static List<String> extractParams(JsonNode part) {
        List<String> params = new ArrayList<>();
        if (part == null) return params;
        if (part.has("value")) {
            params.add(part.get("value").asText());
            return params;
        }
        if (part.has("min")) params.add(part.get("min").asText());
        if (part.has("max")) params.add(part.get("max").asText());
        return params;
    }

    private static List<List<List<String>>> cartesianProduct(List<List<List<String>>> lists) {
        List<List<List<String>>> result = new ArrayList<>();
        result.add(new ArrayList<>());
        for (List<List<String>> list : lists) {
            List<List<List<String>>> newResult = new ArrayList<>();
            for (List<List<String>> prefix : result) {
                for (List<String> element : list) {
                    List<List<String>> newPrefix = new ArrayList<>(prefix);
                    newPrefix.add(element);
                    newResult.add(newPrefix);
                }
            }
            result = newResult;
        }
        return result;
    }

    private static int[] parsePath(String s) {
        String t = s.replace("[", "").replace("]", "");
        if (t.isBlank()) return new int[0];
        String[] parts = t.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    private static String text(JsonNode node, String field) {
        return (node != null && node.has(field) && !node.get(field).isNull()) ? node.get(field).asText() : null;
    }

    private static String textOr(String def, JsonNode node, String field) {
        String v = text(node, field);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static JsonNode safeParseJson(String raw) {
        try { return mapper.readTree(raw); } catch (Exception e) { return null; }
    }

    private static String readBodyAsString(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            byte[] data = is.readAllBytes();
            return (data.length == 0) ? null : new String(data, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return map;
        for (String p : rawQuery.split("&")) {
            String[] kv = p.split("=", 2);
            map.put(urlDecode(kv[0]), kv.length > 1 ? urlDecode(kv[1]) : "");
        }
        return map;
    }

    private static String urlDecode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
    }

    // Localiza um Rule Package pelo nome (nível simples; retorna o primeiro encontrado)
    private static IlrRulePackage findRulePackageByName(IlrSession session, String packageName) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(
            meta.getRulePackage(),
            Arrays.asList(meta.getModelElement_Name()),
            Arrays.asList(packageName)
        );
        @SuppressWarnings("unchecked")
        List<IlrRulePackage> packages = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
        return (packages == null || packages.isEmpty()) ? null : packages.get(0);
    }

    // Localiza uma Action Rule pelo nome, restringindo ao Rule Package informado
    private static IlrActionRule findActionRuleInPackageByName(IlrSession session,
                                                               IlrRulePackage pkg,
                                                               String ruleName) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria byName = new IlrDefaultSearchCriteria(
            meta.getActionRule(),
            Arrays.asList(meta.getModelElement_Name()),
            Arrays.asList(ruleName)
        );
        @SuppressWarnings("unchecked")
        List<IlrActionRule> rules = session.findElements(byName, IlrModelConstants.ELEMENT_DETAILS);
        if (rules == null || rules.isEmpty()) return null;
        for (IlrActionRule r : rules) {
            IlrRulePackage rPkg = r.getRulePackage();
            if (rPkg != null && pkg.getName() != null && pkg.getName().equals(rPkg.getName())) {
                return r;
            }
        }
        return null;
    }

    //Resolve ou cria a cadeia de subpacotes "a.b.c" (raiz + filhos)
    private static IlrRulePackage resolveOrCreateNestedPackageForRuleflow(IlrSession session,
                                                                          IlrRuleProject project,
                                                                          String fullPackageName) throws Exception {
        if (fullPackageName == null || fullPackageName.isBlank()) {
            throw new IllegalArgumentException("packageName vazio");
        }
        String[] parts = Arrays.stream(fullPackageName.split("\\.") )
            .filter(p -> p != null && !p.isBlank())
            .toArray(String[]::new);
        if (parts.length == 0) {
            throw new IllegalArgumentException("packageName inválido");
        }
        // Localiza/cria a raiz
        IlrRulePackage current = findRootPackageForRuleflow(session, parts[0]);
        if (current == null) {
            current = IlrSessionHelper.createRulePackage(session, null, parts[0]);
            session.commit(current);
            System.out.println("[ruleflows] Pacote raiz criado: " + parts[0]);
        }
        // Caminha pelos filhos
        for (int i = 1; i < parts.length; i++) {
            String childName = parts[i];
            IlrRulePackage child = findChildPackageForRuleflow(session, current, childName);
            if (child == null) {
                child = IlrSessionHelper.createRulePackage(session, current, childName);
                session.commit(child);
                System.out.println("[ruleflows] Subpacote criado: " + current.getName() + "." + childName);
            }
            current = child;
        }
        return current;
    }

    //Encontra um Rule Package de raiz pelo nome (sem parent)
    private static IlrRulePackage findRootPackageForRuleflow(IlrSession session, String name) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(
            meta.getRulePackage(),
            Arrays.asList(meta.getModelElement_Name()),
            Arrays.asList(name)
        );
        @SuppressWarnings("unchecked")
        List<IlrRulePackage> packages = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
        if (packages == null || packages.isEmpty()) return null;
        for (IlrRulePackage p : packages) {
            try {
                IlrRulePackage parent = p.getParent();
                if (parent == null) {
                    return p; // raiz
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    //Encontra um filho direto dentro de um Rule Package pai
    private static IlrRulePackage findChildPackageForRuleflow(IlrSession session,
                                                              IlrRulePackage parent,
                                                              String childName) throws Exception {
        @SuppressWarnings("unchecked")
        List<IlrRulePackage> children = parent.getChildren();
        if (children == null || children.isEmpty()) return null;
        for (IlrRulePackage c : children) {
            if (childName.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }
    
    /** Localiza um Variable Set por nome dentro de um pacote específico. */
  

 



    /** Garante que o elemento tem 'name' setado e materializado antes do commit. */
    private static void ensureNameStrong(IlrSession session, Object element, String name, String kind) {
        if (element == null) throw new IllegalArgumentException(kind + " nulo");
        if (name == null || name.isBlank()) throw new IllegalArgumentException(kind + " com nome vazio");

        // 1) tenta setName(String)
        tryInvoke(element, "setName", name);

        // 2) se ainda vazio, tenta setRawValue(ModelElement_Name, name)
        String after = getNameDyn(session, element);
        if (after == null || after.isBlank()) {
            Object nameFeature = session.getBrmPackage().getModelElement_Name();
            setRawValueDyn(element, nameFeature, name);
        }

        // 3) materializa detalhes e revalida
        Object detailed = tryInvoke(session, "getElementDetails", element);
        Object target = (detailed != null) ? detailed : element;

        String finalName = getNameDyn(session, target);
        if (finalName == null || finalName.isBlank()) {
            // último esforço: chama setName e setRawValue novamente no detalhe
            tryInvoke(target, "setName", name);
            Object nameFeature = session.getBrmPackage().getModelElement_Name();
            setRawValueDyn(target, nameFeature, name);

            finalName = getNameDyn(session, target);
            if (finalName == null || finalName.isBlank()) {
                throw new IllegalStateException(kind + " sem nome mesmo após tentativas ("
                        + target.getClass().getName() + "). Valor tentado: '" + name + "'");
            }
        }
    }

    /** Liga o VariableSet ao pacote por reflection, tentando múltiplos nomes. */
    private static boolean attachToPackageReflect(IlrVariableSet vset, IlrRulePackage pkg) {
        if (vset == null || pkg == null) return false;
        if (tryInvokeNoThrow(vset, "setRulePackage", pkg)) return true;
        if (tryInvokeNoThrow(vset, "setParent",      pkg)) return true;
        if (tryInvokeNoThrow(vset, "setOwner",       pkg)) return true;
        if (tryInvokeNoThrow(vset, "setContainer",   pkg)) return true;
        // fallback: alguns modelos permitem add no container
        if (tryInvokeNoThrow(pkg,  "addVariableSet", vset)) return true;
        if (tryInvokeNoThrow(pkg,  "addChild",       vset)) return true;
        if (tryInvokeNoThrow(pkg,  "add",            vset)) return true;
        return false;
    }


/*
    private static Class<?>[] classesOf(Object... args) {
        if (args == null) return new Class<?>[0];
        Class<?>[] cs = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) cs[i] = (args[i] == null) ? Object.class : args[i].getClass();
        return cs;
    }
    private static java.lang.reflect.Method findCompatibleMethod(Class<?> type, String name, Class<?>[] want) {
        // 1) match exato
        try { return type.getMethod(name, want); } catch (NoSuchMethodException ignored) { }
        // 2) por nome/qtde e assignable
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] got = m.getParameterTypes();
            if (got.length != want.length) continue;
            boolean ok = true;
            for (int i = 0; i < got.length; i++) {
                if (want[i] == Object.class) continue; // wildcard
                if (got[i].isAssignableFrom(want[i])) continue;
                // tolerância String/CharSequence
                if (got[i].equals(String.class) && CharSequence.class.isAssignableFrom(want[i])) continue;
                ok = false; break;
            }
            if (ok) return m;
        }
        return null;
    }
*/

    
 // Sempre gere JSON válido (usa Jackson). Se falhar, cai num fallback seguro.
    private static String jsonError(String msg) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", (msg == null ? "" : msg));
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"error\":\"" + escapeJson(String.valueOf(msg)) + "\"}";
        }
    }

    private static String jsonMessage(String msg) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("message", (msg == null ? "" : msg));
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"message\":\"" + escapeJson(String.valueOf(msg)) + "\"}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        // escape básico suficiente para nossas mensagens de erro
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }



    /** Tenta todos os overloads conhecidos (variantes de fixpack) para criar VariableSet. */




    /** Procura o set por nome dentro do pacote informado. */
    @SuppressWarnings("unchecked")
    private static IlrVariableSet findVariableSetByNameInPackage(IlrSession session, IlrRulePackage pkg, String setName) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria byName = new IlrDefaultSearchCriteria(
                meta.getVariableSet(),
                Arrays.asList(meta.getModelElement_Name()),
                Arrays.asList(setName)
        );
        List<IlrVariableSet> sets = session.findElements(byName, IlrModelConstants.ELEMENT_DETAILS);
        if (sets == null || sets.isEmpty()) return null;
        for (IlrVariableSet s : sets) {
            IlrRulePackage p = s.getRulePackage();
            if (p != null && pkg.getName() != null && pkg.getName().equals(p.getName())) return s;
        }
        return null;
    }

    /** Tenta todos os overloads conhecidos (variantes de fixpack) para criar VariableSet. */
    private static IlrVariableSet tryCreateVariableSetAnyOverload(IlrSession session, IlrRulePackage pkg, String name) {
        Object out;
        // 1) createVariableSet(pkg, name)
        out = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", pkg, name);
        if (out instanceof IlrVariableSet) return (IlrVariableSet) out;

        // 2) createVariableSet(pkg)
        out = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", pkg);
        if (out instanceof IlrVariableSet) return (IlrVariableSet) out;

        // 3) createVariableSet(session, pkg, name)
        out = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", session, pkg, name);
        if (out instanceof IlrVariableSet) return (IlrVariableSet) out;

        // 4) createVariableSet(session, pkg)
        out = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", session, pkg);
        if (out instanceof IlrVariableSet) return (IlrVariableSet) out;

        // 5) createVariableSet(session)
        out = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", session);
        if (out instanceof IlrVariableSet) return (IlrVariableSet) out;

        return null;
    }

    /** Garante que o detalhe tem 'name' — tenta múltiplos setters e múltiplas features de nome/label. */
    private static boolean ensureNameOnDetailsRobust(IlrSession session, Object elementDetails, String name) {
        if (elementDetails == null) throw new IllegalArgumentException("Detalhe nulo");
        if (isBlank(name)) throw new IllegalArgumentException("Nome vazio");

        boolean changed = false;

        // 1) setters comuns
        changed |= tryInvokeNoThrow(elementDetails, "setName",        name);
        changed |= tryInvokeNoThrow(elementDetails, "setDisplayName", name);
        changed |= tryInvokeNoThrow(elementDetails, "setLabel",       name);
        changed |= tryInvokeNoThrow(elementDetails, "setTitle",       name);

        // 2) features candidatas (nome/label) de diferentes drops
        for (Object f : candidateNameFeatures(session)) {
            if (f != null) {
                setRawValueDyn(elementDetails, f, name);
                changed = true;
            }
        }

        // 3) revalida
        String after = getNameDyn(session, elementDetails);
        return !isBlank(after);
    }

    /** Possíveis features que armazenam nome/label em diferentes fixpacks. */
    private static Object[] candidateNameFeatures(IlrSession session) {
        IlrBrmPackage meta = session.getBrmPackage();
        List<Object> list = new ArrayList<>();
        // nome técnico mais comum
        list.add(meta.getModelElement_Name());
        // variações possíveis
        list.add(tryInvoke(meta, "getRuleArtifact_Name"));
        list.add(tryInvoke(meta, "getVariableSet_Name"));
        // rótulos que alguns drops exigem
        list.add(tryInvoke(meta, "getModelElement_Label"));
        list.add(tryInvoke(meta, "getModelElement_DisplayName"));
        return list.toArray();
    }

    /** Vincula o VariableSet ao pacote por reflection, sem depender de um método específico. */
    private static boolean attachVariableSetToPackageReflect(IlrVariableSet vset, IlrRulePackage pkg) {
        if (vset == null || pkg == null) return false;
        if (tryInvokeNoThrow(vset, "setRulePackage", pkg)) return true;
        if (tryInvokeNoThrow(vset, "setParent",      pkg)) return true;
        if (tryInvokeNoThrow(vset, "setOwner",       pkg)) return true;
        if (tryInvokeNoThrow(vset, "setContainer",   pkg)) return true;
        // fallback: tenta adicionar pelo container
        if (tryInvokeNoThrow(pkg,  "addVariableSet", vset)) return true;
        if (tryInvokeNoThrow(pkg,  "addChild",       vset)) return true;
        if (tryInvokeNoThrow(pkg,  "add",            vset)) return true;
        return false;
    }

    /** ----- Reflection util: compat entre drops ----- */
    private static boolean tryInvokeNoThrow(Object target, String method, Object arg) {
        try {
            java.lang.reflect.Method m = findCompatibleMethod(target.getClass(), method, new Class<?>[]{ arg.getClass() });
            if (m == null) return false;
            m.setAccessible(true);
            m.invoke(target, arg);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    private static Object tryInvoke(Object target, String methodName, Object... args) {
        if (target == null || methodName == null) return null;
        try {
            java.lang.reflect.Method m = findCompatibleMethod(target.getClass(), methodName, classesOf(args));
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        } catch (Throwable ignore) { }
        return null;
    }
    private static Object tryInvokeStatic(Class<?> type, String methodName, Object... args) {
        if (type == null || methodName == null) return null;
        try {
            java.lang.reflect.Method m = findCompatibleMethod(type, methodName, classesOf(args));
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(null, args);
            }
        } catch (Throwable ignore) { }
        return null;
    }
    private static Class<?>[] classesOf(Object... args) {
        if (args == null) return new Class<?>[0];
        Class<?>[] cs = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) cs[i] = (args[i] == null) ? Object.class : args[i].getClass();
        return cs;
    }
    private static java.lang.reflect.Method findCompatibleMethod(Class<?> type, String name, Class<?>[] want) {
        try { return type.getMethod(name, want); } catch (NoSuchMethodException ignored) { }
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] got = m.getParameterTypes();
            if (got.length != want.length) continue;
            boolean ok = true;
            for (int i = 0; i < got.length; i++) {
                if (want[i] == Object.class) continue; // wildcard
                if (got[i].isAssignableFrom(want[i])) continue;
                if (got[i].equals(String.class) && CharSequence.class.isAssignableFrom(want[i])) continue;
                ok = false; break;
            }
            if (ok) return m;
        }
        return null;
    }

    /** Lê name via getName() ou via getRawValue(ModelElement_Name). */
    private static String getNameDyn(IlrSession session, Object element) {
        Object v = tryInvoke(element, "getName");
        if (v != null) return String.valueOf(v);
        try {
            Object nameFeature = session.getBrmPackage().getModelElement_Name();
            return getRawValueDyn(element, nameFeature);
        } catch (Throwable t) {
            return "";
        }
    }
    /** setRawValue com reflection (aceita qualquer classe concreta de feature do seu drop). */
    private static void setRawValueDyn(Object element, Object feature, String value) {
        if (element == null || feature == null || isBlank(value)) return;
        try {
            java.lang.reflect.Method m = findCompatibleMethod(
                    element.getClass(), "setRawValue", new Class<?>[]{ feature.getClass(), String.class });
            if (m != null) {
                m.setAccessible(true);
                m.invoke(element, feature, value);
            }
        } catch (Throwable ignore) { }
    }
    private static String getRawValueDyn(Object element, Object feature) {
        if (element == null || feature == null) return "";
        try {
            java.lang.reflect.Method m = findCompatibleMethod(
                    element.getClass(), "getRawValue", new Class<?>[]{ feature.getClass() });
            if (m != null) {
                m.setAccessible(true);
                Object v = m.invoke(element, feature);
                return v == null ? "" : String.valueOf(v);
            }
        } catch (Throwable ignore) { }
        return "";
    }
    
 // Monta o nome qualificado do pacote "a.b.c" subindo a cadeia de pais.

    

    /**
     * Tenta, por reflection, qualquer método estático do helper que "movimente/cole/adicione"
     * o elemento em um container, tolerando variações de ordem de argumentos.
     * Retorna true se algum método executar sem exception.
     */
  
 // Descobre o pacote do Variable Set por múltiplas rotas (compat/drops diferentes)
    private static IlrRulePackage resolvePackageOfVariableSet(IlrSession session, IlrVariableSet vset) {
        if (vset == null) return null;
        // 1) getRulePackage()
        try {
            Object rp = tryInvoke(vset, "getRulePackage");
            if (rp instanceof IlrRulePackage) return (IlrRulePackage) rp;
        } catch (Throwable ignore) {}

        // 2) getParent() -> ... até achar IlrRulePackage
        Object cur = null;
        try { cur = tryInvoke(vset, "getParent"); } catch (Throwable ignore) {}
        while (cur != null) {
            if (cur instanceof IlrRulePackage) return (IlrRulePackage) cur;
            Object next = tryInvoke(cur, "getParent");
            if (next == null) next = tryInvoke(cur, "getOwner");
            if (next == null) next = tryInvoke(cur, "getContainer");
            if (next == cur) break;
            cur = next;
        }

        // 3) getOwner()/getContainer() direto
        Object cont = tryInvoke(vset, "getOwner");
        if (cont == null) cont = tryInvoke(vset, "getContainer");
        while (cont != null) {
            if (cont instanceof IlrRulePackage) return (IlrRulePackage) cont;
            Object next = tryInvoke(cont, "getOwner");
            if (next == null) next = tryInvoke(cont, "getContainer");
            if (next == cont) break;
            cont = next;
        }
        return null;
    }

    // Candidatos de "feature" para relacionar VariableSet -> RulePackage/Container
    private static Object[] candidateContainerFeatures(IlrSession session) {
        IlrBrmPackage meta = session.getBrmPackage();
        List<Object> list = new ArrayList<>();
        // nomes comuns/observados em drops diferentes
        list.add(tryInvoke(meta, "getVariableSet_RulePackage"));
        list.add(tryInvoke(meta, "getRuleArtifact_Container"));
        list.add(tryInvoke(meta, "getRuleArtifact_Owner"));
        list.add(tryInvoke(meta, "getModelElement_Container"));
        list.add(tryInvoke(meta, "getModelElement_Owner"));
        return list.stream().filter(Objects::nonNull).toArray();
    }

    /**
     * Define uma referência de feature (container) para o elemento:
     * procura métodos como setReferenceValue(feature, value) OU setRawValue(feature, value),
     * aceitando value de referência (ex.: IlrRulePackage) — não apenas String.
     */
    private static boolean setFeatureReferenceDyn(Object element, Object feature, Object refValue) {
        if (element == null || feature == null || refValue == null) return false;
        Class<?> featureClass = feature.getClass();
        // 1) setReferenceValue(FeatureType, RefType)
        try {
            java.lang.reflect.Method m = findCompatibleMethod(
                    element.getClass(), "setReferenceValue",
                    new Class<?>[]{ featureClass, refValue.getClass() }
            );
            if (m != null) {
                m.setAccessible(true);
                m.invoke(element, feature, refValue);
                return true;
            }
        } catch (Throwable ignore) {}

        // 2) setRawValue(FeatureType, RefType) — alguns drops usam este mesmo nome
        try {
            java.lang.reflect.Method m = findCompatibleMethod(
                    element.getClass(), "setRawValue",
                    new Class<?>[]{ featureClass, refValue.getClass() }
            );
            if (m != null) {
                m.setAccessible(true);
                m.invoke(element, feature, refValue);
                return true;
            }
        } catch (Throwable ignore) {}

        // 3) outros setters menos comuns (compat)
        String[] names = new String[] { "setValue", "setRefValue" };
        for (String n : names) {
            try {
                java.lang.reflect.Method m = findCompatibleMethod(
                        element.getClass(), n,
                        new Class<?>[]{ featureClass, refValue.getClass() }
                );
                if (m != null) {
                    m.setAccessible(true);
                    m.invoke(element, feature, refValue);
                    return true;
                }
            } catch (Throwable ignore) {}
        }
        return false;
    }

    /**
     * Reflection estático flexível:
     * tenta vários métodos de helper (ex.: moveElement/pasteElement/addElement),
     * com diferentes combinações de argumentos.
     */
    private static boolean tryInvokeStaticAny(Class<?> type, String[] methodNameCandidates, Object[][] argCombos) {
        for (String m : methodNameCandidates) {
            for (Object[] args : argCombos) {
                try {
                    java.lang.reflect.Method mm = findCompatibleMethod(type, m, classesOf(args));
                    if (mm != null) {
                        mm.setAccessible(true);
                        mm.invoke(null, args);
                        return true;
                    }
                } catch (Throwable ignore) {
                    // tenta próxima combinação
                }
            }
        }
        return false;
    }

    // Nome qualificado do pacote
    private static String qualifiedName(IlrRulePackage p) {
        if (p == null) return "";
        Deque<String> parts = new ArrayDeque<>();
        IlrRulePackage cur = p;
        while (cur != null) {
            try {
                parts.addFirst(cur.getName() == null ? "" : cur.getName());
                cur = cur.getParent();
            } catch (Exception e) {
                break;
            }
        }
        return String.join(".", parts);
    }
}
