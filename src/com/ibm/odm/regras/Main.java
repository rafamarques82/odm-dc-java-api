
package com.ibm.odm.regras;

import ilog.rules.teamserver.model.IlrApplicationException;
import ilog.rules.teamserver.model.IlrConnectException;
import ilog.rules.teamserver.model.IlrObjectNotFoundException;

import java.util.List;
import java.util.Optional;

/**
 * Executável de teste para a ODMVocabularyService com Decision Service e Baseline expostos.
 *
 * Parâmetros expostos (args ou env):
 *   Args: <decisionServiceName> <baselineName>
 *   Env : DS_NAME, BASELINE
 *
 * Prioridade: argumentos > variáveis de ambiente > default interno.
 *
 * Exemplos:
 *   java -cp ".:lib/*" com.ibm.odm.regras.Main "MeuDecisionService" "%current_key"
 *
 *   (ou via env)
 *   export DS_NAME="MeuDecisionService"; export BASELINE="%current_key"
 *   java -cp ".:lib/*" com.ibm.odm.regras.Main
 */
public class Main {

    /* =========================
     * ======== CONFIG =========
     * =========================
     * Credenciais e conexão (internos)
     */
    private static final String DC_USERNAME   = "odmAdmin";   // usuário do DC
    private static final String DC_PASSWORD   = "odmAdmin"; // senha
    private static final String DC_URL        = "http://my-odm.ibm.com:9060/decisioncenter-api"; // URL do DC
    private static final String DC_DATASOURCE = "jdbc/ilogDataSource"; // nome do repositório

    // Defaults internos (usados se não vier arg/env)
    private static final String DEFAULT_DECISION_SERVICE_NAME = "DS-FinanciamentoImobiliario";
    private static final String DEFAULT_BASELINE_NAME         = "%current_key";

    public static void main(String[] args) {
        // 1) Obter Decision Service e Baseline: args > env > default
        String decisionServiceName = pick(
                argAt(args, 0),
                getenv("DS_NAME"),
                DEFAULT_DECISION_SERVICE_NAME
        );
        String baselineName = pick(
                argAt(args, 1),
                getenv("BASELINE"),
                DEFAULT_BASELINE_NAME
        );

        // Logs de contexto (úteis para MCP tool)
        System.out.printf("Decision Service: %s%n", decisionServiceName);
        System.out.printf("Baseline         : %s%n", baselineName);

        // 2) Validação mínima dos parâmetros internos
        if (!notBlank(DC_USERNAME) || !notBlank(DC_PASSWORD) || !notBlank(DC_URL) || !notBlank(DC_DATASOURCE)) {
            System.err.println("[ERRO] Preencha credenciais/URL/datasource na seção CONFIG.");
            System.exit(2);
        }
        // 3) Validação dos parâmetros expostos
        if (!notBlank(decisionServiceName) || !notBlank(baselineName)) {
            printUsage();
            System.exit(3);
        }

        // 4) Instanciar serviço
        ODMVocabularyService service = new ODMVocabularyService(
                DC_USERNAME, DC_PASSWORD, DC_URL, DC_DATASOURCE
        );

        // 5) Executar e imprimir resultados
        try {
            System.out.println("\nConsultando vocabulários do BOM...\n");

            List<ODMVocabularyService.VocabularyInfo> vocabularies =
                    service.listBOMVocabularies(decisionServiceName, baselineName);

            if (vocabularies.isEmpty()) {
                System.out.println("Nenhum vocabulário encontrado para os parâmetros informados.");
            } else {
                printTable(vocabularies);
            }

            System.out.println("\nOK.");
        } catch (IlrConnectException e) {
            System.err.println("[ERRO] Conexão com Decision Center falhou: " + e.getMessage());
            e.printStackTrace();
            System.exit(10);
        } catch (IlrObjectNotFoundException e) {
            System.err.println("[ERRO] Artefato não encontrado/inacessível: " + e.getMessage());
            e.printStackTrace();
            System.exit(11);
        } catch (IlrApplicationException e) {
            System.err.println("[ERRO] Falha na aplicação da API do DC: " + e.getMessage());
            e.printStackTrace();
            System.exit(12);
        } catch (IllegalArgumentException e) {
            System.err.println("[ERRO] Parâmetro inválido: " + e.getMessage());
            e.printStackTrace();
            System.exit(13);
        } catch (Exception e) {
            System.err.println("[ERRO] Exceção não prevista: " + e.getMessage());
            e.printStackTrace();
            System.exit(14);
        }
    }

    /* ===== Helpers ===== */


private static void printTable(List<ODMVocabularyService.VocabularyInfo> list) {
    String fmt = "%-30s %-20s %-12s %s%n";
    System.out.printf(fmt, "DecisionService", "Baseline", "Locale", "Body");
    System.out.println(repeat('-', 100));

    for (ODMVocabularyService.VocabularyInfo v : list) {
        // <<< sem truncar >>>
        String bodyFull = safe(v.getBody());
        System.out.printf(fmt,
                safe(v.getDecisionService()),
                safe(v.getBaseline()),
                safe(v.getLocale()),
                bodyFull
        );
    }
}


    // Escolhe o primeiro valor não nulo/não vazio
    private static String pick(String... candidates) {
        for (String c : candidates) {
            if (notBlank(c)) return c;
        }
        return null;
    }
    private static String argAt(String[] args, int idx) {
        return (args != null && args.length > idx) ? args[idx] : null;
    }
    private static String getenv(String key) {
        return Optional.ofNullable(System.getenv(key)).orElse(null);
    }
    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String trimTo(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static void printUsage() {
        System.out.println("Uso:");
        System.out.println("  java -cp \".:lib/*\" com.ibm.odm.regras.Main <decisionServiceName> <baselineName>");
        System.out.println();
        System.out.println("Ou defina variáveis de ambiente:");
        System.out.println("  DS_NAME, BASELINE");
        System.out.println();
        System.out.println("Exemplos:");
        System.out.println("  java -cp \".:lib/*\" com.ibm.odm.regras.Main \"MeuDecisionService\" \"%current_key\"");
        System.out.println("  export DS_NAME=\"MeuDecisionService\"; export BASELINE=\"%current_key\"; java -cp \".:lib/*\" com.ibm.odm.regras.Main");
    }
}
