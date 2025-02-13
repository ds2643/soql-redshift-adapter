package com.socrata.common.sqlizer

import scala.language.implicitConversions

import com.socrata.prettyprint.prelude._
import com.socrata.soql.analyzer2._
import com.socrata.soql.collection.NonEmptySeq
import com.socrata.soql.types._
import com.socrata.soql.functions.SoQLFunctions._
import com.socrata.soql.functions.Function
import com.socrata.soql.sqlizer._
import SoQLFunctionSqlizerRedshift._

class SoQLFunctionSqlizerRedshift[MT <: MetaTypes with metatypes.SoQLMetaTypesExt with ({
  type ColumnType = SoQLType; type ColumnValue = SoQLValue
})] extends FuncallSqlizer[MT] {
  import com.socrata.soql.functions.SoQLTypeInfo.hasType

  override val exprSqlFactory = new RedshiftExprSqlFactory[MT]

  sealed trait ExprArg
  object ExprArg {
    implicit def int2arg(idx: Int) = IndexArg(idx)
    implicit def sql2arg(sql: Doc) = SqlArg(sql)
  }
  case class IndexArg(idx: Int) extends ExprArg
  case class SqlArg(sql: Doc) extends ExprArg

  implicit class ExprInterpolator(sc: StringContext) {
    def expr(args: ExprArg*): OrdinaryFunctionSqlizer =
      ofs { (f, runtimeArgs, _) =>
        assert(args.length >= f.function.minArity)
        assert(f.function.allParameters.startsWith(runtimeArgs.map(_.typ)))

        val renderedArgs = args.map {
          case IndexArg(idx) => runtimeArgs(idx).compressed.sql
          case SqlArg(sql) => sql
        }

        val sql =
          (Doc(sc.parts.head) +: (renderedArgs, sc.parts.tail).zipped.map { (arg, part) => arg ++ Doc(part) }).hcat
        exprSqlFactory(sql, f)
      }
  }

  def wrap(e: Expr, exprSql: ExprSql, wrapper: String, additionalWrapperArgs: Doc*) =
    exprSqlFactory((exprSql.compressed.sql +: additionalWrapperArgs).funcall(Doc(wrapper)), e)

  def comment(sqlizer: OrdinaryFunctionSqlizer, comment: String) = ofs { (f, args, ctx) =>
    val e = sqlizer(f, args, ctx)
    exprSqlFactory((d"/*" +#+ Doc(comment) +#+ Doc("*/") +#+ e.compressed.sql).parenthesized, f)
  }

  def numericize(sqlizer: OrdinaryFunctionSqlizer) = ofs { (f, args, ctx) =>
    val e = sqlizer(f, args, ctx)
    assert(e.typ == SoQLNumber)
    exprSqlFactory(e.compressed.sql.parenthesized +#+ d":: $numericType", f)
  }

  def numericize(sqlizer: AggregateFunctionSqlizer) = afs { (f, args, filter, ctx) =>
    val e = sqlizer(f, args, filter, ctx)
    assert(e.typ == SoQLNumber)
    exprSqlFactory(e.compressed.sql.parenthesized +#+ d":: $numericType", f)
  }

  def numericize(sqlizer: WindowedFunctionSqlizer) = wfs { (f, args, filter, partitionBy, orderBy, ctx) =>
    val e = sqlizer(f, args, filter, partitionBy, orderBy, ctx)
    assert(e.typ == SoQLNumber)
    exprSqlFactory(e.compressed.sql.parenthesized +#+ d":: $numericType", f)
  }

  def extractDatePart(datePart: String) = ofs { (e, args, _) =>
    assert(args.length == 1)
    val extractFunc = d"extract"
    val preparedArgs = d"$datePart" +#+ d"from" +#+ args.head.compressed.sql
    exprSqlFactory(preparedArgs.funcall(extractFunc).group, e)
  }

  def dateDiffIn(datePart: String, timeZone: String) = ofs { (e, args, _) =>
    assert(args.length == 2)
    val extractFunc = d"datediff"
    val preparedArgs = d"$datePart" +: args.take(2).map(_.compressed.sql +#+ d"at time zone (text '$timeZone')")
    exprSqlFactory(preparedArgs.funcall(extractFunc).group, e)
  }

  def binaryOpCallFunctionPrefix(
      binaryOp: String,
      sqlFunctionName: String,
      prefixArgs: Seq[Doc] = Nil
  ) = {
    val funcName = Doc(sqlFunctionName)
    ofs { (e, args, _) =>
      assert(args.length == 2)
      val lhs = args(0).compressed.sql.parenthesized
      val rhs = args(1).compressed.sql.parenthesized

      val argsSql = (prefixArgs :+ (d"$lhs $binaryOp $rhs"))

      val sql = argsSql.funcall(funcName)

      exprSqlFactory(sql.group, e)
    }
  }

  def sqlizeNormalOrdinaryWithWrapper(name: String, wrapper: String) = ofs { (f, args, ctx) =>
    val exprSql = sqlizeNormalOrdinaryFuncall(name)(f, args, ctx)
    wrap(f, exprSql, wrapper)
  }

  def sqlizeNormalAggregateWithWrapper(name: String, wrapper: String) = afs { (f, args, filter, ctx) =>
    val exprSql = sqlizeNormalAggregateFuncall(name)(f, args, filter, ctx)
    wrap(f, exprSql, wrapper)
  }

  def sqlizeMultiBuffered(name: String) = ofs { (f, args, ctx) =>
    val exprSql = sqlizeNormalOrdinaryFuncall(name)(f, args, ctx)
    wrap(f, wrap(f, exprSql, "st_buffer", d"0.0"), "st_multi")
  }

  def sqlizeCase = ofs { (f, args, _) =>
    assert(f.function.function eq Case)
    assert(args.length % 2 == 0)
    assert(args.length >= 2)

    val lastCase = args.takeRight(2)

    def sqlizeCondition(conditionConsequent: Seq[ExprSql]) = {
      val Seq(condition, consequent) = conditionConsequent
      new CaseClause(condition.compressed.sql, consequent.compressed.sql)
    }

    val caseBuilder =
      lastCase.head.expr match {
        case LiteralValue(SoQLBoolean(true)) if args.length > 2 =>
          val initialCases = args.dropRight(2)
          val otherwise = lastCase(1)
          Right(new CaseBuilder(
            NonEmptySeq.fromSeq(initialCases.grouped(2).map(sqlizeCondition).toSeq).getOrElse {
              throw new Exception("NonEmptySeq failed but I've checked that there are enough cases")
            },
            Some(new ElseClause(otherwise.compressed.sql))
          ))
        case LiteralValue(SoQLBoolean(true)) if args.length == 2 =>
          // We just have a single clause and the guard on it is a
          // constant true, so just return the clause's consequent.
          // This isn't a useful optimization in the real world, but
          // the tests use `case when true then something end` to
          // force "something" to be compressed.
          Left(args(1).compressed)
        case _ =>
          Right(new CaseBuilder(
            NonEmptySeq.fromSeq(args.grouped(2).map(sqlizeCondition).toSeq).getOrElse {
              throw new Exception("NonEmptySeq failed but I've checked that there are enough cases")
            },
            None
          ))
      }

    caseBuilder match {
      case Right(actualCaseBuilder) =>
        exprSqlFactory(actualCaseBuilder.sql, f)
      case Left(exprSql) =>
        exprSql
    }
  }

  def sqlizeIif = ofs { (f, args, _) =>
    assert(f.function.function eq Iif)
    assert(args.length == 3)

    val sql = caseBuilder(args(0).compressed.sql -> args(1).compressed.sql).withElse(args(2).compressed.sql)

    exprSqlFactory(sql.sql, f)
  }

  def sqlizeGetContext = ofs { (f, args, ctx) =>
    // ok, args have already been sqlized if we want to use them, but
    // we only want to use them if it wasn't a literal value.
    assert(f.function.function eq GetContext)
    assert(args.length == 1)
    assert(f.args.length == 1)
    assert(f.typ == SoQLText)

    def nullLiteral =
      ctx.repFor(SoQLText).nullLiteral(NullLiteral[MT](SoQLText)(f.position.asAtomic))
        .withExpr(f)

    f.args(0) match {
      case LiteralValue(SoQLText(key)) =>
        ctx.extraContext.systemContext.get(key) match {
          case Some(value) =>
            ctx.repFor(SoQLText).literal(LiteralValue[MT](SoQLText(value))(f.position.asAtomic))
              .withExpr(f)
          case None =>
            nullLiteral
        }
      case NullLiteral(_) =>
        nullLiteral
      case _ =>
        ctx.abortSqlization(RedshiftSqlizerError.NonLiteralContextParameter(
          f.position.logicalSource,
          f.position.logicalPosition
        ))
    }
  }

  def sqlizeIsEmpty = ofs { (f, args, _) =>
    assert(f.typ == SoQLBoolean)
    assert(args.length == 1)

    val base = Seq(args(0).compressed.sql).funcall(d"st_isempty")

    // That our is_empty function has different null semantics
    // from st_isempty is a little weird and annoying.
    val sql = args(0).expr match {
      case _: LiteralValue | _: Column =>
        // this way we _might_ be able to use an index
        base +#+ d"or" +#+ args(0).compressed.sql.parenthesized +#+ d"is null"
      case _ =>
        // this keeps us from evaluating whatever the non-trivial expr
        // was more than once.
        Seq(base, d"true").funcall(d"coalesce")
    }

    exprSqlFactory(sql, f)
  }

  def sqlizeNegate = {
    val base = sqlizeUnaryOp("-")

    ofs { (f, args, ctx) =>
      assert(args.length == 1)

      args(0).expr match {
        case LiteralValue(SoQLNumber(n)) =>
          // move the negation into the literal
          ctx.repFor(SoQLNumber).literal(LiteralValue[MT](SoQLNumber(n.negate))(new AtomicPositionInfo(
            f.position.logicalSource,
            f.position.functionNamePosition
          ))).withExpr(f)
        case _ =>
          base(f, args, ctx)
      }
    }
  }

  def sqlizeAntinegate = ofs { (_, args, _) =>
    assert(args.length == 1)
    // this operator only exists for symmetry with unary -, so just
    // get rid of it
    args(0)
  }

  // Given an ordinary function sqlizer, returns a new ordinary
  // function sqlizer that upcases all of its text arguments
  def uncased(sqlizer: OrdinaryFunctionSqlizer): OrdinaryFunctionSqlizer =
    ofs { (f, args, ctx) =>
      sqlizer(f, args.map(uncased), ctx)
    }
  def uncased(expr: ExprSql): ExprSql =
    expr.typ match {
      case SoQLText =>
        exprSqlFactory(Seq(expr.compressed.sql).funcall(Doc("upper")).group, expr.expr)
      case _ =>
        expr
    }

  // if one of the args is a multi-geometry, wrap in st_multi (this is
  // used when a function can _sometimes_ turn a multithing into a
  // thing)
  def preserveMulti(sqlizer: OrdinaryFunctionSqlizer): OrdinaryFunctionSqlizer =
    ofs { (f, args, ctx) =>
      val exprSql = sqlizer(f, args, ctx)
      if (args.exists { arg => arg.typ == SoQLMultiPolygon || arg.typ == SoQLMultiLine || arg.typ == SoQLMultiPoint }) {
        exprSqlFactory(Seq(exprSql.compressed.sql).funcall(d"st_multi"), f)
      } else {
        exprSql
      }
    }

  val ordinaryFunctionMap = (
    Seq[(Function[CT], OrdinaryFunctionSqlizer)](
      IsNull -> sqlizeIsNull,
      IsNotNull -> sqlizeIsNotNull,
      Not -> sqlizeUnaryOp("NOT"),
      Between -> sqlizeTrinaryOp("between", "and"),
      NotBetween -> sqlizeTrinaryOp("not between", "and"),
      In -> sqlizeInlike("IN"),
      CaselessOneOf -> uncased(sqlizeInlike("IN")),
      NotIn -> sqlizeInlike("NOT IN"),
      CaselessNotOneOf -> uncased(sqlizeInlike("NOT IN")),
      Eq -> sqlizeEq,
      EqEq -> sqlizeEq,
      CaselessEq -> uncased(sqlizeEq),
      Neq -> sqlizeNeq,
      BangEq -> sqlizeNeq,
      CaselessNe -> uncased(sqlizeNeq),
      And -> sqlizeBinaryOp("AND"),
      Or -> sqlizeBinaryOp("OR"),
      Lt -> sqlizeProvenancedBinaryOp("<"),
      Lte -> sqlizeProvenancedBinaryOp("<="),
      Gt -> sqlizeProvenancedBinaryOp(">"),
      Gte -> sqlizeProvenancedBinaryOp(">="),
      Least -> sqlizeNormalOrdinaryFuncall("least"),
      Greatest -> sqlizeNormalOrdinaryFuncall("greatest"),
      Like -> sqlizeBinaryOp("LIKE"),
      NotLike -> sqlizeBinaryOp("NOT LIKE"),
      Concat -> sqlizeBinaryOp("||"),
      Lower -> sqlizeNormalOrdinaryFuncall("lower"),
      Upper -> sqlizeNormalOrdinaryFuncall("upper"),
      Length -> sqlizeNormalOrdinaryFuncall("length"),
      Replace -> sqlizeNormalOrdinaryFuncall("replace"),
      Trim -> sqlizeNormalOrdinaryFuncall("trim"),
      TrimLeading -> sqlizeNormalOrdinaryFuncall("ltrim"),
      TrimTrailing -> sqlizeNormalOrdinaryFuncall("rtrim"),
      StartsWith -> comment(expr"${1} = left(${0}, length(${1}))", comment = "start_with"),
      CaselessStartsWith -> uncased(comment(expr"${1} = left(${0}, length(${1}))", comment = "start_with")),
      Contains -> comment(expr"position(${1} in ${0}) <> 0", comment = "soql_contains"),
      CaselessContains -> uncased(comment(expr"position(${1} in ${0}) <> 0", comment = "soql_contains")),
      LeftPad -> sqlizeNormalOrdinaryFuncall(
        "lpad",
        castType = idx =>
          idx match {
            case 1 => Some(Doc("int"))
            case _ => None
          }
      ),
      RightPad -> sqlizeNormalOrdinaryFuncall(
        "rpad",
        castType = idx =>
          idx match {
            case 1 => Some(Doc("int"))
            case _ => None
          }
      ),
      Chr -> sqlizeNormalOrdinaryFuncall(
        "chr",
        castType = idx =>
          idx match {
            case 0 => Some(Doc("int"))
            case _ => None
          }
      ),
      Substr2 -> sqlizeNormalOrdinaryFuncall(
        "substring",
        castType = idx =>
          idx match {
            case 1 => Some(Doc("int"))
            case _ => None
          }
      ),
      Substr3 -> sqlizeNormalOrdinaryFuncall(
        "substring",
        castType = idx =>
          idx match {
            case 1 => Some(Doc("int"))
            case 2 => Some(Doc("int"))
            case _ => None
          }
      ),
      SplitPart -> sqlizeNormalOrdinaryFuncall(
        "split_part",
        castType = idx =>
          idx match {
            case 2 => Some(Doc("int"))
            case _ => None
          }
      ),
      UnaryMinus -> sqlizeNegate,
      UnaryPlus -> sqlizeAntinegate,
      BinaryPlus -> sqlizeBinaryOp("+"),
      BinaryMinus -> sqlizeBinaryOp("-"),
      TimesNumNum -> sqlizeBinaryOp("*"),
      TimesDoubleDouble -> sqlizeBinaryOp("*"),
      TimesMoneyNum -> sqlizeBinaryOp("*"),
      TimesNumMoney -> sqlizeBinaryOp("*"),
      DivNumNum -> sqlizeBinaryOp("/"),
      DivDoubleDouble -> sqlizeBinaryOp("/"),
      DivMoneyNum -> sqlizeBinaryOp("/"),
      DivMoneyMoney -> sqlizeBinaryOp("/"),
      ExpNumNum -> sqlizeBinaryOp("^"),
      ExpDoubleDouble -> sqlizeBinaryOp("^"),
      ModNumNum -> sqlizeBinaryOp("%"),
      ModDoubleDouble -> sqlizeBinaryOp("%"),
      ModMoneyNum -> sqlizeBinaryOp("%"),
      ModMoneyMoney -> sqlizeBinaryOp("%"),
      NaturalLog -> sqlizeNormalOrdinaryFuncall("ln"),
      Absolute -> sqlizeNormalOrdinaryFuncall("abs"),
      Ceiling -> sqlizeNormalOrdinaryFuncall("ceil"),
      Floor -> sqlizeNormalOrdinaryFuncall("floor"),
      Round -> comment(expr"round(${0}, ${1} :: int) :: decimal(30, 7)", comment = "soql_round"),
      WidthBucket -> numericize(sqlizeNormalOrdinaryFuncall("width_bucket")),
      SignedMagnitude10 -> numericize(
        comment(expr"(sign(${0}) * length(floor(abs(${0})) :: text))", comment = "soql_signed_magnitude_10")
      ),
      SignedMagnitudeLinear -> numericize(
        comment(
          expr"(case when (${1}) = 1 then floor(${0}) else sign(${0}) * floor(abs(${0})/(${1}) + 1) end)",
          comment = "soql_signed_magnitude_linear"
        )
      ),

      // Timestamps
      ToFloatingTimestamp -> sqlizeBinaryOp("at time zone"),
      FloatingTimeStampTruncYmd -> sqlizeNormalOrdinaryFuncall("date_trunc", prefixArgs = Seq(d"'day'")),
      FloatingTimeStampTruncYm -> sqlizeNormalOrdinaryFuncall("date_trunc", prefixArgs = Seq(d"'month'")),
      FloatingTimeStampTruncY -> sqlizeNormalOrdinaryFuncall("date_trunc", prefixArgs = Seq(d"'year'")),
      FixedTimeStampZTruncYmd -> sqlizeNormalOrdinaryFuncall("date_trunc", prefixArgs = Seq(d"'day'")),
      FixedTimeStampZTruncYm -> sqlizeNormalOrdinaryFuncall("date_trunc", prefixArgs = Seq(d"'month'")),
      FixedTimeStampZTruncY -> sqlizeNormalOrdinaryFuncall("date_trunc", prefixArgs = Seq(d"'year'")),
      FixedTimeStampTruncYmdAtTimeZone -> binaryOpCallFunctionPrefix(
        "at time zone",
        "date_trunc",
        prefixArgs = Seq(d"'day'")
      ),
      FixedTimeStampTruncYmAtTimeZone -> binaryOpCallFunctionPrefix(
        "at time zone",
        "date_trunc",
        prefixArgs = Seq(d"'month'")
      ),
      FixedTimeStampTruncYAtTimeZone -> binaryOpCallFunctionPrefix(
        "at time zone",
        "date_trunc",
        prefixArgs = Seq(d"'year'")
      ),
      FloatingTimeStampExtractY -> extractDatePart("year"),
      FloatingTimeStampExtractM -> extractDatePart("month"),
      FloatingTimeStampExtractD -> extractDatePart("day"),
      FloatingTimeStampExtractHh -> extractDatePart("hour"),
      FloatingTimeStampExtractMm -> extractDatePart("minute"),
      FloatingTimeStampExtractSs -> extractDatePart("second"),
      FloatingTimeStampExtractDow -> extractDatePart("dayofweek"),
      FloatingTimeStampExtractWoy -> extractDatePart("week"),
      FloatingTimestampExtractIsoY -> extractDatePart("year"),
      EpochSeconds -> extractDatePart("epoch"),
      TimeStampDiffD -> dateDiffIn("day", "UTC"),
      TimeStampAdd -> sqlizeBinaryOp("+"), // Uses a func call ... date_add(...)
      TimeStampPlus -> sqlizeBinaryOp("+"), // Uses an operator ... + ...
      TimeStampMinus -> sqlizeBinaryOp("-"),
      GetUtcDate -> expr"current_date at time zone 'UTC'",

      // Geo-casts
      //      st_geomfromtext is the only function in redshift for text to geom conversion - geom subtypes are not verified through that function
      TextToPoint -> sqlizeNormalOrdinaryFuncall("st_geomfromtext", suffixArgs = Seq(Geo.defaultSRIDLiteral)),
      TextToMultiPoint -> sqlizeNormalOrdinaryFuncall("st_geomfromtext", suffixArgs = Seq(Geo.defaultSRIDLiteral)),
      TextToLine -> sqlizeNormalOrdinaryFuncall("st_geomfromtext", suffixArgs = Seq(Geo.defaultSRIDLiteral)),
      TextToMultiLine -> sqlizeNormalOrdinaryFuncall("st_geomfromtext", suffixArgs = Seq(Geo.defaultSRIDLiteral)),
      TextToPolygon -> sqlizeNormalOrdinaryFuncall("st_geomfromtext", suffixArgs = Seq(Geo.defaultSRIDLiteral)),
      TextToMultiPolygon -> sqlizeNormalOrdinaryFuncall("st_geomfromtext", suffixArgs = Seq(Geo.defaultSRIDLiteral)),

      // Geo
      Union2Pt -> sqlizeNormalOrdinaryWithWrapper("st_union", "st_multi"),
      Union2Poly -> sqlizeNormalOrdinaryWithWrapper("st_union", "st_multi"),
      GeoMultiPolygonFromMultiPolygon -> sqlizeNormalOrdinaryFuncall("st_multi"),
      GeoMultiLineFromMultiLine -> sqlizeNormalOrdinaryFuncall("st_multi"),
      GeoMultiPointFromMultiPoint -> sqlizeNormalOrdinaryFuncall("st_multi"),
      GeoMultiPolygonFromPolygon -> sqlizeNormalOrdinaryFuncall("st_multi"),
      GeoMultiLineFromLine -> sqlizeNormalOrdinaryFuncall("st_multi"),
      GeoMultiPointFromPoint -> sqlizeNormalOrdinaryFuncall("st_multi"),
//    the next 2 functions only work for geometry points, all other types produce an error
      PointToLatitude -> numericize(sqlizeNormalOrdinaryFuncall("st_y")),
      PointToLongitude -> numericize(sqlizeNormalOrdinaryFuncall("st_x")),
      MakePoint -> expr"st_setsrid(st_point(${1}, ${0}), ${Geo.defaultSRIDLiteral})",
      NumberOfPoints -> numericize(sqlizeNormalOrdinaryFuncall("st_npoints")),
      Crosses -> sqlizeNormalOrdinaryFuncall("st_crosses"),
      Intersects -> sqlizeNormalOrdinaryFuncall("st_intersects"),
      Intersection -> sqlizeMultiBuffered("st_intersection"),
      WithinPolygon -> sqlizeNormalOrdinaryFuncall("st_within"),
      WithinBox -> comment(
        expr"st_contains(st_makeenvelope(${2} :: DOUBLE PRECISION, ${3} :: DOUBLE PRECISION, ${4} :: DOUBLE PRECISION, ${1} :: DOUBLE PRECISION), ${0})",
        comment = "within_box"
      ),
      IsEmpty -> sqlizeIsEmpty,
      Simplify -> preserveMulti(sqlizeNormalOrdinaryFuncall("st_simplify")),
      Area -> comment(expr"st_area(${0} :: geography) :: decimal(30, 7)", comment = "soql_area"),
      // DinstanceInMeters only works for points; for other geography or geometry datatypes it would produce an error
      DistanceInMeters -> comment(
        expr"st_distance(${0} :: geography, ${1} :: geography) :: decimal(30, 7)",
        comment = "soql_distance_in_meters"
      ),
      VisibleAt -> comment(
        expr"(not st_isempty(${0})) AND (st_geometrytype(${0}) = 'ST_Point' OR st_geometrytype(${0}) = 'ST_MultiPoint' OR (ST_XMax(${0}) - ST_XMin(${0})) >= ${1} OR (ST_YMax(${0}) - ST_YMin(${0})) >= ${1})",
        comment = "soql_visible_at"
      ),
      ConvexHull -> sqlizeMultiBuffered("st_convexhull"),
      // CuratedRegionTest is implemented slightly different than that in soql-postgres-adapter:
      // in case of not st_valid(geom) -> 'invalid geometry type' instead of st_isvalidreason(geom) as it's not supported in redshift
      CuratedRegionTest -> comment(
        expr"case when st_npoints(${0}) > ${1} then 'too complex' when st_xmin(${0}) < -180 or st_xmax(${0}) > 180 or st_ymin(${0}) < -90 or st_ymax(${0}) > 90 then 'out of bounds' when not st_isvalid(${0}) then 'invalid geography data' when ${0} is null then 'empty' end",
        comment = "soql_curated_region_test"
      ),

      // conditional
      Nullif -> sqlizeNormalOrdinaryFuncall("nullif"),
      Coalesce -> sqlizeNormalOrdinaryFuncall("coalesce"),
      Case -> sqlizeCase,
      Iif -> sqlizeIif,

      // magical
      GetContext -> sqlizeGetContext,

      // simple casts
      TextToBool -> comment(expr"(case when lower(${0}) = 'true' then true else false end)", comment = "TextToBool"),
//      BoolToText -> sqlizeCast("text"),
      TextToNumber -> sqlizeCast("decimal(30, 7)"),
      NumberToText -> sqlizeCast("text"),
      TextToFixedTimestamp -> sqlizeCast("timestamp with time zone"),
      TextToFloatingTimestamp -> sqlizeCast("timestamp without time zone"),
      TextToBlob -> sqlizeTypechangingIdentityCast,
      TextToPhoto -> sqlizeTypechangingIdentityCast
    ) ++ castIdentities.map { f =>
      f -> sqlizeIdentity _
    }
  ).map { case (f, sqlizer) =>
    f.identity -> sqlizer
  }.toMap

  // count_distinct is a separate function for legacy reasons; rewrite it into count(distinct ...)
  def sqlizeCountDistinct(
      e: AggregateFunctionCall,
      args: Seq[ExprSql],
      filter: Option[ExprSql],
      ctx: DynamicContext) = {
    sqlizeNormalAggregateFuncall("count")(e.copy(distinct = true)(e.position), args, filter, ctx)
  }

  def sqlizeMedianAgg(aggFunc: String, percentileFunc: String) = {
    val aggFuncSqlizer = sqlizeNormalAggregateFuncall(aggFunc)
    val percentileFuncName = Doc(percentileFunc)
    afs { (f, args, filter, ctx) =>
      if (f.distinct) {
        aggFuncSqlizer(f, args, filter, ctx)
      } else { // this is faster but there's no way to specify "distinct" with it
        val baseSql =
          (percentileFuncName ++ d"(.50) within group (" ++ Doc.lineCat ++ d"order by" +#+ args(0).compressed.sql).nest(
            2
          ) ++ Doc.lineCat ++ d")"
        exprSqlFactory(baseSql ++ sqlizeFilter(filter), f)
      }
    }
  }

  val aggregateFunctionMap = (
    Seq[(Function[CT], AggregateFunctionSqlizer)](
      Max -> sqlizeNormalAggregateFuncall("max", jsonbWorkaround = true),
      Min -> sqlizeNormalAggregateFuncall("min", jsonbWorkaround = true),
      CountStar -> numericize(sqlizeCountStar _),
      Count -> numericize(sqlizeNormalAggregateFuncall("count")),
      CountDistinct -> numericize(sqlizeCountDistinct _),
      Sum -> sqlizeNormalAggregateFuncall("sum"),
      Avg -> sqlizeNormalAggregateFuncall("avg"),
      Median -> sqlizeNormalAggregateFuncall("median"),
      MedianDisc -> sqlizeMedianAgg("median_disc_ulib_agg", "percentile_disc"),
      RegrIntercept -> sqlizeNormalAggregateFuncall("regr_intercept"),
      RegrR2 -> sqlizeNormalAggregateFuncall("regr_r2"),
      RegrSlope -> sqlizeNormalAggregateFuncall("regr_slope"),
      StddevPop -> sqlizeNormalAggregateFuncall("stddev_pop"),
      StddevSamp -> sqlizeNormalAggregateFuncall("stddev_samp"),
      UnionAggPt -> sqlizeNormalAggregateWithWrapper("st_union", "st_multi"),
      UnionAggLine -> sqlizeNormalAggregateWithWrapper("st_union", "st_multi"),
      UnionAggPoly -> sqlizeNormalAggregateWithWrapper("st_union", "st_multi"),
      Extent -> sqlizeNormalAggregateWithWrapper("st_extent", "st_multi")
    )
  ).map { case (f, sqlizer) =>
    f.identity -> sqlizer
  }.toMap

  def sqlizeLeadLag(name: String) = {
    val sqlizer = sqlizeNormalWindowedFuncall(name)

    wfs { (e, args, filter, partitionBy, orderBy, ctx) =>
      val mungedArgs = args.zipWithIndex.map { case (arg, i) =>
        if (i == 1) {
          assert(arg.typ == SoQLNumber)
          // Bit of a lie; this is no longer actually a SoQLNumber,
          // but we'll just be passing it into lead or lag, so it's
          // fine
          exprSqlFactory(d"(" ++ arg.compressed.sql ++ d") :: int", arg.expr)
        } else {
          arg
        }
      }
      sqlizer(e, mungedArgs, filter, partitionBy, orderBy, ctx)
    }
  }

  def sqlizeNtile(name: String) = {
    val sqlizer = sqlizeNormalWindowedFuncall(name)

    wfs { (e, args, filter, partitionBy, orderBy, ctx) =>
      val mungedArgs = args.zipWithIndex.map { case (arg, i) =>
        if (i == 0) {
          assert(arg.typ == SoQLNumber)
          exprSqlFactory(d"(" ++ arg.compressed.sql ++ d") :: int", arg.expr)
        } else {
          arg
        }
      }
      sqlizer(e, mungedArgs, filter, partitionBy, orderBy, ctx)
    }
  }

  val windowedFunctionMap = (
    Seq[(Function[CT], WindowedFunctionSqlizer)](
      RowNumber -> sqlizeNormalWindowedFuncall("row_number"),
      Rank -> sqlizeNormalWindowedFuncall("rank"),
      DenseRank -> sqlizeNormalWindowedFuncall("dense_rank"),
      FirstValue -> sqlizeNormalWindowedFuncall("first_value"),
      LastValue -> sqlizeNormalWindowedFuncall("last_value"),
      Lead -> sqlizeLeadLag("lead"),
      LeadOffset -> sqlizeLeadLag("lead"),
      Lag -> sqlizeLeadLag("lag"),
      LagOffset -> sqlizeLeadLag("lag"),
      Ntile -> sqlizeNtile("ntile"),

      // aggregate functions, used in a windowed way
      Max -> sqlizeNormalWindowedFuncall("max", jsonbWorkaround = true),
      Min -> sqlizeNormalWindowedFuncall("min", jsonbWorkaround = true),
      CountStar -> numericize(sqlizeCountStarWindowed _),
      Count -> numericize(sqlizeNormalWindowedFuncall("count")),
      // count distinct is not an aggregatable function
      Sum -> sqlizeNormalWindowedFuncall("sum"),
      Avg -> sqlizeNormalWindowedFuncall("avg"),
      Median -> sqlizeNormalWindowedFuncall("median"),
      StddevPop -> sqlizeNormalWindowedFuncall("stddev_pop"),
      StddevSamp -> sqlizeNormalWindowedFuncall("stddev_samp")
    )
  ).map { case (f, sqlizer) =>
    f.identity -> sqlizer
  }.toMap

  override def sqlizeOrdinaryFunction(
      e: FunctionCall,
      args: Seq[ExprSql],
      ctx: DynamicContext
  ): ExprSql = {
    assert(!e.function.isAggregate)
    assert(!e.function.needsWindow)
    ordinaryFunctionMap(e.function.function.identity)(e, args, ctx)
  }

  override def sqlizeAggregateFunction(
      e: AggregateFunctionCall,
      args: Seq[ExprSql],
      filter: Option[ExprSql],
      ctx: DynamicContext
  ): ExprSql = {
    assert(e.function.isAggregate)
    assert(!e.function.needsWindow)
    aggregateFunctionMap(e.function.function.identity)(e, args, filter, ctx)
  }

  def sqlizeWindowedFunction(
      e: WindowedFunctionCall,
      args: Seq[ExprSql],
      filter: Option[ExprSql],
      partitionBy: Seq[ExprSql],
      orderBy: Seq[OrderBySql],
      ctx: DynamicContext
  ): ExprSql = {
    // either the function is a window function or it's an aggregate
    // function being used in a windowed way.
    assert(e.function.needsWindow || e.function.isAggregate)
    windowedFunctionMap(e.function.function.identity)(e, args, filter, partitionBy, orderBy, ctx)
  }

}

object SoQLFunctionSqlizerRedshift {
  val numericType = "decimal(30, 7)"
}
