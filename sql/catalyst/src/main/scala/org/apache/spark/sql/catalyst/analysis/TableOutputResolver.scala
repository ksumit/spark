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

package org.apache.spark.sql.catalyst.analysis

import scala.collection.mutable

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Project}
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.catalyst.util.CharVarcharUtils
import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.StoreAssignmentPolicy
import org.apache.spark.sql.types.{ArrayType, DataType, DecimalType, IntegralType, MapType, StructType}

object TableOutputResolver {
  def resolveOutputColumns(
      tableName: String,
      expected: Seq[Attribute],
      query: LogicalPlan,
      byName: Boolean,
      conf: SQLConf): LogicalPlan = {

    val actualExpectedCols = expected.map { attr =>
      attr.withDataType(CharVarcharUtils.getRawType(attr.metadata).getOrElse(attr.dataType))
    }

    if (actualExpectedCols.size < query.output.size) {
      throw QueryCompilationErrors.cannotWriteTooManyColumnsToTableError(
        tableName, actualExpectedCols, query)
    }

    val errors = new mutable.ArrayBuffer[String]()
    val resolved: Seq[NamedExpression] = if (byName) {
      reorderColumnsByName(query.output, actualExpectedCols, conf, errors += _)
    } else {
      if (actualExpectedCols.size > query.output.size) {
        throw QueryCompilationErrors.cannotWriteNotEnoughColumnsToTableError(
          tableName, actualExpectedCols, query)
      }

      query.output.zip(actualExpectedCols).flatMap {
        case (queryExpr, tableAttr) =>
          checkField(tableAttr, queryExpr, byName, conf, err => errors += err, Seq(tableAttr.name))
      }
    }

    if (errors.nonEmpty) {
      throw QueryCompilationErrors.cannotWriteIncompatibleDataToTableError(tableName, errors.toSeq)
    }

    if (resolved == query.output) {
      query
    } else {
      Project(resolved, query)
    }
  }

  private def reorderColumnsByName(
      inputCols: Seq[NamedExpression],
      expectedCols: Seq[Attribute],
      conf: SQLConf,
      addError: String => Unit,
      colPath: Seq[String] = Nil): Seq[NamedExpression] = {
    val matchedCols = mutable.HashSet.empty[String]
    val reordered = expectedCols.flatMap { expectedCol =>
      val matched = inputCols.filter(col => conf.resolver(col.name, expectedCol.name))
      val newColPath = colPath :+ expectedCol.name
      if (matched.isEmpty) {
        addError(s"Cannot find data for output column '${newColPath.quoted}'")
        None
      } else if (matched.length > 1) {
        addError(s"Ambiguous column name in the input data: '${newColPath.quoted}'")
        None
      } else {
        matchedCols += matched.head.name
        val expectedName = expectedCol.name
        val matchedCol = matched.head match {
          // Save an Alias if we can change the name directly.
          case a: Attribute => a.withName(expectedName)
          case a: Alias => a.withName(expectedName)
          case other => other
        }
        (matchedCol.dataType, expectedCol.dataType) match {
          case (matchedType: StructType, expectedType: StructType) =>
            checkNullability(matchedCol, expectedCol, conf, addError, newColPath)
            resolveStructType(
              matchedCol, matchedType, expectedType, expectedName, conf, addError, newColPath)
          case (matchedType: ArrayType, expectedType: ArrayType) =>
            checkNullability(matchedCol, expectedCol, conf, addError, newColPath)
            resolveArrayType(
              matchedCol, matchedType, expectedType, expectedName, conf, addError, newColPath)
          case (matchedType: MapType, expectedType: MapType) =>
            checkNullability(matchedCol, expectedCol, conf, addError, newColPath)
            resolveMapType(
              matchedCol, matchedType, expectedType, expectedName, conf, addError, newColPath)
          case _ =>
            checkField(expectedCol, matchedCol, byName = true, conf, addError, newColPath)
        }
      }
    }

    if (reordered.length == expectedCols.length) {
      if (matchedCols.size < inputCols.length) {
        val extraCols = inputCols.filterNot(col => matchedCols.contains(col.name))
          .map(col => s"'${col.name}'").mkString(", ")
        addError(s"Cannot write extra fields to struct '${colPath.quoted}': $extraCols")
        Nil
      } else {
        reordered
      }
    } else {
      Nil
    }
  }

