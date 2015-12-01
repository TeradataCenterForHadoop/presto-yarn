/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.teradata.presto.yarn

import com.google.inject.Inject
import com.teradata.presto.yarn.fulfillment.ImmutableNationTable
import com.teradata.presto.yarn.slider.Slider
import com.teradata.presto.yarn.utils.NodeSshUtils
import com.teradata.tempto.BeforeTestWithContext
import com.teradata.tempto.ProductTest
import com.teradata.tempto.Requires
import com.teradata.tempto.assertions.QueryAssert
import com.teradata.tempto.hadoop.hdfs.HdfsClient
import com.teradata.tempto.query.QueryExecutor
import groovy.util.logging.Slf4j
import org.testng.annotations.Test

import javax.inject.Named

import static com.google.common.base.Preconditions.checkState
import static com.teradata.presto.yarn.PrestoCluster.WORKER_COMPONENT
import static com.teradata.presto.yarn.utils.TimeUtils.retryUntil
import static com.teradata.tempto.assertions.QueryAssert.Row.row
import static java.sql.JDBCType.BIGINT
import static java.util.concurrent.TimeUnit.MINUTES
import static org.assertj.core.api.Assertions.assertThat

@Slf4j
class PrestoClusterTest
        extends ProductTest
{
  private static final String APP_CONFIG_TEMPLATE = 'appConfig.json'
  private static final String APP_CONFIG_WITHOUT_CATALOG_TEMPLATE = 'appConfig-test-no-catalog.json'
  private static final String APP_CONFIG_TEST_TEMPLATE = 'appConfig-test.json'

  private static final String JVM_HEAPSIZE = "1024.0MB"
  private static final String JVM_ARGS = "-DHADOOP_USER_NAME=hdfs -Duser.timezone=UTC"
  
  private static final long TIMEOUT = MINUTES.toMillis(4)

  private static final long FLEX_RETRY_TIMEOUT = MINUTES.toMillis(10)
  
  @Inject
  private HdfsClient hdfsClient

  @Inject
  private Slider slider

  @Inject
  private NodeSshUtils nodeSshUtils

  @Inject
  @Named("cluster.master")
  private String master
  
  @Inject
  @Named("cluster.slaves")
  private List<String> workers

  @BeforeTestWithContext
  public void setUp()
  {
    def nodesCount = workers.size() + 1
    if(nodeSshUtils.getNodeIds().size() == nodesCount) {
      log.info("All nodemanagers are running..Skip restart")
    } else {
      restartNodeManagers()
      retryUntil({
        nodeSshUtils.getNodeIds().size() == nodesCount
      }, MINUTES.toMillis(2))
    }
  }

  def void restartNodeManagers ()
  {
    List<String> command = ['/etc/init.d/hadoop-yarn-nodemanager restart || true']
    nodeSshUtils.runOnNode(master, command)
    workers.each { String node ->
      nodeSshUtils.runOnNode(node, command)
    }
    command = ['/etc/init.d/hadoop-yarn-resourcemanager restart || true']
    nodeSshUtils.runOnNode(master, command)
  }

  @Test
  void 'single node presto app lifecycle'()
  {
    PrestoCluster prestoCluster = new PrestoCluster(slider, hdfsClient, 'resources-singlenode.json', APP_CONFIG_TEST_TEMPLATE)
    prestoCluster.withPrestoCluster {
      prestoCluster.assertThatPrestoIsUpAndRunning(0)

      assertThatAllProcessesAreRunning(prestoCluster)
      
      assertThatMemorySettingsAreCorrect(prestoCluster)

      assertThatJvmArgsAreCorrect(prestoCluster)

      assertThatKilledProcessesRespawn(prestoCluster)

      assertThatApplicationIsStoppable(prestoCluster)
    }
  }

  @Test
  void 'single node presto app missing catalog'()
  {
    PrestoCluster prestoCluster = new PrestoCluster(slider, hdfsClient, 'resources-singlenode.json', APP_CONFIG_WITHOUT_CATALOG_TEMPLATE)
    prestoCluster.withPrestoCluster {
      prestoCluster.assertThatPrestoIsUpAndRunning(0)

      assertThatAllProcessesAreRunning(prestoCluster)

      assertThatApplicationIsStoppable(prestoCluster)
    }
  }

  @Test
  void 'single node presto app - adding plugin'()
  {
    PrestoCluster prestoCluster = new PrestoCluster(slider, hdfsClient, 'resources-singlenode.json', APP_CONFIG_TEST_TEMPLATE)
    prestoCluster.withPrestoCluster {
      prestoCluster.assertThatPrestoIsUpAndRunning(0)

      assertThatAllProcessesAreRunning(prestoCluster)

      def queryExecutor = prestoCluster.queryExecutor
      waitForNodesToBeActive(queryExecutor, prestoCluster)
      QueryAssert.assertThat(queryExecutor.executeQuery("SELECT classify(features(1, 2), model) FROM " +
              "(SELECT learn_classifier(labels, features) AS model FROM " +
              "(VALUES ('cat', features(1, 2))) t(labels, features)) t2")
      )
              .containsExactly(row('cat'))
    }
  }

  @Test
  void 'limit single node failures'()
  {
    PrestoCluster prestoCluster = new PrestoCluster(slider, hdfsClient, 'resources-singlenode-label.json', APP_CONFIG_TEMPLATE)
    prestoCluster.withPrestoCluster {
      prestoCluster.assertThatPrestoIsUpAndRunning(0)

      String coordinatorHost = prestoCluster.coordinatorHost

      5.times {
        assertThat(nodeSshUtils.countOfPrestoProcesses(coordinatorHost)).isEqualTo(1)
        nodeSshUtils.killPrestoProcesses(coordinatorHost)
        assertThat(nodeSshUtils.countOfPrestoProcesses(coordinatorHost)).isZero()

        retryUntil({
          nodeSshUtils.countOfPrestoProcesses(coordinatorHost) == 1
        }, TIMEOUT)

        assertThat(prestoCluster.status().isPresent()).isTrue()
      }

      // presto cluster should fail after 5 failures in a row
      nodeSshUtils.killPrestoProcesses(coordinatorHost)
      retryUntil({
        !prestoCluster.status().isPresent()
      }, TIMEOUT)
    }
  }

  @Test
  void 'multi node with placement lifecycle'()
  {
    PrestoCluster prestoCluster = new PrestoCluster(slider, hdfsClient, 'resources-multinode.json', APP_CONFIG_TEMPLATE)
    prestoCluster.withPrestoCluster {
      prestoCluster.assertThatPrestoIsUpAndRunning(workersCount())

      assertThatAllProcessesAreRunning(prestoCluster)

      assertThatKilledProcessesRespawn(prestoCluster)

      // check placement policy
      assertThat(prestoCluster.coordinatorHost).contains(master)
      assertThat(prestoCluster.workerHosts, is(workers))

      assertThatApplicationIsStoppable(prestoCluster)
    }
  }

  private int workersCount() {
    return workers.size()
  }

  @Test
  @Requires(ImmutableNationTable.class)
  void 'multi node with placement - checking connectors'()
  {
    PrestoCluster prestoCluster = new PrestoCluster(slider, hdfsClient, 'resources-multinode.json', APP_CONFIG_TEMPLATE)
    prestoCluster.withPrestoCluster {
      prestoCluster.assertThatPrestoIsUpAndRunning(workersCount())

      def queryExecutor = prestoCluster.queryExecutor
      waitForPrestoConnectors(queryExecutor, ['hive', 'tpch'])
      waitForNodesToBeActive(queryExecutor, prestoCluster)
      assertThatCountFromNationWorks(queryExecutor, 'tpch.tiny.nation')
      assertThatCountFromNationWorks(queryExecutor, 'hive.default.nation')
    }
  }

  def waitForNodesToBeActive(QueryExecutor queryExecutor, PrestoCluster prestoCluster) {
    retryUntil({
      def result = queryExecutor.executeQuery('select count(*) from system.runtime.nodes where active = true')
      log.debug("Number of active nodes: ${result.rows()}")
      return result.rows() == [[prestoCluster.allNodes.size()]]
    }, TIMEOUT)

  }

  def waitForPrestoConnectors(QueryExecutor queryExecutor, List<String> connectors)
  {
    def connectorRows = connectors.collect({
      [it]
    })
    retryUntil({
      def result = queryExecutor.executeQuery('select connector_id from system.metadata.catalogs')
      log.debug("Current presto connectors: ${result.rows()}")
      return result.rows().containsAll(connectorRows)
    }, TIMEOUT)
  }

  private void assertThatCountFromNationWorks(QueryExecutor queryExecutor, String nationTable)
  {
    QueryAssert.assertThat(queryExecutor.executeQuery("select count(*) from ${nationTable}"))
            .hasColumns(BIGINT)
            .containsExactly(row(25))
  }

  @Test
  void 'labeling subset of nodes - single cordinatoor@master'()
  {
    PrestoCluster prestoCluster = new PrestoCluster(slider, hdfsClient, 'resources-single-coordinator@master.json', APP_CONFIG_TEMPLATE)
    prestoCluster.withPrestoCluster {
      prestoCluster.assertThatPrestoIsUpAndRunning(0)

      // check placement policy
      assertThat(prestoCluster.coordinatorHost).contains(master)
      assertThat(prestoCluster.workerHosts).isEmpty()
    }
  }

  @Test
  void 'flex set of workers - multinode-flex-worker'()
  {
    PrestoCluster prestoCluster = new PrestoCluster(slider, hdfsClient, 'resources-multinode-single-worker.json', APP_CONFIG_TEMPLATE)
    prestoCluster.withPrestoCluster {
      prestoCluster.assertThatPrestoIsUpAndRunning(1)
      assertThatAllProcessesAreRunning(prestoCluster)

      checkState(workers.size() >= 2, "Number of slaves set in the test yaml configuration should be atleast 3")
      flexWorkersAndAssertThatComponentsAreRunning(2, prestoCluster)
      flexWorkersAndAssertThatComponentsAreRunning(1, prestoCluster)

      assertThatApplicationIsStoppable(prestoCluster)
    }
  }

  def waitForWorkers(int nodeCount, String component, PrestoCluster prestoCluster)
  {
    retryUntil({
      int liveContainers = prestoCluster.getLiveContainers(component)
      log.info("Number of live containers after 'flex'ing: " + liveContainers)
      liveContainers == nodeCount
    }, FLEX_RETRY_TIMEOUT)
  }


  private void flexWorkersAndAssertThatComponentsAreRunning(int workersCount, PrestoCluster prestoCluster)
  {
    prestoCluster.flex(WORKER_COMPONENT, workersCount)
    waitForWorkers(workersCount, WORKER_COMPONENT, prestoCluster)
    
    prestoCluster.waitForComponents(workersCount)
  }

  private void assertThatMemorySettingsAreCorrect(PrestoCluster prestoCluster)
  {
    String coordinatorHost = prestoCluster.coordinatorHost
    def prestoJvmMemory = nodeSshUtils.getPrestoJvmMemory(coordinatorHost)

    assertThat(prestoJvmMemory).isEqualTo(JVM_HEAPSIZE)
  }

  private void assertThatJvmArgsAreCorrect(PrestoCluster prestoCluster)
  {
    String coordinatorHost = prestoCluster.coordinatorHost
    def prestoProcess = nodeSshUtils.getPrestoJvmProcess(coordinatorHost)

    assertThat(prestoProcess).contains(JVM_ARGS)
  }

  private void assertThatAllProcessesAreRunning(PrestoCluster prestoCluster)
  {
    Map<String, Integer> prestoProcessesCountOnHosts = prestoCluster.allNodes
            .groupBy { it }
            .collectEntries { key, value -> [(key): value.size()] }

    log.info("Presto processes distribution: ${prestoProcessesCountOnHosts}")
    prestoProcessesCountOnHosts.each { host, count ->
      assertThat(nodeSshUtils.countOfPrestoProcesses(host)).isEqualTo(count)
    }
  }

  private void assertThatKilledProcessesRespawn(PrestoCluster prestoCluster)
  {
    String coordinatorHost = prestoCluster.coordinatorHost
    int processesCount = nodeSshUtils.countOfPrestoProcesses(coordinatorHost)
    nodeSshUtils.killPrestoProcesses(coordinatorHost)

    assertThat(nodeSshUtils.countOfPrestoProcesses(coordinatorHost)).isZero()

    retryUntil({
      nodeSshUtils.countOfPrestoProcesses(coordinatorHost) == processesCount
    }, TIMEOUT)
  }

  private void assertThatApplicationIsStoppable(PrestoCluster prestoCluster)
  {
    def allNodes = prestoCluster.allNodes

    prestoCluster.stop()
    
    log.debug("Checking if presto process is stopped")
    allNodes.each { host ->
      retryUntil({
        nodeSshUtils.countOfPrestoProcesses(host) == 0
      }, TIMEOUT)
    }
  }
}
