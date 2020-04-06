package com.databricks.labs.validation

import com.databricks.labs.validation.utils.SparkSessionWrapper
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.{Column, DataFrame, RelationalGroupedDataset, Row}
import org.apache.spark.sql.functions.{
  array, col, collect_list, collect_set,
  explode, expr, lit, struct, sum, when, count
}
import org.apache.spark.sql.types._
import utils.Helpers._

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable

class Validator(ruleSet: RuleSet, detailLvl: Int) extends SparkSessionWrapper {

  import spark.implicits._

  private val boundaryRules = ruleSet.getRules.filter(_.ruleType == "bounds")
  private val categoricalRules = ruleSet.getRules.filter(rule => rule.ruleType == "validNumerics" ||
    rule.ruleType == "validStrings")
  private val dateTimeRules = ruleSet.getRules.filter(_.ruleType == "dateTime")
  private val complexRules = ruleSet.getRules.filter(_.ruleType == "complex")
  private val byCols = ruleSet.getGroupBys map col

  case class Selects(output: Column, select: Column*)

  private def buildValidationsByType(rule: Rule): Column = {
    val nulls = mutable.Map[String, Column](
      "bounds" -> lit(null).cast(ArrayType(DoubleType)).alias("bounds"),
      "validNumerics" -> lit(null).cast(ArrayType(DoubleType)).alias("validNumerics"),
      "validStrings" -> lit(null).cast(ArrayType(StringType)).alias("validStrings"),
      "validDate" -> lit(null).cast(LongType).alias("validDate")
    )
    rule.ruleType match {
      case "bounds" => nulls("bounds") = array(lit(rule.boundaries.lower), lit(rule.boundaries.upper)).alias("bounds")
      case "validNumerics" => nulls("validNumerics") = lit(rule.validNumerics).alias("validNumerics")
      case "validStrings" => nulls("validStrings") = lit(rule.validStrings).alias("validStrings")
    }
    val validationsByType = nulls.toMap.values.toSeq
    struct(
      validationsByType: _*
    ).alias("Validation_Values")
  }

  private def buildOutputStruct(rule: Rule, results: Seq[Column]): Column = {
    struct(
      lit(rule.ruleName).alias("Rule_Name"),
      lit(rule.ruleType).alias("Rule_Type"),
      buildValidationsByType(rule),
      struct(results: _*).alias("Results")
    ).alias("Validation")
  }

  private def simplifyReport(df: DataFrame): DataFrame = {
    val summaryCols = Seq(
      col("Validations.Rule_Name"),
      col("Validations.Rule_Type"),
      col("Validations.Validation_Values"),
      col("Validations.Results.Invalid_Count"),
      col("Validations.Results.Failed")
    )
    if (ruleSet.getGroupBys.isEmpty) {
      df.select(summaryCols: _*)
        .orderBy('Failed.desc, 'Rule_Name)
    } else {
      df.select(byCols ++ summaryCols: _*)
        .orderBy('Failed.desc, 'Rule_Name)
    }
  }

  /**
   * TODO - This function should identify the results of the aggregate functions and update
   * TODO - the input column to be a lit of the calculated aggreate and thus - the rule will no longer
   * TODO - be an aggregate and can be compared to pro
   * TODO - Only when detailLVL is >= 2 as it will add a complete action
   *
   * @return
   */
  private def deriveAggValues(rules: Array[Rule]): Unit = {
    val scopedRules = rules.filter(rule => rule.isAgg && rule.ruleType == "bounds")
    if (!scopedRules.isEmpty) {
      val aggsSelects = scopedRules.map(rule => {
        if (!ruleSet.isGrouped) { // grouped
          val first = rule.inputColumn.cast(DoubleType).alias(rule.ruleName)
          val result = struct(
            lit(rule.ruleName).alias("Rule_Name"),
            col(rule.ruleName).alias("agg_val")
          )
          Selects(result, first)
        } else { // Not Grouped TODO -- Implement
          rule.inputColumn.alias(rule.ruleName) //TMP HOLD
          Selects(lit("1"), lit(2))
        }
      })
      
      val processedDF = ruleSet.getDf
        .select(aggsSelects.map(_.select.head): _*)
        .select(explode(array(aggsSelects.map(_.output): _*)).alias("agg_vals"))
        .select(col("agg_vals.Rule_Name"), col("agg_vals.agg_val"))
      val aggColVals = processedDF.rdd.map(row => (row.getString(0), row.getDouble(1))).collectAsMap()
      scopedRules.foreach(rule => {
        rule.setColumn(lit(aggColVals(rule.ruleName)).alias(rule.ruleName))
        rule.setIsAgg
      })
    }
  }

