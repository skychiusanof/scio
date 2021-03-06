/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.spotify.scio.sql

import com.spotify.scio.values.SCollection
import com.spotify.scio.schemas.Schema
import org.apache.beam.sdk.values.TupleTag

import scala.language.experimental.macros
import scala.language.existentials
import scala.reflect.macros.whitebox

trait SQLBuilder {
  def as[B: Schema]: SCollection[B]
}

object SQLBuilder {
  private[sql] def apply[A](
    q: String,
    ref: SCollectionRef[A],
    tag: TupleTag[A],
    udfs: List[Udf]
  ): SQLBuilder = new SQLBuilder {
    def as[B: Schema] =
      Sql
        .from(ref.coll)(ref.schema)
        .queryAs(new Query[ref._A, B](q, tag, udfs))
  }

  private[sql] def apply[A0, A1](
    q: String,
    ref0: SCollectionRef[A0],
    ref1: SCollectionRef[A1],
    tag0: TupleTag[A0],
    tag1: TupleTag[A1],
    udfs: List[Udf]
  ): SQLBuilder = new SQLBuilder {
    def as[B: Schema] =
      Sql
        .from(ref0.coll, ref1.coll)(ref0.schema, ref1.schema)
        .queryAs(new Query2[ref0._A, ref1._A, B](q, tag0, tag1, udfs))
  }
}

sealed trait SqlParam

final case class SCollectionRef[A: Schema](coll: SCollection[A]) extends SqlParam {
  type _A = A
  val schema = Schema[A]
}

final case class UdfRef(udf: Udf) extends SqlParam

final class SqlInterpolator(private val sc: StringContext) extends AnyVal {

  private def paramToString(
    tags: Map[String, (SCollectionRef[_], TupleTag[_])]
  )(p: SqlParam): String =
    p match {
      case SCollectionRef(scoll) =>
        tags(scoll.name)._2.getId
      case UdfRef(udf) =>
        udf.fnName
    }

  def sql(p0: SqlParam, ps: SqlParam*): SQLBuilder = {
    val params = p0 :: ps.toList

    val tags =
      params.zipWithIndex.collect {
        case (ref @ SCollectionRef(scoll), i) =>
          (scoll.name, (ref, new TupleTag[ref._A](s"SCOLLECTION_$i")))
      }.toMap

    val udfs = params.collect { case UdfRef(u) => u }
    val strings = sc.parts.iterator
    val expressions = params.iterator
    val toString = paramToString(tags) _

    val expr = expressions.map(toString)
    val q =
      strings.zipAll(expr, "", "").foldLeft("") { case (a, (x, y)) => s"${a}${x} ${y}" }

    tags.values.toList match {
      case (ref, tag) :: Nil =>
        SQLBuilder[ref._A](q, ref, tag, udfs)
      case (ref0, tag0) :: (ref1, tag1) :: Nil =>
        SQLBuilder[ref0._A, ref1._A](q, ref0, ref1, tag0, tag1, udfs)
      case ts =>
        throw new IllegalArgumentException(
          "sql interpolation only support JOIN on up to 2 unique " +
            s"SCollections, found ${ts.length}"
        )
    }
  }

  def tsql(ps: Any*): SQLBuilder =
    macro SqlInterpolatorMacro.builder
}

private abstract class SqlInterpolatorMacroHelpers {
  val ctx: whitebox.Context
  import ctx.universe._

  def partsFromContext: List[Tree] = {
    ctx.prefix.tree match {
      case Apply(_, Apply(_, xs: List[_]) :: Nil) => xs
      case tree =>
        ctx.abort(
          ctx.enclosingPosition,
          s"Implementation error. Expected tsql string interpolation, found $tree"
        )
    }
  }

  def buildSQLString(parts: List[Tree], tags: List[String]): String = {
    val ps2 =
      parts.map {
        case Literal(Constant(s: String)) => s
        case tree =>
          ctx.abort(
            ctx.enclosingPosition,
            s"Implementation error. Expected Literal(Constant(...)), found $tree"
          )
      }

    ps2
      .zipAll(tags, "", "")
      .foldLeft("") { case (a, (x, y)) => s"${a}${x} ${y}" }
  }

