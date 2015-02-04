/**
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.fferreira.example.hazelcast.cassandra;

import com.datastax.driver.core.Session;
import com.fferreira.example.hazelcast.Constants;
import com.fferreira.example.hazelcast.EventEntity;
import com.fferreira.example.hazelcast.HazelcastStore;
import com.fferreira.example.hazelcast.HazelcastWorker;
import java.util.Set;
import java.util.UUID;
import static org.testng.AssertJUnit.assertEquals;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test(suiteName = "Hazelcast Cassandra Worker Test", priority = 2)
public class HCCassandraWorkerTest {

  private static final String CREATE_GROUP = "create_group";
  private static final String RUD_GROUP = "rud_group";
  private static final String COLD_START_GROUP = "cold_start_group";

  private CassandraClient dao;

  private HazelcastWorker worker;
  private HazelcastStore store;
  private HCCassandraMapStore mapStore;

  // data to be shared in test
  private String id;
  private String event;
  private int counter = 0;

  @BeforeClass
  public void setUpClass() throws Exception {

    // creating cassandra client with IP added to POM.
    dao = new CassandraClient();
    dao.initialize(System.getProperty("cassandra.ip"));

    // creating data keyspace and table
    final Session session = dao.connect();
    session.execute("CREATE KEYSPACE IF NOT EXISTS "
        + Constants.CASSANDRA_KEYSPACE + " WITH replication "
        + "= {'class':'SimpleStrategy', 'replication_factor':3};");
    session.execute("CREATE TABLE IF NOT EXISTS "
        + Constants.CASSANDRA_KEYSPACE_TABLE_NAME + " ("
        + "id text PRIMARY KEY," + "data text" + ");");

    mapStore = new HCCassandraMapStore();
    mapStore.setDao(dao);

    // starting 3 instances of hazelcast
    store = new HazelcastStore(mapStore, Constants.CASSANDRA_MAP_STORE);
    new HazelcastStore(mapStore, Constants.CASSANDRA_MAP_STORE);
    new HazelcastStore(mapStore, Constants.CASSANDRA_MAP_STORE);
    
    worker = new HazelcastWorker(Constants.CASSANDRA_MAP_STORE);
  }

  @AfterClass
  public void tearDownClass() throws Exception {
    // droping data keyspace and table
    final Session session = dao.connect();
    session//
        .execute("DROP TABLE IF EXISTS "
            + Constants.CASSANDRA_KEYSPACE_TABLE_NAME + ";");
    session.execute("DROP KEYSPACE IF EXISTS " + Constants.CASSANDRA_KEYSPACE
        + ";");
    // stoping hazelcast instance and client
    store.destroy();
    worker.destroy();
  }

  @Test(groups = CREATE_GROUP, invocationCount = 3)
  public void test_add_event() throws InterruptedException {
    counter++;
    id = "event_cassamdra_" + counter + "_" + UUID.randomUUID();
    event = "Event Cassandra data " + counter;
    worker.addSubscriber(id, event);
    // just give time to it since it is async
    Thread.sleep(3000);
    assertEquals(dao.findAll().size(), counter);
  }

  @Test(dependsOnGroups = CREATE_GROUP, groups = RUD_GROUP)
  public void test_remove_subscriber() throws Exception {
    worker.removeSubscriber(id);
    // just give time to it since it is async
    Thread.sleep(2000);
    counter--;

    assertEquals(dao.findAll().size(), counter);

    // getting valid values to id and event
    final EventEntity res = dao.findAll().get(0);
    id = res.getId();
    event = res.getMessage();
  }

  @Test(dependsOnGroups = CREATE_GROUP, groups = RUD_GROUP)
  public void test_get_event() {
    assertEquals(worker.getEvent(id), event);
  }

  @Test(dependsOnGroups = CREATE_GROUP, groups = RUD_GROUP)
  public void test_get_event_with_given_message() {
    final Set<String> events = worker.getEventsWithMessage(event);
    assertEquals(events.size(), 1);
    assertEquals(events.toArray()[0], id);
  }

  @Test(dependsOnGroups = RUD_GROUP, groups = COLD_START_GROUP)
  public void test_get_event_data_after_instance_down() {
    store.destroy();
    worker.destroy();

    // initializing a new instance to test cold start
    store = new HazelcastStore(mapStore, Constants.CASSANDRA_MAP_STORE);
    worker = new HazelcastWorker(Constants.CASSANDRA_MAP_STORE);

    assertEquals(worker.getEvent(id), event);
  }

  @Test(dependsOnGroups = COLD_START_GROUP)
  public void test_get_event_with_given_message_before_coldstart()
      throws InterruptedException {
    final Set<String> events = worker.getEventsWithMessage(event);
    assertEquals(events.size(), 1);
    assertEquals(events.toArray()[0], id);
  }

}
