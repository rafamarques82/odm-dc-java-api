
package com.ibm.odm.regras;

import ilog.rules.teamserver.brm.IlrBaseline;
import ilog.rules.teamserver.brm.IlrRulePackage;
import ilog.rules.teamserver.brm.IlrRuleProject;
import ilog.rules.teamserver.brm.IlrRuleflow;
import ilog.rules.teamserver.model.IlrSession;
import ilog.rules.teamserver.model.IlrSessionHelper;

/**
 * Teste simples (ODM 9.5, API remota):
 * Cria/edita um Ruleflow com um único RuleTask entre Start e Stop.
 *
 * Args (opcionais):
 *   1) projectName   (default: "MeuDecisionService")
 *   2) packageName   (default: "01 - Elegibilidade")
 *   3) ruleflowName  (default: "MainFlowSimple")
 *   4) baselineName  (default: "Main")
 *   5) mainFlowTask  (default: "true")
 */
public class ODMRuleflowTest {

    // ===== Ajuste seu ambiente =====
    private static final String DC_USERNAME   = "odmAdmin";
    private static final String DC_PASSWORD   = "odmAdmin";
    private static final String DC_URL        = "http://my-odm.ibm.com:9060/decisioncenter-api";
    private static final String DC_DATASOURCE = "jdbc/ilogDataSource";

    public static void main(String[] args) {
        String projectName   = (args.length >= 1 && !args[0].isBlank()) ? args[0] : "DS-FinanciamentoImobiliario";
        String packageName   = (args.length >= 2 && !args[1].isBlank()) ? args[1] : "main";
        String ruleflowName  = (args.length >= 3 && !args[2].isBlank()) ? args[2] : "MainFlowSimple";
        String baselineName  = (args.length >= 4 && !args[3].isBlank()) ? args[3] : "Main";
        Boolean setAsMain    = (args.length >= 5 && !args[4].isBlank()) ? Boolean.valueOf(args[4]) : Boolean.TRUE;

        ODMRuleflowService rfService = new ODMRuleflowService(DC_URL, DC_DATASOURCE, DC_USERNAME, DC_PASSWORD);
        IlrSession session = null;

        try {
            System.out.println("== Abrindo sessão remota ==");
            session = rfService.openSession();

            // Projeto + baseline
            IlrRuleProject project = rfService.getProjectOrThrow(session, projectName);
            IlrBaseline baseline = resolveBaseline(session, project, baselineName);
            session.setWorkingBaseline(baseline);
            System.out.println("Baseline ativa: " + baseline.getName());

            // Pacote (escopo da tarefa)
            IlrRulePackage pkg = rfService.findOrCreatePackage(session, project, packageName);

            // DRF mínimo (Start → RuleTask(packageName) → Stop) + NodeList + TransitionList
            String drfBody = buildSimpleDRF(packageName);

            // Cria/edita o Ruleflow e grava Body + MainFlowTask
            IlrRuleflow rf = rfService.createOrUpdateRuleflow(session, project, pkg, ruleflowName, drfBody, setAsMain);

            // LOG
            System.out.println("== Ruleflow simples criado/atualizado ==");
            System.out.println(rfService.describeRuleflow(rf));
            String bodyAfter = rf.getBody();
            System.out.println("\n[DEBUG] Body (trecho):\n" +
                    (bodyAfter == null ? "<null>" : bodyAfter.substring(0, Math.min(bodyAfter.length(), 300))));
            System.out.println("\nAbra no Business console → 'View Source' para ver o XML; o diagrama simples deve renderizar.");

        } catch (Exception e) {
            System.err.println("[ERRO] " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        } finally {
            rfService.closeSession(session);
            System.out.println("== Sessão encerrada ==");
        }
    }

    private static IlrBaseline resolveBaseline(IlrSession session, IlrRuleProject project, String baselineName) throws Exception {
        if (baselineName == null || baselineName.isBlank()) {
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

    /** DRF simples com TaskList + NodeList + TransitionList (sem Resources) */
    private static String buildSimpleDRF(String packageName) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Ruleflow xmlns=\"http://schemas.ilog.com/Rules/7.0/Ruleflow\">\n");
        sb.append("  <Body>\n");
        sb.append("    <TaskList>\n");
        sb.append("      <StartTask Identifier=\"tStart\"/>\n");
        sb.append("      <RuleTask ExecutionMode=\"Sequential\" ExitCriteria=\"None\" Identifier=\"tRule\" Ordering=\"Default\">\n");
        sb.append("        <RuleList>\n");
        sb.append("          <Package Name=\"").append(packageName).append("\"/>\n");
        sb.append("        </RuleList>\n");
        sb.append("      </RuleTask>\n");
        sb.append("      <StopTask Identifier=\"tStop\"/>\n");
        sb.append("    </TaskList>\n");
        sb.append("    <NodeList>\n");
        sb.append("      <TaskNode Identifier=\"nStart\" Task=\"tStart\"/>\n");
        sb.append("      <TaskNode Identifier=\"nRule\"  Task=\"tRule\"/>\n");
        sb.append("      <TaskNode Identifier=\"nStop\"  Task=\"tStop\"/>\n");
        sb.append("    </NodeList>\n");
        sb.append("    <TransitionList>\n");
        sb.append("      <Transition Identifier=\"x0\" Source=\"nStart\" Target=\"nRule\"/>\n");
        sb.append("      <Transition Identifier=\"x1\" Source=\"nRule\"  Target=\"nStop\"/>\n");
        sb.append("    </TransitionList>\n");
        sb.append("  </Body>\n");
        sb.append("  <Resources><ResourceSet Locale=\"pt_BR\"/></Resources>\n");
        sb.append("  <Properties><imports/></Properties>\n");
        sb.append("</Ruleflow>\n");
        return sb.toString();
    }
}
