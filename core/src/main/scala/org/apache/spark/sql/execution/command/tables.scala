/*
 * Copyright 2018 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.command

import com.pingcap.tispark.utils.ReflectionUtil._
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.NoSuchDatabaseException
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.types.{MetadataBuilder, StringType, StructType}
import org.apache.spark.sql.{AnalysisException, Row, SparkSession, TiContext}

import scala.collection.mutable.ArrayBuffer

/**
 * CHECK Spark [[org.apache.spark.sql.execution.command.ShowTablesCommand]]
 *
 * @param tiContext tiContext which contains our catalog info
 * @param delegate original ShowTablesCommand
 */
case class TiShowTablesCommand(tiContext: TiContext, delegate: ShowTablesCommand)
    extends TiCommand(delegate) {
  override def run(sparkSession: SparkSession): Seq[Row] = {
    val db = delegate.databaseName.getOrElse(tiCatalog.getCurrentDatabase)
    if (delegate.partitionSpec.isEmpty) {
      // Show the information of tables.
      val tables =
        delegate.tableIdentifierPattern
          .map(tiCatalog.listTables(db, _))
          .getOrElse(tiCatalog.listTables(db))
      tables.map { tableIdent =>
        val database = tableIdent.database.getOrElse("")
        val tableName = tableIdent.table
        val isTemp = tiCatalog.isTemporaryTable(tableIdent)
        if (delegate.isExtended) {
          val information = tiCatalog.getTempViewOrPermanentTableMetadata(tableIdent).simpleString
          Row(database, tableName, isTemp, s"$information\n")
        } else {
          Row(database, tableName, isTemp)
        }
      }
    } else {
      // Show the information of partitions.
      //
      // Note: tableIdentifierPattern should be non-empty, otherwise a [[ParseException]]
      // should have been thrown by the sql parser.
      val tableIdent = TableIdentifier(delegate.tableIdentifierPattern.get, Some(db))
      val table = tiCatalog.getTableMetadata(tableIdent).identifier
      val partition = tiCatalog.getPartition(tableIdent, delegate.partitionSpec.get)
      val database = table.database.getOrElse("")
      val tableName = table.table
      val isTemp = tiCatalog.isTemporaryTable(table)
      val information = partition.simpleString
      Seq(Row(database, tableName, isTemp, s"$information\n"))
    }
  }
}

/**
 * CHECK Spark [[org.apache.spark.sql.execution.command.DescribeTableCommand]]
 *
 * @param tiContext tiContext which contains our catalog info
 * @param delegate original DescribeTableCommand
 */
case class TiDescribeTablesCommand(tiContext: TiContext, delegate: DescribeTableCommand)
    extends TiCommand(delegate) {
  override val output: Seq[Attribute] = Seq(
    // Column names are based on Hive.
    newAttributeReference(
      "col_name",
      StringType,
      nullable = false,
      new MetadataBuilder().putString("comment", "name of the column").build()
    ),
    newAttributeReference(
      "data_type",
      StringType,
      nullable = false,
      new MetadataBuilder().putString("comment", "data type of the column").build()
    ),
    newAttributeReference(
      "nullable",
      StringType,
      nullable = false,
      new MetadataBuilder().putString("comment", "whether the column is nullable").build()
    ),
    newAttributeReference(
      "comment",
      StringType,
      nullable = true,
      new MetadataBuilder().putString("comment", "comment of the column").build()
    )
  )

  override def run(sparkSession: SparkSession): Seq[Row] = {
    if (tiCatalog.isTemporaryTable(delegate.table)) {
      val schema = tiCatalog.getTempViewOrPermanentTableMetadata(delegate.table).schema
      getDelegateResult(sparkSession, schema)
    } else {
      tiCatalog
        .catalogOf(delegate.table.database)
        .getOrElse(
          throw new NoSuchDatabaseException(
            delegate.table.database.getOrElse(tiCatalog.getCurrentDatabase)
          )
        ) match {
        case _: TiSessionCatalog =>
          val result = new ArrayBuffer[Row]
          if (delegate.partitionSpec.nonEmpty) {
            throw new AnalysisException(
              s"DESC PARTITION is not supported on TiDB table: ${delegate.table.identifier}"
            )
          }
          val metadata = tiCatalog.getTableMetadata(delegate.table)
          describeSchema(metadata.schema, result, header = false)

          if (delegate.isExtended) {
            describeFormattedTableInfo(metadata, result)
          }

          result
        case _: SessionCatalog =>
          val schema = tiCatalog.getTableMetadata(delegate.table).schema
          getDelegateResult(sparkSession, schema)
      }
    }
  }

  private def getDelegateResult(sparkSession: SparkSession, schema: StructType): Seq[Row] = {
    val (delegateResult, extendedResult) =
      delegate.run(sparkSession).zipWithIndex.splitAt(schema.length)
    delegateResult.map(
      (r: (Row, Int)) => Row(r._1(0), r._1(1), schema.fields(r._2).nullable.toString, r._1(2))
    ) ++ extendedResult.map(
      r => Row(r._1(0), r._1(1), "", r._1(2))
    )
  }

  private def describeSchema(schema: StructType,
                             buffer: ArrayBuffer[Row],
                             header: Boolean): Unit = {
    if (header) {
      append(buffer, s"# ${output.head.name}", output(1).name, output(2).name)
    }
    schema.foreach { column =>
      append(
        buffer,
        column.name,
        column.dataType.simpleString,
        column.getComment().orNull,
        column.nullable.toString
      )
    }
  }

  private def append(buffer: ArrayBuffer[Row],
                     column: String,
                     dataType: String,
                     comment: String,
                     nullable: String = ""): Unit =
    buffer += Row(column, dataType, nullable, comment)

  private def describeFormattedTableInfo(table: CatalogTable, buffer: ArrayBuffer[Row]): Unit = {
    // TODO: Add more extended table information.
    append(buffer, "", "", "")
    append(buffer, "# Detailed Table Information", "", "")
    table.toLinkedHashMap.foreach { s =>
      append(buffer, s._1, s._2, "")
    }
  }

}

