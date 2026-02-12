
package com.ibm.odm.regras;

import com.fasterxml.jackson.databind.JsonNode;
import ilog.rules.dt.IlrDTController;
import ilog.rules.dt.IlrDTExpressionManager;
import ilog.rules.dt.model.IlrDTActionDefinition;
import ilog.rules.dt.model.IlrDTModel;
import ilog.rules.dt.model.IlrDTPartitionDefinition;
import ilog.rules.dt.model.helper.IlrDTPropertyHelper;
import ilog.rules.dt.model.expression.IlrDTExpressionDefinition;
import ilog.rules.dt.model.helper.IlrDTHelper;
import ilog.rules.teamserver.model.IlrSession;

import java.util.*;

/**
 * Constrói uma Decision Table a partir de um JSON "model" (somente JSON no endpoint).
 * Observação:
 * - Cria colunas (condições/ações) com títulos/verbalizações.
 * - Garante pelo menos uma linha (linha 0) para evitar erro no editor.
 * - Partições e valores nas folhas ficam marcados como TODO para implementação conforme a sua versão do ODM.
 */
public final class DTJsonBuilder {

    private DTJsonBuilder() {}

    public static void populateModelFromJson(IlrSession session,
                                             IlrDTController ctrl,
                                             IlrDTModel dtModel,
                                             JsonNode modelNode,
                                             Locale locale) throws Exception {
        if (modelNode == null || modelNode.isNull()) return;

        // Expression manager (necessário para criar ExpressionDefinition)
        IlrDTExpressionManager em = dtModel.getExpressionManager();

        // 1) Preconditions (TODO: ligar ao modelo se necessário)
        JsonNode pres = modelNode.get("preconditions");
        if (pres != null && pres.isArray()) {
            for (JsonNode p : pres) {
                String stmt = text(p, "statement");
                if (!isBlank(stmt)) {
                    // TODO: criar e adicionar pré-condição (IlrDTHelper.setPreconditionsText ou API específica)
                }
            }
        }

        // 2) Conditions — cria colunas (hierarquia) usando newPartitionDefinition + addPartitionDefinition
        for (JsonNode c : arrayOrEmpty(modelNode, "conditions")) {
            addConditionRecursive(dtModel, em, c, locale);
        }

        // 3) Actions — cria colunas de ação usando newActionDefinition + addActionDefinition
        for (JsonNode a : arrayOrEmpty(modelNode, "actions")) {
            addActionColumn(dtModel, em, a, locale);
        }

        // 4) Garantir pelo menos 1 linha (row 0) com itens de condição
        int condCount = dtModel.getPartitionDefinitionCount();
        if (condCount > 0) {
            for (int col = 0; col < condCount; col++) {
                IlrDTHelper.createPartitionItemAt(dtModel, 0, col);
            }
        }

        // 5) Valores nas folhas por paths (se fornecidos)
        for (JsonNode a : arrayOrEmpty(modelNode, "actions")) {
            applyActionValuesByPath(ctrl, dtModel, a);
        }
    }

    /** Cria uma coluna de condição e, se houver, processa colunas-filhas aninhadas. */
    private static void addConditionRecursive(IlrDTModel dtModel, IlrDTExpressionManager em, JsonNode cDef, Locale locale) throws Exception {
        String title = text(cDef, "title");
        String statement = text(cDef, "statement");
        String type = textOr("range:number", cDef, "type");

        // 1) ExpressionDefinition (template da coluna)
        IlrDTExpressionDefinition exprDef =
                em.newExpressionDefinition(statement != null ? statement : "");

        // 2) PartitionDefinition via fábrica do modelo
        IlrDTPartitionDefinition def = dtModel.newPartitionDefinition(exprDef);

        // 3) Título/visibilidade
        if (!isBlank(title)) {
            IlrDTPropertyHelper.setDefinitionTitle(def, title);
        }

        // 4) Inserir na posição (no fim da lista)
        dtModel.addPartitionDefinition(dtModel.getPartitionDefinitionCount(), def);

        // === TODO: criar partições deste nível conforme "type"/"partitions" ===
        // JsonNode partitions = cDef.get("partitions");
        // JsonNode extraForRowIndex = cDef.get("extraForRowIndex");
        // JsonNode partitionsDefault = cDef.get("partitionsDefault");
        // Use o controller/model para criar itens "between/atLeast/lessThan/moreThan"
        // e encadear próxima coluna via árvore de partições.

        // Processa filhos (próxima coluna)
        JsonNode children = cDef.get("children");
        if (children != null && children.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = children.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode childDef = e.getValue();
                if (isBlank(text(childDef, "title"))) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) childDef).put("title", e.getKey());
                }
                addConditionRecursive(dtModel, em, childDef, locale);
            }
        }
    }

    /** Cria uma coluna de ação (sem preencher células ainda). */
    private static void addActionColumn(IlrDTModel dtModel, IlrDTExpressionManager em, JsonNode aDef, Locale locale) throws Exception {
        String title = text(aDef, "title");
        String statement = text(aDef, "statement"); // ex.: "set ... to <a number>"

        // 1) ExpressionDefinition da ação
        IlrDTExpressionDefinition actExprDef =
                em.newExpressionDefinition(statement != null ? statement : "");

        // 2) ActionDefinition via fábrica do modelo
        IlrDTActionDefinition actDef = dtModel.newActionDefinition(actExprDef);

        // 3) Título/visibilidade
        if (!isBlank(title)) {
            IlrDTPropertyHelper.setDefinitionTitle(actDef, title);
        }

        // 4) Inserir no fim
        dtModel.addActionDefinition(dtModel.getActionDefinitionCount(), actDef);
    }

    /** Preenche valores nas folhas via paths "[i,j,k]" se disponibilizados. */
    private static void applyActionValuesByPath(IlrDTController ctrl, IlrDTModel dtModel, JsonNode aDef) throws Exception {
        JsonNode values = aDef.get("valuesByPath");
        if (values == null || !values.isObject()) return;

        Iterator<Map.Entry<String, JsonNode>> it = values.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String pathStr = e.getKey();
            JsonNode vNode = e.getValue();

            int[] path = parsePath(pathStr);
            if (path == null) continue;

            // TODO: navegar pela árvore de partições até a folha indicada por "path"
            // e setar o valor no action cell correspondente.
            // double v = vNode.asDouble();
            // setActionCellValueAtPath(dtModel, actionIndex /*0 se única ação*/, path, v);
        }
    }

    // ===================== Helpers JSON/string =====================

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String text(JsonNode node, String field) {
        return (node != null && node.has(field) && !node.get(field).isNull()) ? node.get(field).asText() : null;
    }

    private static String textOr(String def, JsonNode node, String field) {
        String v = text(node, field);
        return isBlank(v) ? def : v;
    }

    private static List<JsonNode> arrayOrEmpty(JsonNode node, String field) {
        JsonNode arr = node == null ? null : node.get(field);
        if (arr != null && arr.isArray()) {
            List<JsonNode> list = new ArrayList<>();
            arr.forEach(list::add);
            return list;
        }
        return Collections.emptyList();
    }

    /** Converte "[0,1,0]" em {0,1,0}. */
    private static int[] parsePath(String s) {
        if (isBlank(s)) return null;
        String t = s.trim();
        if (t.startsWith("[")) t = t.substring(1);
        if (t.endsWith("]")) t = t.substring(0, t.length() - 1);
        if (t.isBlank()) return new int[0];
        String[] parts = t.split("\\s*,\\s*");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignore) { return null; }
        }
        return out;
    }
}
