/*
 * Copyright 2014–2017 SlamData Inc.
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

package quasar.yggdrasil.execution

import quasar.precog.common.security._
import quasar.yggdrasil.vfs._

import scalaz._

trait Execution[M[+_], A] {
  def executorFor(apiKey: APIKey): EitherT[M, String, QueryExecutor[M, A]]
}

trait Platform[M[+_], Block, A] extends Execution[M, A] with SecureVFSModule[M, Block] {
  def vfs: SecureVFS
}
