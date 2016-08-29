package ygg.tests

import scalaz._, Scalaz._, Ordering._
import ygg._, common._, json._
import JsonTestSupport._
import scala.Predef.identity

class JsonASTSpec extends quasar.SequentialQspec {
  "JObjects equal even fields order is different" in {
    implicit val gen = Arbitrary(vectorOfN(15, genJField))
    prop((fs: Vector[JField]) => jobject(fs: _*) must_=== jobject(fs.shuffle: _*))
  }

  "Functor identity" in {
    val identityProp = (json: JValue) => json == (json mapUp identity)
    prop(identityProp)
  }

  "Functor composition" in {
    // Works in scalacheck 1.12.5
    // Fails in ScalaCheck 1.13.0
    //
    // [error] could not find implicit value for parameter arbitrary2: org.scalacheck.Arbitrary[JValue => JValue]
    // [error]     prop(compositionProp)
    // [error]         ^
    // [error] one error found
    val compositionProp = (json: JValue, fa: JValue => JValue, fb: JValue => JValue) => json.mapUp(fb).mapUp(fa) == json.mapUp(fa compose fb)

    prop(compositionProp)
  }

  "Monoid identity" in {
    val identityProp = (json: JValue) => (json ++ JUndefined == json) && (JUndefined ++ json == json)
    prop(identityProp)
  }

  "Monoid associativity" in {
    val assocProp = (x: JValue, y: JValue, z: JValue) => x ++ (y ++ z) == (x ++ y) ++ z
    prop(assocProp)
  }

  "Merge identity" in {
    val identityProp = (json: JValue) => (json merge JUndefined) == json && (JUndefined merge json) == json
    prop(identityProp)
  }

  "Merge idempotency" in {
    val idempotencyProp = (x: JValue) => (x merge x) == x
    prop(idempotencyProp)
  }

  "Diff identity" in {
    val identityProp = (json: JValue) =>
      (json diff JUndefined) == Diff(JUndefined, JUndefined, json) &&
        (JUndefined diff json) == Diff(JUndefined, json, JUndefined)

    prop(identityProp)
  }

  "Diff with self is empty" in {
    val emptyProp = (x: JValue) => (x diff x) == Diff(JUndefined, JUndefined, JUndefined)
    prop(emptyProp)
  }

  "Diff is subset of originals" in {
    val subsetProp = (x: JObject, y: JObject) => {
      val Diff(c, a, _) = x diff y
      y == (y merge (c merge a))
    }
    prop(subsetProp)
  }

  "Diff result is same when fields are reordered" in {
    val reorderProp = (x: JObject) => (x diff reorderFields(x)) == Diff(JUndefined, JUndefined, JUndefined)
    prop(reorderProp)
  }

  "delete" in {
    (json"""{ "foo": { "bar": 1, "baz": 2 } }""" delete JPath("foo.bar")) must_=== Some(json"""{ "foo": { "baz": 2 } }""")
  }

  "Remove all" in {
    val removeAllProp = (x: JValue) =>
      (x remove { _ =>
        true
      }) == JUndefined
    prop(removeAllProp)
  }

  "Remove nothing" in prop((x: JValue) => x remove (_ => false) must_=== x)

  "flattenWithPath includes empty object values" in {
    val test     = jobject(JField("a", jobject()))
    val expected = List((JPath(".a"), jobject()))

    test.flattenWithPath must_== expected
  }

  "flattenWithPath includes empty array values" in {
    val data     = json"""{ "a": [] }"""
    val expected = Vector(JPath(".a") -> jarray())

    data.flattenWithPath must_=== expected
  }

  "flattenWithPath for values produces a single value with the identity path" in {
    val test     = json"1" //JNum(1)
    val expected = Vec(NoJPath -> test)

    test.flattenWithPath must_=== expected
  }

  "flattenWithPath on arrays produces index values" in {
    val test = JArray(JNum(1) :: Nil)

    val expected = List((JPath("[0]"), JNum(1)))

    test.flattenWithPath must_== expected
  }

  "flattenWithPath does not produce JUndefined entries" in {
    val test     = json"""{ "c":2, "fn":[{ "fr":-2 }] }"""
    val expected = List(
      JPath(".c")        -> JNum("2"),
      JPath(".fn[0].fr") -> JNum("-2")
    )

    test.flattenWithPath.sorted must_== expected
  }

  "unflatten is the inverse of flattenWithPath" in {
    val inverse = (value: JValue) => unflatten(value.flattenWithPath) == value

    prop(inverse)
  }

  "Set and retrieve an arbitrary jvalue at an arbitrary path" in {
    runArbitraryPathSpec
  }.flakyTest

  "sort arrays" in {
    val v1 = JParser.parseUnsafe("""[1, 1, 1]""")
    val v2 = JParser.parseUnsafe("""[1, 1, 1]""")

    Order[JValue].order(v1, v2) must_== Ordering.EQ
  }

  "sort objects by key" in {
    val v1: JValue = JObject(
      JField("a", JNum(1)) ::
        JField("b", JNum(2)) ::
          JField("c", JNum(3)) :: Nil
    )

    val v2: JValue = JObject(
      JField("b", JNum(2)) ::
        JField("c", JNum(3)) :: Nil
    )

    (v1 ?|? v2) must_=== LT
  }

  "sort objects by key then value" in {
    val v1: JValue = JObject(
      JField("a", JNum(1)) ::
        JField("b", JNum(2)) ::
          JField("c", JNum(3)) :: Nil
    )

    val v2: JValue = JObject(
      JField("a", JNum(2)) ::
        JField("b", JNum(3)) ::
          JField("c", JNum(4)) :: Nil
    )

    (v1 ?|? v2) must_=== LT
  }

  "sort objects with undefined members" in {
    val v1: JValue = JObject(
      JField("a", JUndefined) ::
        JField("b", JNum(2)) ::
          JField("c", JNum(3)) :: Nil
    )

    val v2: JValue = JObject(
      JField("a", JNum(2)) ::
        JField("b", JNum(3)) ::
          JField("c", JNum(4)) :: Nil
    )

    (v1 ?|? v2) must_=== GT
  }

  "Properly --> subclasses of JValue" in {
    JNumStr("1.234").asNum.isInstanceOf[JNum] mustEqual true
  }

  def runArbitraryPathSpec = {
    val setProp = (jv: JValue, p: JPath, toSet: JValue) => {
      (!badPath(jv, p)) ==> {
        ((p == NoJPath) && (jv.set(p, toSet) == toSet)) ||
        (jv.set(p, toSet).get(p) == toSet)
      }
    }

    val insertProp = (jv: JValue, p: JPath, toSet: JValue) => {
      (!badPath(jv, p) && (jv(p) == JUndefined)) ==> {
        (jv, p.nodes) match {
          case (JObject(_), JPathField(_) +: _) | (JArray(_), JPathIndex(_) +: _) | (JNull | JUndefined, _) =>
            ((p == NoJPath) && (jv.unsafeInsert(p, toSet) == toSet)) ||
              (jv.unsafeInsert(p, toSet).get(p) == toSet)

          case _ =>
            jv.unsafeInsert(p, toSet) must throwA[RuntimeException]
        }
      }
    }

    prop(setProp) && prop(insertProp)
  }

  private def reorderFields(json: JValue) = json mapUp {
    case JObject(xs) => JObject(sciTreeMap(xs.toSeq: _*))
    case x           => x
  }
}
