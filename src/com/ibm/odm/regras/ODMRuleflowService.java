
package com.ibm.odm.regras;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import ilog.rules.teamserver.brm.IlrBaseline;
import ilog.rules.teamserver.brm.IlrBrmPackage;
import ilog.rules.teamserver.brm.IlrRulePackage;
import ilog.rules.teamserver.brm.IlrRuleProject;
import ilog.rules.teamserver.brm.IlrRuleflow;
import ilog.rules.teamserver.client.IlrRemoteSessionFactory;
import ilog.rules.teamserver.model.IlrDefaultSearchCriteria;
import ilog.rules.teamserver.model.IlrModelConstants;
import ilog.rules.teamserver.model.IlrObjectNotFoundException;
import ilog.rules.teamserver.model.IlrSession;
import ilog.rules.teamserver.model.IlrSessionFactory;
import ilog.rules.teamserver.model.IlrSessionHelper;

/**
 * Serviço para criar/alterar Ruleflows no IBM ODM Decision Center via API remota.
 *
 * Estratégia compatível:
 *  - cria o Ruleflow (helper ou createElement)
 *  - define o nome (ModelElement_Name)
 *  - associa ao pacote via meta-atributo PackageElement_RulePackage
 *  - commit
 *  - lock → atualiza Body (DRF) e MainFlowTask → commit → unlock
 *
 * Referências:
 *  - Decision Center API (sessão remota, baseline, commit/lock) 
 *  - IlrRuleflow herda IlrPackageElement; meta PackageElement_RulePackage
 */
public class ODMRuleflowService {

    private final String serverUrl;
    private final String datasource;
    private final String login;
    private final String password;

    public ODMRuleflowService(String serverUrl, String datasource, String login, String password) {
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        this.datasource = Objects.requireNonNull(datasource, "datasource");
        this.login = Objects.requireNonNull(login, "login");
        this.password = Objects.requireNonNull(password, "password");
    }

    /** Abre sessão remota no Decision Center */
    public IlrSession openSession() throws Exception {
        IlrSessionFactory factory = new IlrRemoteSessionFactory();
        factory.connect(login, password, serverUrl, datasource);
        return factory.getSession();
    }

    /** Fecha sessão remota */
    public void closeSession(IlrSession session) {
        if (session != null) {
            try { session.close(); } catch (Exception ignore) {}
        }
    }

    /** Posiciona baseline atual do projeto (passo crítico) */
    public void setWorkingBaselineToProject(IlrSession session, IlrRuleProject project) throws Exception {
        IlrBaseline current = IlrSessionHelper.getCurrentBaseline(session, project);
        session.setWorkingBaseline(current);
    }

    /** Localiza projeto por nome (lança IllegalArgumentException se não existir) */
    public IlrRuleProject getProjectOrThrow(IlrSession session, String projectName) throws Exception {
        IlrRuleProject project = (IlrRuleProject) IlrSessionHelper.getProjectNamed(session, projectName);
        if (project == null) {
            throw new IllegalArgumentException("Projeto não encontrado: " + projectName);
        }
        return project;
    }

