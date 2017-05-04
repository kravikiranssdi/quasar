package quasar.blueeyes.json

import quasar.blueeyes._

import serialization.Extractor
import serialization.Extractor.Error._
import serialization.SerializationImplicits._
import scalaz._, Scalaz._
import Validation.fromTryCatchNonFatal

object JParser {
  // legacy parsing methods
  @deprecated("Use parseFromString() instead, which returns a Validation", "1.0")
  def parse(str: String): JValue = new StringParser(str).parse()

  def parseUnsafe(str: String): JValue = new StringParser(str).parse()

  type Result[A]   = Validation[Throwable, A]
  type Extract[A]  = Validation[Extractor.Error, A]
  type AsyncResult = (AsyncParse, AsyncParser)

  def parseFromString(str: String): Result[JValue] =
    fromTryCatchNonFatal(new StringParser(str).parse())

  def validateFromString[A: Extractor](str: String) =
    ((thrown _) <-: parseFromString(str)) flatMap { _.validated[A] }

  def parseFromFile(file: File): Result[JValue] =
    fromTryCatchNonFatal(ChannelParser.fromFile(file).parse())

  def validateFromFile[A: Extractor](file: File) =
    ((thrown _) <-: parseFromFile(file)) flatMap { _.validated[A] }

  def parseFromByteBuffer(buf: ByteBuffer): Result[JValue] =
    fromTryCatchNonFatal(new ByteBufferParser(buf).parse())

  def validateFromByteBuffer[A: Extractor](buf: ByteBuffer) =
    ((thrown _) <-: parseFromByteBuffer(buf)) flatMap { _.validated[A] }

  def parseManyFromString(str: String): Result[Seq[JValue]] =
    fromTryCatchNonFatal(new StringParser(str).parseMany())

  def validateManyFromString[A: Extractor](str: String) =
    ((thrown _) <-: parseManyFromString(str)) flatMap { _.toStream.map(_.validated[A]).sequence[Extract, A] }

  def parseManyFromFile(file: File): Result[Seq[JValue]] =
    fromTryCatchNonFatal(ChannelParser.fromFile(file).parseMany())

  def validateManyFromFile[A: Extractor](file: File) =
    ((thrown _) <-: parseManyFromFile(file)) flatMap { _.toStream.map(_.validated[A]).sequence[Extract, A] }

  def parseManyFromByteBuffer(buf: ByteBuffer): Result[Seq[JValue]] =
    fromTryCatchNonFatal(new ByteBufferParser(buf).parseMany())

  def validateManyFromByteBuffer[A: Extractor](buf: ByteBuffer) =
    ((thrown _) <-: parseManyFromByteBuffer(buf)) flatMap { _.toStream.map(_.validated[A]).sequence[Extract, A] }
}
