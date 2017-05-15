package org.camunda.bpm.engine.test.api.runtime;

import static org.camunda.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.camunda.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;

import java.util.Collection;

import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.authorization.Permissions;
import org.camunda.bpm.engine.authorization.Resources;
import org.camunda.bpm.engine.batch.Batch;
import org.camunda.bpm.engine.batch.history.HistoricBatch;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.test.RequiredHistoryLevel;
import org.camunda.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.camunda.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.camunda.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.camunda.bpm.engine.test.util.ProcessEngineTestRule;
import org.camunda.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * 
 * @author Anna Pazola
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
@RunWith(Parameterized.class)
public class BatchRestartAuthorizationTest {

  protected static final String TEST_REASON = "test reason";
  protected static final String JOB_EXCEPTION_DEFINITION_XML = "org/camunda/bpm/engine/test/api/mgmt/ManagementServiceTest.testGetJobExceptionStacktrace.bpmn20.xml";

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);
  protected BatchModificationHelper helper = new BatchModificationHelper(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(authRule).around(testRule);

  @Parameterized.Parameter
  public AuthorizationScenario scenario;

  @Parameterized.Parameters(name = "Scenario {index}")
  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
        scenario()
            .withAuthorizations(
                grant(Resources.BATCH, "batchId", "userId", Permissions.CREATE),
                grant(Resources.PROCESS_DEFINITION, "processDefinition", "userId", Permissions.READ_HISTORY, Permissions.CREATE_INSTANCE),
                grant(Resources.PROCESS_INSTANCE, "*", "userId", Permissions.CREATE)
            ).succeeds(),
        scenario()
            .withAuthorizations(
                grant(Resources.PROCESS_INSTANCE, "*", "userId", Permissions.CREATE),
                grant(Resources.PROCESS_DEFINITION, "processDefinition", "userId", Permissions.READ_HISTORY, Permissions.CREATE_INSTANCE)
            ).failsDueToRequired(
                grant(Resources.BATCH, "batchId", "userId", Permissions.CREATE))
            .succeeds()
    );
  }

  @After
  public void tearDown() {
    authRule.deleteUsersAndGroups();
  }

  @After
  public void cleanBatch() {
    Batch batch = engineRule.getManagementService().createBatchQuery().singleResult();
    if (batch != null) {
      engineRule.getManagementService().deleteBatch(
          batch.getId(), true);
    }

    HistoricBatch historicBatch = engineRule.getHistoryService().createHistoricBatchQuery().singleResult();
    if (historicBatch != null) {
      engineRule.getHistoryService().deleteHistoricBatch(
          historicBatch.getId());
    }
  }

  @After
  public void removeBatches() {
    helper.removeAllRunningAndHistoricBatches();
  }

  @Test
  public void executeBatch() {
    //given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.TWO_TASKS_PROCESS);

    ProcessInstance processInstance1 = engineRule.getRuntimeService().startProcessInstanceByKey("Process");
    ProcessInstance processInstance2 = engineRule.getRuntimeService().startProcessInstanceByKey("Process");
    engineRule.getRuntimeService().deleteProcessInstance(processInstance1.getId(), TEST_REASON);
    engineRule.getRuntimeService().deleteProcessInstance(processInstance2.getId(), TEST_REASON);

    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("processInstance1", processInstance1.getId())
        .bindResource("restartedProcessInstance", "*")
        .bindResource("processInstance2", processInstance2.getId())
        .bindResource("processDefinition", "Process")
        .bindResource("batchId", "*")
        .start();

    Batch batch = engineRule.getRuntimeService()
        .restartProcessInstances(processDefinition.getId())
        .processInstanceIds(processInstance1.getId(), processInstance2.getId())
        .startAfterActivity("userTask1")
        .executeAsync();

    if (batch != null) {
      Job job = engineRule.getManagementService().createJobQuery().jobDefinitionId(batch.getSeedJobDefinitionId()).singleResult();

      // seed job
      engineRule.getManagementService().executeJob(job.getId());

      for (Job pending : engineRule.getManagementService().createJobQuery().jobDefinitionId(batch.getBatchJobDefinitionId()).list()) {
        engineRule.getManagementService().executeJob(pending.getId());
      }
    }
    // then
    authRule.assertScenario(scenario);
  }
}