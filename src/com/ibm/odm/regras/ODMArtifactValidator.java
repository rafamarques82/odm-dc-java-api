package com.ibm.odm.regras;

import ilog.rules.teamserver.brm.IlrActionRule;
import ilog.rules.teamserver.brm.IlrRuleflow;
import ilog.rules.teamserver.model.IlrObjectNotFoundException;
import ilog.rules.dt.model.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validador de artefatos do IBM ODM (Action Rule, Decision Table e Ruleflow).
 * Checagens focadas em "escrita correta":
 *  - Regra: corpo não vazio, placeholders <...>, TODO/FIXME, balanceamento de (), {}, [], aspas.
 *  - Decision Table: colunas mínimas, títulos vazios/duplicados, root/expressões iniciais.
 *  - Ruleflow: XML bem‑formado + indícios de tarefas/flows.
 *
 * Observações:
 *  - Não executa regras nem valida semântica do ILR AL (apenas heurísticas textuais/estruturais).
 *  - Pode ser usado após criar/editar artefatos para bloquear a conclusão quando inválidos.
 */
public class ODMArtifactValidator {

    // ======================
    // DTOs de resultado
    // ======================
    public enum Severity { INFO, WARN, ERROR }

    public static class ValidationIssue {
        private final Severity severity;
        private final String message;
        private final String location;
        public ValidationIssue(Severity severity, String message, String location) {
            this.severity = severity; this.message = message; this.location = location;
        }
        public Severity getSeverity() { return severity; }
        public String getMessage()  { return message; }
        public String getLocation() { return location; }
        @Override public String toString() {
            return severity + ": " + message + (location == null || location.isBlank() ? "" : " (" + location + ")");
        }
    }

    public static class ValidationReport {
        private final List<ValidationIssue> issues = new ArrayList<>();
        public void add(ValidationIssue issue) { if (issue != null) issues.add(issue); }
        public void addAll(Collection<ValidationIssue> list) { if (list != null) issues.addAll(list); }
        public List<ValidationIssue> getIssues() { return Collections.unmodifiableList(issues); }
        public boolean isValid() {
            for (ValidationIssue i : issues) if (i.getSeverity() == Severity.ERROR) return false;
            return true;
        }
        public Map<String, Long> summaryBySeverity() {
            Map<String, Long> m = new LinkedHashMap<>();
            long e = issues.stream().filter(i -> i.getSeverity() == Severity.ERROR).count();
            long w = issues.stream().filter(i -> i.getSeverity() == Severity.WARN).count();
            long f = issues.stream().filter(i -> i.getSeverity() == Severity.INFO).count();
            m.put("ERROR", e); m.put("WARN", w); m.put("INFO", f);
            return m;
        }
    }

    // ======================
    // API pública
    // ======================

    /** Valida uma Action Rule (corpo, placeholders, balanceamento básico). 
     * @throws IlrObjectNotFoundException */
    public ValidationReport validateActionRule(IlrActionRule rule) throws IlrObjectNotFoundException {
        ValidationReport report = new ValidationReport();
        if (rule == null) {
            report.add(new ValidationIssue(Severity.ERROR, "Regra nula", "ActionRule"));
            return report;
        }
        String name = safe(rule.getName());
        String body = rule.getDefinition() != null ? safe(rule.getDefinition().getBody()) : "";
       

int priority = 0;
String priorityStr = String.valueOf(rule.getPriority());
try {
    priority = Integer.parseInt(priorityStr.trim());
} catch (Exception e) {
    // Em algumas bases, a prioridade pode estar vazia ou não numérica.
    report.add(new ValidationIssue(Severity.WARN,
            "Prioridade não numérica: " + priorityStr,
            "ActionRule.priority"));
}

        if (name.isBlank()) report.add(new ValidationIssue(Severity.ERROR, "Nome da regra vazio", "ActionRule.name"));
        if (priority < 0) report.add(new ValidationIssue(Severity.WARN, "Prioridade negativa", "ActionRule.priority"));
        if (body.isBlank()) {
            report.add(new ValidationIssue(Severity.ERROR, "Corpo da regra vazio", "ActionRule.body"));
            return report;
        }
        // Placeholders <...>
        if (PLACEHOLDER_PATTERN.matcher(body).find()) {
            report.add(new ValidationIssue(Severity.ERROR, "Corpo contém placeholders '<...>'", "ActionRule.body"));
        }
        // Marcadores de dívida técnica
        if (containsAnyIgnoreCase(body, Arrays.asList("TODO", "FIXME", "PENDING"))) {
            report.add(new ValidationIssue(Severity.WARN, "Corpo contém marcadores de tarefa (TODO/FIXME)", "ActionRule.body"));
        }
        // Balanceamento (), {}, []
        if (!balancedDelimiters(body)) {
            report.add(new ValidationIssue(Severity.ERROR, "Delimitadores não balanceados ((), {}, [])", "ActionRule.body"));
        }
        // Aspas duplas em quantidade ímpar (string possivelmente não fechada)
        long quotes = body.chars().filter(c -> c == '"').count();
        if (quotes % 2 != 0) {
            report.add(new ValidationIssue(Severity.WARN, "Quantidade ímpar de aspas duplas", "ActionRule.body"));
        }
        return report;
    }