/**
 * CHECK Spark [[org.apache.spark.sql.execution.command.ShowColumnsCommand]]
 *
 * @param tiContext tiContext which contains our catalog info
 * @param delegate original ShowColumnsCommand
 */
case class TiShowColumnsCommand(tiContext: TiContext, delegate: ShowColumnsCommand)
    extends TiCommand(delegate) {
  override def run(sparkSession: SparkSession): Seq[Row] = {
    val databaseName = delegate.databaseName
    val tableName = delegate.tableName
    val catalog = tiCatalog
    val resolver = sparkSession.sessionState.conf.resolver
    val lookupTable = databaseName match {
      case None => tableName
      case Some(db) if tableName.database.exists(!resolver(_, db)) =>
        throw new AnalysisException(
          s"SHOW COLUMNS with conflicting databases: '$db' != '${tableName.database.get}'"
        )
      case Some(db) => TableIdentifier(tableName.identifier, Some(db))
    }
    val table = catalog.getTempViewOrPermanentTableMetadata(lookupTable)
    table.schema.map { c =>
      Row(c.name)
    }
  }
}

case class TiCreateTableLikeCommand(tiContext: TiContext, delegate: CreateTableLikeCommand)
    extends RunnableCommand {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val catalog = tiContext.tiCatalog
    val sourceTableDesc = catalog.getTempViewOrPermanentTableMetadata(delegate.sourceTable)

    val newProvider = if (sourceTableDesc.tableType == CatalogTableType.VIEW) {
      Some(sparkSession.sessionState.conf.defaultDataSourceName)
    } else if (catalog
                 .catalogOf(sourceTableDesc.identifier.database)
                 .exists(_.isInstanceOf[TiSessionCatalog])) {
      // TODO: use tikv datasource
      Some(sparkSession.sessionState.conf.defaultDataSourceName)
    } else {
      sourceTableDesc.provider
    }

    // If the location is specified, we create an external table internally.
    // Otherwise create a managed table.
    val tblType =
      if (delegate.location.isEmpty) CatalogTableType.MANAGED else CatalogTableType.EXTERNAL

    val newTableDesc =
      CatalogTable(
        identifier = delegate.targetTable,
        tableType = tblType,
        storage = sourceTableDesc.storage
          .copy(locationUri = delegate.location.map(CatalogUtils.stringToURI)),
        schema = sourceTableDesc.schema,
        provider = newProvider,
        partitionColumnNames = sourceTableDesc.partitionColumnNames,
        bucketSpec = sourceTableDesc.bucketSpec
      )
    callSessionCatalogCreateTable(catalog, newTableDesc, delegate.ifNotExists)
    Seq.empty[Row]
  }
}
