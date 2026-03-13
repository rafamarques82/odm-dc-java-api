package com.ibm.odm.regras;

import ilog.rules.teamserver.brm.*;
import ilog.rules.teamserver.client.IlrRemoteSessionFactory;
import ilog.rules.teamserver.model.*;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EClass;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Serviço para criar e gerenciar Test Suites no IBM ODM 9.5 Decision Center.
 * 
 * IMPORTANTE: 
 * - Test Suites ficam diretamente no projeto, não em pacotes
 * - Cenários de teste são importados via Excel no Decision Center, não via API
 * - Este serviço apenas cria o Test Suite vazio
 * 
 * @author ODM Tools
 */
public class ODMTestSuiteService {

    private final String serverUrl;
    private final String datasource;
    private final String login;
    private final String password;

    public ODMTestSuiteService(String serverUrl, String datasource, String login, String password) {
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl não pode ser nulo");
        this.datasource = Objects.requireNonNull(datasource, "datasource não pode ser nulo");
        this.login = Objects.requireNonNull(login, "login não pode ser nulo");
        this.password = Objects.requireNonNull(password, "password não pode ser nulo");
    }

    // ==================== Sessão / Projeto / Baseline ====================

    public IlrSession openSession() throws IlrConnectException {
        IlrSessionFactory factory = new IlrRemoteSessionFactory();
        factory.connect(login, password, serverUrl, datasource);
        return factory.getSession();
    }