    /** Valida uma Decision Table (títulos, colunas, expressões mínimas, ações). */
    public ValidationReport validateDecisionTable(IlrDTModel model) {
        ValidationReport report = new ValidationReport();
        if (model == null) {
            report.add(new ValidationIssue(Severity.ERROR, "Modelo da Decision Table nulo", "DT.model"));
            return report;
        }
        int condCount = model.getPartitionDefinitionCount();
        int actCount  = model.getActionDefinitionCount();
        if (condCount == 0) report.add(new ValidationIssue(Severity.ERROR, "Nenhuma coluna de condição definida", "DT.conditions"));
        if (actCount  == 0) report.add(new ValidationIssue(Severity.WARN,  "Nenhuma coluna de ação definida",      "DT.actions"));

        // Títulos de condição: não vazios e sem duplicidade (case-insensitive)
        Set<String> titles = new HashSet<>();
        for (int i = 0; i < condCount; i++) {
            String t = safe(ilog.rules.dt.model.helper.IlrDTPropertyHelper.getDefinitionTitle(model.getPartitionDefinition(i)));
            if (t.isBlank()) report.add(new ValidationIssue(Severity.ERROR, "Título de coluna de condição vazio", "DT.condition[" + i + "]"));
            String tl = t.toLowerCase(Locale.ROOT);
            if (!tl.isBlank() && !titles.add(tl)) {
                report.add(new ValidationIssue(Severity.WARN, "Título de condição duplicado: " + t, "DT.conditions"));
            }
        }
        // Títulos de ação: não vazios
        for (int i = 0; i < actCount; i++) {
            String t = safe(ilog.rules.dt.model.helper.IlrDTPropertyHelper.getDefinitionTitle(model.getActionDefinition(i)));
            if (t.isBlank()) report.add(new ValidationIssue(Severity.ERROR, "Título de coluna de ação vazio", "DT.action[" + i + "]"));
        }
        // Root e ao menos uma expressão nas primeiras linhas
        IlrDTPartition root = model.getRoot();
        if (root == null) {
            report.add(new ValidationIssue(Severity.ERROR, "Árvore de partições (root) não inicializada", "DT.root"));
        } else {
            int items = root.getPartitionItemCount();
            if (items == 0) {
                report.add(new ValidationIssue(Severity.WARN, "Decision Table vazia (0 linhas)", "DT.root"));
            } else {
                int withExpr = 0;
                for (int r = 0; r < items; r++) {
                    IlrDTPartitionItem it = root.getPartitionItem(r);
                    if (it.getExpression() != null) withExpr++;
                }
                if (withExpr == 0) report.add(new ValidationIssue(Severity.ERROR, "Nenhuma expressão definida nas linhas iniciais", "DT.root"));
            }
        }
        return report;
    }

