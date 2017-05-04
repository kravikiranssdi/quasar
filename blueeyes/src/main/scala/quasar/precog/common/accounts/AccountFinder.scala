package quasar.precog.common
package accounts

import quasar.precog.common.security._

import org.slf4s.Logging

import scalaz._
import scalaz.syntax.monad._

trait AccountFinder[M[+ _]] extends Logging { self =>
  def findAccountByAPIKey(apiKey: APIKey): M[Option[AccountId]]

  def findAccountDetailsById(accountId: AccountId): M[Option[AccountDetails]]

  def withM[N[+ _]](implicit t: M ~> N) = new AccountFinder[N] {
    def findAccountByAPIKey(apiKey: APIKey) = t(self.findAccountByAPIKey(apiKey))

    def findAccountDetailsById(accountId: AccountId) = t(self.findAccountDetailsById(accountId))
  }
}

object AccountFinder {
  def Empty[M[+ _]: Monad] = new AccountFinder[M] {
    def findAccountByAPIKey(apiKey: APIKey)          = None.point[M]
    def findAccountDetailsById(accountId: AccountId) = None.point[M]
  }
}
