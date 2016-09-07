/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.marklogic

import quasar.Predef._
import quasar.NameGenerator

import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Uri
import scalaz._
import scalaz.std.string._
import scalaz.std.iterable._
import scalaz.std.tuple._
import scalaz.syntax.foldable._
import scalaz.syntax.std.option._

package object xquery {

  type XPath = String

  type Prologs = ISet[Prolog]
  type PrologW[F[_]] = MonadTell[F, Prologs]

  sealed abstract class SortDirection

  object SortDirection {

    case object Descending extends SortDirection
    case object Ascending  extends SortDirection

    def fromQScript(s: quasar.qscript.SortDir): SortDirection = s match {
      case quasar.qscript.SortDir.Ascending  => Ascending
      case quasar.qscript.SortDir.Descending => Descending
    }
  }

  final case class ParamName(value: xml.QName) {
    def as(tpe: SequenceType): FunctionParam = FunctionParam(this, tpe)
    def xqy: XQuery = XQuery(s"$$${value}")
  }

  object ParamName {
    implicit val order: Order[ParamName] =
      Order.orderBy(_.value)

    implicit val show: Show[ParamName] =
      Show.shows(_.xqy.toString)
  }

  final case class SequenceType(value: String) extends scala.AnyVal

  object SequenceType {
    val Top: SequenceType = SequenceType("item()*")

    implicit val order: Order[SequenceType] =
      Order.orderBy(_.value)

    implicit val show: Show[SequenceType] =
      Show.shows(_.value)
  }

  final case class FunctionParam(name: ParamName, tpe: SequenceType) {
    def render: String = s"${name.xqy} as ${tpe.value}"
  }

  object FunctionParam {
    implicit val order: Order[FunctionParam] =
      Order.orderBy(fp => (fp.name, fp.tpe))

    implicit val show: Show[FunctionParam] =
      Show.shows(fp => s"FunctionParam(${fp.render})")
  }

  def asArg(opt: Option[XQuery]): String =
    opt.map(", " + _.toString).orZero

  def declare(fname: xml.QName): FunctionDecl.FunctionDeclDsl =
    FunctionDecl.FunctionDeclDsl(fname)

  def declareLocal(fname: xml.NCName): FunctionDecl.FunctionDeclDsl =
    declare(xml.NSPrefix.local(fname))

  def freshVar[F[_]: NameGenerator: Functor]: F[String] =
    NameGenerator[F].prefixedName("$v")

  def mkSeq[F[_]: Foldable](fa: F[XQuery]): XQuery =
    XQuery(s"(${fa.toList.map(_.toString).intercalate(", ")})")

  def mkSeq_(x: XQuery, xs: XQuery*): XQuery =
    mkSeq(x +: xs)

  def module(prefix: String Refined xml.IsNCName, uri: String Refined Uri, locs: (String Refined Uri)*): ModuleImport =
    ModuleImport(Some(xml.NSPrefix(xml.NCName(prefix))), xml.NSUri(uri), locs.map(xml.NSUri(_)).toIList)

  def namespace(prefix: String Refined xml.IsNCName, uri: String Refined Uri): NamespaceDecl =
    NamespaceDecl(xml.NSPrefix(xml.NCName(prefix)), xml.NSUri(uri))

  def xmlElement(name: String, content: String): String =
    s"<$name>$content</$name>"
}
