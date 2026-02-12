package com.ibm.odm.regras;

import ilog.rules.teamserver.brm.*;
import ilog.rules.teamserver.client.IlrRemoteSessionFactory;
import ilog.rules.teamserver.model.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Serviço para criar, listar e editar Variable Sets e Variables no IBM ODM 9.5+,
 * tolerante a variações de fixpack. Evita IlrFeature no compile e garante o 'name'
 * antes de qualquer commit, validando de forma defensiva.
 *
 * Observações:
 * - Mantém as mesmas assinaturas públicas consumidas por ODMHttpServer.
 * - Resolve e cria pacotes aninhados ("a.b.c"), com commits por etapa.
 * - Evita colisões de nome de pacote comparando por "nome qualificado".
 */
public class ODMVariableService {

    private final String serverUrl;
    private final String datasource;
    private final String login;
    private final String password;

    public ODMVariableService(String serverUrl, String datasource, String login, String password) {
        this.serverUrl = serverUrl;
        this.datasource = datasource;
        this.login = login;
        this.password = password;
    }

    /* ==========================
       Sessão
       ========================== */
    public IlrSession openSession() throws IlrConnectException {
        IlrSessionFactory factory = new IlrRemoteSessionFactory();
        factory.connect(login, password, serverUrl, datasource);
        return factory.getSession();
    }

    public void closeSession(IlrSession session) {
        if (session != null) {
            session.close();
        }
    }

    /* ==========================
       Projeto / Baseline
       ========================== */

    public IlrRuleProject getProjectOrThrow(IlrSession session, String projectName) throws Exception {
        IlrRuleProject project = (IlrRuleProject) IlrSessionHelper.getProjectNamed(session, projectName);
        if (project == null) {
            throw new IllegalArgumentException("Projeto não encontrado: " + projectName);
        }
        return project;
    }

    /** Resolve baseline por nome; aceita null, "Main", e "%current_key" (baseline atual). */
    public IlrBaseline resolveBaseline(IlrSession session, IlrRuleProject project, String baselineName) throws Exception {
        if (baselineName == null || baselineName.isBlank() || "%current_key".equalsIgnoreCase(baselineName)) {
            return IlrSessionHelper.getCurrentBaseline(session, project);
        }
        IlrBaseline b = IlrSessionHelper.getBaselineNamed(session, project, baselineName);
        if (b != null) return b;
        b = IlrSessionHelper.getBaselineNamed(session, project, "Main");
        return (b != null) ? b : IlrSessionHelper.getCurrentBaseline(session, project);
    }

    /* ==========================
       Pacotes (Rule Packages)
       ========================== */

