/*
 *  Copyright (c) 2017 Uber Technologies, Inc. (hoodie-dev-group@uber.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.uber.hoodie

import java.util
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

import com.uber.hoodie.DataSourceReadOptions._
import com.uber.hoodie.DataSourceWriteOptions._
import com.uber.hoodie.common.table.HoodieTableMetaClient
import com.uber.hoodie.common.util.{FSUtils, TypedProperties}
import com.uber.hoodie.config.HoodieWriteConfig
import com.uber.hoodie.exception.HoodieException
import com.uber.hoodie.hive.{HiveSyncConfig, HiveSyncTool}
import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.log4j.LogManager
import org.apache.spark.api.java.JavaSparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

/**
  * Hoodie Spark Datasource, for reading and writing hoodie datasets
  *
  */
class DefaultSource extends RelationProvider
  with SchemaRelationProvider
  with CreatableRelationProvider
  with DataSourceRegister
  with Serializable {

  private val log = LogManager.getLogger(classOf[DefaultSource])

  override def createRelation(sqlContext: SQLContext,
                              parameters: Map[String, String]): BaseRelation = {
    createRelation(sqlContext, parameters, null)
  }

  /**
    * Add default options for unspecified read options keys.
    *
    * @param parameters
    * @return
    */
  def parametersWithReadDefaults(parameters: Map[String, String]) = {
    val defaultsMap = new ConcurrentHashMap[String, String](mapAsJavaMap(parameters))
    defaultsMap.putIfAbsent(VIEW_TYPE_OPT_KEY, DEFAULT_VIEW_TYPE_OPT_VAL)
    mapAsScalaMap(defaultsMap)
  }

  override def createRelation(sqlContext: SQLContext,
                              optParams: Map[String, String],
                              schema: StructType): BaseRelation = {
    val parameters = parametersWithReadDefaults(optParams)
    val path = parameters.get("path")
    if (path.isEmpty) {
      throw new HoodieException("'path' must be specified.")
    }

    if (parameters(VIEW_TYPE_OPT_KEY).equals(VIEW_TYPE_REALTIME_OPT_VAL)) {
      throw new HoodieException("Realtime view not supported yet via data source. Please use HiveContext route.")
    }

    if (parameters(VIEW_TYPE_OPT_KEY).equals(VIEW_TYPE_INCREMENTAL_OPT_VAL)) {
      new IncrementalRelation(sqlContext, path.get, optParams, schema)
    } else {
      // this is just effectively RO view only, where `path` can contain a mix of
      // non-hoodie/hoodie path files. set the path filter up
      sqlContext.sparkContext.hadoopConfiguration.setClass(
        "mapreduce.input.pathFilter.class",
        classOf[com.uber.hoodie.hadoop.HoodieROTablePathFilter],
        classOf[org.apache.hadoop.fs.PathFilter]);

      log.info("Constructing hoodie (as parquet) data source with options :" + parameters)
      // simply return as a regular parquet relation
      DataSource.apply(
        sparkSession = sqlContext.sparkSession,
        userSpecifiedSchema = Option(schema),
        className = "parquet",
        options = parameters.toMap)
        .resolveRelation()
    }
  }

  /**
    * Add default options for unspecified write options keys.
    *
    * @param parameters
    * @return
    */
  def parametersWithWriteDefaults(parameters: Map[String, String]) = {
    val defaultsMap = new ConcurrentHashMap[String, String](mapAsJavaMap(parameters))
    defaultsMap.putIfAbsent(OPERATION_OPT_KEY, DEFAULT_OPERATION_OPT_VAL)
    defaultsMap.putIfAbsent(STORAGE_TYPE_OPT_KEY, DEFAULT_STORAGE_TYPE_OPT_VAL)
    defaultsMap.putIfAbsent(PRECOMBINE_FIELD_OPT_KEY, DEFAULT_PRECOMBINE_FIELD_OPT_VAL)
    defaultsMap.putIfAbsent(PAYLOAD_CLASS_OPT_KEY, DEFAULT_PAYLOAD_OPT_VAL)
    defaultsMap.putIfAbsent(RECORDKEY_FIELD_OPT_KEY, DEFAULT_RECORDKEY_FIELD_OPT_VAL)
    defaultsMap.putIfAbsent(PARTITIONPATH_FIELD_OPT_KEY, DEFAULT_PARTITIONPATH_FIELD_OPT_VAL)
    defaultsMap.putIfAbsent(KEYGENERATOR_CLASS_OPT_KEY, DEFAULT_KEYGENERATOR_CLASS_OPT_VAL)
    defaultsMap.putIfAbsent(COMMIT_METADATA_KEYPREFIX_OPT_KEY, DEFAULT_COMMIT_METADATA_KEYPREFIX_OPT_VAL)
    defaultsMap.putIfAbsent(HIVE_SYNC_ENABLED_OPT_KEY, DEFAULT_HIVE_SYNC_ENABLED_OPT_VAL)
    defaultsMap.putIfAbsent(HIVE_DATABASE_OPT_KEY, DEFAULT_HIVE_DATABASE_OPT_VAL)
    defaultsMap.putIfAbsent(HIVE_TABLE_OPT_KEY, DEFAULT_HIVE_TABLE_OPT_VAL)
    defaultsMap.putIfAbsent(HIVE_USER_OPT_KEY, DEFAULT_HIVE_USER_OPT_VAL)
    defaultsMap.putIfAbsent(HIVE_PASS_OPT_KEY, DEFAULT_HIVE_PASS_OPT_VAL)
    defaultsMap.putIfAbsent(HIVE_URL_OPT_KEY, DEFAULT_HIVE_URL_OPT_VAL)
    defaultsMap.putIfAbsent(HIVE_PARTITION_FIELDS_OPT_KEY, DEFAULT_HIVE_PARTITION_FIELDS_OPT_VAL)
    defaultsMap.putIfAbsent(HIVE_PARTITION_EXTRACTOR_CLASS_OPT_KEY, DEFAULT_HIVE_PARTITION_EXTRACTOR_CLASS_OPT_VAL)
    defaultsMap.putIfAbsent(HIVE_ASSUME_DATE_PARTITION_OPT_KEY, DEFAULT_HIVE_ASSUME_DATE_PARTITION_OPT_VAL)
    mapAsScalaMap(defaultsMap)
  }

  def toProperties(params: Map[String, String]): TypedProperties = {
    val props = new TypedProperties()
    params.foreach(kv => props.setProperty(kv._1, kv._2))
    props
  }


  override def createRelation(sqlContext: SQLContext,
                              mode: SaveMode,
                              optParams: Map[String, String],
                              df: DataFrame): BaseRelation = {

    val parameters = parametersWithWriteDefaults(optParams).toMap
    val sparkContext = sqlContext.sparkContext
    val path = parameters.get("path")
    val tblName = parameters.get(HoodieWriteConfig.TABLE_NAME)
    if (path.isEmpty || tblName.isEmpty) {
      throw new HoodieException(s"'${HoodieWriteConfig.TABLE_NAME}', 'path' must be set.")
    }
    val serializer = sparkContext.getConf.get("spark.serializer")
    if (!serializer.equals("org.apache.spark.serializer.KryoSerializer")) {
      throw new HoodieException(s"${serializer} serialization is not supported by hoodie. Please use kryo.")
    }

    val storageType = parameters(STORAGE_TYPE_OPT_KEY)
    val operation = parameters(OPERATION_OPT_KEY)

    // register classes & schemas
    val structName = s"${tblName.get}_record"
    val nameSpace = s"hoodie.${tblName.get}"
    sparkContext.getConf.registerKryoClasses(
      Array(classOf[org.apache.avro.generic.GenericData],
        classOf[org.apache.avro.Schema]))
    val schema = AvroConversionUtils.convertStructTypeToAvroSchema(df.schema, structName, nameSpace)
    sparkContext.getConf.registerAvroSchemas(schema)
    log.info(s"Registered avro schema : ${schema.toString(true)}");

    // Convert to RDD[HoodieRecord]
    val keyGenerator = DataSourceUtils.createKeyGenerator(
      parameters(KEYGENERATOR_CLASS_OPT_KEY),
      toProperties(parameters)
    )
    val genericRecords: RDD[GenericRecord] = AvroConversionUtils.createRdd(df, structName, nameSpace)
    val hoodieRecords = genericRecords.map(gr => {
      val orderingVal = DataSourceUtils.getNestedFieldValAsString(
        gr, parameters(PRECOMBINE_FIELD_OPT_KEY)).asInstanceOf[Comparable[_]]
      DataSourceUtils.createHoodieRecord(gr,
        orderingVal, keyGenerator.getKey(gr), parameters(PAYLOAD_CLASS_OPT_KEY))
    }).toJavaRDD();


    val basePath = new Path(parameters.get("path").get)
    val fs = basePath.getFileSystem(sparkContext.hadoopConfiguration)
    var exists = fs.exists(basePath)

    // Handle various save modes
    if (mode == SaveMode.ErrorIfExists && exists) {
      throw new HoodieException(s"basePath ${basePath} already exists.")
    }
    if (mode == SaveMode.Ignore && exists) {
      log.warn(s" basePath ${basePath} already exists. Ignoring & not performing actual writes.")
      return createRelation(sqlContext, parameters, df.schema)
    }
    if (mode == SaveMode.Overwrite && exists) {
      log.warn(s" basePath ${basePath} already exists. Deleting existing data & overwriting with new data.")
      fs.delete(basePath, true)
      exists = false
    }

    // Create the dataset if not present (APPEND mode)
    if (!exists) {
      HoodieTableMetaClient.initTableType(sparkContext.hadoopConfiguration, path.get, storageType,
        tblName.get, "archived")
    }

    // Create a HoodieWriteClient & issue the write.
    val jsc = new JavaSparkContext(sparkContext);
    val client = DataSourceUtils.createHoodieClient(jsc,
      schema.toString,
      path.get,
      tblName.get,
      mapAsJavaMap(parameters)
    )
    val commitTime = client.startCommit();

    val writeStatuses = DataSourceUtils.doWriteOperation(client, hoodieRecords, commitTime, operation)
    // Check for errors and commit the write.
    val errorCount = writeStatuses.rdd.filter(ws => ws.hasErrors).count()
    if (errorCount == 0) {
      log.info("No errors. Proceeding to commit the write.");
      val metaMap = parameters.filter(kv =>
        kv._1.startsWith(parameters(COMMIT_METADATA_KEYPREFIX_OPT_KEY)))
      val success = if (metaMap.isEmpty) {
        client.commit(commitTime, writeStatuses)
      } else {
        client.commit(commitTime, writeStatuses,
          Optional.of(new util.HashMap[String, String](mapAsJavaMap(metaMap))))
      }

      if (success) {
        log.info("Commit " + commitTime + " successful!")
      }
      else {
        log.info("Commit " + commitTime + " failed!")
      }

      val hiveSyncEnabled = parameters.get(HIVE_SYNC_ENABLED_OPT_KEY).map(r => r.toBoolean).getOrElse(false)
      if (hiveSyncEnabled) {
        log.info("Syncing to Hive Metastore (URL: " + parameters(HIVE_URL_OPT_KEY) + ")")
        val fs = FSUtils.getFs(basePath.toString, jsc.hadoopConfiguration)
        syncHive(basePath, fs, parameters)
      }
      client.close
    } else {
      log.error(s"Upsert failed with ${errorCount} errors :");
      if (log.isTraceEnabled) {
        log.trace("Printing out the top 100 errors")
        writeStatuses.rdd.filter(ws => ws.hasErrors)
          .take(100)
          .foreach(ws => {
            log.trace("Global error :", ws.getGlobalError)
            if (ws.getErrors.size() > 0) {
              ws.getErrors.foreach(kt =>
                log.trace(s"Error for key: ${kt._1}", kt._2))
            }
          })
      }
    }
    createRelation(sqlContext, parameters, df.schema)
  }

  private def syncHive(basePath: Path, fs: FileSystem, parameters: Map[String, String]): Boolean = {
    val hiveSyncConfig: HiveSyncConfig = buildSyncConfig(basePath, parameters)
    val hiveConf: HiveConf = new HiveConf()
    hiveConf.addResource(fs.getConf)
    new HiveSyncTool(hiveSyncConfig, hiveConf, fs).syncHoodieTable()
    true
  }

  private def buildSyncConfig(basePath: Path, parameters: Map[String, String]): HiveSyncConfig = {
    val hiveSyncConfig: HiveSyncConfig = new HiveSyncConfig()
    hiveSyncConfig.basePath = basePath.toString
    hiveSyncConfig.assumeDatePartitioning =
      parameters.get(HIVE_ASSUME_DATE_PARTITION_OPT_KEY).exists(r => r.toBoolean)
    hiveSyncConfig.databaseName = parameters(HIVE_DATABASE_OPT_KEY)
    hiveSyncConfig.tableName = parameters(HIVE_TABLE_OPT_KEY)
    hiveSyncConfig.hiveUser = parameters(HIVE_USER_OPT_KEY)
    hiveSyncConfig.hivePass = parameters(HIVE_PASS_OPT_KEY)
    hiveSyncConfig.jdbcUrl = parameters(HIVE_URL_OPT_KEY)
    hiveSyncConfig.partitionFields =
      ListBuffer(parameters(HIVE_PARTITION_FIELDS_OPT_KEY).split(",").map(_.trim).toList: _*)
    hiveSyncConfig.partitionValueExtractorClass = parameters(HIVE_PARTITION_EXTRACTOR_CLASS_OPT_KEY)
    hiveSyncConfig
  }
  override def shortName(): String = "hoodie"
}
