
package com.ibm.odm.regras;

public class TesteDeleteProjeto {

    public static void main(String[] args) {

        // --- CONFIGURAÇÕES DO DC ---
 String serverUrl  = "http://my-odm.ibm.com:9060/decisioncenter-api"; // URL do DC;
        String datasource = "jdbc/ilogDataSource";
        String login      = "odmAdmin";
        String password   = "odmAdmin";

        // --- PROJETO A SER DELETADO ---
        String projectName = "MeuProjeto";

        ODMRuleService service = new ODMRuleService(
                serverUrl,
                datasource,
                login,
                password
        );

        try {
            boolean ok = service.deleteProjectByName(projectName);

            if (ok) {
                System.out.println("Projeto deletado com sucesso: " + projectName);
            } else {
                System.out.println("Projeto não encontrado: " + projectName);
            }

        } catch (Exception e) {
            System.err.println("Erro ao deletar projeto: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