  private def buildBaseSelects(rules: Array[Rule]): Array[Selects] = {

    if (detailLvl > 1) deriveAggValues(rules)

    // Build base selects
    rules.map(rule => {

      // Results must have Invalid_Count & Failed
      rule.ruleType match {
        case "bounds" =>
          val invalid = rule.inputColumn < rule.boundaries.lower || rule.inputColumn > rule.boundaries.upper
          val failed = when(
            col(rule.ruleName) < rule.boundaries.lower || col(rule.ruleName) > rule.boundaries.upper, true)
            .otherwise(false).alias("Failed")
          val first = if (!rule.isAgg) { // Not Agg
            sum(when(invalid, 1).otherwise(0)).alias(rule.ruleName)
          } else { // Is Agg
            rule.inputColumn.alias(rule.ruleName)
          }
          val results = if (rule.isAgg) {
            Seq(lit(null).cast(LongType).alias("Invalid_Count"), failed)
          } else {
            Seq(col(rule.ruleName).cast(LongType).alias("Invalid_Count"), failed)
          }
          Selects(buildOutputStruct(rule, results), first)
        case x if x == "validNumerics" || x == "validStrings" =>
          val invalid = if (x == "validNumerics") {
            expr(s"size(array_except(${rule.ruleName}," +
              s"array(${rule.validNumerics.mkString("D,")}D)))")
          } else {
            expr(s"size(array_except(${rule.ruleName}," +
              s"array('${rule.validStrings.mkString("','")}')))")
          }
          val failed = when(invalid > 0, true).otherwise(false).alias("Failed")
          // TODO -- Cardinality check and WARNING
          val first = collect_set(rule.inputColumn).alias(rule.ruleName)
          val results = Seq(invalid.cast(LongType).alias("Invalid_Count"), failed)
          Selects(buildOutputStruct(rule, results), first)
        case "validDate" => ???
        case "complex" => ???
      }
    })
  }

  /**
   * TODO - Implement rule handlers for dates accepting
   * Column Type (i.e. current_timestamp and current_date)
   * java.util.Date
   * Validated strings (regex?) to pass into spark and convert to date/ts
   */
  private def validatedateTimeRules: Unit = ???

  /**
   * Are there common complex rule patterns that should be added?
   */
  private def validateComplexRules: Unit = ???

  private[validation] def validate: DataFrame = {

    //    val selects = buildBaseSelects(boundaryRules)
    val selects = buildBaseSelects(boundaryRules) ++ buildBaseSelects(categoricalRules)
    val fullOutput = explode(array(selects.map(_.output): _*)).alias("Validations")
    val summaryDF = if (ruleSet.getGroupBys.isEmpty) {
      ruleSet.getDf
        .select(selects.map(_.select.head): _*)
        .select(fullOutput)
    } else {
      ruleSet.getDf
        .groupBy(byCols: _*)
        .agg(selects.map(_.select.head).head, selects.map(_.select.head).tail: _*)
        .select(byCols :+ fullOutput: _*)
    }
    simplifyReport(summaryDF)
    //    summaryDF
  }

}

object Validator {
  def apply(ruleSet: RuleSet, detailLvl: Int): Validator = new Validator(ruleSet, detailLvl)
}