  private def checkNullability(
      input: Expression,
      expected: Attribute,
      conf: SQLConf,
      addError: String => Unit,
      colPath: Seq[String]): Unit = {
    if (input.nullable && !expected.nullable &&
      conf.storeAssignmentPolicy != StoreAssignmentPolicy.LEGACY) {
      addError(s"Cannot write nullable values to non-null column '${colPath.quoted}'")
    }
  }

  private def resolveStructType(
      input: NamedExpression,
      inputType: StructType,
      expectedType: StructType,
      expectedName: String,
      conf: SQLConf,
      addError: String => Unit,
      colPath: Seq[String]): Option[NamedExpression] = {
    val fields = inputType.zipWithIndex.map { case (f, i) =>
      Alias(GetStructField(input, i, Some(f.name)), f.name)()
    }
    val reordered = reorderColumnsByName(fields, expectedType.toAttributes, conf, addError, colPath)
    if (reordered.length == expectedType.length) {
      val struct = CreateStruct(reordered)
      val res = if (input.nullable) {
        If(IsNull(input), Literal(null, struct.dataType), struct)
      } else {
        struct
      }
      Some(Alias(res, expectedName)())
    } else {
      None
    }
  }

  private def resolveArrayType(
      input: NamedExpression,
      inputType: ArrayType,
      expectedType: ArrayType,
      expectedName: String,
      conf: SQLConf,
      addError: String => Unit,
      colPath: Seq[String]): Option[NamedExpression] = {
    if (inputType.containsNull && !expectedType.containsNull) {
      addError(s"Cannot write nullable elements to array of non-nulls: '${colPath.quoted}'")
      None
    } else {
      val param = NamedLambdaVariable("element", inputType.elementType, inputType.containsNull)
      val fakeAttr =
        AttributeReference("element", expectedType.elementType, expectedType.containsNull)()
      val res = reorderColumnsByName(Seq(param), Seq(fakeAttr), conf, addError, colPath)
      if (res.length == 1) {
        val func = LambdaFunction(res.head, Seq(param))
        Some(Alias(ArrayTransform(input, func), expectedName)())
      } else {
        None
      }
    }
  }

  private def resolveMapType(
      input: NamedExpression,
      inputType: MapType,
      expectedType: MapType,
      expectedName: String,
      conf: SQLConf,
      addError: String => Unit,
      colPath: Seq[String]): Option[NamedExpression] = {
    if (inputType.valueContainsNull && !expectedType.valueContainsNull) {
      addError(s"Cannot write nullable values to map of non-nulls: '${colPath.quoted}'")
      None
    } else {
      val keyParam = NamedLambdaVariable("key", inputType.keyType, nullable = false)
      val fakeKeyAttr = AttributeReference("key", expectedType.keyType, nullable = false)()
      val resKey = reorderColumnsByName(
        Seq(keyParam), Seq(fakeKeyAttr), conf, addError, colPath)

      val valueParam =
        NamedLambdaVariable("value", inputType.valueType, inputType.valueContainsNull)
      val fakeValueAttr =
        AttributeReference("value", expectedType.valueType, expectedType.valueContainsNull)()
      val resValue = reorderColumnsByName(
        Seq(valueParam), Seq(fakeValueAttr), conf, addError, colPath)

      if (resKey.length == 1 && resValue.length == 1) {
        val keyFunc = LambdaFunction(resKey.head, Seq(keyParam))
        val valueFunc = LambdaFunction(resValue.head, Seq(valueParam))
        val newKeys = ArrayTransform(MapKeys(input), keyFunc)
        val newValues = ArrayTransform(MapValues(input), valueFunc)
        Some(Alias(MapFromArrays(newKeys, newValues), expectedName)())
      } else {
        None
      }
    }
  }