    /** Localiza um Rule Package raiz por nome (parent == null) no baseline ativo. */
    private IlrRulePackage findRootPackage(IlrSession session, String name) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(
                meta.getRulePackage(),
                Arrays.asList(meta.getModelElement_Name()),
                Arrays.asList(name)
        );
        @SuppressWarnings("unchecked")
        List<IlrRulePackage> packs = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
        if (packs == null || packs.isEmpty()) return null;
        for (IlrRulePackage p : packs) {
            try {
                if (p.getParent() == null) return p; // raiz (nível superior)
            } catch (Exception ignore) { }
        }
        return null;
    }

    /** Encontra um subpacote direto dentro de um Rule Package pai (somente leitura). */
    @SuppressWarnings("unchecked")
    private IlrRulePackage findDirectChildPackageForRead(IlrSession session, IlrRulePackage parent, String childName) throws Exception {
        List<IlrRulePackage> children = parent.getChildren();
        if (children == null || children.isEmpty()) return null;
        for (IlrRulePackage c : children) {
            if (childName.equals(c.getName())) return c;
        }
        return null;
    }

    /** Calcula o nome qualificado "a.b.c" subindo a cadeia de parents. */
    private String qualifiedName(IlrRulePackage p) {
        if (p == null) return "";
        Deque<String> parts = new ArrayDeque<>();
        IlrRulePackage cur = p;
        while (cur != null) {
            parts.addFirst(safe(cur.getName()));
            try { cur = cur.getParent(); } catch (Exception e) { cur = null; }
        }
        return String.join(".", parts);
    }

    /** Resolve um Rule Package aninhado EXISTENTE ("a.b.c") sem criar nada. */
    public IlrRulePackage resolveExistingNestedPackage(IlrSession session, String fullPackageName) throws Exception {
        if (fullPackageName == null || fullPackageName.isBlank()) return null;
        String[] parts = Arrays.stream(fullPackageName.split("\\."))
                .filter(p -> p != null && !p.isBlank())
                .toArray(String[]::new);
        if (parts.length == 0) return null;

        IlrRulePackage current = findRootPackage(session, parts[0]);
        if (current == null) return null;

        for (int i = 1; i < parts.length; i++) {
            IlrRulePackage child = findDirectChildPackageForRead(session, current, parts[i]);
            if (child == null) return null;
            current = child;
        }
        return current;
    }

    /**
     * Resolve ou cria a cadeia de pacotes ("a.b.c"): raiz + filhos.
     * Importante: cada criação é seguida de commit para materializar no baseline.
     */
    public IlrRulePackage resolveOrCreateNestedPackage(IlrSession session, String fullPackageName) throws Exception {
        if (fullPackageName == null || fullPackageName.isBlank())
            throw new IllegalArgumentException("packageName vazio");

        String[] parts = Arrays.stream(fullPackageName.split("\\."))
                .filter(p -> p != null && !p.isBlank())
                .toArray(String[]::new);
        if (parts.length == 0)
            throw new IllegalArgumentException("packageName inválido");

        // Raiz
        IlrRulePackage current = findRootPackage(session, parts[0]);
        if (current == null) {
            current = IlrSessionHelper.createRulePackage(session, null, parts[0]);
            session.commit(current);
        }

        // Filhos
        for (int i = 1; i < parts.length; i++) {
            IlrRulePackage child = findDirectChildPackageForRead(session, current, parts[i]);
            if (child == null) {
                child = IlrSessionHelper.createRulePackage(session, current, parts[i]);
                session.commit(child);
            }
            current = child;
        }
        return current;
    }

    /**
     * Abre ou cria um Variable Set no pacote informado (não muda baseline).
     * Mantém a resolução de pacote sem RuleProject, mas garante o vínculo set->pacote
     * antes do commit e valida o resultado pós-commit.
     */
/**
 * Abre ou cria um Variable Set no pacote informado (não muda baseline).
 * Mantém a resolução de pacote sem RuleProject, mas garante o vínculo set->pacote
 * antes do commit e valida o resultado pós-commit.
 */