    /** Busca um pacote por nome via critérios (ELEMENT_DETAILS) */
    public IlrRulePackage findRulePackageByName(IlrSession session, String packageName) throws Exception {
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

    /** Localiza ou cria um pacote na raiz (baseline atual) */
    public IlrRulePackage findOrCreatePackage(IlrSession session, IlrRuleProject project, String packageName) throws Exception {
        setWorkingBaselineToProject(session, project);
        IlrRulePackage pkg = findRulePackageByName(session, packageName);
        if (pkg == null) {
            pkg = IlrSessionHelper.createRulePackage(session, null, packageName);
            session.commit(pkg);
            System.out.println("Pacote criado automaticamente: " + packageName);
        }
        return pkg;
    }

    /** Localiza Ruleflow pelo nome e confirma que pertence ao pacote informado */
    public IlrRuleflow findRuleflowInPackageByName(IlrSession session, IlrRulePackage pkg, String ruleflowName) throws Exception {
        IlrBrmPackage meta = session.getBrmPackage();
        IlrDefaultSearchCriteria byName = new IlrDefaultSearchCriteria(
            meta.getRuleflow(),
            Arrays.asList(meta.getModelElement_Name()),
            Arrays.asList(ruleflowName)
        );
        @SuppressWarnings("unchecked")
        List<IlrRuleflow> flows = session.findElements(byName, IlrModelConstants.ELEMENT_DETAILS);
        if (flows == null || flows.isEmpty()) return null;

        for (IlrRuleflow rf : flows) {
            try {
                IlrRulePackage rfPkg = rf.getRulePackage(); // pode lançar IlrObjectNotFoundException
                if (rfPkg != null && pkg.getName() != null && pkg.getName().equals(rfPkg.getName())) {
                    return rf;
                }
            } catch (IlrObjectNotFoundException onf) {
                // se não achar o pacote do elemento, continua procurando
            }
        }
        return null;
    }

    /**
     * Cria ou atualiza um Ruleflow:
     *  - Se não existir, cria via helper (ou createElement), NOMEIA e ASSOCIA ao pacote via meta PackageElement_RulePackage; commit.
     *  - Atualiza Body (DRF) e (opcional) MainFlowTask com lock/commit/unlock.
     */
    public IlrRuleflow createOrUpdateRuleflow(IlrSession session,
                                              IlrRuleProject project,
                                              IlrRulePackage pkg,
                                              String ruleflowName,
                                              String drfBody,
                                              Boolean setAsMainFlowTask) throws Exception {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(pkg, "pkg");
        Objects.requireNonNull(ruleflowName, "ruleflowName");

        setWorkingBaselineToProject(session, project);
        IlrBrmPackage meta = session.getBrmPackage();

        IlrRuleflow rf = findRuleflowInPackageByName(session, pkg, ruleflowName);
        if (rf == null) {
            // 1) Criação do Ruleflow (helper disponível nas suas libs)
            IlrRuleflow rfTmp = IlrSessionHelper.createRuleflow(session);

            // 2) Nome do Ruleflow
            rfTmp.setRawValue(meta.getModelElement_Name(), ruleflowName);

            // 3) Associar ao pacote via meta-atributo de IlrPackageElement (PackageElement_RulePackage)
            rfTmp.setRawValue(meta.getPackageElement_RulePackage(), pkg);

            // 4) Persistir criação+associação
            session.commit(rfTmp);

            rf = findRuleflowInPackageByName(session, pkg, ruleflowName);
            if (rf == null) {
                throw new IllegalStateException("Falha ao localizar Ruleflow recém-associado ao pacote: " + ruleflowName);
            }
            System.out.println("Ruleflow criado e associado ao pacote: " + ruleflowName);
        } else {
            System.out.println("Ruleflow localizado: " + ruleflowName);
        }

        // 5) lock para edição
        session.lockElement(rf);
        try {
            // Atualiza o Body (DRF)
            if (drfBody != null && !drfBody.isBlank()) {
                rf.setRawValue(meta.getRuleflow_Body(), drfBody);
            }
            // Opcional: main flow task
            if (setAsMainFlowTask != null) {
            	rf.setRawValue(meta.getRuleflow_MainFlowTask(), setAsMainFlowTask);
            	}
            // commit das alterações
            session.commit(rf);
        } finally {
            session.unlockElement(rf);
        }

        return rf;
    }

    /** Descrição básica de um Ruleflow */
    public String describeRuleflow(IlrRuleflow rf) {
        String pkgName = "";
        try {
            pkgName = (rf.getRulePackage() != null) ? rf.getRulePackage().getName() : "";
        } catch (IlrObjectNotFoundException ignore) {
            // pacote não resolvido
        }
        String body = rf.getBody() == null ? "" : rf.getBody();
        String locale = rf.getLocale() == null ? "" : rf.getLocale();
        return String.format(
            "Ruleflow: %s%nPacote: %s%nMainFlowTask: %s%nLocale: %s%nBody (presente?): %s",
            rf.getName(),
            pkgName,
            rf.isMainFlowTask(),
            locale,
            !body.isEmpty()
        );
    }
}