  // For table insertions, capture the overflow errors and show proper message.
  // Without this method, the overflow errors of castings will show hints for turning off ANSI SQL
  // mode, which are not helpful since the behavior is controlled by the store assignment policy.
  def checkCastOverflowInTableInsert(cast: Cast, columnName: String): Expression = {
    if (canCauseCastOverflow(cast)) {
      CheckOverflowInTableInsert(cast, columnName)
    } else {
      cast
    }
  }

  private def containsIntegralOrDecimalType(dt: DataType): Boolean = dt match {
    case _: IntegralType | _: DecimalType => true
    case a: ArrayType => containsIntegralOrDecimalType(a.elementType)
    case m: MapType =>
      containsIntegralOrDecimalType(m.keyType) || containsIntegralOrDecimalType(m.valueType)
    case s: StructType =>
      s.fields.exists(sf => containsIntegralOrDecimalType(sf.dataType))
    case _ => false
  }

  private def canCauseCastOverflow(cast: Cast): Boolean = {
    containsIntegralOrDecimalType(cast.dataType) &&
      !Cast.canUpCast(cast.child.dataType, cast.dataType)
  }

  private def checkField(
      tableAttr: Attribute,
      queryExpr: NamedExpression,
      byName: Boolean,
      conf: SQLConf,
      addError: String => Unit,
      colPath: Seq[String]): Option[NamedExpression] = {

    val attrTypeHasCharVarchar = CharVarcharUtils.hasCharVarchar(tableAttr.dataType)
    val attrTypeWithoutCharVarchar = if (attrTypeHasCharVarchar) {
      CharVarcharUtils.replaceCharVarcharWithString(tableAttr.dataType)
    } else {
      tableAttr.dataType
    }
    val storeAssignmentPolicy = conf.storeAssignmentPolicy
    lazy val outputField = if (DataTypeUtils.sameType(tableAttr.dataType, queryExpr.dataType) &&
      tableAttr.name == queryExpr.name &&
      tableAttr.metadata == queryExpr.metadata) {
      Some(queryExpr)
    } else {
      val casted = cast(queryExpr, attrTypeWithoutCharVarchar, conf, colPath.quoted)
      val exprWithStrLenCheck = if (conf.charVarcharAsString || !attrTypeHasCharVarchar) {
        casted
      } else {
        CharVarcharUtils.stringLengthCheck(casted, tableAttr.dataType)
      }
      // Renaming is needed for handling the following cases like
      // 1) Column names/types do not match, e.g., INSERT INTO TABLE tab1 SELECT 1, 2
      // 2) Target tables have column metadata
      Some(Alias(exprWithStrLenCheck, tableAttr.name)(explicitMetadata = Some(tableAttr.metadata)))
    }

    storeAssignmentPolicy match {
      case StoreAssignmentPolicy.LEGACY =>
        outputField

      case StoreAssignmentPolicy.STRICT | StoreAssignmentPolicy.ANSI =>
        // run the type check first to ensure type errors are present
        val canWrite = DataType.canWrite(
          queryExpr.dataType, attrTypeWithoutCharVarchar, byName, conf.resolver, colPath.quoted,
          storeAssignmentPolicy, addError)
        if (queryExpr.nullable && !tableAttr.nullable) {
          addError(s"Cannot write nullable values to non-null column '${colPath.quoted}'")
          None

        } else if (!canWrite) {
          None

        } else {
          outputField
        }
    }
  }

  private def cast(
      expr: Expression,
      expectedType: DataType,
      conf: SQLConf,
      colName: String): Expression = {

    conf.storeAssignmentPolicy match {
      case StoreAssignmentPolicy.ANSI =>
        val cast = Cast(expr, expectedType, Option(conf.sessionLocalTimeZone), ansiEnabled = true)
        cast.setTagValue(Cast.BY_TABLE_INSERTION, ())
        checkCastOverflowInTableInsert(cast, colName)

      case StoreAssignmentPolicy.LEGACY =>
        Cast(expr, expectedType, Option(conf.sessionLocalTimeZone), ansiEnabled = false)

      case _ =>
        Cast(expr, expectedType, Option(conf.sessionLocalTimeZone))
    }
  }
}