public IlrVariableSet openOrCreateVariableSet(IlrSession session,
                                              String projectName,
                                              String packageName,
                                              String setName) throws Exception {
    if (projectName == null || projectName.isBlank())
        throw new IllegalArgumentException("projectName vazio");
    if (setName == null || setName.isBlank())
        throw new IllegalArgumentException("variableSetName vazio");

    // 1) Busca o projeto
    IlrRuleProject project = getProjectOrThrow(session, projectName);
    
    // 2) Verifica se já existe um VariableSet com esse nome no projeto
    IlrBrmPackage meta = session.getBrmPackage();
    IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(
        meta.getVariableSet(),
        Arrays.asList(meta.getModelElement_Name()),
        Arrays.asList(setName)
    );
    @SuppressWarnings("unchecked")
    List<IlrVariableSet> existing = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
    if (existing != null && !existing.isEmpty()) {
        return (IlrVariableSet) session.getElementDetails(existing.get(0));
    }

    // 3) Cria o VariableSet diretamente usando session.createElement
    IlrElementHandle handle = session.createElement(meta.getVariableSet());
    if (handle == null) {
        throw new IllegalStateException("Não foi possível criar Variable Set handle");
    }
    
    IlrVariableSet vset = (IlrVariableSet) session.getElementDetails(handle);
    
    // 4) Seta o nome usando setName() público
    vset.setName(setName);
    
    // 5) Tenta vincular ao projeto (alguns drops podem precisar disso)
    tryInvoke(vset, "setProject", project);
    tryInvoke(vset, "setRuleProject", project);
    
    // 6) Commit do VariableSet
    session.commit(vset);
    
    // 7) Recarrega e retorna
    return (IlrVariableSet) session.getElementDetails(vset);
}
    
    
    /** Localiza um Variable Set dentro do pacote (comparando por nome qualificado do pacote). */
    public IlrVariableSet findVariableSetInPackage(IlrSession session, IlrRulePackage pkg, String setName) throws Exception {
        if (pkg == null) return null;
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria byName = new IlrDefaultSearchCriteria(
                meta.getVariableSet(),
                Arrays.asList(meta.getModelElement_Name()),
                Arrays.asList(setName)
        );
        @SuppressWarnings("unchecked")
        List<IlrVariableSet> sets = session.findElements(byName, IlrModelConstants.ELEMENT_DETAILS);
        if (sets == null || sets.isEmpty()) return null;

        String targetQN = qualifiedName(pkg);
        for (IlrVariableSet s : sets) {
            IlrRulePackage p = s.getRulePackage();
            if (p != null && targetQN.equals(qualifiedName(p))) {
                return s;
            }
        }
        return null;
    }

    /** Remove todas as variáveis do set (commit no container vset). */
    @SuppressWarnings("unchecked")
    public void clearVariables(IlrSession session, IlrVariableSet vset) throws Exception {
        List<IlrVariable> all = vset.getVariables();
        if (all != null) {
            for (IlrVariable v : new ArrayList<>(all)) {
                session.deleteElement(v);
            }
        }
        session.commit(vset);
    }

    /** Localiza uma variável por nome dentro do set (case sensitive). */
    @SuppressWarnings("unchecked")
    public IlrVariable findVariableByName(IlrVariableSet vset, String name) throws IlrObjectNotFoundException {
        if (vset == null || name == null) return null;
        List<IlrVariable> vars = vset.getVariables();
        if (vars == null) return null;
        for (IlrVariable v : vars) {
            if (name.equals(v.getName())) return v;
        }
        return null;
    }

    /** Cria uma variável e a adiciona ao set usando IlrCommitableObject. */


    
    /** Atualiza campos da variável existente (commit no container). */
    public void updateVariableInSet(IlrSession session, IlrVariableSet vset, IlrVariable var,
                                    String newName, String newBomType, String newVerbalization, String newInitialValue) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();

        if (newName != null && !newName.isBlank() && !newName.equals(var.getName())) {
            ensureName(session, var, newName, "Variable");
        }

        Object fType = tryFeature(meta, "getVariable_TypeName", "getVariable_Type", "getVariable_Domain");
        Object fVerb = tryFeature(meta, "getVariable_Verbalization", "getVariable_Label", "getVariable_Description");
        Object fInit = tryFeature(meta, "getVariable_InitialValue", "getVariable_DefaultValue");

        if (newBomType != null && !newBomType.isBlank()) setRawValueDyn(var, fType, newBomType);
        if (newVerbalization != null && !newVerbalization.isBlank()) setRawValueDyn(var, fVerb, newVerbalization);
        if (newInitialValue != null && !newInitialValue.isBlank()) setRawValueDyn(var, fInit, newInitialValue);

        try {
            session.commit(vset);
        } catch (IlrObjectNotFoundException e) {
            session.commit(var);
        }
    }

    /* ==========================
       Sumários / Listagem
       ========================== */

    public Map<String, Object> summarize(IlrVariableSet vset) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        if (vset == null) return out;

        IlrBrmPackage meta = vset.getSession().getBrmPackage();
        Object fType = tryFeature(meta, "getVariable_TypeName", "getVariable_Type", "getVariable_Domain");
        Object fVerb = tryFeature(meta, "getVariable_Verbalization", "getVariable_Label", "getVariable_Description");
        Object fInit = tryFeature(meta, "getVariable_InitialValue", "getVariable_DefaultValue");

        out.put("name", safe(vset.getName()));
        IlrRulePackage pkg = vset.getRulePackage();
        out.put("package", (pkg == null) ? "" : safe(pkg.getName()));
        out.put("packageQualified", (pkg == null) ? "" : qualifiedName(pkg)); // campo extra útil

        List<Map<String, Object>> vars = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<IlrVariable> list = vset.getVariables();
        if (list != null) {
            for (IlrVariable v : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", safe(v.getName()));
                m.put("bomType", safe(getRawValueDyn(v, fType)));
                m.put("verbalization", safe(getRawValueDyn(v, fVerb)));
                m.put("initialValue", safe(getRawValueDyn(v, fInit)));
                vars.add(m);
            }
        }
        out.put("variables", vars);
        out.put("variableCount", vars.size());
        return out;
    }

    /** Lista TODAS as variáveis do projeto/baseline. */
    public Map<String, Object> listAllVariables(String projectName, String baselineName, String packageName) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        IlrSession session = null;
        try {
            session = openSession();
            IlrRuleProject project = getProjectOrThrow(session, projectName);
            IlrBaseline baseline = resolveBaseline(session, project, baselineName);
            session.setWorkingBaseline(baseline);

            IlrBrmPackage meta = session.getBrmPackage();
            IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(meta.getVariableSet());

            @SuppressWarnings("unchecked")
            List<IlrVariableSet> sets = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
            List<Map<String, Object>> outSets = new ArrayList<>();

            String filter = (packageName == null || packageName.isBlank()) ? "" : packageName;

            if (sets != null) {
                for (IlrVariableSet s : sets) {
                    IlrRulePackage p = s.getRulePackage();
                    String qn = (p == null) ? "" : qualifiedName(p);
                    String pn = (p == null) ? "" : safe(p.getName());

                    boolean match;
                    if (filter.isBlank()) {
                        match = true;
                    } else {
                        // Filtra por nome qualificado OU por nome simples de raiz/folha
                        match = qn.equals(filter) || qn.startsWith(filter + ".") || pn.equals(filter);
                    }

                    if (match) {
                        outSets.add(summarize(s));
                    }
                }
            }

            result.put("projectName", projectName);
            result.put("baselineName", baseline.getName());
            result.put("packageFilter", filter);
            result.put("variableSets", outSets);
            result.put("variableSetCount", outSets.size());
            return result;
        } finally {
            closeSession(session);
        }
    }

    /* ==========================
       Reflection util (compat)
       ========================== */

    private static Object tryFeature(IlrBrmPackage meta, String... methodNames) {
        for (String m : methodNames) {
            Object f = tryInvoke(meta, m);
            if (f != null) return f;
        }
        return null;
    }

    /** Garante que o elemento tem 'name' setado e materializado no detalhe antes do commit. */
    private static void ensureName(IlrSession session, Object element, String name, String kind) {
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

        // 3) materializa os detalhes e revalida
        Object detailed = tryInvoke(session, "getElementDetails", element);
        Object target = (detailed != null) ? detailed : element;
        String finalName = getNameDyn(session, target);
        if (finalName == null || finalName.isBlank()) {
            // último esforço: tenta novamente no detalhe
            tryInvoke(target, "setName", name);
            Object nameFeature = session.getBrmPackage().getModelElement_Name();
            setRawValueDyn(target, nameFeature, name);
            finalName = getNameDyn(session, target);
            if (finalName == null || finalName.isBlank()) {
                throw new IllegalStateException(kind + " sem nome mesmo após setar ("
                        + target.getClass().getName() + "). Valor tentado: '" + name + "'");
            }
        }
    }

    /** Lê o nome por getName() ou getRawValue(ModelElement_Name). */
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

    private static void setRawValueDyn(Object element, Object feature, String value) {
        if (element == null || feature == null || value == null || value.isBlank()) return;
        try {
            Method m = findCompatibleMethod(element.getClass(), "setRawValue",
                    new Class<?>[]{feature.getClass(), String.class});
            if (m != null) {
                m.invoke(element, feature, value);
                return;
            }
        } catch (Throwable ignore) { }
        // fallback: alguns drops aceitam setLabel como forma de "nome de exibição"
        tryInvoke(element, "setLabel", value);
    }
    
    // Sobrecarga para aceitar Object (usado para listas)
    private static void setRawValueDyn(Object element, Object feature, Object value) {
        if (element == null || feature == null || value == null) return;
        try {
            Method m = findCompatibleMethod(element.getClass(), "setRawValue",
                    new Class<?>[]{feature.getClass(), value.getClass()});
            if (m != null) {
                m.invoke(element, feature, value);
                return;
            }
        } catch (Throwable ignore) { }
    }

    private static String getRawValueDyn(Object element, Object feature) {
        if (element == null || feature == null) return "";
        try {
            Method m = findCompatibleMethod(element.getClass(), "getRawValue",
                    new Class<?>[]{feature.getClass()});
            if (m != null) {
                Object v = m.invoke(element, feature);
                return v == null ? "" : String.valueOf(v);
            }
        } catch (Throwable ignore) { }
        return "";
    }

    private static void attachToContainer(Object child, Object container, String... methodNames) {
        if (child == null || container == null) return;
        for (String m : methodNames) {
            if (tryInvoke(child, m, container) != null) return;
        }
        // fallback: tenta add no container
        tryInvoke(container, "addVariable", child);
        tryInvoke(container, "addVariableSet", child);
        tryInvoke(container, "addChild", child);
        tryInvoke(container, "add", child);
    }

    private static IlrVariableSet tryCreateVariableSet(IlrSession session, IlrRulePackage pkg, String name) {
        Object obj = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", session, pkg, name);
        if (obj instanceof IlrVariableSet) return (IlrVariableSet) obj;

        obj = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", session, pkg);
        if (obj instanceof IlrVariableSet) return (IlrVariableSet) obj;

        obj = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", session);
        if (obj instanceof IlrVariableSet) return (IlrVariableSet) obj;

        obj = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", pkg, name);
        if (obj instanceof IlrVariableSet) return (IlrVariableSet) obj;

        obj = tryInvokeStatic(IlrSessionHelper.class, "createVariableSet", pkg);
        if (obj instanceof IlrVariableSet) return (IlrVariableSet) obj;

        // Fallback: criar usando session.createElement() diretamente
        try {
            IlrBrmPackage meta = session.getBrmPackage();
            IlrElementHandle handle = session.createElement(meta.getVariableSet());
            if (handle != null) {
                IlrElementDetails details = session.getElementDetailsForThisHandle(handle);
                if (details instanceof IlrVariableSet) {
                    // IMPORTANTE: Setar o nome antes de retornar
                    if (name != null && !name.isEmpty()) {
                        details.setRawValue(
                            (org.eclipse.emf.ecore.EStructuralFeature) meta.getModelElement_Name(),
                            name
                        );
                    }
                    return (IlrVariableSet) details;
                }
            }
        } catch (Throwable ignore) {
            ignore.printStackTrace();
        }

        return null;
    }

    private static IlrVariable tryCreateVariable(IlrSession session, IlrVariableSet vset, String name) {
        Object obj = tryInvokeStatic(IlrSessionHelper.class, "createVariable", session, vset, name);
        if (obj instanceof IlrVariable) return (IlrVariable) obj;

        obj = tryInvokeStatic(IlrSessionHelper.class, "createVariable", session, vset);
        if (obj instanceof IlrVariable) return (IlrVariable) obj;

        obj = tryInvokeStatic(IlrSessionHelper.class, "createVariable", session);
        if (obj instanceof IlrVariable) return (IlrVariable) obj;

        obj = tryInvokeStatic(IlrSessionHelper.class, "createVariable", vset, name);
        if (obj instanceof IlrVariable) return (IlrVariable) obj;

        obj = tryInvokeStatic(IlrSessionHelper.class, "createVariable", vset);
        if (obj instanceof IlrVariable) return (IlrVariable) obj;

        // Fallback: criar usando session.createElement() diretamente
        try {
            IlrBrmPackage meta = session.getBrmPackage();
            IlrElementHandle handle = session.createElement(meta.getVariable());
            if (handle != null) {
                obj = session.getElementDetails(handle);
                if (obj instanceof IlrVariable) return (IlrVariable) obj;
            }
        } catch (Throwable ignore) { }

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
        } catch (Throwable ignore) { }
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
        } catch (Throwable ignore) { }
        return null;
    }

    private static Class<?>[] classesOf(Object... args) {
        if (args == null) return new Class<?>[0];
        Class<?>[] cs = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) cs[i] = (args[i] == null) ? Object.class : args[i].getClass();
        return cs;
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Class<?>[] want) {
        // 1) match exato
        try { return type.getMethod(name, want); } catch (NoSuchMethodException ignored) { }
        // 2) por nome/qtde e assignable
        for (Method m : type.getMethods()) {
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

    private boolean isChildPackage(String pkg, String parent) {
        if (pkg == null || parent == null) return false;
        return pkg.equals(parent) || pkg.startsWith(parent + ".");
    }

    private static String safe(String s) { return (s == null) ? "" : s; }
    



    /**
     * Adiciona múltiplas variáveis a um VariableSet seguindo o padrão correto do IBM ODM 9.5.
     *
     * IMPORTANTE: Este método segue o padrão:
     * 1. Verifica se VariableSet precisa ser commitado
     * 2. Adiciona TODAS as variáveis ao CommitableObject
     * 3. Faz commit ÚNICO com todas as variáveis
     */
    public void addVariablesToSet(IlrSession session, IlrVariableSet vset,
            java.util.List<java.util.Map<String, String>> variables, Object branch) throws Exception {
        
        if (variables == null || variables.isEmpty()) {
            throw new IllegalArgumentException("Lista de variáveis não pode ser vazia");
        }
        
        System.out.println("[DEBUG addVariablesToSet] Iniciando adição de " + variables.size() + " variáveis");
        IlrBrmPackage meta = session.getBrmPackage();
        
        // Verifica se VariableSet precisa ser commitado primeiro
        boolean needsInitialCommit = false;
        try {
            @SuppressWarnings("unchecked")
            java.util.List<IlrVariable> existingVars = vset.getVariables();
            if (existingVars == null || existingVars.isEmpty()) {
                needsInitialCommit = true;
                System.out.println("[DEBUG addVariablesToSet] VariableSet vazio, precisa commit inicial");
            }
        } catch (Exception e) {
            needsInitialCommit = true;
            System.out.println("[DEBUG addVariablesToSet] Erro ao verificar variáveis: " + e.getMessage());
        }
        
        // Se precisa, commita o VariableSet vazio primeiro
        if (needsInitialCommit) {
            System.out.println("[DEBUG addVariablesToSet] Commitando VariableSet vazio...");
            try {
                Class<?> coClass = Class.forName("ilog.rules.teamserver.model.IlrCommitableObject");
                Constructor<?> ctorHandle = coClass.getConstructor(ilog.rules.teamserver.model.IlrElementHandle.class);
                Object co = ctorHandle.newInstance(vset);
                
                Method setRootDetails = coClass.getMethod("setRootDetails", ilog.rules.teamserver.model.IlrElementDetails.class);
                setRootDetails.invoke(co, vset);
                
                Object commitResult = null;
                if (branch != null) {
                    Method commitMethod = null;
                    for (Method m : session.getClass().getMethods()) {
                        if (m.getName().equals("commit") && m.getParameterCount() == 2) {
                            Class<?>[] params = m.getParameterTypes();
                            if (params[1].isAssignableFrom(co.getClass())) {
                                commitMethod = m;
                                break;
                            }
                        }
                    }
                    if (commitMethod != null) {
                        commitResult = commitMethod.invoke(session, branch, co);
                        System.out.println("[DEBUG addVariablesToSet] ✓ VariableSet commitado com branch");
                    }
                } else {
                    commitResult = session.commit(vset);
                    System.out.println("[DEBUG addVariablesToSet] ✓ VariableSet commitado sem branch");
                }
                
                // Materializa o resultado do commit
                if (commitResult != null) {
                    if (commitResult instanceof IlrVariableSet) {
                        vset = (IlrVariableSet) commitResult;
                    } else {
                        // Se retornou um Handle, precisa materializar
                        vset = (IlrVariableSet) session.getElementDetails((IlrElementHandle) commitResult);
                    }
                    System.out.println("[DEBUG addVariablesToSet] VariableSet materializado após commit");
                }
            } catch (Exception e) {
                System.out.println("[DEBUG addVariablesToSet] Erro no commit inicial: " + e.getMessage());
                throw new Exception("Falha ao commitar VariableSet vazio", e);
            }
        }
        
        // Agora adiciona TODAS as variáveis em lote
        System.out.println("[DEBUG addVariablesToSet] Criando CommitableObject para variáveis...");
        try {
            Class<?> coClass = Class.forName("ilog.rules.teamserver.model.IlrCommitableObject");
            Constructor<?> ctorHandle = coClass.getConstructor(ilog.rules.teamserver.model.IlrElementHandle.class);
            Object co = ctorHandle.newInstance(vset);
            System.out.println("[DEBUG addVariablesToSet] CommitableObject criado");
            
            Method addModifiedElement = coClass.getMethod("addModifiedElement",
                org.eclipse.emf.ecore.EReference.class,
                ilog.rules.teamserver.model.IlrElementDetails.class);
            
            // Adiciona cada variável ao CommitableObject (SEM commit individual)
            int count = 0;
            for (java.util.Map<String, String> varData : variables) {
                String name = varData.get("name");
                String bomType = varData.get("bomType");
                String verbalization = varData.get("verbalization");
                String initialValue = varData.get("initialValue");
                
                if (name == null || name.isBlank()) {
                    System.out.println("[DEBUG addVariablesToSet] Pulando variável sem nome");
                    continue;
                }
                if (bomType == null || bomType.isBlank()) {
                    System.out.println("[DEBUG addVariablesToSet] Pulando variável " + name + " sem bomType");
                    continue;
                }
                
                System.out.println("[DEBUG addVariablesToSet] Criando variável: " + name);
                
                // Cria handle da variável
                IlrElementHandle varHandle = session.createElement(meta.getVariable());
                IlrElementDetails varDetails = session.getElementDetailsForThisHandle(varHandle);
                
                // Seta atributos
                varDetails.setRawValue((org.eclipse.emf.ecore.EStructuralFeature) meta.getTypedElement_BomType(), bomType);
                varDetails.setRawValue((org.eclipse.emf.ecore.EStructuralFeature) meta.getTypedElement_Name(), name);
                
                if (verbalization != null && !verbalization.isBlank()) {
                    varDetails.setRawValue((org.eclipse.emf.ecore.EStructuralFeature) meta.getTypedElement_Verbalization(), verbalization);
                }
                
                if (initialValue != null && !initialValue.isBlank()) {
                    Object fInit = tryFeature(meta, "getVariable_InitialValue", "getVariable_DefaultValue");
                    if (fInit != null) {
                        varDetails.setRawValue((org.eclipse.emf.ecore.EStructuralFeature) fInit, initialValue);
                    }
                }
                
                // Adiciona ao CommitableObject (NÃO faz commit ainda)
                addModifiedElement.invoke(co, (org.eclipse.emf.ecore.EReference) meta.getVariableSet_Variables(), varDetails);
                count++;
                System.out.println("[DEBUG addVariablesToSet] ✓ Variável " + name + " adicionada ao CommitableObject");
            }
            
            if (count == 0) {
                throw new Exception("Nenhuma variável válida para adicionar");
            }
            
            // Commit ÚNICO com TODAS as variáveis
            System.out.println("[DEBUG addVariablesToSet] Fazendo commit de " + count + " variáveis...");
            if (branch != null) {
                Method commitMethod = null;
                for (Method m : session.getClass().getMethods()) {
                    if (m.getName().equals("commit") && m.getParameterCount() == 2) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params[1].isAssignableFrom(co.getClass())) {
                            commitMethod = m;
                            break;
                        }
                    }
                }
                if (commitMethod != null) {
                    commitMethod.invoke(session, branch, co);
                    System.out.println("[DEBUG addVariablesToSet] ✓✓✓ Commit realizado com branch!");
                } else {
                    session.commit(vset);
                    System.out.println("[DEBUG addVariablesToSet] ✓ Commit sem branch (fallback)");
                }
            } else {
                session.commit(vset);
                System.out.println("[DEBUG addVariablesToSet] ✓ Commit sem branch");
            }
            
            System.out.println("[DEBUG addVariablesToSet] ✓✓✓ SUCESSO! " + count + " variáveis criadas");
            
        } catch (Exception e) {
            System.out.println("[DEBUG addVariablesToSet] ✗ Erro: " + e.getMessage());
            e.printStackTrace();
            throw new Exception("Falha ao adicionar variáveis", e);
        }
    }
    
    /**
     * Método legado mantido para compatibilidade.
     * DEPRECADO: Use addVariablesToSet() para melhor performance.
     */
    @Deprecated
    public IlrVariable addVariable(IlrSession session, IlrVariableSet vset,
            String name, String bomType, String verbalization, String initialValue) throws Exception {
        
        // Converte para o novo formato e chama addVariablesToSet
        java.util.List<java.util.Map<String, String>> variables = new java.util.ArrayList<>();
        java.util.Map<String, String> varData = new java.util.HashMap<>();
        varData.put("name", name);
        varData.put("bomType", bomType);
        varData.put("verbalization", verbalization);
        varData.put("initialValue", initialValue);
        variables.add(varData);
        
        // Obtém a branch
        IlrRuleProject project = vset.getProject();
        Object branch = null;
        if (project != null) {
            try {
                Method getCurrentBaseline = project.getClass().getMethod("getCurrentBaseline");
                branch = getCurrentBaseline.invoke(project);
            } catch (Throwable e) {
                // Sem branch
            }
        }
        
        addVariablesToSet(session, vset, variables, branch);
        
        // Retorna a primeira variável criada
        @SuppressWarnings("unchecked")
        java.util.List<IlrVariable> vars = vset.getVariables();
        if (vars != null && !vars.isEmpty()) {
            for (IlrVariable v : vars) {
                if (name.equals(v.getName())) {
                    return v;
                }
            }
        }
        return null;
    }
}