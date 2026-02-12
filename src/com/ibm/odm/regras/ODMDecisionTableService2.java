
package com.ibm.odm.regras;

import java.util.*;
import org.eclipse.emf.ecore.EReference;

import ilog.rules.dt.IlrDTController;
import ilog.rules.dt.IlrDTExpressionManager;
import ilog.rules.dt.model.*;
import ilog.rules.dt.model.expression.*;
import ilog.rules.dt.model.helper.IlrDTHelper;
import ilog.rules.dt.model.helper.IlrDTPropertyHelper;

import ilog.rules.teamserver.brm.*;
import ilog.rules.teamserver.client.IlrRemoteSessionFactory;
import ilog.rules.teamserver.model.*;
import ilog.rules.teamserver.model.permissions.*;

import ilog.rules.teamserver.brm.*;
import ilog.rules.teamserver.model.*;
import ilog.rules.teamserver.model.permissions.*;


/**
 * ODMDecisionTableService — versão consolidada com:
 * - Criação automática de pacotes (tokenização por '.' e '/')
 * - Métodos para criar, editar e persistir Decision Tables
 * - addRowToNonRootColumn para colunas não-root
 */
public class ODMDecisionTableService2 {

    private final String dcUrl;
    private final String dcDatasource;
    private final String dcUser;
    private final String dcPassword;

    public ODMDecisionTableService2(String dcUrl, String dcDatasource, String dcUser, String dcPassword) {
        if (dcUrl == null) throw new IllegalArgumentException("dcUrl não pode ser nulo");
        if (dcDatasource == null) throw new IllegalArgumentException("dcDatasource não pode ser nulo");
        if (dcUser == null) throw new IllegalArgumentException("dcUser não pode ser nulo");
        if (dcPassword == null) throw new IllegalArgumentException("dcPassword não pode ser nulo");
        this.dcUrl = dcUrl;
        this.dcDatasource = dcDatasource;
        this.dcUser = dcUser;
        this.dcPassword = dcPassword;
    }

    public IlrSession openSession() throws IlrConnectException, IlrPermissionException, IlrObjectNotFoundException {
        IlrRemoteSessionFactory factory = new IlrRemoteSessionFactory();
        factory.connect(dcUser, dcPassword, dcUrl, dcDatasource);
        IlrSession session = factory.getSession();
        session.beginUsage();
        return session;
    }

    public void closeSession(IlrSession session) {
        if (session != null) {
            try { session.endUsage(); } catch (Exception ignore) {}
            try { session.close(); } catch (Exception ignore) {}
        }
    }

    public IlrRuleProject getProject(IlrSession session, String projectName) throws IlrObjectNotFoundException {
        IlrRuleProject project = IlrSessionHelper.getProjectNamed(session, projectName);
        if (project == null) throw new IllegalArgumentException("Projeto não encontrado: " + projectName);
        return project;
    }

    public void setWorkingBaseline(IlrSession session, IlrRuleProject project, String baselineName)
            throws IlrObjectNotFoundException, IlrPermissionException {
        IlrBaseline baseline = (baselineName == null || baselineName.trim().isEmpty())
                ? IlrSessionHelper.getCurrentBaseline(session, project)
                : IlrSessionHelper.getBaselineNamed(session, project, baselineName);
        if (baseline == null) {
            baseline = IlrSessionHelper.getBaselineNamed(session, project, "Main");
            if (baseline == null) baseline = IlrSessionHelper.getCurrentBaseline(session, project);
        }
        session.setWorkingBaseline(baseline);
    }

    public IlrDecisionTable createDecisionTable(IlrSession session,
                                                String projectName,
                                                String packagePath,
                                                String tableName,
                                                String baselineName,
                                                Locale locale) throws Exception {
        IlrRuleProject project = getProject(session, projectName);
        setWorkingBaseline(session, project, baselineName);

        IlrRulePackage pkg = getOrCreatePackagePath(session, packagePath);
        IlrDecisionTable dTable = (IlrDecisionTable) IlrSessionHelper.createRule(session,
                session.getBrmPackage().getDecisionTable(), pkg, tableName, null);

        IlrDTController dtController = IlrSessionHelper.getDTController(session, dTable, locale == null ? Locale.US : locale);
        IlrDTModel dtModel = dtController.getDTModel();

        stripDefaultStructure(dtModel);
        persistDT(session, dTable, dtController);
        return dTable;
    }

    public IlrDTController getDTController(IlrSession session, IlrDecisionTable dTable) throws IlrObjectNotFoundException {
        String locale = dTable.getLocale();
        Locale loc = (locale == null || locale.trim().isEmpty()) ? Locale.US : new Locale(locale);
        return IlrSessionHelper.getDTController(session, dTable, loc);
    }

