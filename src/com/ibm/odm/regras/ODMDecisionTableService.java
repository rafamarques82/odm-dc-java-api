
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

public class ODMDecisionTableService {
  private final String dcUrl;
  private final String dcDatasource;
  private final String dcUser;
  private final String dcPassword;

  public ODMDecisionTableService(String dcUrl, String dcDatasource, String dcUser, String dcPassword) {
    if (dcUrl == null) throw new IllegalArgumentException("dcUrl não pode ser nulo");
    if (dcDatasource == null) throw new IllegalArgumentException("dcDatasource não pode ser nulo");
    if (dcUser == null) throw new IllegalArgumentException("dcUser não pode ser nulo");
    if (dcPassword == null) throw new IllegalArgumentException("dcPassword não pode ser nulo");
    this.dcUrl = dcUrl;
    this.dcDatasource = dcDatasource;
    this.dcUser = dcUser;
    this.dcPassword = dcPassword;
  }

  // ===== Sessão / Baseline ==================================================
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
      try { session.close(); }     catch (Exception ignore) {}
    }
  }

  public IlrRuleProject getProject(IlrSession session, String projectName) throws IlrObjectNotFoundException {
    IlrRuleProject project = IlrSessionHelper.getProjectNamed(session, projectName);
    if (project == null) throw new IllegalArgumentException("Projeto não encontrado: " + projectName);
    return project;
  }

  public void setWorkingBaseline(IlrSession session, IlrRuleProject project, String baselineName)
      throws IlrObjectNotFoundException, IlrPermissionException {
    IlrBaseline baseline;
    if (baselineName == null || baselineName.trim().isEmpty()) {
      baseline = IlrSessionHelper.getCurrentBaseline(session, project);
    } else {
      baseline = IlrSessionHelper.getBaselineNamed(session, project, baselineName);
      if (baseline == null) {
        baseline = IlrSessionHelper.getBaselineNamed(session, project, "Main");
        if (baseline == null) baseline = IlrSessionHelper.getCurrentBaseline(session, project);
      }
    }
    session.setWorkingBaseline(baseline);
  }

  // ===== Busca / Criação ====================================================
  public IlrDecisionTable findDecisionTable(IlrSession session, String projectName, String packagePath, String tableName)
      throws Exception {
    IlrRuleProject project = getProject(session, projectName);
    IlrRulePackage pkg = getOrCreatePackagePath(session, packagePath);

    IlrBrmPackage brm = session.getBrmPackage();
    List<org.eclipse.emf.ecore.EStructuralFeature> features = new ArrayList<>();
    features.add(brm.getPackageElement_RulePackage());

    List<Object> values = new ArrayList<>();
    values.add(pkg);

    IlrDefaultSearchCriteria sc = new IlrDefaultSearchCriteria(
        brm.getPackageElement(), features, values, null,
        IlrModelConstants.SCOPE_PROJECT, null, true);

    @SuppressWarnings("unchecked")
    List<Object> found = session.findElements(sc);

    for (Object o : found) {
      if (o instanceof IlrPackageElement) {
        IlrPackageElement pe = (IlrPackageElement) o;
        String name = (String) pe.getRawValue(brm.getModelElement_Name());
        if (tableName.equals(name) && pe instanceof IlrDecisionTable) {
          return (IlrDecisionTable) pe;
        }
      }
    }
    return null;
  }

  public IlrDecisionTable openOrCreateDecisionTable(
      IlrSession session,
      String projectName,
      String packagePath,
      String tableName,
      String baselineName,
      Locale locale,
      boolean resetIfExists) throws Exception {

    IlrRuleProject project = getProject(session, projectName);
    setWorkingBaseline(session, project, baselineName);

    IlrDecisionTable existing = findDecisionTable(session, projectName, packagePath, tableName);
    if (existing != null) {
      if (resetIfExists) {
        // Apaga e recria para garantir artefato limpo
        session.deleteElement(existing);
        session.commit(project);
        return createDecisionTable(session, projectName, packagePath, tableName, baselineName, locale);
      }
      return existing;
    }
    return createDecisionTable(session, projectName, packagePath, tableName, baselineName, locale);
  }

  public IlrDecisionTable createDecisionTable(
      IlrSession session,
      String projectName,
      String packagePath,
      String tableName,
      String baselineName,
      Locale locale) throws Exception {

    IlrRuleProject project = getProject(session, projectName);
    setWorkingBaseline(session, project, baselineName);

    IlrRulePackage pkg = getOrCreatePackagePath(session, packagePath);

    IlrDecisionTable dTable = (IlrDecisionTable) IlrSessionHelper.createRule(
        session, session.getBrmPackage().getDecisionTable(), pkg, tableName, null);

    IlrDTController dtController =
        IlrSessionHelper.getDTController(session, dTable, (locale == null ? Locale.US : locale));
    IlrDTModel dtModel = dtController.getDTModel();

    // Reset da estrutura e persistência inicial
    hardResetDecisionTable(dtModel);
    persistDT(session, dTable, dtController);

    return dTable;
  }

  /** Usa o locale do chamador. */
  public IlrDTController getDTController(IlrSession session, IlrDecisionTable dTable, Locale locale)
      throws IlrObjectNotFoundException {
    Locale loc = (locale == null ? Locale.US : locale);
    return IlrSessionHelper.getDTController(session, dTable, loc);
  }

  /** Compatível com artefatos que já tenham locale setado. */
  public IlrDTController getDTController(IlrSession session, IlrDecisionTable dTable)
      throws IlrObjectNotFoundException {
    String locale = dTable.getLocale();
    Locale loc = (locale == null || locale.trim().isEmpty()) ? Locale.US : new Locale(locale);
    return IlrSessionHelper.getDTController(session, dTable, loc);
  }

  public void persistDT(IlrSession session, IlrDecisionTable dTable, IlrDTController dtController)
      throws IlrApplicationException {
    String body = IlrSessionHelper.dtControllerToStorableString(session, dtController);
    IlrSessionHelper.setDefinition(session, dTable, body);
  }

  // ===== Reset Hard =========================================================
  /**
   * Remove TODAS as definições (partições e ações) e itens remanescentes.
   * Garante count==0 para PartitionDefinition e ActionDefinition.
   */
  public void hardResetDecisionTable(IlrDTModel dtModel) {
    // Remove ações
    while (dtModel.getActionDefinitionCount() > 0) {
      IlrDTActionDefinition ad = dtModel.getActionDefinition(0);
      dtModel.removeActionDefinition(ad);
    }
    // Remove partições (colunas de condição), incluindo raiz
    int safety = 0;
    while (dtModel.getPartitionDefinitionCount() > 0 && safety++ < 100) {
      IlrDTPartitionDefinition def = dtModel.getPartitionDefinition(0);
      dtModel.removePartitionDefinition(def);
    }
    // Remove itens da raiz se sobrar
    IlrDTPartition root = dtModel.getRoot();
    safety = 0;
    while (root != null && root.getPartitionItemCount() > 0 && safety++ < 100) {
      IlrDTPartitionItem pi = root.getPartitionItem(0);
      dtModel.removePartitionItem(pi);
      root = dtModel.getRoot();
    }
  }

  // ===== Colunas / Linhas ===================================================
  public void setPreconditions(IlrDTModel dtModel, String preconditionsText) {
    if (preconditionsText != null && !preconditionsText.trim().isEmpty()) {
      IlrDTHelper.setPreconditionsText(dtModel, preconditionsText);
    }
  }

  public IlrDTPartitionDefinition addConditionColumn(IlrDTModel dtModel, String columnTitle, String expressionDefinitionText) {
    // Reutiliza por título (se já existir)
    for (int i = 0; i < dtModel.getPartitionDefinitionCount(); i++) {
      IlrDTPartitionDefinition existing = dtModel.getPartitionDefinition(i);
      String existingTitle = IlrDTPropertyHelper.getDefinitionTitle(existing);
      if (existingTitle != null && existingTitle.equalsIgnoreCase(columnTitle)) {
        return existing;
      }
    }

    IlrDTExpressionManager em = dtModel.getExpressionManager();
    IlrDTExpressionDefinition exprDef = em.newExpressionDefinition(expressionDefinitionText);
    IlrDTPartitionDefinition partDef = dtModel.newPartitionDefinition(exprDef);

    dtModel.addPartitionDefinition(dtModel.getPartitionDefinitionCount(), partDef);
    if (columnTitle != null && !columnTitle.trim().isEmpty()) {
      IlrDTPropertyHelper.setDefinitionTitle(partDef, columnTitle);
    }
    return partDef;
  }

  public IlrDTPartitionItem addRowToColumn(
      IlrDTModel dtModel,
      IlrDTPartitionDefinition columnDef,
      int position,
      String overriddenDefinitionOrNull,
      List<String> parameters) {

    IlrDTExpressionManager em = dtModel.getExpressionManager();
    IlrDTExpressionDefinition exprDef = columnDef.getExpressionDefinition();

    IlrDTExpressionInstance instance = (overriddenDefinitionOrNull == null)
        ? em.newExpressionInstance(exprDef, parameters)
        : em.newOverriddenExpressionInstance(overriddenDefinitionOrNull, parameters, exprDef);

    return dtModel.addPartitionItem(dtModel.getRoot(), position, instance);
  }

  public IlrDTPartitionItem addRowToNonRootColumn(
      IlrDTModel dtModel,
      IlrDTPartitionDefinition nonRootColumnDef,
      IlrDTPartitionItem parentRow,
      int position,
      String overriddenDefinitionOrNull,
      List<String> parameters) {

    IlrDTExpressionManager em = dtModel.getExpressionManager();
    IlrDTExpressionDefinition exprDef = nonRootColumnDef.getExpressionDefinition();

    Object next = parentRow.getNextStatement();
    IlrDTPartition targetPartition;

    if (next instanceof IlrDTPartition) {
      targetPartition = (IlrDTPartition) next;
    } else {
      IlrDTExpressionInstance first = (overriddenDefinitionOrNull == null)
          ? em.newExpressionInstance(exprDef, parameters)
          : em.newOverriddenExpressionInstance(overriddenDefinitionOrNull, parameters, exprDef);

      targetPartition = dtModel.addPartition(parentRow, nonRootColumnDef, first);
      if (position == 0) {
        return targetPartition.getPartitionItem(0);
      }
    }

    IlrDTExpressionInstance instance = (overriddenDefinitionOrNull == null)
        ? em.newExpressionInstance(exprDef, parameters)
        : em.newOverriddenExpressionInstance(overriddenDefinitionOrNull, parameters, exprDef);

    return dtModel.addPartitionItem(targetPartition, position, instance);
  }

  // ===== Ações ==============================================================

  public IlrDTActionDefinition addActionColumn(IlrDTModel dtModel, String title, String actionExpressionDefinitionText) {
    // 🔎 Reutiliza por título (evita duplicar coluna de ação)
    for (int i = 0; i < dtModel.getActionDefinitionCount(); i++) {
      IlrDTActionDefinition existing = dtModel.getActionDefinition(i);
      String existingTitle = IlrDTPropertyHelper.getDefinitionTitle(existing);
      if (existingTitle != null && existingTitle.equalsIgnoreCase(title)) {
        return existing;
      }
    }

    IlrDTExpressionManager em = dtModel.getExpressionManager();
    IlrDTExpressionDefinition exprDef = em.newExpressionDefinition(actionExpressionDefinitionText, null);

    IlrDTActionDefinition actionDef = dtModel.newActionDefinition(exprDef);
    if (title != null && !title.trim().isEmpty()) {
      IlrDTPropertyHelper.setDefinitionTitle(actionDef, title);
    }
    dtModel.addActionDefinition(dtModel.getActionDefinitionCount(), actionDef);
    return actionDef;
  }

  public void setActionAtPath(
      IlrDTModel dtModel,
      int[] path,
      IlrDTActionDefinition actionDef,
      List<String> actionParameters) {

    IlrDTPartitionItem leaf = getLeafItem(dtModel, path);
    Object next = leaf.getNextStatement();

    if (!(next instanceof IlrDTActionSet)) {
      throw new IllegalStateException(
          "ActionSet ausente no item folha. Garanta que a coluna de ação foi adicionada e que a DT foi persistida antes de setar ações.");
    }

    IlrDTActionSet actionSet = (IlrDTActionSet) next;
    IlrDTExpressionManager em = dtModel.getExpressionManager();
    IlrDTExpressionInstance actionExpr =
        em.newExpressionInstance(actionDef.getExpressionDefinition(), actionParameters);

    dtModel.addAction(actionSet, 0, actionDef, actionExpr);
  }

  public IlrDTPartitionItem getLeafItem(IlrDTModel model, int[] path) {
    if (path == null || path.length == 0) throw new IllegalArgumentException("path vazio");

    IlrDTPartition part = model.getRoot();
    IlrDTPartitionItem item = part.getPartitionItem(path[0]);

    for (int i = 1; i < path.length; i++) {
      Object next = item.getNextStatement();
      if (!(next instanceof IlrDTPartition)) {
        break;
      }
      part = (IlrDTPartition) next;
      item = part.getPartitionItem(path[i]);
    }
    return item;
  }

  // ===== Pacotes ============================================================

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

  @SuppressWarnings({ "unchecked", "rawtypes" })
  public List<IlrRulePackage> getFoldersInFolder(IlrSession session, IlrRulePackage folder)
      throws IlrRoleRestrictedPermissionException, IlrObjectNotFoundException {

    IlrBrmPackage brm = session.getBrmPackage();
    List features = new ArrayList();
    EReference parentRef = brm.getRulePackage_Parent();
    features.add(parentRef);

    List values = new ArrayList();
    values.add(folder);

    IlrDefaultSearchCriteria sc = new IlrDefaultSearchCriteria(
        brm.getRulePackage(), features, values, null,
        IlrModelConstants.SCOPE_PROJECT, null, true);

    List list = session.findElements(sc);
    List<IlrRulePackage> ret = new ArrayList<>();

    for (Object o : list) {
      IlrRulePackage sub = (IlrRulePackage) o;
      if ((sub.getParent() == null && folder == null)
          || (sub.getParent() != null && folder != null && sub.getParent().equals(folder, true))) {
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
    List<String> tokens = new ArrayList<>();
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
