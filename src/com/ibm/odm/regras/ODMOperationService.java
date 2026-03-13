package com.ibm.odm.regras;

import ilog.rules.teamserver.brm.*;
import ilog.rules.teamserver.client.IlrRemoteSessionFactory;
import ilog.rules.teamserver.model.*;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com.ibm.rules.decisionservice.model.IDsDecisionOperation;
import com.ibm.rules.decisionservice.model.IDsRuleflow;
import ilog.rules.teamserver.model.deployment.commands.CreateDecisionOperation;
import ilog.rules.teamserver.model.deployment.commands.EditDecisionOperation;

import java.lang.reflect.Method;
import java.util.*;

/**
 * ODMOperationService — criação/atualização de Decision Operation (drop-agnóstico) — FAIL-SOFT
 *
 * Principais pontos desta versão:
 *  - Associa o Ruleflow à Operation via DSM usando setRawValue(feature, IlrRuleflow) e materializa o objeto antes.
 *  - Lê de volta o ruleflow atualmente associado (readLinkedRuleflowName) e inclui em dsmRuleflowLinked na saída.
 *  - Persiste rulesetName em design-time se houver feature no DSM; caso contrário, fallback em VS (ruleset_name).
 *  - Mantém overloads saveOperation(...) com 11, 13 e 14 parâmetros para compat com diferentes chamadas.
 */
public class ODMOperationService {
    private final String serverUrl;
    private final String datasource;
    private final String login;
    private final String password;

    public ODMOperationService(String serverUrl, String datasource, String login, String password) {
        this.serverUrl = serverUrl;
        this.datasource = datasource;
        this.login = login;
        this.password = password;
    }

