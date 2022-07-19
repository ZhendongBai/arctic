/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.flink.table;

import com.netease.arctic.flink.FlinkTestBase;
import com.netease.arctic.flink.kafka.testutils.KafkaTestBase;
import com.netease.arctic.flink.util.DataUtil;
import com.netease.arctic.table.TableProperties;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.table.api.ApiExpression;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.CloseableIterator;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.netease.arctic.ams.api.MockArcticMetastoreServer.TEST_CATALOG_NAME;
import static com.netease.arctic.table.TableProperties.ENABLE_LOG_STORE;
import static com.netease.arctic.table.TableProperties.LOCATION;
import static com.netease.arctic.table.TableProperties.LOG_STORE_ADDRESS;
import static com.netease.arctic.table.TableProperties.LOG_STORE_MESSAGE_TOPIC;
import static org.apache.flink.table.api.Expressions.$;

public class TestKeyed extends FlinkTestBase {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private static final String DB = PK_TABLE_ID.getDatabase();
  private static final String TABLE = "test_keyed";
  private static final String TOPIC = String.join(".", TEST_CATALOG_NAME, DB, TABLE);
  private static final KafkaTestBase kafkaTestBase = new KafkaTestBase();

  public void before() {
    super.before();
    super.config();
  }

  @BeforeClass
  public static void prepare() throws Exception {
    kafkaTestBase.prepare();
  }

  @AfterClass
  public static void shutdown() throws Exception {
    kafkaTestBase.shutDownServices();
  }

  @After
  public void after() {
    sql("DROP TABLE IF EXISTS arcticCatalog." + DB + "." + TABLE);
  }