  def inferImplicitSchemas[B: ctx.WeakTypeTag](
    wttA: Type
  ): (ctx.Tree, ctx.Tree, Schema[_], Schema[_]) = {
    val wttB = ctx.weakTypeTag[B]

    val needA = ctx.typecheck(tq"_root_.com.spotify.scio.schemas.Schema[$wttA]", ctx.TYPEmode).tpe
    val needB = ctx.typecheck(tq"_root_.com.spotify.scio.schemas.Schema[$wttB]", ctx.TYPEmode).tpe

    val sa = ctx.inferImplicitValue(needA)
    val sb = ctx.inferImplicitValue(needB)

    val schemaATree = ctx.untypecheck(sa.duplicate)
    val schemaBTree = ctx.untypecheck(sb.duplicate)

    val (scha, schab) =
      ctx.eval(ctx.Expr[(Schema[_], Schema[_])](q"($schemaATree, $schemaBTree)"))
    (sa, sb, scha, schab)

  }

  def inferImplicitSchemas[C: ctx.WeakTypeTag](
    wttA: Type,
    wttB: Type
  ): (ctx.Tree, ctx.Tree, ctx.Tree, Schema[_], Schema[_], Schema[_]) = {
    val wttC = ctx.weakTypeTag[C]

    val needA = ctx.typecheck(tq"_root_.com.spotify.scio.schemas.Schema[$wttA]", ctx.TYPEmode).tpe
    val needB = ctx.typecheck(tq"_root_.com.spotify.scio.schemas.Schema[$wttB]", ctx.TYPEmode).tpe
    val needC = ctx.typecheck(tq"_root_.com.spotify.scio.schemas.Schema[$wttC]", ctx.TYPEmode).tpe

    val sa = ctx.inferImplicitValue(needA)
    val sb = ctx.inferImplicitValue(needB)
    val sc = ctx.inferImplicitValue(needC)

    val schemaATree = ctx.untypecheck(sa.duplicate)
    val schemaBTree = ctx.untypecheck(sb.duplicate)
    val schemaCTree = ctx.untypecheck(sc.duplicate)

    val (scha, schab, schac) =
      ctx.eval(
        ctx
          .Expr[(Schema[_], Schema[_], Schema[_])](q"($schemaATree, $schemaBTree, $schemaCTree)")
      )
    (sa, sb, sc, scha, schab, schac)
  }

  def tagFor(t: Type, lbl: String): Tree =
    q"new _root_.org.apache.beam.sdk.values.TupleTag[$t]($lbl)"
}

object SqlInterpolatorMacro {

  /**
   * This static annotation is used to pass (static) parameters to SqlInterpolatorMacro.expand
   */
  final class SqlParts(parts: List[String], ps: Any*) extends scala.annotation.StaticAnnotation

  def builder(c: whitebox.Context)(ps: c.Expr[Any]*): c.Expr[SQLBuilder] = {
    val h = new { val ctx: c.type = c } with SqlInterpolatorMacroHelpers
    import h._
    import c.universe._

    val parts = partsFromContext

    val className = TypeName(c.freshName("SQLBuilder"))
    val fakeName = TypeName(c.freshName("FakeImpl"))

    // Yo Dawg i herd you like macros...
    //
    // The following tree generates an anonymous class to lazily expand tsqlImpl.
    //
    // It basically acts as curryfication of the macro,
    // where the interpolated String and it's parameters are partially applied
    // while the expected output type (and therefore the expected data schema) stays unapplied.
    // Sadly macro do not allow explicit parameter passing so the following code would be illegal
    // and the macro expansion would fail with: "term macros cannot override abstract methods"
    //   def as[B: Schema]: SCollection[B] =
    //     macro _root_.com.spotify.scio.sql.SqlInterpolatorMacro.expand[B](parts, ps)
    //
    // We workaround the limitation by using a StaticAnnotation to pass static values,
    // as described in: https://stackoverflow.com/a/25219644/2383092
    //
    // It is also illegal for a macro to override abstract methods,
    // which is why an intermediate class $fakeName is introduced.
    // Note that we HAVE TO extend SQLBuilder, otherwise, `tsqlImpl` fails to see the concrete
    // type of B, which also makes the macro expansion fails.
    val tree =
      q"""
        {
          import _root_.com.spotify.scio.values.SCollection
          import _root_.com.spotify.scio.schemas.Schema

          sealed trait $fakeName  extends _root_.com.spotify.scio.sql.SQLBuilder {
            def as[B: Schema]: SCollection[B] = ???
          }

          final class $className extends $fakeName {
            import scala.language.experimental.macros

            @_root_.com.spotify.scio.sql.SqlInterpolatorMacro.SqlParts(List(..$parts),..$ps)
            def as[B: Schema]: SCollection[B] =
              macro _root_.com.spotify.scio.sql.SqlInterpolatorMacro.expand[B]
          }
          new $className
        }
        """

    c.Expr[SQLBuilder](tree)
  }