    public void persistDT(IlrSession session, IlrDecisionTable dTable, IlrDTController dtController) throws IlrApplicationException {
        String body = IlrSessionHelper.dtControllerToStorableString(session, dtController);
        IlrSessionHelper.setDefinition(session, dTable, body);
    }

    public void setPreconditions(IlrDTModel dtModel, String preconditionsText) {
        if (preconditionsText != null && !preconditionsText.trim().isEmpty()) {
            IlrDTHelper.setPreconditionsText(dtModel, preconditionsText);
        }
    }


public IlrDTPartitionDefinition addConditionColumn(IlrDTModel dtModel, String columnTitle, String expressionDefinitionText) {
    IlrDTExpressionManager em = dtModel.getExpressionManager();
    IlrDTExpressionDefinition exprDef = em.newExpressionDefinition(expressionDefinitionText);
    IlrDTPartitionDefinition partDef;

    if (dtModel.getPartitionDefinitionCount() == 1 && isDefaultRoot(dtModel)) {
        // Reutiliza a coluna default como root
        partDef = dtModel.getPartitionDefinition(0);
        partDef.setExpression(exprDef);
    } else {
        // Cria nova coluna
        partDef = dtModel.newPartitionDefinition(exprDef);
        dtModel.addPartitionDefinition(dtModel.getPartitionDefinitionCount(), partDef);
    }

    if (columnTitle != null && !columnTitle.trim().isEmpty()) {
        IlrDTPropertyHelper.setDefinitionTitle(partDef, columnTitle);
    }
    return partDef;
}


private boolean isDefaultRoot(IlrDTModel model) {
    IlrDTPartitionDefinition rootDef = model.getPartitionDefinition(0);
    IlrDTExpressionDefinition exprDef = rootDef.getExpressionDefinition();
    // Se não há expressão definida, consideramos como default
    return exprDef == null;
}



    public IlrDTPartitionItem addRowToColumn(IlrDTModel dtModel,
                                             IlrDTPartitionDefinition columnDef,
                                             int position,
                                             String overriddenDefinitionOrNull,
                                             List<String> parameters) {
        if (!isRootColumn(dtModel, columnDef)) {
            throw new IllegalStateException("Para colunas > 0, use addPartitionUnderRow.");
        }
        IlrDTExpressionManager em = dtModel.getExpressionManager();
        IlrDTExpressionDefinition exprDef = columnDef.getExpressionDefinition();
        IlrDTExpressionInstance instance = (overriddenDefinitionOrNull == null)
                ? em.newExpressionInstance(exprDef, parameters)
                : em.newOverriddenExpressionInstance(overriddenDefinitionOrNull, parameters, exprDef);
        return dtModel.addPartitionItem(dtModel.getRoot(), position, instance);
    }

    public IlrDTPartition addPartitionUnderRow(IlrDTModel dtModel,
                                               IlrDTPartitionDefinition columnDef,
                                               IlrDTPartitionItem parentRow,
                                               List<String> parametersForFirstItem) {
        IlrDTExpressionManager em = dtModel.getExpressionManager();
        IlrDTExpressionDefinition exprDef = columnDef.getExpressionDefinition();
        IlrDTExpressionInstance inst = em.newExpressionInstance(exprDef, parametersForFirstItem);
        return dtModel.addPartition(parentRow, columnDef, inst);
    }

    public IlrDTPartitionItem addRowToNonRootColumn(IlrDTModel dtModel,
                                                    IlrDTPartitionDefinition nonRootColumnDef,
                                                    int parentRowIndex,
                                                    int position,
                                                    String overriddenDefinitionOrNull,
                                                    List<String> parameters) {
        IlrDTPartition root = dtModel.getRoot();
        IlrDTPartitionItem parentRow = root.getPartitionItem(parentRowIndex);
        IlrDTPartition targetPartition = (IlrDTPartition) parentRow.getNextStatement();

        IlrDTExpressionManager em = dtModel.getExpressionManager();
        IlrDTExpressionDefinition exprDef = nonRootColumnDef.getExpressionDefinition();
        IlrDTExpressionInstance instance = (overriddenDefinitionOrNull == null)
                ? em.newExpressionInstance(exprDef, parameters)
                : em.newOverriddenExpressionInstance(overriddenDefinitionOrNull, parameters, exprDef);

        return dtModel.addPartitionItem(targetPartition, position, instance);
    }

    public IlrDTActionDefinition addActionColumn(IlrDTModel dtModel, String title, String actionExpressionDefinitionText) {
        IlrDTExpressionManager em = dtModel.getExpressionManager();
        IlrDTExpressionDefinition exprDef = em.newExpressionDefinition(actionExpressionDefinitionText, null);
        IlrDTActionDefinition actionDef = dtModel.newActionDefinition(exprDef);
        if (title != null && !title.trim().isEmpty()) {
            IlrDTPropertyHelper.setDefinitionTitle(actionDef, title);
        }
        dtModel.addActionDefinition(dtModel.getActionDefinitionCount(), actionDef);
        return actionDef;
    }