  @Test
  public void testSinkSourceFile() throws IOException {
    List<Object[]> data = new LinkedList<>();
    data.add(new Object[]{RowKind.INSERT, 1000004, "a", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{RowKind.DELETE, 1000015, "b", LocalDateTime.parse("2022-06-17T10:08:11.0")});
    data.add(new Object[]{RowKind.DELETE, 1000011, "c", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{RowKind.UPDATE_BEFORE, 1000021, "d", LocalDateTime.parse("2022-06-17T10:11:11.0")});
    data.add(new Object[]{RowKind.UPDATE_AFTER, 1000021, "e", LocalDateTime.parse("2022-06-17T10:11:11.0")});
    data.add(new Object[]{RowKind.INSERT, 1000015, "e", LocalDateTime.parse("2022-06-17T10:10:11.0")});

    DataStream<RowData> source = getEnv().fromCollection(DataUtil.toRowData(data),
        InternalTypeInfo.ofFields(
            DataTypes.INT().getLogicalType(),
            DataTypes.VARCHAR(100).getLogicalType(),
            DataTypes.TIMESTAMP().getLogicalType()
        ));

    Table input = getTableEnv().fromDataStream(source, $("id"), $("name"), $("op_time"));
    getTableEnv().createTemporaryView("input", input);

    sql("CREATE CATALOG arcticCatalog WITH %s", toWithClause(props));
    sql("CREATE TABLE arcticCatalog." + DB + "." + TABLE +
        " (" +
        " id INT," +
        " name STRING," +
        " op_time TIMESTAMP," +
        " PRIMARY KEY (id) NOT ENFORCED " +
        ") PARTITIONED BY(op_time) " +
        " WITH (" +
        " 'connector' = 'arctic'," +
        " 'location' = '" + tableDir.getAbsolutePath() + "'" +
        ")");

    sql("insert into arcticCatalog." + DB + "." + TABLE +
        "/*+ OPTIONS(" +
        "'arctic.emit.mode'='file'" +
        ")*/ select * from input");

    List<Row> actual =
        sql("select * from arcticCatalog." + DB + "." + TABLE +
            "/*+ OPTIONS(" +
            "'arctic.read.mode'='file'" +
            ")*/" +
            "");

    List<Object[]> expected = new LinkedList<>();
    expected.add(new Object[]{RowKind.INSERT, 1000004, "a", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    expected.add(new Object[]{RowKind.UPDATE_BEFORE, 1000021, "d", LocalDateTime.parse("2022-06-17T10:11:11.0")});
    expected.add(new Object[]{RowKind.UPDATE_AFTER, 1000021, "e", LocalDateTime.parse("2022-06-17T10:11:11.0")});
    expected.add(new Object[]{RowKind.INSERT, 1000015, "e", LocalDateTime.parse("2022-06-17T10:10:11.0")});

    Assert.assertEquals(DataUtil.toRowSet(expected), new HashSet<>(actual));
  }

  @Test
  public void testUnpartitionLogSinkSource() throws Exception {
    String topic = TOPIC + "testUnpartitionLogSinkSource";
    kafkaTestBase.createTopics(KAFKA_PARTITION_NUMS, topic);

    List<Object[]> data = new LinkedList<>();
    data.add(new Object[]{1000004, "a"});
    data.add(new Object[]{1000015, "b"});
    data.add(new Object[]{1000011, "c"});
    data.add(new Object[]{1000014, "d"});
    data.add(new Object[]{1000021, "d"});
    data.add(new Object[]{1000007, "e"});

    List<ApiExpression> rows = DataUtil.toRows(data);

    Table input = getTableEnv().fromValues(DataTypes.ROW(
            DataTypes.FIELD("id", DataTypes.INT()),
            DataTypes.FIELD("name", DataTypes.STRING())
        ),
        rows
    );
    getTableEnv().createTemporaryView("input", input);

    sql("CREATE CATALOG arcticCatalog WITH %s", toWithClause(props));

    Map<String, String> tableProperties = new HashMap<>();
    tableProperties.put(ENABLE_LOG_STORE, "true");
    tableProperties.put(LOG_STORE_ADDRESS, kafkaTestBase.brokerConnectionStrings);
    tableProperties.put(LOG_STORE_MESSAGE_TOPIC, topic);
    tableProperties.put(LOCATION, tableDir.getAbsolutePath());
    sql("CREATE TABLE IF NOT EXISTS arcticCatalog." + DB + "." + TABLE + "(" +
        " id INT, name STRING, PRIMARY KEY (id) NOT ENFORCED) WITH %s", toWithClause(tableProperties));

    sql("insert into arcticCatalog." + DB + "." + TABLE + " /*+ OPTIONS(" +
        "'arctic.emit.mode'='log'" +
        ", 'log.version'='v1'" +
        ") */" +
        " select * from input");

    TableResult result = exec("select * from arcticCatalog." + DB + "." + TABLE +
        "/*+ OPTIONS(" +
        "'arctic.read.mode'='log'" +
        ", 'scan.startup.mode'='earliest-offset'" +
        ")*/" +
        "");

    Set<Row> actual = new HashSet<>();
    try (CloseableIterator<Row> iterator = result.collect()) {
      for (Object[] datum : data) {
        Row row = iterator.next();
        actual.add(row);
      }
    }
    Assert.assertEquals(DataUtil.toRowSet(data), actual);
    result.getJobClient().ifPresent(JobClient::cancel);
  }

  @Test
  public void testUnPartitionDoubleSink() throws Exception {
    String topic = TOPIC + "testUnPartitionDoubleSink";
    kafkaTestBase.createTopics(KAFKA_PARTITION_NUMS, topic);

    List<Object[]> data = new LinkedList<>();
    data.add(new Object[]{1000004, "a"});
    data.add(new Object[]{1000015, "b"});
    data.add(new Object[]{1000011, "c"});
    data.add(new Object[]{1000014, "d"});
    data.add(new Object[]{1000021, "d"});
    data.add(new Object[]{1000007, "e"});

    List<ApiExpression> rows = DataUtil.toRows(data);

    Table input = getTableEnv().fromValues(DataTypes.ROW(
            DataTypes.FIELD("id", DataTypes.INT()),
            DataTypes.FIELD("name", DataTypes.STRING())
        ),
        rows
    );
    getTableEnv().createTemporaryView("input", input);
    sql("CREATE CATALOG arcticCatalog WITH %s", toWithClause(props));

    Map<String, String> tableProperties = new HashMap<>();
    tableProperties.put(ENABLE_LOG_STORE, "true");
    tableProperties.put(LOG_STORE_ADDRESS, kafkaTestBase.brokerConnectionStrings);
    tableProperties.put(LOG_STORE_MESSAGE_TOPIC, topic);
    tableProperties.put(LOCATION, tableDir.getAbsolutePath());
    sql("CREATE TABLE IF NOT EXISTS arcticCatalog." + DB + "." + TABLE + "(" +
        " id INT, name STRING, PRIMARY KEY (id) NOT ENFORCED) WITH %s", toWithClause(tableProperties));

    sql("insert into arcticCatalog." + DB + "." + TABLE + " /*+ OPTIONS(" +
        "'arctic.emit.mode'='file, log'" +
        ") */" +
        "select id, name from input");

    Assert.assertEquals(DataUtil.toRowSet(data),
        new HashSet<>(sql("select * from arcticCatalog." + DB + "." + TABLE)));

    TableResult result = exec("select * from arcticCatalog." + DB + "." + TABLE +
        " /*+ OPTIONS('arctic.read.mode'='log', 'scan.startup.mode'='earliest-offset') */");
    Set<Row> actual = new HashSet<>();
    try (CloseableIterator<Row> iterator = result.collect()) {
      for (Object[] datum : data) {
        actual.add(iterator.next());
      }
    }
    Assert.assertEquals(DataUtil.toRowSet(data), actual);
    result.getJobClient().ifPresent(JobClient::cancel);
  }

  @Test
  public void testPartitionSinkFile() throws IOException {
    List<Object[]> data = new LinkedList<>();
    data.add(new Object[]{1000004, "a", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{1000015, "b", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{1000011, "c", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{1000014, "d", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{1000015, "d", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{1000007, "e", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{1000007, "e", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    List<ApiExpression> rows = DataUtil.toRows(data);

    Table input = getTableEnv().fromValues(DataTypes.ROW(
            DataTypes.FIELD("id", DataTypes.INT()),
            DataTypes.FIELD("name", DataTypes.STRING()),
            DataTypes.FIELD("op_time", DataTypes.TIMESTAMP())
        ),
        rows
    );
    getTableEnv().createTemporaryView("input", input);

    sql("CREATE CATALOG arcticCatalog WITH %s", toWithClause(props));

    sql("CREATE TABLE IF NOT EXISTS arcticCatalog." + DB + "." + TABLE + "(" +
        " id INT, name STRING, op_time TIMESTAMP, PRIMARY KEY (id) NOT ENFORCED " +
        ") PARTITIONED BY(op_time) WITH ('connector' = 'arctic', 'location' = '" + tableDir.getAbsolutePath() + "')");

    sql("insert into arcticCatalog." + DB + "." + TABLE +
        "/*+ OPTIONS(" +
        "'arctic.emit.mode'='file'" +
        ")*/" + " select * from input");

    Assert.assertEquals(DataUtil.toRowSet(data),
        new HashSet<>(sql("select * from arcticCatalog." + DB + "." + TABLE)));
  }

  @Test
  public void testFileUpsert() throws IOException {
    List<Object[]> data = new LinkedList<>();
    data.add(new Object[]{RowKind.INSERT, 1000004, "a", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{RowKind.DELETE, 1000015, "b", LocalDateTime.parse("2022-06-17T10:08:11.0")});
    data.add(new Object[]{RowKind.DELETE, 1000011, "c", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{RowKind.UPDATE_BEFORE, 1000021, "d", LocalDateTime.parse("2022-06-17T10:11:11.0")});
    data.add(new Object[]{RowKind.UPDATE_AFTER, 1000021, "e", LocalDateTime.parse("2022-06-17T10:11:11.0")});
    data.add(new Object[]{RowKind.INSERT, 1000015, "e", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    DataStream<RowData> source = getEnv().fromCollection(DataUtil.toRowData(data),
        InternalTypeInfo.ofFields(
            DataTypes.INT().getLogicalType(),
            DataTypes.VARCHAR(100).getLogicalType(),
            DataTypes.TIMESTAMP().getLogicalType()
        ));

    Table input = getTableEnv().fromDataStream(source, $("id"), $("name"), $("op_time"));
    getTableEnv().createTemporaryView("input", input);

    sql("CREATE CATALOG arcticCatalog WITH %s", toWithClause(props));

    Map<String, String> tableProperties = new HashMap<>();
    tableProperties.put(TableProperties.UPSERT_ENABLED, "true");
    tableProperties.put(LOCATION, tableDir.getAbsolutePath());
    sql("CREATE TABLE IF NOT EXISTS arcticCatalog." + DB + "." + TABLE + "(" +
        " id INT, name STRING, op_time TIMESTAMP, PRIMARY KEY (id) NOT ENFORCED " +
        ") PARTITIONED BY(op_time) WITH %s", toWithClause(tableProperties));

    sql("insert into arcticCatalog." + DB + "." + TABLE +
        "/*+ OPTIONS(" +
        "'arctic.emit.mode'='file'" +
        ")*/" + " select * from input");

    List<Object[]> expected = new LinkedList<>();
    expected.add(new Object[]{RowKind.INSERT, 1000004, "a", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    expected.add(new Object[]{RowKind.DELETE, 1000004, "a", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    expected.add(new Object[]{RowKind.UPDATE_BEFORE, 1000021, "d", LocalDateTime.parse("2022-06-17T10:11:11.0")});
    expected.add(new Object[]{RowKind.UPDATE_AFTER, 1000021, "e", LocalDateTime.parse("2022-06-17T10:11:11.0")});
    expected.add(new Object[]{RowKind.DELETE, 1000015, "e", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    expected.add(new Object[]{RowKind.INSERT, 1000015, "e", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    Assert.assertEquals(DataUtil.toRowSet(expected),
        new HashSet<>(sql("select * from arcticCatalog." + DB + "." + TABLE)));
  }

  @Test
  public void testPartitionLogSinkSource() throws Exception {
    String topic = TOPIC + "testPartitionLogSinkSource";
    kafkaTestBase.createTopics(KAFKA_PARTITION_NUMS, topic);

    List<Object[]> data = new LinkedList<>();
    data.add(new Object[]{1000004, "a", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{1000015, "b", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{1000011, "c", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{1000014, "d", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{1000015, "d", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{1000007, "e", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{1000007, "e", LocalDateTime.parse("2022-06-18T10:10:11.0")});

    List<ApiExpression> rows = DataUtil.toRows(data);

    Table input = getTableEnv().fromValues(DataTypes.ROW(
            DataTypes.FIELD("id", DataTypes.INT()),
            DataTypes.FIELD("name", DataTypes.STRING()),
            DataTypes.FIELD("op_time", DataTypes.TIMESTAMP())
        ),
        rows
    );
    getTableEnv().createTemporaryView("input", input);

    sql("CREATE CATALOG arcticCatalog WITH %s", toWithClause(props));

    Map<String, String> tableProperties = new HashMap<>();
    tableProperties.put(ENABLE_LOG_STORE, "true");
    tableProperties.put(LOG_STORE_ADDRESS, kafkaTestBase.brokerConnectionStrings);
    tableProperties.put(LOG_STORE_MESSAGE_TOPIC, topic);
    tableProperties.put(LOCATION, tableDir.getAbsolutePath());
    sql("CREATE TABLE IF NOT EXISTS arcticCatalog." + DB + "." + TABLE + "(" +
        " id INT, name STRING, op_time TIMESTAMP, PRIMARY KEY (id) NOT ENFORCED " +
        ") PARTITIONED BY(op_time) WITH %s", toWithClause(tableProperties));

    sql("insert into arcticCatalog." + DB + "." + TABLE + " /*+ OPTIONS(" +
        "'arctic.emit.mode'='log'" +
        ", 'log.version'='v1'" +
        ") */" +
        " select * from input");

    TableResult result = exec("select * from arcticCatalog." + DB + "." + TABLE +
        "/*+ OPTIONS(" +
        "'arctic.read.mode'='log'" +
        ", 'scan.startup.mode'='earliest-offset'" +
        ")*/" +
        "");
    Set<Row> actual = new HashSet<>();
    try (CloseableIterator<Row> iterator = result.collect()) {
      for (Object[] datum : data) {
        Row row = iterator.next();
        actual.add(row);
      }
    }
    Assert.assertEquals(DataUtil.toRowSet(data), actual);
    result.getJobClient().ifPresent(JobClient::cancel);
  }

  @Test
  public void testPartitionDoubleSink() throws Exception {
    String topic = TOPIC + "testPartitionDoubleSink";
    kafkaTestBase.createTopics(KAFKA_PARTITION_NUMS, topic);

    List<Object[]> data = new LinkedList<>();
    data.add(new Object[]{1000004, "a", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{1000015, "b", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{1000011, "c", LocalDateTime.parse("2022-06-17T10:10:11.0")});
    data.add(new Object[]{1000014, "d", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{1000015, "d", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{1000007, "e", LocalDateTime.parse("2022-06-18T10:10:11.0")});
    data.add(new Object[]{1000007, "e", LocalDateTime.parse("2022-06-18T10:10:11.0")});

    List<ApiExpression> rows = DataUtil.toRows(data);

    Table input = getTableEnv().fromValues(DataTypes.ROW(
            DataTypes.FIELD("id", DataTypes.INT()),
            DataTypes.FIELD("name", DataTypes.STRING()),
            DataTypes.FIELD("op_time", DataTypes.TIMESTAMP())
        ),
        rows
    );
    getTableEnv().createTemporaryView("input", input);
    sql("CREATE CATALOG arcticCatalog WITH %s", toWithClause(props));

    Map<String, String> tableProperties = new HashMap<>();
    tableProperties.put(ENABLE_LOG_STORE, "true");
    tableProperties.put(LOG_STORE_ADDRESS, kafkaTestBase.brokerConnectionStrings);
    tableProperties.put(LOG_STORE_MESSAGE_TOPIC, topic);
    tableProperties.put(LOCATION, tableDir.getAbsolutePath());
    sql("CREATE TABLE IF NOT EXISTS arcticCatalog." + DB + "." + TABLE + "(" +
        " id INT, name STRING, op_time TIMESTAMP, PRIMARY KEY (id) NOT ENFORCED " +
        ") PARTITIONED BY(op_time) WITH %s", toWithClause(tableProperties));

    sql("insert into arcticCatalog." + DB + "." + TABLE + " /*+ OPTIONS(" +
        "'arctic.emit.mode'='file, log'" +
        ", 'log.version'='v1'" +
        ") */" +
        "select * from input");

    Assert.assertEquals(DataUtil.toRowSet(data),
        new HashSet<>(sql("select * from arcticCatalog." + DB + "." + TABLE)));
    TableResult result = exec("select * from arcticCatalog." + DB + "." + TABLE +
        " /*+ OPTIONS('arctic.read.mode'='log', 'scan.startup.mode'='earliest-offset') */");

    Set<Row> actual = new HashSet<>();
    try (CloseableIterator<Row> iterator = result.collect()) {
      for (Object[] datum : data) {
        actual.add(iterator.next());
      }
    }
    Assert.assertEquals(DataUtil.toRowSet(data), actual);

    result.getJobClient().ifPresent(JobClient::cancel);
  }
}
