package ygg.tests

import ygg._, common._, table._, trans._
import SampleData._

class DistinctSpec extends ColumnarTableQspec {
  "in distinct" >> {
    "be the identity on tables with no duplicate rows"                            in testDistinctIdentity
    "peform properly when the same row appears inside two different slices"       in testDistinctAcrossSlices
    "peform properly again when the same row appears inside two different slices" in testDistinctAcrossSlices2
    "have no duplicate rows"                                                      in testDistinct
  }

  def testDistinctIdentity = {
    implicit val gen: Arbitrary[SampleData] = sort(distinct(sample(schema)))

    prop { (sample: SampleData) =>
      val table  = fromSample(sample)
      toJsonSeq(table distinct root) must_=== sample.data
    }
  }

  def testDistinctAcrossSlices = {
    val data = jsonMany"""
      {"key":[1,1],"value":{}}
      {"key":[1,1],"value":{}}
      {"key":[2,1],"value":{}}
      {"key":[2,2],"value":{}}
      {"key":[1,2],"value":{"em":[{"fbk":-1,"l":210574764564691780},[[],""]],"fzz":false,"z3y":[{"o":[],"tv":false,"wd":null},{"in0":[],"sry":{}}]}}
      {"key":[1,2],"value":{"em":[{"fbk":-1,"l":210574764564691780},[[],""]],"fzz":false,"z3y":[{"o":[],"tv":false,"wd":null},{"in0":[],"sry":{}}]}}
    """
    val table = fromJson(data, maxBlockSize = 5)
    toJsonSeq(table distinct root) must_=== data.distinct.toStream
  }

  def testDistinctAcrossSlices2 = {
    val data = jsonMany"""
      {"key":[1.0,1.0],"value":{"elxk7vv":-8.988465674311579E+307}}
      {"key":[1.0,1.0],"value":{"elxk7vv":-8.988465674311579E+307}}
      {"key":[2.0,4.0],"value":{"elxk7vv":-6.465000919622952E+307}}
      {"key":[4.0,3.0],"value":{"elxk7vv":-2.2425006462798597E+307}}
      {"key":[5.0,8.0],"value":{"elxk7vv":-1.0}}
      {"key":[5.0,8.0],"value":{"elxk7vv":-1.0}}
      {"key":[3.0,1.0],"value":[[]]}
      {"key":[3.0,8.0],"value":[[]]}
      {"key":[6.0,7.0],"value":[[]]}
      {"key":[7.0,2.0],"value":[[]]}
      {"key":[8.0,1.0],"value":[[]]}
      {"key":[8.0,1.0],"value":[[]]}
      {"key":[8.0,4.0],"value":[[]]}
    """
    val table = fromJson(data, maxBlockSize = 5)

    toJsonSeq(table distinct root) must_=== data.distinct
  }

  def testDistinct = {
    implicit val gen = sort(duplicateRows(sample(schema)))
    prop { (sample: SampleData) =>
      val table = fromSample(sample)
      toJsonSeq(table distinct root) must_=== sample.data.distinct
    }
  }
}
