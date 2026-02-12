
package com.ibm.odm.regras;

import java.util.Arrays;
import java.util.List;

import ilog.rules.teamserver.brm.IlrActionRule;
import ilog.rules.teamserver.brm.IlrDefinition;
import ilog.rules.teamserver.brm.IlrRule;
import ilog.rules.teamserver.brm.IlrRulePackage;
import ilog.rules.teamserver.brm.IlrRuleProject;
import ilog.rules.teamserver.client.IlrRemoteSessionFactory;
import ilog.rules.teamserver.model.IlrCommitableObject;
import ilog.rules.teamserver.model.IlrConnectException;
import ilog.rules.teamserver.model.IlrDefaultSearchCriteria;
import ilog.rules.teamserver.model.IlrModelConstants;
import ilog.rules.teamserver.model.IlrObjectNotFoundException;
import ilog.rules.teamserver.model.IlrSession;
import ilog.rules.teamserver.model.IlrSessionFactory;
import ilog.rules.teamserver.model.IlrSessionHelper;
import ilog.rules.teamserver.brm.IlrBrmPackage;

public class ODMRuleService {

    private final String serverUrl;
    private final String datasource;
    private final String login;
    private final String password;

    public ODMRuleService(String serverUrl, String datasource, String login, String password) {
        this.serverUrl = serverUrl;
        this.datasource = datasource;
        this.login = login;
        this.password = password;
    }

    /** Abre sessão remota no Decision Center */
    public IlrSession openSession() throws IlrConnectException {
        IlrSessionFactory factory = new IlrRemoteSessionFactory();
        factory.connect(login, password, serverUrl, datasource);
        return factory.getSession();
    }

    /** Fecha sessão remota */
    public void closeSession(IlrSession session) {
        if (session != null) {
            session.close();
        }
    }

    /** Cria projeto se não existir; caso exista, retorna o existente */
    public IlrRuleProject createProjectIfNotExists(IlrSession session, String projectName) throws Exception {
        IlrRuleProject project = (IlrRuleProject) IlrSessionHelper.getProjectNamed(session, projectName);
        if (project == null) {
            project = (IlrRuleProject) IlrSessionHelper.createRuleProject(session, projectName);
            session.commit(project);
            System.out.println("Projeto criado: " + projectName);
        } else {
            System.out.println("Projeto já existe: " + projectName);
        }
        return project;
    }

    /** Posiciona a sessão no baseline atual do projeto (passo crítico) */
    public void setWorkingBaselineToProject(IlrSession session, IlrRuleProject project) throws Exception {
        ilog.rules.teamserver.brm.IlrBaseline current = IlrSessionHelper.getCurrentBaseline(session, project);
        session.setWorkingBaseline(current);
    }

    /** Cria um pacote na raiz do projeto (pai = null), com sessão posicionada no baseline do projeto */
    public IlrRulePackage createRootPackage(IlrSession session, IlrRuleProject project, String packageName) throws Exception {
        setWorkingBaselineToProject(session, project);
        IlrRulePackage pkg = IlrSessionHelper.createRulePackage(session, null, packageName);
        session.commit(pkg);
        System.out.println("Pacote criado na raiz: " + packageName);
        return pkg;
    }

    /** Cria uma Action Rule simples dentro do pacote */
    public IlrRule createActionRule(IlrSession session, IlrRulePackage pkg, String ruleName, String ruleBodyIrl) throws Exception {
        IlrRule rule = IlrSessionHelper.createActionRule(session, pkg, ruleName, ruleBodyIrl);
        session.commit(rule);
        System.out.println("Regra criada: " + ruleName);
        return rule;
    }

    // ============================
    // MÉTODOS DE ALTERAÇÃO DE REGRAS
    // ============================

    /** Localiza uma Action Rule pelo nome dentro do projeto (baseline atual) */
    public IlrActionRule findActionRuleByName(IlrSession session, IlrRuleProject project, String ruleName) throws Exception {
        setWorkingBaselineToProject(session, project);

        IlrBrmPackage meta = session.getBrmPackage(); // metamodelo
        IlrDefaultSearchCriteria criteria = new IlrDefaultSearchCriteria(
            meta.getActionRule(),
            Arrays.asList(meta.getModelElement_Name()),
            Arrays.asList(ruleName)
        );

        @SuppressWarnings("unchecked")
        List<IlrActionRule> details = session.findElements(criteria, IlrModelConstants.ELEMENT_DETAILS);
        if (details == null || details.isEmpty()) {
            return null;
        }
        return details.get(0);
    }

    /** Efetua lock na regra (boa prática antes de editar) */
    public void lockRule(IlrSession session, IlrActionRule rule) throws Exception {
        session.lockElement(rule);
    }

    /** Libera lock da regra */
    public void unlockRule(IlrSession session, IlrActionRule rule) throws Exception {
        session.unlockElement(rule);
    }