    public void setActionAt(IlrDTModel dtModel,
                            int firstColumnRowIndex,
                            int secondColumnRowIndex,
                            IlrDTActionDefinition actionDef,
                            List<String> actionParameters) {
        IlrDTPartition durationPartition = dtModel.getRoot();
        IlrDTPartitionItem durationItem = durationPartition.getPartitionItem(firstColumnRowIndex);
        IlrDTPartition ltvPartition = (IlrDTPartition) durationItem.getNextStatement();
        IlrDTPartitionItem ltvItem = ltvPartition.getPartitionItem(secondColumnRowIndex);
        IlrDTActionSet actionSet = (IlrDTActionSet) ltvItem.getStatement();

        IlrDTExpressionManager em = dtModel.getExpressionManager();
        IlrDTExpressionInstance actionExpr = em.newExpressionInstance(actionDef.getExpressionDefinition(), actionParameters);
        dtModel.addAction(actionSet, 0, actionDef, actionExpr);
    }

    public void stripDefaultStructure(IlrDTModel dtModel) {
        int partitions = dtModel.getPartitionDefinitionCount();
        for (int i = 1; i < partitions; i++) {
            IlrDTPartitionDefinition def = dtModel.getPartitionDefinition(1);
            dtModel.removePartitionDefinition(def);
        }
        if (dtModel.getActionDefinitionCount() > 0) {
            IlrDTActionDefinition ad = dtModel.getActionDefinition(0);
            dtModel.removeActionDefinition(ad);
        }
        IlrDTPartition root = dtModel.getRoot();
        while (root.getPartitionItemCount() > 1) {
            IlrDTPartitionItem pi = root.getPartitionItem(1);
            dtModel.removePartitionItem(pi);
        }
    }

    private boolean isRootColumn(IlrDTModel model, IlrDTPartitionDefinition def) {
        return model.getPartitionDefinitionCount() > 0 && model.getPartitionDefinition(0).equals(def);
    }

    // Criação automática de pacotes
    public IlrRulePackage getOrCreatePackagePath(IlrSession session, String packagePath) throws Exception {
        String[] tokens = tokenizePackagePath(packagePath);
        IlrRulePackage current = null;
        for (String token : tokens) {
            IlrRulePackage found = findSubPackage(session, current, token);
            if (found == null) {
                found = IlrSessionHelper.createRulePackage(session, current, token);
                session.commit(found);
            }
            current = found;
        }
        return current;
    }

    private IlrRulePackage findSubPackage(IlrSession session, IlrRulePackage parent, String name)
            throws IlrRoleRestrictedPermissionException, IlrObjectNotFoundException {
        List<IlrRulePackage> folders = getFoldersInFolder(session, parent);
        IlrBrmPackage brm = session.getBrmPackage();
        for (IlrRulePackage p : folders) {
            String packname = (String) p.getRawValue(brm.getModelElement_Name());
            if (packname != null && packname.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    public List<IlrRulePackage> getFoldersInFolder(IlrSession session, IlrRulePackage folder)
            throws IlrRoleRestrictedPermissionException, IlrObjectNotFoundException {
        IlrBrmPackage brm = session.getBrmPackage();
        List features = new ArrayList();
        EReference parentRef = brm.getRulePackage_Parent();
        features.add(parentRef);
        List values = new ArrayList();
        values.add(folder);
        IlrDefaultSearchCriteria sc = new IlrDefaultSearchCriteria(brm.getRulePackage(), features, values,
                null, IlrModelConstants.SCOPE_PROJECT, null, true);
        List list = session.findElements(sc);
        List<IlrRulePackage> ret = new ArrayList<IlrRulePackage>();
        for (Object o : list) {
            IlrRulePackage sub = (IlrRulePackage) o;
            if ((sub.getParent() == null && folder == null) ||
                (sub.getParent() != null && folder != null && sub.getParent().equals(folder, true))) {
                ret.add(sub);
            }
        }
        return ret;
    }

    public static String[] tokenizePackagePath(String packagePath) {
        if (packagePath == null) return new String[0];
        packagePath = packagePath.trim();
        if (packagePath.isEmpty()) return new String[0];
        String[] slashParts = packagePath.split("/");
        List<String> tokens = new ArrayList<String>();
        for (String part : slashParts) {
            if (part == null) continue;
            part = part.trim();
            if (part.isEmpty()) continue;
            String[] dotSegs = part.split("\\.");
            for (String seg : dotSegs) {
                if (!seg.isEmpty()) tokens.add(seg);
            }
        }
        return tokens.toArray(new String[0]);
    }
}