    /** Valida um Ruleflow (DRF/XML bem‑formado + presença de elementos básicos). */
    public ValidationReport validateRuleflow(IlrRuleflow rf) {
        ValidationReport report = new ValidationReport();
        if (rf == null) {
            report.add(new ValidationIssue(Severity.ERROR, "Ruleflow nulo", "Ruleflow"));
            return report;
        }
        String locale = safe(rf.getLocale());
        String body   = safe(rf.getBody());
        if (locale.isBlank()) report.add(new ValidationIssue(Severity.WARN, "Locale do ruleflow vazio", "Ruleflow.locale"));
        if (body.isBlank()) {
            report.add(new ValidationIssue(Severity.ERROR, "Body do ruleflow vazio", "Ruleflow.body"));
            return report;
        }
        // XML bem‑formado
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setValidating(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            String rootName = (doc.getDocumentElement() != null) ? doc.getDocumentElement().getNodeName() : "";
            if (rootName.isBlank()) report.add(new ValidationIssue(Severity.ERROR, "XML sem elemento raiz", "Ruleflow.body"));
            // Heurística simples: indícios de tarefas/flow
            String xmlLower = body.toLowerCase(Locale.ROOT);
            if (!(xmlLower.contains("task") || xmlLower.contains("flow") || xmlLower.contains("ruleflow"))) {
                report.add(new ValidationIssue(Severity.WARN, "XML não aparenta conter tarefas/flows", "Ruleflow.body"));
            }
        } catch (Exception parseError) {
            report.add(new ValidationIssue(Severity.ERROR, "Body do ruleflow não é XML bem‑formado: " + parseError.getMessage(), "Ruleflow.body"));
        }
        return report;
    }

    // ======================
    // Utilitários públicos (úteis para pré‑validação)
    // ======================

    /** Pré‑valida apenas o texto de uma regra (sem precisar de IlrActionRule). */
    public static ValidationReport preValidateRuleBody(String body) {
        ValidationReport rep = new ValidationReport();
        String b = safe(body);
        if (b.isBlank()) {
            rep.add(new ValidationIssue(Severity.ERROR, "Corpo da regra vazio", "ActionRule.body"));
            return rep;
        }
        if (PLACEHOLDER_PATTERN.matcher(b).find()) rep.add(new ValidationIssue(Severity.ERROR, "Placeholders '<...>' detectados", "ActionRule.body"));
        if (containsAnyIgnoreCase(b, Arrays.asList("TODO", "FIXME", "PENDING"))) rep.add(new ValidationIssue(Severity.WARN, "Marcadores de tarefa (TODO/FIXME)", "ActionRule.body"));
        if (!balancedDelimiters(b)) rep.add(new ValidationIssue(Severity.ERROR, "Delimitadores não balanceados ((), {}, [])", "ActionRule.body"));
        long quotes = b.chars().filter(c -> c == '"').count();
        if (quotes % 2 != 0) rep.add(new ValidationIssue(Severity.WARN, "Quantidade ímpar de aspas duplas", "ActionRule.body"));
        return rep;
    }

    /** Pré‑valida apenas o XML do ruleflow. */
    public static ValidationReport preValidateRuleflowXml(String xml) {
        ValidationReport rep = new ValidationReport();
        String x = safe(xml);
        if (x.isBlank()) {
            rep.add(new ValidationIssue(Severity.ERROR, "Body do ruleflow vazio", "Ruleflow.body"));
            return rep;
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setValidating(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            db.parse(new ByteArrayInputStream(x.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            rep.add(new ValidationIssue(Severity.ERROR, "XML inválido: " + e.getMessage(), "Ruleflow.body"));
        }
        return rep;
    }

    // ======================
    // Utilitários internos
    // ======================

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("<[^>]+>");

    private static String safe(String s) { return s == null ? "" : s; }

    private static boolean containsAnyIgnoreCase(String text, List<String> tokens) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String t : tokens) if (lower.contains(t.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** Verifica balanceamento básico de (), {}, [] no texto. */
    private static boolean balancedDelimiters(String s) {
        Deque<Character> stack = new ArrayDeque<>();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '(': case '{': case '[': stack.push(c); break;
                case ')': if (stack.isEmpty() || stack.pop() != '(') return false; break;
                case '}': if (stack.isEmpty() || stack.pop() != '{') return false; break;
                case ']': if (stack.isEmpty() || stack.pop() != '[') return false; break;
                default: // ignore
            }
        }
        return stack.isEmpty();
    }
    
}
