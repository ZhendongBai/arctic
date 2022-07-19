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

package com.netease.arctic.spark;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.TableIdentifier;
import com.netease.arctic.table.UnkeyedTable;
import java.util.List;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Schema;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.StructLikeMap;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.types.StructType;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestOptimizeWrite extends SparkTestBase {

  private final String database = "db";
  private final String sinkTable = "sink_table";
  private final String sourceTable = "source_table";
  private final TableIdentifier identifier = TableIdentifier.of(catalogName, database, sinkTable);

  @Before
  public void before() {
    sql("use " + catalogName);
    sql("create database if not exists {0}", database);
    List<Row> rows = Lists.newArrayList(
        RowFactory.create(1, "aaa", "aaa"),
        RowFactory.create(2, "bbb", "aaa"),
        RowFactory.create(3, "aaa", "bbb"),
        RowFactory.create(4, "bbb", "bbb"),
        RowFactory.create(5, "aaa", "ccc"),
        RowFactory.create(6, "bbb", "ccc")
    );
    StructType schema = SparkSchemaUtil.convert(new Schema(
        Types.NestedField.of(1, false, "id", Types.IntegerType.get()),
        Types.NestedField.of(2, false, "column1", Types.StringType.get()),
        Types.NestedField.of(3, false, "column2", Types.StringType.get())
    ));
    Dataset<Row> ds = spark.createDataFrame(rows, schema);
    ds = ds.repartition(new Column("column2"));
    ds.registerTempTable(sourceTable);
  }

  @After
  public void cleanUpTable() {
    sql("drop table " + database + "." + sinkTable);
    sql("drop table " + sourceTable);
    sql("drop database " + database);
  }

  /**
   * no shuffle.
   * source[3partition] -> sink[2partition]
   * 6 file
   */
  @Test
  public void testModeIsNone() {
    sql("create table {0}.{1} ( \n" +
            " id int , \n" +
            " column1 string , \n " +
            " column2 string, \n" +
            " primary key (id) \n" +
            ") using arctic \n" +
            " partitioned by ( column1 ) \n" +
            " TBLPROPERTIES(''write.distribution-mode'' = ''none'') "
        , database, sinkTable);
    sql("insert overwrite {0}.{1} SELECT id, column1, column2 from {2}",
        database, sinkTable, sourceTable);
    rows = sql("select * from {0}.{1} order by id", database, sinkTable);
    Assert.assertEquals(6, rows.size());
    Assert.assertEquals(
        6,
        baseTableSize(identifier));
  }

  /**
   * shuffle auto
   */
  @Test
  public void testPrimaryKeyPartitionedTable() {
    sql("create table {0}.{1} ( \n" +
            " id int , \n" +
            " column1 string , \n " +
            " column2 string, \n" +
            " primary key (id) \n" +
            ") using arctic \n" +
            " partitioned by ( column1 ) \n" +
            " TBLPROPERTIES(''write.distribution-mode'' = ''hash'', " +
            "''write.distribution.hash-mode'' = ''auto''," +
            "''base.file-index.hash-bucket'' = ''1'')"
        , database, sinkTable);
    sql("insert overwrite {0}.{1} SELECT id, column1, column2 from {2}",
        database, sinkTable, sourceTable);
    rows = sql("select * from {0}.{1} order by id", database, sinkTable);
    Assert.assertEquals(6, rows.size());
    Assert.assertEquals(
        2,
        Iterables.size(loadTable(identifier).asKeyedTable().baseTable().newScan().planFiles()));
  }

  @Test
  public void testPartitionedTableWithoutPrimaryKey() {
    sql("create table {0}.{1} ( \n" +
            " id int , \n" +
            " column1 string , \n " +
            " column2 string, \n" +
            " primary key (id) \n" +
            ") using arctic \n" +
            " partitioned by ( column1 ) \n" +
            " TBLPROPERTIES(''write.distribution-mode'' = ''hash'', " +
            "''write.distribution.hash-mode'' = ''partition-key'')"
        , database, sinkTable);
    sql("insert overwrite {0}.{1} SELECT id, column1, column2 from {2}",
        database, sinkTable, sourceTable);
    rows = sql("select * from {0}.{1} order by id", database, sinkTable);
    Assert.assertEquals(6, rows.size());
    Assert.assertEquals(
        4,
        Iterables.size(loadTable(identifier).asKeyedTable().baseTable().newScan().planFiles()));
  }

  /**
   * shuffle by primary key
   * source[3partition] -> shuffle %1 primary key[1partition] -> sink[2partition]
   * write 2 file
   */
  @Test
  public void testPrimaryKeyTableWithoutPartition() {
    sql("create table {0}.{1} ( \n" +
            " id int , \n" +
            " column1 string , \n " +
            " column2 string, \n" +
            " primary key (id) \n" +
            ") using arctic \n" +
            " partitioned by ( column1 ) \n" +
            " TBLPROPERTIES(''write.distribution-mode'' = ''hash'', " +
            "''write.distribution.hash-mode'' = ''primary-key''," +
            "''base.file-index.hash-bucket'' = ''1'')"
        , database, sinkTable);
    sql("insert overwrite {0}.{1} SELECT id, column1, column2 from {2}",
        database, sinkTable, sourceTable);
    rows = sql("select * from {0}.{1} order by id", database, sinkTable);
    Assert.assertEquals(6, rows.size());
    Assert.assertEquals(
        2,
        Iterables.size(loadTable(identifier).asKeyedTable().baseTable().newScan().planFiles()));
  }

  /**
   * shuffle by primary key
   * source[3partition] -> shuffle %2 primary key -> sink[2partition]
   * write 4 file
   */
  @Test
  public void testPrimaryKeyTableFileSplitNum() {
    sql("create table {0}.{1} ( \n" +
            " id int , \n" +
            " column1 string , \n " +
            " column2 string, \n" +
            " primary key (id) \n" +
            ") using arctic \n" +
            " partitioned by ( column1 ) \n" +
            " TBLPROPERTIES(''write.distribution-mode'' = ''hash'', " +
            "''write.distribution.hash-mode'' = ''primary-key''," +
            "''base.file-index.hash-bucket'' = ''2'')"
        , database, sinkTable);
    sql("insert overwrite {0}.{1} SELECT id, column1, column2 from {2}",
        database, sinkTable, sourceTable);
    rows = sql("select * from {0}.{1} order by id", database, sinkTable);
    Assert.assertEquals(6, rows.size());
    // Assert.assertEquals(
    //     4,
    //     Iterables.size(loadTable(identifier).asKeyedTable().baseTable().newScan().planFiles()));
    Assert.assertEquals(4,
        baseTableSize(identifier));
  }

  protected long baseTableSize(TableIdentifier identifier) {
    ArcticTable arcticTable = loadTable(identifier);
    UnkeyedTable base = null;
    if (arcticTable.isKeyedTable()) {
      base = arcticTable.asKeyedTable().baseTable();
    } else {
      base = arcticTable.asUnkeyedTable();
    }
    StructLikeMap<List<DataFile>> dfMap = partitionFiles(base);
    return dfMap.values().stream().map(List::size)
        .reduce(0, Integer::sum).longValue();
  }
}
