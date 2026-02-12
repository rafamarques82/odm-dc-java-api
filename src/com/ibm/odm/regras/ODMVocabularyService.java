
package com.ibm.odm.regras;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import ilog.rules.teamserver.brm.IlrBaseline;
import ilog.rules.teamserver.brm.IlrBrmPackage;
import ilog.rules.teamserver.brm.IlrRuleProject;
import ilog.rules.teamserver.brm.IlrVocabulary;
import ilog.rules.teamserver.client.IlrRemoteSessionFactory;
import ilog.rules.teamserver.model.IlrApplicationException;
import ilog.rules.teamserver.model.IlrConnectException;
import ilog.rules.teamserver.model.IlrElementDetails;
import ilog.rules.teamserver.model.IlrObjectNotFoundException;
import ilog.rules.teamserver.model.IlrSession;
import ilog.rules.teamserver.model.IlrSessionHelper;
import ilog.rules.teamserver.model.finders.DataFinder;

/**
 * Cliente para retornar os vocabulários (verbalizações) do BOM de uma baseline específica
 * em um Decision Service do IBM ODM Decision Center.
 *
 * Compatível com linhas 8.5–8.11 (sem try-with-resources; fechamento manual da sessão).
 * 
 * Referências IBM:
 * - "Using the Decision Center API": abertura de sessão, projetos, baselines. 
 * - Interface IlrVocabulary e DataFinder: localização de artefatos do BRM.
 */
public class ODMVocabularyService {

    private  String username;
    private  String password;
    private  String url;
    private  String datasource;

    public ODMVocabularyService(String username, String password, String url, String datasource) {
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.url = Objects.requireNonNull(url, "url");
        this.datasource = Objects.requireNonNull(datasource, "datasource");
    }

    /**
     * Retorna os vocabulários (verbalizações) do BOM para uma baseline/branch específica.
     *
     * @param decisionServiceName Nome do Decision Service (projeto no DC).
     * @param baselineName        Nome exato da baseline/branch; se for "%current_key", usa a baseline atual.
     * @return Lista de vocabulários (locale + body) da baseline informada.
     * @throws IlrConnectException         erro de conexão com o DC
     * @throws IlrApplicationException     erros de aplicação da API
     * @throws IlrObjectNotFoundException  quando artefatos são inexistentes/inacessíveis
     */
    public List<VocabularyInfo> listBOMVocabularies(String decisionServiceName, String baselineName)
            throws IlrConnectException, IlrApplicationException, IlrObjectNotFoundException {

        IlrSession session = null;
        try {
            session = openSession();

            // Projeto (Decision Service)
            IlrRuleProject project = getProjectOrThrow(session, decisionServiceName);

            // Resolver baseline pelo nome (case-insensitive + aliases) e "%current_key"
            IlrBaseline baseline = resolveBaselineByName(session, project, baselineName);
            if (baseline == null) {
                throw new IllegalArgumentException("Baseline/branch não encontrada: " + baselineName);
            }

            // Trabalhar na baseline informada
            session.setWorkingBaseline(baseline);

            // Buscar vocabulários do BOM (apenas projeto principal; sem dependências)
            IlrBrmPackage brm = session.getBrmPackage();
            DataFinder finder = DataFinder.createDataFinder(brm.getVocabulary())
                                          .setUseDependencies(false);

            List<IlrElementDetails> elements = finder.findDetails(session);
            List<VocabularyInfo> result = new ArrayList<VocabularyInfo>(elements.size());

            for (IlrElementDetails details : elements) {
                IlrVocabulary vocab = (IlrVocabulary) details;
                result.add(new VocabularyInfo(
                    project.getName(),
                    baseline.getName(),
                    safeString(vocab.getLocale()),
                    safeString(vocab.getBody())
                ));
            }

            return result;

        } finally {
            closeQuietly(session);
        }
    }

    // ======================
    // Utilitários internos
    // ======================

    private IlrSession openSession() throws IlrConnectException {
        IlrRemoteSessionFactory factory = new IlrRemoteSessionFactory();
        factory.connect(username, password, url, datasource); // API oficial do DC
        return factory.getSession();
    }

    private void closeQuietly(IlrSession session) {
        if (session != null) {
            try { session.close(); } catch (Exception ignore) {}
        }
    }

    private IlrRuleProject getProjectOrThrow(IlrSession session, String decisionServiceName)
            throws IlrApplicationException, IlrObjectNotFoundException {
        IlrRuleProject project = IlrSessionHelper.getProjectNamed(session, decisionServiceName);
        if (project == null) {
            throw new IllegalArgumentException("Decision Service não encontrado: " + decisionServiceName);
        }
        return project;
    }

    /**
     * Resolve a baseline/branch pelo nome informado, tratando:
     * - token especial "%current_key" => baseline atual
     * - comparação case-insensitive
     * - aliases comuns ("main" => "Main", etc.)
     * Não percorre dependências; foco apenas no projeto principal.
     */
    private IlrBaseline resolveBaselineByName(IlrSession session, IlrRuleProject project, String baselineName)
            throws IlrApplicationException, IlrObjectNotFoundException {

        // token especial: baseline atual
        if (baselineName != null) {
            String token = baselineName.trim();
            if ("%current_key".equalsIgnoreCase(token)) {
                return IlrSessionHelper.getCurrentBaseline(session, project);
            }
        }

        // sem nome => baseline atual
        if (baselineName == null || baselineName.trim().length() == 0) {
            return IlrSessionHelper.getCurrentBaseline(session, project);
        }

        // tentativa direta por nome
        IlrBaseline baseline = IlrSessionHelper.getBaselineNamed(session, project, baselineName);
        if (baseline != null) return baseline;

        // case-insensitive no projeto
        List<IlrBaseline> baselines = IlrSessionHelper.getBaselines(session, project);
        String wanted = baselineName.trim().toLowerCase(Locale.ROOT);
        for (IlrBaseline b : baselines) {
            String name = b.getName();
            if (name != null && name.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return b;
            }
        }

        // aliases comuns
        Map<String, String> aliases = Map.of(
            "main", "Main",
            "master", "Main",
            "principal", "Main",
            "mainline", "Main",
            "develop", "Development",
            "dev", "Development"
        );
        String alias = aliases.get(wanted);
        if (alias != null) {
            baseline = IlrSessionHelper.getBaselineNamed(session, project, alias);
            if (baseline != null) return baseline;
            for (IlrBaseline b : baselines) {
                String name = b.getName();
                if (name != null && name.trim().equalsIgnoreCase(alias)) {
                    return b;
                }
            }
        }

        // não achou
        return null;
    }

    private String safeString(String s) { return s == null ? "" : s; }

    // ======================
    // DTO de retorno
    // ======================

    /**
     * Informações de vocabulário (verbalização) do BOM.
     */
    public static class VocabularyInfo {
        private final String decisionService;
        private final String baseline;
        private final String locale;
        private final String body;

        public VocabularyInfo(String decisionService, String baseline, String locale, String body) {
            this.decisionService = decisionService;
            this.baseline = baseline;
            this.locale = locale;
            this.body = body;
        }

        public String getDecisionService() { return decisionService; }
        public String getBaseline()        { return baseline; }
        public String getLocale()          { return locale; }
        public String getBody()            { return body; }
    }
}