    /**
     * Atualiza o corpo IRL da Action Rule.
     * A definição do corpo é um elemento agregado: precisamos usar IlrCommitableObject.
     */
    public void updateActionRuleBody(IlrSession session, IlrActionRule rule, String newIRLBody) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefinition def = rule.getDefinition();

        // altera o corpo IRL na definição agregada
        def.setRawValue(meta.getDefinition_Body(), newIRLBody);

        // usa IlrCommitableObject para commitar a definição alterada
        IlrCommitableObject co = new IlrCommitableObject(rule);
        co.addModifiedElement(meta.getRuleArtifact_Definition(), def);

        session.commit(co);
        System.out.println("Corpo IRL atualizado: " + rule.getName());
    }

    /** Atualiza a prioridade da regra (ex.: 1..10) */
    public void updateActionRulePriority(IlrSession session, IlrActionRule rule, int priority) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        rule.setRawValue(meta.getRule_Priority(), Integer.toString(priority));
        session.commit(rule);
        System.out.println("Prioridade atualizada: " + rule.getName() + " => " + priority);
    }

    /** Renomeia a regra */
    public void renameActionRule(IlrSession session, IlrActionRule rule, String newName) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        rule.setRawValue(meta.getModelElement_Name(), newName);
        session.commit(rule);
        System.out.println("Regra renomeada para: " + newName);
    }

    // ============================
    // EXEMPLO: localizar, lock, editar corpo, prioridade e unlock
    // ============================
    public void editRuleExample(String projectName, String ruleName, String newIRLBody, int newPriority, String newName) throws Exception {
        IlrSession session = null;
        try {
            session = openSession();

            IlrRuleProject project = (IlrRuleProject) IlrSessionHelper.getProjectNamed(session, projectName);
            if (project == null) {
                throw new IllegalStateException("Projeto não encontrado: " + projectName);
            }

            IlrActionRule rule = findActionRuleByName(session, project, ruleName);
            if (rule == null) {
                throw new IllegalStateException("Regra não encontrada: " + ruleName);
            }

            // Lock
            lockRule(session, rule);

            // Atualiza corpo IRL
            updateActionRuleBody(session, rule, newIRLBody);

            // Atualiza prioridade
            updateActionRulePriority(session, rule, newPriority);

            // (Opcional) Renomeia a regra
            if (newName != null && !newName.isBlank()) {
                renameActionRule(session, rule, newName);
            }

            // Unlock
            unlockRule(session, rule);

            System.out.println("Edição concluída com sucesso.");

        } finally {
            closeSession(session);
        }
    }

/**
 * Retorna detalhes completos de uma Action Rule usando métodos diretos da API.
 * @throws IlrObjectNotFoundException 
 */
public String getRuleDetails(IlrActionRule rule) throws IlrObjectNotFoundException {
    String name = rule.getName();
    String priority = rule.getPriority(); // método direto
    String packageName = (rule.getRulePackage() != null) ? rule.getRulePackage().getName() : "Desconhecido";

    // Corpo IRL via definição
    String body = "";
    if (rule.getDefinition() != null) {
        body = rule.getDefinition().getBody(); // método direto
    }

    return String.format(
        "Regra: %s%nPacote: %s%nPrioridade: %s%nCorpo IRL:%n%s",
        name,
        packageName,
        priority,
        body
    );
}
 


public void deleteProject(IlrSession session, IlrRuleProject project) throws Exception {
    if (session == null) {
        throw new IllegalArgumentException("Sessão não pode ser nula.");
    }
    if (project == null) {
        throw new IllegalArgumentException("Projeto não pode ser nulo.");
    }

    // Baseline do projeto (boa prática)
    setWorkingBaselineToProject(session, project);

    // Tenta aplicar lock (se outro usuário detém o lock, apenas avisa)
    boolean lockedByUs = false;
    try {
        session.lockElement(project);
        lockedByUs = true;
    } catch (Exception e) {
        System.err.println("[WARN] Não foi possível aplicar lock no projeto: " + e.getMessage());
    }

    // Exclui e commita
    session.deleteElement(project);
    session.commit(project); // <-- aqui está a correção

    // (Opcional) tentar liberar lock; normalmente não é necessário após delete
    if (lockedByUs) {
        try { session.unlockElement(project); } catch (Exception ignore) {}
    }

    System.out.println("Projeto deletado: " + project.getName());
}

public boolean deleteProjectByName(String projectName) throws Exception {
    IlrSession session = null;
    try {
        session = openSession();
        IlrRuleProject project = (IlrRuleProject) IlrSessionHelper.getProjectNamed(session, projectName);
        if (project == null) {
            System.out.println("Projeto não encontrado: " + projectName);
            return false;
        }
        deleteProject(session, project);
        return true;
    } finally {
        closeSession(session);
    }
}





}
