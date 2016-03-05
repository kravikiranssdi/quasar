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

package quasar.fp.numeric

import eu.timepit.refined.scalacheck.numeric.Bounded
import org.scalacheck.Gen
import org.scalacheck.Gen.Choose

object SafeIntForVectorArbitrary {
  implicit val chooseSafeIntForVector: Choose[SafeIntForVector] = new Choose[SafeIntForVector] {
    def choose(low: SafeIntForVector, high: SafeIntForVector) =
      Gen.choose(low.value, high.value).map(SafeIntForVector.unsafe)
  }

  implicit val SafeIntForVectorBounded: Bounded[SafeIntForVector] =
    Bounded(SafeIntForVector.unsafe(SafeIntForVector.minValue), SafeIntForVector.unsafe(SafeIntForVector.maxValue))
}