    // ================== Sessão / Projeto / Baseline ==================
    public IlrSession openSession() throws IlrConnectException {
        IlrSessionFactory factory = new IlrRemoteSessionFactory();
        factory.connect(login, password, serverUrl, datasource);
        return factory.getSession();
    }
    public void closeSession(IlrSession s) {
        if (s != null) try { s.close(); } catch (Exception ignore) {}
    }
    public IlrRuleProject getProjectOrThrow(IlrSession session, String projectName) throws Exception {
        IlrRuleProject p = (IlrRuleProject) IlrSessionHelper.getProjectNamed(session, projectName);
        if (p == null) throw new IllegalStateException("Projeto não encontrado: " + projectName);
        return p;
    }
    public IlrBaseline resolveBaseline(IlrSession session, IlrRuleProject project, String baselineName) throws Exception {
        if (baselineName == null || baselineName.isBlank() || "%current_key".equalsIgnoreCase(baselineName)) {
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
    private ODMVariableService vars() {
        return new ODMVariableService(serverUrl, datasource, login, password);
    }

    // ================== Ruleflow (busca global) ==================
    @SuppressWarnings("unchecked")
    public IlrRuleflow findRuleflowByNameAnyPackage(IlrSession session, String ruleflowName) throws Exception {
        if (ruleflowName == null || ruleflowName.isBlank()) return null;
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(
                meta.getRuleflow(),
                Arrays.asList(meta.getModelElement_Name()),
                Arrays.asList(ruleflowName)
        );
        List<IlrRuleflow> flows = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
        if (flows == null || flows.isEmpty()) return null;

        // Preferir flow em pacote raiz (parent == null). Se ainda ambíguo, retornar null.
        List<IlrRuleflow> roots = new ArrayList<>();
        for (IlrRuleflow rf : flows) {
            IlrRulePackage parent = null;
            try {
                IlrRulePackage pkg = rf.getRulePackage();
                if (pkg == null) continue;
                parent = pkg.getParent();
            } catch (Exception ignore) {}
            if (parent == null) roots.add(rf);
        }
        if (roots.size() == 1) return roots.get(0);
        if (flows.size() == 1) return flows.get(0);
        return null; // ambíguo
    }

    // ================== CORE: Create/Update Operation ==================
    /**
     * Cria/atualiza Decision Operation (DSM) e:
     *  - adiciona parâmetros
     *  - associa Ruleflow (se informado) e lê de volta o vínculo (dsmRuleflowLinked)
     *  - tenta persistir rulesetName (feature DSM) ou grava em VS (fallback)
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> createOrUpdateServiceOperation(
            IlrSession session,
            IlrRuleProject project,
            String packagePathIgnored,          // Operation não vive em Rule Package
            String operationName,
            String description,
            List<Map<String, String>> parameters,
            String ruleflowName,
            String rulesetName,
            String variableSetName               // Nome do Variable Set para parâmetros
    ) throws Exception {

        Map<String,String> result = new LinkedHashMap<>();
        result.put("operationName", operationName);

        System.out.println("\n[ODMOperationService] ========== CRIANDO/ATUALIZANDO DECISION OPERATION ==========");
        System.out.println("[ODMOperationService] Nome: " + operationName);
        System.out.println("[ODMOperationService] Ruleflow requisitado: " + ruleflowName);
        System.out.println("[ODMOperationService] RulesetName: " + rulesetName);
        System.out.println("[ODMOperationService] Parâmetros: " + (parameters != null ? parameters.size() : 0));

        try {
            // Metamodelos via reflexão
            IlrBrmPackage brm = session.getBrmPackage();
            Method getModelInfoMethod = session.getClass().getMethod("getModelInfo");
            Object modelInfo = getModelInfoMethod.invoke(session);
            Method getDsmPackageMethod = modelInfo.getClass().getMethod("getDsmPackage");
            Object dsm = getDsmPackageMethod.invoke(modelInfo);
            Object nameESF = brm.getModelElement_Name();
            System.out.println("[ODMOperationService] ✓ Metamodelos (BRM/DSM) obtidos via reflexão");

            // 1) Cria Operation (DSM)
            Method getOperationMethod = dsm.getClass().getMethod("getOperation");
            Object operationType = getOperationMethod.invoke(dsm);
            IlrElementHandle operationHandle = session.createElement((EClass) operationType);
            if (operationHandle == null) throw new IllegalStateException("Falha ao criar operation handle");
            System.out.println("[ODMOperationService] ✓ Operation handle criado");

            // 2) Define propriedades básicas
            IlrElementDetails opDtls = session.getElementDetailsForThisHandle(operationHandle);
            opDtls.setRawValue((EStructuralFeature) nameESF, operationName);
            if (description != null && !description.isBlank()) {
                Method getBusinessDisplayNameMethod = dsm.getClass().getMethod("getOperation_BusinessDisplayName");
                Object businessDisplayNameFeature = getBusinessDisplayNameMethod.invoke(dsm);
                opDtls.setRawValue((EStructuralFeature) businessDisplayNameFeature, description);
            }

            // 3) Commit inicial
            IlrCommitableObject cObj = new IlrCommitableObject(operationHandle);
            cObj.setRootDetails(opDtls);
            operationHandle = session.commit(cObj);
            if (operationHandle == null) throw new IllegalStateException("Falha ao commitar operation");
            System.out.println("[ODMOperationService] ✓ Operation commitada: " + operationName);

            // 4) Adiciona variáveis (parâmetros) à Operation
            if (parameters != null && !parameters.isEmpty()) {
                // Determina qual Variable Set usar (prioridade: parâmetro > "variaveis" > "global")
                String vsName = (variableSetName != null && !variableSetName.isBlank())
                    ? variableSetName
                    : "variaveis";
                
                for (Map<String, String> param : parameters) {
                    String pName = param.get("name");
                    String direction = param.get("direction");
                    if (pName == null || pName.isBlank()) continue;

                    // Busca Variable Set (tenta o especificado, depois fallback para "global")
                    IlrElementHandle varSetHandle = findVariableSetHandleByName(session, vsName);
                    if (varSetHandle == null && !"global".equals(vsName)) {
                        System.out.println("[ODMOperationService] ⚠ Variable Set '" + vsName + "' não encontrado, tentando 'global'...");
                        varSetHandle = findVariableSetHandleByName(session, "global");
                    }
                    if (varSetHandle == null) {
                        System.out.println("[ODMOperationService] ✗ Variable Set '" + vsName + "' não encontrado");
                        continue;
                    }

                    // Cria OperationVariable
                    Method getOperationVariableMethod = dsm.getClass().getMethod("getOperationVariable");
                    Object opVarType = getOperationVariableMethod.invoke(dsm);
                    IlrElementHandle opVarHandle = session.createElement((EClass) opVarType);
                    IlrElementDetails opVarDtls = session.getElementDetailsForThisHandle(opVarHandle);

                    // nome
                    Method getVarNameMethod = dsm.getClass().getMethod("getOperationVariable_VariableName");
                    Object varNameFeature = getVarNameMethod.invoke(dsm);
                    opVarDtls.setRawValue((EStructuralFeature) varNameFeature, pName);

                    // variable set (usa setRawValue com o handle, como no exemplo oficial)
                    Method getVarSetMethod = dsm.getClass().getMethod("getOperationVariable_VariableSet");
                    Object varSetFeature = getVarSetMethod.invoke(dsm);
                    opVarDtls.setRawValue((EStructuralFeature) varSetFeature, varSetHandle);

                    // direção (enum -> literal string)
                    Method getDirectionMethod = dsm.getClass().getMethod("getOperationVariable_Direction");
                    Object directionFeature = getDirectionMethod.invoke(dsm);
                    Method getDirectionKindMethod = brm.getClass().getMethod("getDirectionKind");
                    Object directionKindEnum = getDirectionKindMethod.invoke(brm);
                    String literalName = "IN".equalsIgnoreCase(direction) ? "IN" :
                            "OUT".equalsIgnoreCase(direction) ? "OUT" :
                                    "INOUT".equalsIgnoreCase(direction) ? "INOUT" : "IN";
                    Method getEEnumLiteralMethod = directionKindEnum.getClass().getMethod("getEEnumLiteral", String.class);
                    Object directionLiteral = getEEnumLiteralMethod.invoke(directionKindEnum, literalName);
                    String directionString = directionLiteral.toString();
                    opVarDtls.setRawValue((EStructuralFeature) directionFeature, directionString);

                    // adiciona na Operation
                    Method getRefVarsMethod = dsm.getClass().getMethod("getOperation_ReferencedVariables");
                    Object refVarsFeature = getRefVarsMethod.invoke(dsm);
                    cObj = new IlrCommitableObject(operationHandle);
                    cObj.addModifiedElement((EReference) refVarsFeature, opVarDtls);
                    operationHandle = session.commit(cObj);

                    System.out.println("[ODMOperationService] ✓ Parâmetro adicionado: " + pName + " (" + direction + ")");
                }
            }

            // 5) Associa Ruleflow (se informado)
            if (ruleflowName != null && !ruleflowName.isBlank()) {
                IlrRuleflow ruleflow = findRuleflowByNameAnyPackage(session, ruleflowName);
                if (ruleflow != null) {
                    // Obter o nome completo do ruleflow com o projeto
                    String ruleflowFullPath = null;
                    String projectName = null;
                    try {
                        // Obter o projeto do ruleflow
                        IlrRuleProject rfProject = (IlrRuleProject) ruleflow.getProject();
                        projectName = rfProject.getName();
                        ruleflowFullPath = projectName + "/" + ruleflowName;
                        System.out.println("[ODMOperationService] Ruleflow path completo: " + ruleflowFullPath);
                    } catch (Exception e) {
                        System.out.println("[ODMOperationService] ⚠ Não conseguiu obter projeto do ruleflow: " + e.getMessage());
                        ruleflowFullPath = ruleflowName; // fallback
                    }
                    
                    // Tentativa 1: Usar EditDecisionOperation (forma oficial)
                    boolean success = false;
                    if (projectName != null) {
                        success = associateRuleflowViaEditCommand(session, operationHandle, ruleflowFullPath, operationName, projectName);
                        if (success) {
                            System.out.println("[ODMOperationService] ✓ Ruleflow associado via EditDecisionOperation!");
                        }
                    }
                    
                    // Tentativa 2: Usar setRawValue com objeto materializado (fallback)
                    if (!success) {
                        System.out.println("[ODMOperationService] Associando ruleflow via setRawValue...");
                    try {
                        Method getRuleflowMethod = dsm.getClass().getMethod("getOperation_Ruleflow");
                        Object ruleflowFeature = getRuleflowMethod.invoke(dsm);
                        
                        Method getUsingRuleflowMethod = dsm.getClass().getMethod("getOperation_UsingRuleflow");
                        Object usingRuleflowFeature = getUsingRuleflowMethod.invoke(dsm);
                        
                        ruleflow = (IlrRuleflow) session.getElementDetails(ruleflow);
                        IlrElementDetails opDtlsRuleflow = session.getElementDetails(operationHandle);
                        
                        // Seta o flag UsingRuleflow como true (conforme exemplo)
                        opDtlsRuleflow.setRawValue((EStructuralFeature) usingRuleflowFeature, true);
                        
                        // Seta o Ruleflow (conforme exemplo)
                        opDtlsRuleflow.setRawValue((EStructuralFeature) ruleflowFeature, ruleflow);
                        
                        // Tentar setar também o mainRuleflow com o path completo
                        try {
                            Method getMainRuleflowMethod = dsm.getClass().getMethod("getOperation_MainRuleflow");
                            Object mainRuleflowFeature = getMainRuleflowMethod.invoke(dsm);
                            opDtlsRuleflow.setRawValue((EStructuralFeature) mainRuleflowFeature, ruleflowFullPath);
                            System.out.println("[ODMOperationService] ✓ mainRuleflow setado: " + ruleflowFullPath);
                        } catch (Exception e) {
                            System.out.println("[ODMOperationService] ⚠ Não conseguiu setar mainRuleflow: " + e.getMessage());
                        }
                        
                        IlrCommitableObject cObjRuleflow = new IlrCommitableObject(operationHandle);
                        cObjRuleflow.setRootDetails(opDtlsRuleflow);
                        IlrElementHandle newHandle = session.commit(cObjRuleflow);
                        if (newHandle != null) {
                            operationHandle = newHandle;
                            System.out.println("[ODMOperationService] ✓ Ruleflow associado via setRawValue: " + ruleflowFullPath + ", UsingRuleflow=true");
                        }
                    } catch (Exception e) {
                        System.out.println("[ODMOperationService] ✗ Falha ao associar ruleflow: " + e.getMessage());
                        e.printStackTrace();
                    }
                    }
                } else {
                    System.out.println("[ODMOperationService] ⚠ Ruleflow não encontrado: " + ruleflowName);
                }
            }

            // 5.1) Configura Extractor (sem usar reflexão para mainRuleflow)
            if (ruleflowName != null && !ruleflowName.isBlank()) {
                try {
                    System.out.println("[ODMOperationService] [DEBUG] Configurando extractor...");
                    IlrElementDetails opDtlsExtra = session.getElementDetails(operationHandle);
                    boolean modified = false;
                    
                    // Extractor
                    try {
                        Method getExtractorMethod = dsm.getClass().getMethod("getOperation_Extractor");
                        Object extractorFeature = getExtractorMethod.invoke(dsm);
                        String extractorName = operationName + "_extractor";
                        opDtlsExtra.setRawValue((EStructuralFeature) extractorFeature, extractorName);
                        modified = true;
                        System.out.println("[ODMOperationService] ✓ Extractor setado: " + extractorName);
                    } catch (Exception ignore) {}
                    
                    // ExtractorValidator
                    try {
                        Method getValidatorMethod = dsm.getClass().getMethod("getOperation_ExtractorValidator");
                 
                        Object validatorFeature = getValidatorMethod.invoke(dsm);
                        opDtlsExtra.setRawValue((EStructuralFeature) validatorFeature, "Default Validator");
                        modified = true;
                        System.out.println("[ODMOperationService] ✓ ExtractorValidator setado");
                    } catch (Exception ignore) {}
                    
                    // Commit se algo foi modificado
                    if (modified) {
                        cObj = new IlrCommitableObject(operationHandle);
                        cObj.setRootDetails(opDtlsExtra);
                        IlrElementHandle newHandle = session.commit(cObj);
                        if (newHandle != null) {
                            operationHandle = newHandle;
                            System.out.println("[ODMOperationService] ✓ Configurações extras commitadas");
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[ODMOperationService] ⚠ Falha ao configurar extras: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            // 5.2) Leitura de volta (independe de ter vindo ruleflow no POST)
            System.out.println("[ODMOperationService] [DEBUG] Iniciando leitura do ruleflow associado...");
            String linkedRf = readLinkedRuleflowName(session, dsm, operationHandle);
            System.out.println("[ODMOperationService] [DEBUG] Resultado da leitura: '" + linkedRf + "'");
            result.put("dsmRuleflowLinked", linkedRf == null ? "" : linkedRf);
            if (linkedRf != null && !linkedRf.isBlank()) {
                System.out.println("[ODMOperationService] (DSM) Ruleflow atualmente ligado à Operation = " + linkedRf);
            } else {
                System.out.println("[ODMOperationService] (DSM) Operation sem ruleflow ligado (ou leitura não disponível)");
            }

            // 6) Grava rulesetName (se informado): feature DSM ou fallback em VS
            if (rulesetName != null && !rulesetName.isBlank()) {
                boolean wroteDesignTime = setOperationRulesetDesignTime(session, dsm, operationHandle, rulesetName);
                if (!wroteDesignTime) {
                    System.out.println("[ODMOperationService] ↪ Fallback: gravando 'ruleset_name' em descriptor VS");
                    tryPersistRulesetNameInDescriptor(session, operationName, rulesetName);
                }
            }

            result.put("approach", "dsm_operation");
            result.put("status", "created");
            result.put("operationHandle", operationHandle.toString());
            System.out.println("[ODMOperationService] ✓✓✓ DECISION OPERATION CRIADA/ATUALIZADA COM SUCESSO! ✓✓✓");

        } catch (Exception e) {
            System.err.println("[ODMOperationService] ERRO: " + e.getMessage());
            e.printStackTrace();
            result.put("approach", "error");
            result.put("error", e.getMessage());
        }

        System.out.println("[ODMOperationService] ========== FIM CRIAÇÃO ==========\n");
        return result;
    }

    /**
     * Associa Ruleflow usando a classe oficial EditDecisionOperation do ODM.
     * Esta é a forma CORRETA usada pelo próprio Decision Center.
     */
    private boolean associateRuleflowViaEditCommand(
            IlrSession session,
            IlrElementHandle operationHandle,
            String ruleflowName,
            String operationName,
            String projectName) {
        try {
            System.out.println("[ODMOperationService] [EDIT COMMAND] Usando EditDecisionOperation oficial...");
            
            // Pega os detalhes da operation
            IlrElementDetails opDetails = session.getElementDetails(operationHandle);
            
            // O ODM usa IDs no formato "dsm.Operation:projectId:elementId"
            // O toString() retorna: "IlrElementHandleImpl@xxx[type: dsm.Operation, ejbIdentifier: dsm.Operation:266:269]"
            // Precisamos extrair apenas "dsm.Operation:266:269"
            String opId = null;
            
            try {
                String handleStr = operationHandle.toString();
                System.out.println("[ODMOperationService] [DEBUG] Handle toString: " + handleStr);
                
                // Extrair o ejbIdentifier
                if (handleStr != null && handleStr.contains("ejbIdentifier:")) {
                    int startIdx = handleStr.indexOf("ejbIdentifier:") + "ejbIdentifier:".length();
                    int endIdx = handleStr.indexOf("]", startIdx);
                    if (endIdx > startIdx) {
                        opId = handleStr.substring(startIdx, endIdx).trim();
                        System.out.println("[ODMOperationService] [DEBUG] ID extraído: " + opId);
                    }
                }
            } catch (Exception e) {
                System.out.println("[ODMOperationService] [DEBUG] Erro ao extrair ID: " + e.getMessage());
            }
            
            // Se não conseguiu extrair, tentar via reflexão para obter ejbIdentifier
            if (opId == null) {
                try {
                    Method getEjbIdMethod = operationHandle.getClass().getMethod("getEjbIdentifier");
                    Object ejbId = getEjbIdMethod.invoke(operationHandle);
                    if (ejbId != null) {
                        opId = String.valueOf(ejbId);
                        System.out.println("[ODMOperationService] [DEBUG] ID via getEjbIdentifier: " + opId);
                    }
                } catch (Exception e) {
                    System.out.println("[ODMOperationService] [DEBUG] Não tem getEjbIdentifier: " + e.getMessage());
                }
            }
            
            if (opId == null || opId.isBlank()) {
                System.out.println("[ODMOperationService] [EDIT COMMAND] Não conseguiu obter ID no formato dsm.Operation:x:y");
                return false;
            }
            
            EditDecisionOperation editCmd = new EditDecisionOperation(session);
            
            // CRÍTICO: Setar o baselineId (obrigatório para BaselineCommand)
            try {
                IlrBaseline baseline = session.getWorkingBaseline();
                if (baseline != null) {
                    // Tentar obter o ID da baseline via reflexão
                    try {
                        Method getBaselineIdMethod = baseline.getClass().getMethod("getBaselineId");
                        Object blId = getBaselineIdMethod.invoke(baseline);
                        if (blId != null) {
                            editCmd.setBaselineId(String.valueOf(blId));
                            System.out.println("[ODMOperationService] [DEBUG] BaselineId setado: " + blId);
                        }
                    } catch (Exception e) {
                        // Tentar toString da baseline
                        String blStr = baseline.toString();
                        System.out.println("[ODMOperationService] [DEBUG] Baseline toString: " + blStr);
                        // Usar o nome da baseline como ID
                        editCmd.setBaselineId(baseline.getName());
                        System.out.println("[ODMOperationService] [DEBUG] BaselineId setado via getName: " + baseline.getName());
                    }
                }
            } catch (Exception e) {
                System.out.println("[ODMOperationService] [DEBUG] Erro ao obter baseline: " + e.getMessage());
            }
            
            // Configurar TODOS os campos necessários usando os métodos corretos
            editCmd.setId(opId);  // ← Método correto (não setDecisionOperationId)!
            editCmd.setMainRuleflow(ruleflowName);
            editCmd.setOperationName(operationName);
            editCmd.setDescription("Operation updated via API");
            
            // Setar o projeto (recebido como parâmetro)
            try {
                editCmd.setStoredInProject(projectName);
                System.out.println("[ODMOperationService] [DEBUG] StoredInProject setado: " + projectName);
            } catch (Exception e) {
                System.out.println("[ODMOperationService] [DEBUG] Não conseguiu setar storedInProject: " + e.getMessage());
            }
            
            // Tentar executar SEM verificar isApplicable (pode estar bugado)
            System.out.println("[ODMOperationService] [DEBUG] Executando comando diretamente...");
            boolean success = false;
            try {
                success = editCmd.execute();
                System.out.println("[ODMOperationService] [DEBUG] Resultado execute(): " + success);
            } catch (Exception execEx) {
                System.out.println("[ODMOperationService] [DEBUG] Erro no execute(): " + execEx.getMessage());
                execEx.printStackTrace();
            }
            
            if (success) {
                System.out.println("[ODMOperationService] ✓ Ruleflow associado via EditDecisionOperation: " + ruleflowName);
            } else {
                System.out.println("[ODMOperationService] [EDIT COMMAND] execute() retornou false");
            }
            return success;
            
        } catch (Exception e) {
            System.out.println("[ODMOperationService] [EDIT COMMAND] Falhou: " + e.getMessage());
            return false;
        }
    }

    /**
     * Associa Ruleflow usando API DSM de alto nível (IDsDecisionOperation).
     * Retorna true se conseguiu, false caso contrário.
     */
    private boolean associateRuleflowViaDSM(
            IlrSession session,
            IlrElementHandle operationHandle,
            IlrRuleflow ruleflow,
            String ruleflowName) {
        try {
            // Obter detalhes da Operation
            IlrElementDetails opDetails = session.getElementDetails(operationHandle);
            
            // Tentar cast para interface DSM
            IDsDecisionOperation dsmOp = (IDsDecisionOperation) opDetails;
            
            // Materializar ruleflow
            IlrElementDetails rfDetails = session.getElementDetails(ruleflow);
            IDsRuleflow dsmRuleflow = (IDsRuleflow) rfDetails;
            
            // Usar API de alto nível
            dsmOp.setRuleflow(dsmRuleflow);
            dsmOp.setRuleflowName(ruleflowName);
            dsmOp.setUsingRuleflow(true);
            
            // Commit usando IlrCommitableObject com o handle original
            IlrCommitableObject cObj = new IlrCommitableObject(operationHandle);
            cObj.setRootDetails(opDetails);
            session.commit(cObj);
            
            // Verificar
            String linked = dsmOp.getRuleflowName();
            boolean using = dsmOp.isUsingRuleflow();
            
            System.out.println("[ODMOperationService] [DSM API] Ruleflow: " + linked + ", Using: " + using);
            
            return linked != null && !linked.isBlank() && using;
            
        } catch (ClassCastException e) {
            System.out.println("[ODMOperationService] [DSM API] Cast falhou - API DSM não disponível");
            return false;
        } catch (Exception e) {
            System.out.println("[ODMOperationService] [DSM API] Erro: " + e.getMessage());
            return false;
        }
    }

    /**
     * Associa o Ruleflow à Operation (método via reflexão).
     * Usa setRawValue de acordo com o exemplo fornecido:
     * operation.setRawValue(dsm.getOperation_UsingRuleflow(), true);
     * operation.setRawValue(dsm.getOperation_Ruleflow(), ruleflow);
     */
    private boolean setOperationRuleflowReference(
            IlrSession session,
            Object dsm,
            IlrElementHandle operationHandle,
            IlrRuleflow ruleflow) {
        try {
            // Obter as features do DSM
            Method getRuleflowMethod = dsm.getClass().getMethod("getOperation_Ruleflow");
            Object ruleflowFeature = getRuleflowMethod.invoke(dsm);
            
            Method getUsingRuleflowMethod = dsm.getClass().getMethod("getOperation_UsingRuleflow");
            Object usingRuleflowFeature = getUsingRuleflowMethod.invoke(dsm);

            // Materializa o ruleflow
            ruleflow = (IlrRuleflow) session.getElementDetails(ruleflow);

            // Pega os detalhes atuais da operation
            IlrElementDetails opDtls = session.getElementDetails(operationHandle);

            // Seta o flag UsingRuleflow como true (conforme exemplo)
            opDtls.setRawValue((EStructuralFeature) usingRuleflowFeature, true);
            
            // Seta o Ruleflow (conforme exemplo)
            opDtls.setRawValue((EStructuralFeature) ruleflowFeature, ruleflow);

            // Commit usando setRootDetails
            IlrCommitableObject cObj = new IlrCommitableObject(operationHandle);
            cObj.setRootDetails(opDtls);
            operationHandle = session.commit(cObj);

            System.out.println("[ODMOperationService] ✓ Ruleflow associado via setRawValue: "
                    + (ruleflow == null ? "<null>" : ruleflow.getName()) + ", UsingRuleflow=true");
            return true;
        } catch (Exception e) {
            System.out.println("[ODMOperationService] ⚠ Falha ao associar ruleflow: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Lê do DSM o ruleflow atualmente associado à Operation e retorna o nome.
     * Tenta API DSM primeiro, depois fallback para reflexão.
     */
    private String readLinkedRuleflowName(IlrSession session, Object dsm, IlrElementHandle operationHandle) {
        // Tenta API DSM primeiro
        try {
            IlrElementDetails opDtls = session.getElementDetails(operationHandle);
            IDsDecisionOperation dsmOp = (IDsDecisionOperation) opDtls;
            
            String rfName = dsmOp.getRuleflowName();
            boolean using = dsmOp.isUsingRuleflow();
            
            System.out.println("[ODMOperationService] [DSM API] getRuleflowName: " + rfName + ", isUsing: " + using);
            
            if (rfName != null && !rfName.isBlank()) {
                return rfName;
            }
        } catch (ClassCastException e) {
            System.out.println("[ODMOperationService] [DSM API] Cast falhou, usando reflexão...");
        } catch (Exception e) {
            System.out.println("[ODMOperationService] [DSM API] Erro na leitura: " + e.getMessage());
        }
        
        // Fallback para reflexão
        try {
            Method getRuleflowMethod = dsm.getClass().getMethod("getOperation_Ruleflow");
            Object ruleflowFeature = getRuleflowMethod.invoke(dsm);
            IlrElementDetails opDtls = session.getElementDetails(operationHandle);
            Method getRaw = opDtls.getClass().getMethod("getRawValue", EStructuralFeature.class);
            getRaw.setAccessible(true);
            Object linked = getRaw.invoke(opDtls, ruleflowFeature);
            
            if (linked == null) return "";
            if (linked instanceof IlrElementHandle) {
                linked = session.getElementDetails((IlrElementHandle) linked);
            }
            try {
                Object name = linked.getClass().getMethod("getName").invoke(linked);
                return (name == null) ? "" : String.valueOf(name);
            } catch (Throwable ignore) {
                return String.valueOf(linked);
            }
        } catch (Throwable t) {
            System.out.println("[ODMOperationService] [REFLEXÃO] Erro: " + t.getMessage());
            return "";
        }
    }

    /**
     * Grava rulesetName em design-time. Tenta API DSM primeiro, depois reflexão.
     */
    private boolean setOperationRulesetDesignTime(IlrSession session, Object dsm, IlrElementHandle operationHandle, String rulesetName) {
        // Tenta API DSM primeiro
        try {
            IlrElementDetails opDtls = session.getElementDetails(operationHandle);
            IDsDecisionOperation dsmOp = (IDsDecisionOperation) opDtls;
            
            dsmOp.setRulesetName(rulesetName);
            
            // Commit usando IlrCommitableObject
            IlrCommitableObject cObj = new IlrCommitableObject(operationHandle);
            cObj.setRootDetails(opDtls);
            session.commit(cObj);
            
            System.out.println("[ODMOperationService] ✓ RulesetName gravado via API DSM: " + rulesetName);
            return true;
        } catch (ClassCastException e) {
            System.out.println("[ODMOperationService] [DSM API] Cast falhou, usando reflexão...");
        } catch (Exception e) {
            System.out.println("[ODMOperationService] [DSM API] Erro ao gravar ruleset: " + e.getMessage());
        }
        
        // Fallback para reflexão
        final String[] featureCandidates = new String[] {
                "getOperation_RulesetName",
                "getOperation_RuleSetName",
                "getOperation_RuleappName",
                "getOperation_RuleAppName"
        };
        for (String m : featureCandidates) {
            try {
                Method getter = dsm.getClass().getMethod(m);
                Object feature = getter.invoke(dsm);
                if (feature == null) continue;

                IlrElementDetails opDtls = session.getElementDetailsForThisHandle(operationHandle);
                boolean ok = setRawValueDyn(opDtls, feature, String.valueOf(rulesetName));
                if (ok) {
                    IlrCommitableObject cObj = new IlrCommitableObject(operationHandle);
                    cObj.setRootDetails(opDtls);
                    session.commit(cObj);
                    System.out.println("[ODMOperationService] ✓ RulesetName gravado via reflexão: " + m);
                    return true;
                }
            } catch (NoSuchMethodException nsme) {
                // tenta próximo
            } catch (Exception e) {
                System.out.println("[ODMOperationService] ⚠ Falha ao gravar rulesetName via " + m + ": " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Fallback: grava 'ruleset_name' como metadado no(s) Variable Set(s) de descriptor.
     */
    private void tryPersistRulesetNameInDescriptor(IlrSession session, String operationName, String rulesetName) throws Exception {
        ODMVariableService vsvc = vars();
        String opSafe = sanitize(operationName);
        List<String> descVSNames = Collections.singletonList("operation_" + opSafe + "_descriptor");
        List<IlrVariableSet> descVSList = resolveExistingSetsGlobal(session, descVSNames);
        boolean ok = upsertAcrossSets(session, vsvc, descVSList, "ruleset_name", nvl(rulesetName));
        if (!ok) {
            System.out.println("[ODMOperationService] ⚠ 'ruleset_name' não gravado (variável inexistente nos descriptor VS)");
        }
    }

    // ================== Utils DSM/Reflection (genéricos) ==================
    private static boolean setReferenceValueDyn(Object elementDetails, Object feature, Object refHandleOrObject) {
        if (elementDetails == null || feature == null || refHandleOrObject == null) return false;
        try {
            Method m = findCompatibleMethod(
                    elementDetails.getClass(), "setReferenceValue",
                    new Class<?>[]{ feature.getClass(), refHandleOrObject.getClass() }
            );
            if (m != null) {
                m.setAccessible(true);
                m.invoke(elementDetails, feature, refHandleOrObject);
                return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private static boolean setRawValueDyn(Object elementOrDetails, Object feature, Object value) {
        if (elementOrDetails == null || feature == null || value == null) return false;
        try {
            Method m = findCompatibleMethod(
                    elementOrDetails.getClass(), "setRawValue",
                    new Class<?>[]{ feature.getClass(), value.getClass() }
            );
            if (m != null) {
                m.setAccessible(true);
                m.invoke(elementOrDetails, feature, value);
                return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    // ================== Busca de Variable Set (por nome) ==================
    @SuppressWarnings("unchecked")
    private IlrElementHandle findVariableSetHandleByName(IlrSession session, String setName) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(
                meta.getVariableSet(),
                Arrays.asList(meta.getModelElement_Name()),
                Arrays.asList(setName)
        );
        List<IlrElementHandle> handles = session.findElements(criteria, IlrModelConstants.ELEMENT_HANDLE);
        if (handles != null && !handles.isEmpty()) return handles.get(0);
        return null;
    }

    @SuppressWarnings("unchecked")
    private IlrVariableSet findVariableSetByName(IlrSession session, String setName) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(
                meta.getVariableSet(),
                Arrays.asList(meta.getModelElement_Name()),
                Arrays.asList(setName)
        );
        List<IlrVariableSet> sets = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
        if (sets != null && !sets.isEmpty()) return sets.get(0);
        return null;
    }

    // ================== API: salvar / visualizar ==================
    // Overload (11 parâmetros) — compat legado
    public Map<String,Object> saveOperation(
            String projectName,
            String baselineName,
            String operationName,
            String ruleflowPackageIgnored,
            String ruleflowName,
            List<Map<String,String>> parameters,
            Map<String,String> rulesetParameters,
            String registryRootPackageIgnored,
            List<String> descriptorSetNames,
            List<String> paramsSetNames,
            List<String> rulesetParamSetNames
    ) throws Exception {
        return saveOperation(projectName, baselineName, operationName,
                ruleflowPackageIgnored, ruleflowName, parameters, rulesetParameters,
                registryRootPackageIgnored, descriptorSetNames, paramsSetNames, rulesetParamSetNames,
                null, null, null);
    }

    // Overload (13 parâmetros) — compat com endpoint atual (sem rulesetName)
    public Map<String,Object> saveOperation(
            String projectName,
            String baselineName,
            String operationName,
            String ruleflowPackageIgnored,
            String ruleflowName,
            List<Map<String,String>> parameters,
            Map<String,String> rulesetParameters,
            String registryRootPackageIgnored,
            List<String> descriptorSetNames,
            List<String> paramsSetNames,
            List<String> rulesetParamSetNames,
            String packagePathIgnored,
            String description
    ) throws Exception {
        return saveOperation(projectName, baselineName, operationName,
                ruleflowPackageIgnored, ruleflowName, parameters, rulesetParameters,
                registryRootPackageIgnored, descriptorSetNames, paramsSetNames, rulesetParamSetNames,
                packagePathIgnored, description, null);
    }

    // Versão completa (14 parâmetros) com rulesetName no final
    public Map<String,Object> saveOperation(
            String projectName,
            String baselineName,
            String operationName,
            String ruleflowPackageIgnored,
            String ruleflowName,
            List<Map<String,String>> parameters,
            Map<String,String> rulesetParameters,
            String registryRootPackageIgnored,
            List<String> descriptorSetNames,
            List<String> paramsSetNames,
            List<String> rulesetParamSetNames,
            String packagePathIgnored,
            String description,
            String rulesetName
    ) throws Exception {
        return saveOperation(projectName, baselineName, operationName,
                ruleflowPackageIgnored, ruleflowName, parameters, rulesetParameters,
                registryRootPackageIgnored, descriptorSetNames, paramsSetNames, rulesetParamSetNames,
                packagePathIgnored, description, rulesetName, null);
    }

    // Versão completa (15 parâmetros) com rulesetName e variableSetName
    public Map<String,Object> saveOperation(
            String projectName,
            String baselineName,
            String operationName,
            String ruleflowPackageIgnored,
            String ruleflowName,
            List<Map<String,String>> parameters,
            Map<String,String> rulesetParameters,
            String registryRootPackageIgnored,
            List<String> descriptorSetNames,
            List<String> paramsSetNames,
            List<String> rulesetParamSetNames,
            String packagePathIgnored,
            String description,
            String rulesetName,
            String variableSetName              // Nome do Variable Set para parâmetros da Operation
    ) throws Exception {
        IlrSession session = null;
        Map<String,Object> out = new LinkedHashMap<>();
        try {
            session = openSession();
            IlrRuleProject project = getProjectOrThrow(session, projectName);
            IlrBaseline baseline = resolveBaseline(session, project, baselineName);
            session.setWorkingBaseline(baseline);

            ODMVariableService vsvc = vars();

            System.out.println("\n========== DEBUG: INÍCIO SAVE OPERATION ==========");
            System.out.println("[DEBUG] Project: " + projectName);
            System.out.println("[DEBUG] Baseline: " + baseline.getName());
            System.out.println("[DEBUG] Operation: " + operationName);
            System.out.println("[DEBUG] Description: " + description);
            System.out.println("[DEBUG] Ruleflow (request): " + ruleflowName);
            System.out.println("[DEBUG] RulesetName: " + rulesetName);
            System.out.println("[DEBUG] Parameters count: " + (parameters != null ? parameters.size() : 0));
            System.out.println("[DEBUG] Variable Set Name: " + (variableSetName != null ? variableSetName : "(default: variaveis)"));

            // 1) Cria/atualiza Operation + associa ruleflow + lê de volta o vínculo
            Map<String,String> opInfo = createOrUpdateServiceOperation(
                    session, project, packagePathIgnored, operationName, description, parameters, ruleflowName, rulesetName, variableSetName
            );
            out.putAll(opInfo);
            out.put("dsmRuleflowLinked", opInfo.getOrDefault("dsmRuleflowLinked", ""));

            // 2) Monta nomes-padrão de VS (não obrigatórios)
            System.out.println("\n[DEBUG] Montando nomes de Variable Sets...");
            String opSafe = sanitize(operationName);
            List<String> descVSNames = (descriptorSetNames != null && !descriptorSetNames.isEmpty())
                    ? descriptorSetNames
                    : Collections.singletonList("operation_" + opSafe + "_descriptor");
            List<String> paramVSNames = (paramsSetNames != null && !paramsSetNames.isEmpty())
                    ? paramsSetNames
                    : Collections.singletonList("operation_" + opSafe + "_params");
            List<String> rsVSNames = (rulesetParamSetNames != null && !rulesetParamSetNames.isEmpty())
                    ? rulesetParamSetNames
                    : Collections.singletonList("rulesetparams_" + opSafe);
            System.out.println("[DEBUG] Descriptor VS names: " + descVSNames);
            System.out.println("[DEBUG] Params VS names: " + paramVSNames);
            System.out.println("[DEBUG] Ruleset Params VS names: " + rsVSNames);

            // 3) Resolve VS existentes (busca GLOBAL)
            System.out.println("\n[DEBUG] Buscando Variable Sets existentes...");
            List<IlrVariableSet> descVSList = resolveExistingSetsGlobal(session, descVSNames);
            System.out.println("[DEBUG] Descriptor VS encontrados: " + descVSList.size());
            List<IlrVariableSet> paramVSList = resolveExistingSetsGlobal(session, paramVSNames);
            System.out.println("[DEBUG] Params VS encontrados: " + paramVSList.size());
            List<IlrVariableSet> rsVSList = resolveExistingSetsGlobal(session, rsVSNames);
            System.out.println("[DEBUG] Ruleset Params VS encontrados: " + rsVSList.size());

            // ---------- DESCRIPTOR (opcional) ----------
            System.out.println("\n[DEBUG] Processando DESCRIPTOR...");
            int descUpdated = 0; List<String> descMissing = new ArrayList<>();
            if (ruleflowName != null && !ruleflowName.isBlank()) {
                if (upsertAcrossSets(session, vsvc, descVSList, "ruleflow_package", nvl(ruleflowPackageIgnored))) {
                    descUpdated++;
                } else { descMissing.add("ruleflow_package"); }
                if (upsertAcrossSets(session, vsvc, descVSList, "ruleflow_name", nvl(ruleflowName))) {
                    descUpdated++;
                } else { descMissing.add("ruleflow_name"); }
            }
            if (rulesetName != null && !rulesetName.isBlank()) {
                if (upsertAcrossSets(session, vsvc, descVSList, "ruleset_name", nvl(rulesetName))) {
                    descUpdated++;
                } else { descMissing.add("ruleset_name"); }
            }

            // ---------- PARAMETERS (opcional) ----------
            System.out.println("\n[DEBUG] Processando PARAMETERS...");
            int paramUpdated = 0; List<String> paramMissing = new ArrayList<>();
            if (parameters != null && !parameters.isEmpty()) {
                for (Map<String,String> p : parameters) {
                    String pName = nvl(p.get("name"));
                    String dir   = nvl(p.get("direction"));
                    String typ   = nvl(p.get("bomType"));
                    if (pName.isBlank()) continue;
                    String json = toJsonParam(pName, dir, typ);
                    if (upsertAcrossSets(session, vsvc, paramVSList, pName, json)) {
                        paramUpdated++;
                        System.out.println("[DEBUG] ✓ Parâmetro gravado: " + pName);
                    } else {
                        paramMissing.add(pName);
                        System.out.println("[DEBUG] ✗ Parâmetro NÃO gravado (variável não existe): " + pName);
                    }
                }
            }

            // ---------- RULESET PARAMS (pares livres, opcional) ----------
            System.out.println("\n[DEBUG] Processando RULESET PARAMETERS...");
            int rsUpdated = 0; List<String> rsMissing = new ArrayList<>();
            if (rulesetParameters != null && !rulesetParameters.isEmpty()) {
                for (Map.Entry<String,String> e : rulesetParameters.entrySet()) {
                    String key = e.getKey();
                    String val = nvl(e.getValue());
                    if (upsertAcrossSets(session, vsvc, rsVSList, key, val)) {
                        rsUpdated++;
                        System.out.println("[DEBUG] ✓ Ruleset param gravado: " + key);
                    } else {
                        rsMissing.add(key);
                        System.out.println("[DEBUG] ✗ Ruleset param NÃO gravado (variável não existe): " + key);
                    }
                }
            }

            // ---------- RESUMO ----------
            System.out.println("\n========== DEBUG: RESUMO ==========");
            System.out.println("[DEBUG] Descriptor updated: " + descUpdated + ", missing: " + descMissing);
            System.out.println("[DEBUG] Parameters updated: " + paramUpdated + ", missing: " + paramMissing);
            System.out.println("[DEBUG] Ruleset params updated: " + rsUpdated + ", missing: " + rsMissing);
            System.out.println("========== DEBUG: FIM SAVE OPERATION ==========\n");

            out.put("projectName", projectName);
            out.put("baselineName", baseline.getName());
            out.put("operationName", operationName);
            out.put("descriptorSets", descVSNames);
            out.put("paramsSets", paramVSNames);
            out.put("rulesetParamSets", rsVSNames);
            out.put("ruleflowNameProvided", nvl(ruleflowName));
            out.put("dsmRuleflowLinked", out.getOrDefault("dsmRuleflowLinked", ""));
            out.put("descriptorUpdatedCount", descUpdated);
            out.put("descriptorMissing", descMissing);
            out.put("parametersUpdatedCount", paramUpdated);
            out.put("parametersMissing", paramMissing);
            out.put("rulesetParametersUpdatedCount", rsUpdated);
            out.put("rulesetParametersMissing", rsMissing);
            out.put("status", "saved");
            return out;

        } finally {
            closeSession(session);
        }
    }

    /**
     * View consolidada a partir de múltiplos VS (sem efeitos colaterais).
     */
    @SuppressWarnings("unchecked")
    public Map<String,Object> viewOperationMulti(
            String projectName, String baselineName, String operationName,
            List<String> descriptorSetNames,
            List<String> paramsSetNames,
            List<String> rulesetParamSetNames) throws Exception {

        IlrSession session = null;
        Map<String,Object> out = new LinkedHashMap<>();
        try {
            session = openSession();
            IlrRuleProject project = getProjectOrThrow(session, projectName);
            IlrBaseline baseline = resolveBaseline(session, project, baselineName);
            session.setWorkingBaseline(baseline);

            out.put("projectName", projectName);
            out.put("baselineName", baseline.getName());
            out.put("operationName", operationName);

            String opSafe = sanitize(operationName);
            List<String> descVSNames = (descriptorSetNames != null && !descriptorSetNames.isEmpty())
                    ? descriptorSetNames
                    : Collections.singletonList("operation_" + opSafe + "_descriptor");
            List<String> paramVSNames = (paramsSetNames != null && !paramsSetNames.isEmpty())
                    ? paramsSetNames
                    : Collections.singletonList("operation_" + opSafe + "_params");
            List<String> rsVSNames = (rulesetParamSetNames != null && !rulesetParamSetNames.isEmpty())
                    ? rulesetParamSetNames
                    : Collections.singletonList("rulesetparams_" + opSafe);

            List<IlrVariableSet> descVSList = resolveExistingSetsGlobal(session, descVSNames);
            List<IlrVariableSet> paramVSList = resolveExistingSetsGlobal(session, paramVSNames);
            List<IlrVariableSet> rsVSList = resolveExistingSetsGlobal(session, rsVSNames);

            // Descriptor (primeiro valor encontrado)
            Map<String,String> descriptor = new LinkedHashMap<>();
            descriptor.put("ruleflow.package", "");
            descriptor.put("ruleflow.name", "");
            descriptor.put("ruleset.name", "");

            for (IlrVariableSet s : descVSList) {
                if (descriptor.get("ruleflow.package").isBlank()) {
                    String v = readStringVariable(session, s, "ruleflow_package");
                    if (!v.isBlank()) descriptor.put("ruleflow.package", v);
                }
                if (descriptor.get("ruleflow.name").isBlank()) {
                    String v = readStringVariable(session, s, "ruleflow_name");
                    if (!v.isBlank()) descriptor.put("ruleflow.name", v);
                }
                if (descriptor.get("ruleset.name").isBlank()) {
                    String v = readStringVariable(session, s, "ruleset_name");
                    if (!v.isBlank()) descriptor.put("ruleset.name", v);
                }
                if (!descriptor.get("ruleflow.package").isBlank()
                        && !descriptor.get("ruleflow.name").isBlank()
                        && !descriptor.get("ruleset.name").isBlank()) break;
            }
            out.put("descriptor", descriptor);

            // Params (agrega todas)
            List<Map<String,String>> params = new ArrayList<>();
            for (IlrVariableSet vset : paramVSList) {
                List<IlrVariable> vars = (List<IlrVariable>) tryInvoke(session.getElementDetails(vset), "getVariables");
                if (vars == null) continue;
                for (IlrVariable v : vars) {
                    String nameStored = nvl(v.getName());
                    String val = nvl(v.getInitialValue());
                    Map<String,String> row = parseParamJson(val);
                    String nameOriginal = row.get("name");
                    row.put("name", (nameOriginal == null || nameOriginal.isBlank()) ? nameStored : nameOriginal);
                    params.add(row);
                }
            }
            out.put("parameters", params);

            // Ruleset params (agrega; último VS sobrepõe)
            Map<String,String> rset = new LinkedHashMap<>();
            for (IlrVariableSet vset : rsVSList) {
                List<IlrVariable> vars = (List<IlrVariable>) tryInvoke(session.getElementDetails(vset), "getVariables");
                if (vars == null) continue;
                for (IlrVariable v : vars) {
                    rset.put(nvl(v.getName()), nvl(v.getInitialValue()));
                }
            }
            out.put("rulesetParameters", rset);

            out.put("descriptorSets", descVSNames);
            out.put("paramsSets", paramVSNames);
            out.put("rulesetParamSets", rsVSNames);
            return out;

        } finally {
            closeSession(session);
        }
    }

    // ================== Helpers: busca global de VS / update variável ==================
    @SuppressWarnings("unchecked")
    private List<IlrVariableSet> findVariableSetsByNameAnyPackage(IlrSession session, String setName) throws Exception {
        List<IlrVariableSet> out = new ArrayList<>();
        if (setName == null || setName.isBlank()) return out;
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria byName = new IlrDefaultSearchCriteria(
                meta.getVariableSet(),
                Arrays.asList(meta.getModelElement_Name()),
                Arrays.asList(setName)
        );
        List<IlrVariableSet> sets = session.findElements(byName, IlrModelConstants.ELEMENT_DETAILS);
        if (sets != null) {
            for (IlrVariableSet s : sets) out.add((IlrVariableSet) session.getElementDetails(s));
        }
        return out;
    }
    private List<IlrVariableSet> resolveExistingSetsGlobal(IlrSession session, List<String> names) throws Exception {
        List<IlrVariableSet> out = new ArrayList<>();
        if (names == null) return out;
        for (String n : names) {
            List<IlrVariableSet> found = findVariableSetsByNameAnyPackage(session, n);
            if (found != null) out.addAll(found);
        }
        return out;
    }
    private boolean upsertAcrossSets(IlrSession session, ODMVariableService vsvc,
                                     List<IlrVariableSet> sets, String varName, String value) throws Exception {
        if (sets == null || sets.isEmpty() || varName == null || varName.isBlank()) return false;
        for (IlrVariableSet vs : sets) {
            if (upsertStringVarNoCreate(session, vsvc, vs, varName, value, true)) return true;
        }
        return false;
    }
    private boolean upsertStringVarNoCreate(IlrSession session, ODMVariableService vsvc,
                                            IlrVariableSet vset, String varName, String value,
                                            boolean trySanitized) throws Exception {
        if (varName == null || varName.isBlank()) return false;
        IlrVariable existing = vsvc.findVariableByName(vset, varName);
        if (existing == null && trySanitized) {
            String safeName = sanitize(varName);
            if (!safeName.equals(varName)) {
                existing = vsvc.findVariableByName(vset, safeName);
            }
        }
        if (existing == null) return false; // não cria
        existing = (IlrVariable) session.getElementDetails(existing);
        String currentName = existing.getName();
        vsvc.updateVariableInSet(session, vset, existing, currentName, "java.lang.String", currentName, value);
        return true;
    }

    // ================== Utils e reflexão ==================
    private String readStringVariable(IlrSession session, IlrVariableSet vset, String varName) {
        try {
            @SuppressWarnings("unchecked")
            java.util.List<IlrVariable> vars = vset.getVariables();
            if (vars == null) return "";
            for (IlrVariable v : vars) {
                if (varName.equals(v.getName())) {
                    String iv = v.getInitialValue();
                    return (iv == null) ? "" : iv;
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
    private static String sanitize(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[^A-Za-z0-9_\\-]", "_");
        out = out.replaceAll("_+", "_");
        if (!out.isEmpty() && Character.isDigit(out.charAt(0))) out = "_" + out;
        return out;
    }
    private static Class<?>[] classesOf(Object... args) {
        if (args == null) return new Class<?>[0];
        Class<?>[] cs = new Class<?>[args.length];
        for (int i=0;i<args.length;i++) cs[i] = (args[i]==null)? Object.class : args[i].getClass();
        return cs;
    }
    private static Method findCompatibleMethod(Class<?> type, String name, Class<?>[] want) {
        try { return type.getMethod(name, want); } catch (NoSuchMethodException ignored) {}
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] got = m.getParameterTypes();
            if (got.length != want.length) continue;
            boolean ok = true;
            for (int i=0;i<got.length;i++) {
                if (want[i] == Object.class) continue;
                if (got[i].isAssignableFrom(want[i])) continue;
                if (got[i].equals(String.class) && CharSequence.class.isAssignableFrom(want[i])) continue;
                ok = false; break;
            }
            if (ok) return m;
        }
        return null;
    }
    private static Object tryInvoke(Object target, String methodName, Object... args) {
        if (target == null || methodName == null) return null;
        try {
            Method m = findCompatibleMethod(target.getClass(), methodName, classesOf(args));
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        } catch (Throwable ignore) {}
        return null;
    }
    private static Object tryInvokeStatic(Class<?> type, String methodName, Object... args) {
        if (type == null || methodName == null) return null;
        try {
            Method m = findCompatibleMethod(type, methodName, classesOf(args));
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(null, args);
            }
        } catch (Throwable ignore) {}
        return null;
    }
    private static String toJsonParam(String name, String direction, String bomType) {
        String n = esc(nvl(name));
        String d = esc(nvl(direction));
        String t = esc(nvl(bomType));
        return "{" +
                "\"name\":\""+n+"\"," +
                "\"direction\":\""+d+"\"," +
                "\"bomType\":\""+t+"\"" +
                "}";
    }
    private static Map<String,String> parseParamJson(String json) {
        Map<String,String> out = new LinkedHashMap<>();
        try {
            String t = (json == null) ? "" : json.trim();
            if (!t.startsWith("{") || !t.endsWith("}")) return out;
            t = t.substring(1, t.length()-1);
            String[] parts = t.split(",");
            for (String p : parts) {
                String[] kv = p.split(":", 2);
                if (kv.length != 2) continue;
                String k = kv[0].trim();
                if (k.startsWith("\"") && k.endsWith("\"")) k = k.substring(1, k.length()-1);
                String v = kv[1].trim();
                if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length()-1);
                v = v.replace("\\\"", "\"").replace("\\\\", "\\");
                out.put(k, v);
            }
        } catch (Exception ignore) {}
        return out;
    }
    private static String nvl(String s) { return (s == null) ? "" : s; }
    private static String esc(String s) { return nvl(s).replace("\\", "\\\\").replace("\"", "\\\""); }
}
