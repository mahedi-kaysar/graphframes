/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.graphframes.lib

import java.io.IOException

import scala.reflect.ClassTag

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions.{col, lit}
import org.apache.spark.sql.types.DataTypes

import org.graphframes._
import org.graphframes.GraphFrame._

class ConnectedComponentsSuite extends SparkFunSuite with GraphFrameTestSparkContext {

  test("single vertex") {
    val v = sqlContext.createDataFrame(List(
      (0L, "a", "b"))).toDF("id", "vattr", "gender")
    // Create an empty dataframe with the proper columns.
    val e = sqlContext.createDataFrame(List((0L, 0L, 1L))).toDF("src", "dst", "test")
      .filter("src > 10")
    val g = GraphFrame(v, e)
    val comps = ConnectedComponents.run(g)
    TestUtils.testSchemaInvariants(g, comps)
    TestUtils.checkColumnType(comps.schema, "component", DataTypes.LongType)
    assert(comps.count() === 1)
    assert(comps.select("id", "component", "vattr", "gender").collect()
      === Seq(Row(0L, 0L, "a", "b")))
  }

  test("two connected vertices") {
    val v = sqlContext.createDataFrame(List(
      (0L, "a0", "b0"),
      (1L, "a1", "b1"))).toDF("id", "A", "B")
    val e = sqlContext.createDataFrame(List(
      (0L, 1L, "a01", "b01"))).toDF("src", "dst", "A", "B")
    val g = GraphFrame(v, e)
    val comps = g.connectedComponents.run()
    TestUtils.testSchemaInvariants(g, comps)
    assert(comps.count() === 2)
    val vxs = comps.sort("id").select("id", "component", "A", "B").collect()
    assert(List(Row(0L, 0L, "a0", "b0"), Row(1L, 0L, "a1", "b1")) === vxs)
  }

  test("friends graph") {
    val friends = examples.Graphs.friends
    val expected = Set(Set("a", "b", "c", "d", "e", "f"), Set("g"))
    for ((algorithm, broadcastThreshold) <-
         Seq(("graphx", 1000000), ("graphframes", 100000), ("graphframes", 1))) {
      val components = friends.connectedComponents
        .setAlgorithm(algorithm)
        .setBroadcastThreshold(broadcastThreshold)
        .run()
      assertComponents(components, expected)
    }
  }

  test("really large long IDs") {
    val max = Long.MaxValue
    val chain = examples.Graphs.chain(10L)
    val vertices = chain.vertices.select((lit(max) - col(ID)).as(ID))
    val edges = chain.edges.select((lit(max) - col(SRC)).as(SRC), (lit(max) - col(DST)).as(DST))
    val g = GraphFrame(vertices, edges)
    val components = g.connectedComponents.run()
    assert(components.count() === 10L)
    assert(components.groupBy("component").count().count() === 1L)
  }

  test("checkpoint interval") {
    val friends = examples.Graphs.friends
    val expected = Set(Set("a", "b", "c", "d", "e", "f"), Set("g"))

    val cc = new ConnectedComponents(friends)
    assert(cc.getCheckpointInterval === 1,
      s"Default checkpoint interval should be 1, but got ${cc.getCheckpointInterval}.")

    val checkpointDir = sc.getCheckpointDir
    assert(checkpointDir.nonEmpty)

    sc.setCheckpointDir(null)
    withClue("Should throw an IOException if sc.getCheckpointDir is empty " +
      "and checkpointInterval is positive.") {
      intercept[IOException] {
        cc.run()
      }
    }

    // Checks whether the input DataFrame is from some checkpoint data.
    // TODO: The implemetnation is a little hacky.
    def isFromCheckpoint(df: DataFrame): Boolean = {
      df.queryExecution.logical.toString().toLowerCase.contains("parquet")
    }

    val components0 = cc.setCheckpointInterval(0).run()
    assertComponents(components0, expected)
    assert(!isFromCheckpoint(components0),
      "The result shouldn't depend on checkpoint data if checkpointing is disabled.")

    sc.setCheckpointDir(checkpointDir.get)

    val components1 = cc.setCheckpointInterval(1).run()
    assertComponents(components1, expected)
    assert(isFromCheckpoint(components1),
      "The result should depend on chekpoint data if checkpoint interval is 1.")

    val components10 = cc.setCheckpointInterval(10).run()
    assertComponents(components10, expected)
    assert(!isFromCheckpoint(components10),
      "The result shouldn't depend on checkpoint data if converged before first checkpoint.")
  }

  private def assertComponents[T: ClassTag](actual: DataFrame, expected: Set[Set[T]]): Unit = {
    // note: not using agg + collect_list because collect_list is not available in 1.6.2
    val actualComponents = actual.select("component", "id").rdd
      .map { case Row(component: Long, id: T) =>
        (component, id)
      }.groupByKey()
      .values
      .map(_.toSeq)
      .collect()
      .map { ids =>
        val idSet = ids.toSet
        assert(idSet.size === ids.size,
          s"Found duplicated component assignment in [${ids.mkString(",")}].")
        idSet
      }.toSet
    assert(actualComponents === expected)
  }
}
