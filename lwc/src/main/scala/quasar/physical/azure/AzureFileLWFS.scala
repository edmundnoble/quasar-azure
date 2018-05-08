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

package quasar.physical
package azure

import quasar.Data
import quasar.contrib.pathy._
import quasar.mimir.LightweightFileSystem
import slamdata.Predef._

import scala.xml

import fs2.Stream
import io.circe.Json
import org.http4s._
import org.http4s.client._
import org.http4s.scalaxml.{xml => xmlDecoder}
import pathy._
import Path.{DirName, FileName}
import scalaz._, Scalaz._
import scalaz.concurrent.Task

// three functions comprise the LightweightFileSystem API
// (and informally, the lightweight connector API itself)
// since they're all implemented in their own files and
// delegated to, we'll focus on the interface itself.
final class AzureFileLWFS(uri: Uri, client: Client) extends LightweightFileSystem {

  def suffixPathOntoUri(uri: Uri, path: Path[Path.Abs, _, _]): Uri = {
    val segments: List[String] = Path.flatten(
      none,
      scala.sys.error("unreachable"),
      scala.sys.error("unreachable"),
      _.some,
      _.some,
      path
    ).toList.flatMap(_.toList)

    segments.foldLeft(uri)(_ / _)
  }

  // analogue of POSIX "ls" or Windows "dir", used to find
  // the "immediate children" of a directory.
  def children(dir: ADir): Task[Option[Set[PathSegment]]] = {
    val listUri = suffixPathOntoUri(uri, dir) +?
      ("comp", "list") +?
      ("restype", "directory")

    val request = Request(uri = listUri, headers = Headers(
      Header("x-ms-date", java.time.Instant.now.toEpochMilli.shows),
      Header("x-ms-version", "2017-07-29")
    ))

    client.expect[xml.Elem](request).attempt.flatMap {
      case -\/(UnexpectedStatus(Status.NotFound)) =>
        Task.now(none)
      case -\/(ex) =>
        Task.fail(ex)
      case \/-(topLevelElem) =>
        val entries = topLevelElem \\ "EnumerationResults" \\ "Entries"
        val files = (entries \\ "File" \\ "Name").map(n => FileName(n.text).right[DirName])
        val dirs = (entries \\ "Directory" \\ "Name").map(n => DirName(n.text).left[FileName])
        Task.now((files ++ dirs).toSet.some)
    }
  }

    private def streamRequestThroughFs2[A](client: Client, req: Request)(f: Response => Stream[Task, A]): Task[Option[Stream[Task, A]]] = {
      client.open(req).flatMap {
        case DisposableResponse(response, dispose) =>
          response.status match {
            case Status.NotFound => Task.now(none)
            case Status.Ok => Task.now(f(response).onFinalize[Task](dispose).some)
            case s => Task.fail(new Exception(s"Unexpected status $s"))
          }
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def circeJsonToData(json: Json): Data = {
    json.fold(
      Data.Null,
      Data.Bool,
      n => Data.Dec(n.toBigDecimal.getOrElse(n.toDouble)),
      Data.Str,
      js => Data.Arr(js.map(circeJsonToData)(scala.collection.breakOut)),
      js => Data.Obj(ListMap(js.toList.map { case (k, v) => k -> circeJsonToData(v) }: _*))
    )
  }

  def read(file: AFile): Task[Option[Stream[Task, Data]]] = {
    val fileUri = suffixPathOntoUri(uri, file)
    val request = Request(uri = fileUri)
    streamRequestThroughFs2(client, request)(resp =>
      fs2Conversion.processToFs2(resp.body)
        .evalMap(_.decodeUtf8.fold(Task.fail, Task.now))
        .through(parsing.stringArrayParser[Task])
        .map(circeJsonToData)
    )
  }

  def exists(file: AFile): Task[Boolean] = {
    val metadataUri = suffixPathOntoUri(uri, file) +? ("comp", "metadata")
    for {
      status <- client.status(Request(uri = metadataUri))
      res <- status match {
        case Status.NotFound => false.pure[Task]
        case Status.Ok       => true.pure[Task]
        case s               => Task.fail(new Exception(s"Unexpected status: $s"))
      }
    } yield res
  }


}