    public void closeSession(IlrSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignore) {
            }
        }
    }

    public IlrRuleProject getProjectOrThrow(IlrSession session, String projectName) throws Exception {
        IlrRuleProject project = (IlrRuleProject) IlrSessionHelper.getProjectNamed(session, projectName);
        if (project == null) {
            throw new IllegalArgumentException("Projeto não encontrado: " + projectName);
        }
        return project;
    }

    public IlrBaseline resolveBaseline(IlrSession session, IlrRuleProject project, String baselineName) 
            throws Exception {
        if (baselineName == null || baselineName.isBlank() || "%current_key".equalsIgnoreCase(baselineName)) {
            return IlrSessionHelper.getCurrentBaseline(session, project);
        }
        IlrBaseline baseline = IlrSessionHelper.getBaselineNamed(session, project, baselineName);
        if (baseline != null) {
            return baseline;
        }
        baseline = IlrSessionHelper.getBaselineNamed(session, project, "Main");
        return (baseline != null) ? baseline : IlrSessionHelper.getCurrentBaseline(session, project);
    }

    public void setWorkingBaseline(IlrSession session, IlrRuleProject project, String baselineName) 
            throws Exception {
        IlrBaseline baseline = resolveBaseline(session, project, baselineName);
        session.setWorkingBaseline(baseline);
    }

    // ==================== Test Suite - Core ====================

    /**
     * Busca um Test Suite por nome no projeto.
     */
    public IlrElementDetails findTestSuiteByName(IlrSession session, EClass testSuiteClass, 
            String testSuiteName) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(
                testSuiteClass,
                Arrays.asList(meta.getModelElement_Name()),
                Arrays.asList(testSuiteName)
        );
        @SuppressWarnings("unchecked")
        List<IlrElementDetails> elements = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
        
        return (elements == null || elements.isEmpty()) ? null : elements.get(0);
    }

    /**
     * Busca uma Operation por nome no projeto.
     */
    public IlrElementDetails findOperationByName(IlrSession session, String operationName) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        
        // Tenta obter a classe Operation
        EClass operationClass = getEClass(meta, "getOperation", "getDecisionOperation");
        if (operationClass == null) {
            return null;
        }
        
        IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(
                operationClass,
                Arrays.asList(meta.getModelElement_Name()),
                Arrays.asList(operationName)
        );
        @SuppressWarnings("unchecked")
        List<IlrElementDetails> elements = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
        
        return (elements == null || elements.isEmpty()) ? null : elements.get(0);
    }

    /**
     * Cria um Test Suite vazio.
     * Os cenários de teste devem ser importados via Excel no Decision Center.
     *
     * @param session Sessão ativa
     * @param project Projeto ODM
     * @param testSuiteName Nome do test suite
     * @param operationName Nome da Decision Operation a ser testada
     * @param serverName Nome do servidor de execução (opcional)
     * @return Test Suite criado
     */
    public IlrElementDetails createTestSuite(
            IlrSession session,
            IlrRuleProject project,
            String testSuiteName,
            String operationName,
            String serverName) throws Exception {

        Objects.requireNonNull(project, "project não pode ser nulo");
        Objects.requireNonNull(testSuiteName, "testSuiteName não pode ser nulo");
        Objects.requireNonNull(operationName, "operationName não pode ser nulo");

        setWorkingBaseline(session, project, null);
        IlrBrmPackage meta = session.getBrmPackage();

        // Obtém a classe TestSuite do metamodelo
        EClass testSuiteClass = getTestSuiteClass(meta);
        if (testSuiteClass == null) {
            throw new IllegalStateException("TestSuite não disponível nesta versão do ODM");
        }

        // Verifica se já existe
        IlrElementDetails existing = findTestSuiteByName(session, testSuiteClass, testSuiteName);
        if (existing != null) {
            System.out.println("Test Suite já existe: " + testSuiteName);
            return existing;
        }

        // Busca a Operation
        IlrElementDetails operation = findOperationByName(session, operationName);
        if (operation == null) {
            throw new IllegalArgumentException("Operation não encontrada: " + operationName);
        }

        // Cria novo test suite
        IlrElementHandle handle = session.createElement(testSuiteClass);
        IlrElementDetails testSuite = session.getElementDetails(handle);
        
        // Define nome
        testSuite.setRawValue(meta.getModelElement_Name(), testSuiteName);
        
        // Associa à Operation
        EStructuralFeature operationFeature = getFeature(meta, "getTestSuite_Operation", "getTestSuite_DecisionOperation");
        if (operationFeature != null) {
            testSuite.setRawValue(operationFeature, operation);
        }
        
        // Define servidor se fornecido
        if (serverName != null && !serverName.isBlank()) {
            EStructuralFeature serverFeature = getFeature(meta, "getTestSuite_Server", "getTestSuite_ExecutionServer");
            if (serverFeature != null) {
                testSuite.setRawValue(serverFeature, serverName);
            }
        }
        
        // Associa ao projeto
        EStructuralFeature projectFeature = getFeature(meta, "getTestSuite_Project", "getModelElement_Project");
        if (projectFeature != null) {
            testSuite.setRawValue(projectFeature, project);
        }
        
        // Commit
        session.commit(testSuite);
        
        System.out.println("Test Suite criado: " + testSuiteName);
        System.out.println("Operation: " + operationName);
        System.out.println("Importe os cenários de teste via Excel no Decision Center");
        
        return testSuite;
    }

    /**
     * Obtém a classe TestSuite do metamodelo.
     */
    private EClass getTestSuiteClass(IlrBrmPackage meta) {
        Object result = tryInvoke(meta, "getTestSuite");
        if (result instanceof EClass) {
            return (EClass) result;
        }
        result = tryInvoke(meta, "getTestScenarioSuite");
        if (result instanceof EClass) {
            return (EClass) result;
        }
        return null;
    }

    private EClass getEClass(IlrBrmPackage meta, String... methodNames) {
        for (String methodName : methodNames) {
            Object result = tryInvoke(meta, methodName);
            if (result instanceof EClass) {
                return (EClass) result;
            }
        }
        return null;
    }

    private EStructuralFeature getFeature(IlrBrmPackage meta, String... methodNames) {
        for (String methodName : methodNames) {
            Object result = tryInvoke(meta, methodName);
            if (result instanceof EStructuralFeature) {
                return (EStructuralFeature) result;
            }
        }
        return null;
    }

    private Object tryInvoke(Object target, String methodName, Object... args) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
            }
            Method method = target.getClass().getMethod(methodName, paramTypes);
            return method.invoke(target, args);
        } catch (NoSuchMethodException e) {
            try {
                for (Method m : target.getClass().getMethods()) {
                    if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                        m.setAccessible(true);
                        return m.invoke(target, args);
                    }
                }
            } catch (Exception ex) {
                // Ignora
            }
        } catch (Exception e) {
            // Ignora
        }
        return null;
    }

    // ==================== Listagem ====================

    /**
     * Sumário de um Test Suite.
     */
    public Map<String, Object> summarizeTestSuite(IlrElementDetails testSuite) throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        
        String name = (String) tryInvoke(testSuite, "getName");
        summary.put("name", name != null ? name : "");
        
        // Operation associada
        try {
            Object opObj = tryInvoke(testSuite, "getOperation");
            if (opObj != null) {
                String opName = (String) tryInvoke(opObj, "getName");
                summary.put("operation", opName != null ? opName : "");
            } else {
                summary.put("operation", "");
            }
        } catch (Exception e) {
            summary.put("operation", "");
        }
        
        // Servidor
        String server = (String) tryInvoke(testSuite, "getServer");
        summary.put("server", server != null ? server : "");
        
        // Projeto
        try {
            Object projObj = tryInvoke(testSuite, "getProject");
            if (projObj instanceof IlrRuleProject) {
                IlrRuleProject proj = (IlrRuleProject) projObj;
                summary.put("project", proj.getName());
            } else {
                summary.put("project", "");
            }
        } catch (Exception e) {
            summary.put("project", "");
        }
        
        return summary;
    }

    /**
     * Lista todos os test suites de um projeto.
     */
    public List<Map<String, Object>> listTestSuites(String projectName, String baselineName) throws Exception {
        IlrSession session = null;
        try {
            session = openSession();
            IlrRuleProject project = getProjectOrThrow(session, projectName);
            setWorkingBaseline(session, project, baselineName);
            
            IlrBrmPackage meta = session.getBrmPackage();
            EClass testSuiteClass = getTestSuiteClass(meta);
            if (testSuiteClass == null) {
                throw new IllegalStateException("TestSuite não disponível nesta versão do ODM");
            }
            
            IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(testSuiteClass);
            
            @SuppressWarnings("unchecked")
            List<IlrElementDetails> suites = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
            
            List<Map<String, Object>> result = new ArrayList<>();
            if (suites != null) {
                for (IlrElementDetails suite : suites) {
                    result.add(summarizeTestSuite(suite));
                }
            }
            
            return result;
        } finally {
            closeSession(session);
        }
    }
}

// Made with Bob
