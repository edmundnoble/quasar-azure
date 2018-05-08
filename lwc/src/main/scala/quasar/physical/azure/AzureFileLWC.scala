/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.physical.azure

import quasar.fs.FileSystemType
import quasar.fs.mount.ConnectionUri
import quasar.mimir.{LightweightConnector, LightweightFileSystem, SlamDB}
import slamdata.Predef._

import org.http4s.client.blaze.PooledHttp1Client
import org.http4s.Uri

import scalaz._
import scalaz.syntax.either._
import scalaz.concurrent.Task

final class AzureFileLWC() extends LightweightConnector {
  // File share URI
    def init(uri: ConnectionUri): EitherT[Task, String, (LightweightFileSystem, Task[Unit])] =
    EitherT {
      val httpUri = Uri.fromString(uri.value)
      httpUri.fold[Task[(LightweightFileSystem, Task[Unit])]](
        e => Task.fail(new Exception(s"Error when parsing $httpUri as URI:\n$e")),
        { u =>
          val client = PooledHttp1Client()
          Task.now((new AzureFileLWFS(u, client), client.shutdown))
        }
      ).map(_.right[String])
    }
}

object AzureFileDB extends SlamDB {
  val Type = FileSystemType("azurefile")
  val lwc = new AzureFileLWC()
}