  def expand[B: c.WeakTypeTag](
    c: whitebox.Context
  )(schB: c.Expr[Schema[B]]): c.Expr[SCollection[B]] = {
    import c.universe._

    val annotationParams =
      c.macroApplication.symbol.annotations
        .filter(_.tree.tpe <:< typeOf[SqlParts])
        .flatMap(_.tree.children.tail)

    if (annotationParams.isEmpty)
      c.abort(c.enclosingPosition, "Annotation body not provided!")

    val ps: List[c.Expr[Any]] =
      annotationParams.tail.map(t => c.Expr[Any](t))

    val parts =
      annotationParams.head match {
        case Apply(TypeApply(Select(Select(_, _), TermName("apply")), _), pas) =>
          pas
        case tree =>
          c.abort(
            c.enclosingPosition,
            s"Failed to extract SQL parts. Expected List(...), found $tree"
          )
      }

    tsqlImpl[B](c)(parts, ps: _*)
  }

  def tsqlImpl[B: c.WeakTypeTag](
    c: whitebox.Context
  )(parts: List[c.Tree], ps: c.Expr[Any]*): c.Expr[SCollection[B]] = {
    val h = new { val ctx: c.type = c } with SqlInterpolatorMacroHelpers
    import h._
    import c.universe._

    val (ss, other) =
      ps.partition(_.actualType.typeSymbol == typeOf[SCollection[Any]].typeSymbol)

    other.headOption.foreach { t =>
      c.abort(
        c.enclosingPosition,
        s"tsql interpolation only support arguments of type SCollection. Found $t"
      )
    }

    val wttB = c.weakTypeTag[B]

    val scs: List[(Tree, Type)] =
      ss.map { p =>
        val a = p.actualType.typeArgs.head
        (p.tree, a)
      }.toList

    val distinctSCollections =
      scs.map {
        case (tree, t) =>
          (tree.symbol, (tree, t))
      }.toMap

    def toSCollectionName(s: Tree) = s.symbol.name.encodedName.toString

    distinctSCollections.values.toList match {
      case (c0, t0) :: Nil =>
        val tag0 = tagFor(t0, Sql.SCollectionTypeName)
        val sql = buildSQLString(parts, scs.map(_ => Sql.SCollectionTypeName))

        val (implA, implB, sIn, sOut) =
          inferImplicitSchemas[B](t0)

        // ... so I put a macro in your macro so you can compile while you compile
        val q = q"_root_.com.spotify.scio.sql.Queries.typed[$t0, $wttB]($sql)"

        c.Expr[SCollection[B]](q"""
            _root_.com.spotify.scio.sql.Sql
                .from($c0)($implA)
                .queryAs($q)($implB)""")
      case (c0, t0) :: (c1, t1) :: Nil =>
        val tag0 = tagFor(t0, toSCollectionName(c0))
        val tag1 = tagFor(t1, toSCollectionName(c1))
        val sql = buildSQLString(parts, scs.map(x => toSCollectionName(x._1)))

        val q = q"_root_.com.spotify.scio.sql.Queries.typed[$t0, $t1, $wttB]($sql, $tag0, $tag1)"

        val (implA, implC, implB, sA, sC, sOut) =
          inferImplicitSchemas[B](t0, t1)

        c.Expr[SCollection[B]](q"""
            _root_.com.spotify.scio.sql.Sql
                .from($c0, $c1)($implA, $implC)
                .queryAs($q)($implB)""")
      case d =>
        val ns = d.map(_._1).mkString(", ")
        c.abort(
          c.enclosingPosition,
          s"BeamSQL can only join up to 2 SCollections, found ${d.size}: $ns"
        )
    }
  }

}
