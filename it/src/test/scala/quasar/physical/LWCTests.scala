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

import slamdata.Predef._

import org.specs2.execute.Result
import org.specs2.mutable.Specification

import pathy._
import quasar.Data
import quasar.contrib.pathy._
import quasar.fs.mount.ConnectionUri
import quasar.mimir.LightweightFileSystem
import scalaz._, Scalaz._
import scalaz.concurrent.Task

final case class TestFS(
  // map from file paths to JSON data;
  // if the file exists but is not parseable
  // it's `None`
  allFiles: Map[AFile, Option[List[Data]]],
  // full directory structure
  allPaths: Map[ADir, List[APath]]
)

final case class MultiCauseException(val msg: Option[String], val causes: List[Throwable]) extends Exception

object LWCTests {

  // This is for fs2/scalaz interop.
  // It may be replaceable with fs2-scalaz.
  implicit val fs2CatchableTask: fs2.util.Catchable[Task] = new fs2.util.Catchable[Task] {
    def attempt[A](fa: Task[A]): Task[fs2.util.Attempt[A]] = fa.attempt.map(_.toEither)
    def flatMap[A, B](a: Task[A])(f: A => Task[B]): Task[B] = a.flatMap(f)
    def pure[A](a: A): Task[A] = Task.now(a)
    def fail[A](err: Throwable): Task[A] = Task.fail(err)
  }

  type T = Task[Unit]

  def runTests[A](success: A, failure: String => A): Task[A] = {
    val testUri: ConnectionUri =
      ConnectionUri("https://slamdata.file.core.windows.net/testdata")

    val testData = TestFS(
      // insert testing filesystem data
      Map((Path.rootDir </> Path.dir("testDataDir") </> Path.file("array.json")) ->
        Some(List(Data.Arr(List(Data.Int(1), Data.Int(2), Data.Int(3)))))),
      // insert testing filesystem paths
      Map((Path.rootDir </> Path.dir("testDataDir")) ->
        List(Path.rootDir </> Path.dir("testDataDir") </> Path.file("array.json")))
    )

    def makeLwfs: Task[LightweightFileSystem] =
      AzureFileDB.lwc.init(testUri).run.flatMap {
        case \/-((r, _)) => Task.now(r)
        case -\/(err) => Task.fail(new Exception(s"Failed to initialize LWC: $err"))
      }

    (for {
      lwfs <- makeLwfs
      runTest = testLaws("My Connector", lwfs, testData)
      a <- gather(List(runTest), none).attempt.flatMap {
        case -\/(ex) =>
          Task.delay(failure(showThrowable(ex, 0)))
        case \/-(_) =>
          Task.delay(success)
      }
    } yield a)
  }

  def makeTestFS(lwfs: LightweightFileSystem): Task[TestFS] = {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def findPaths(root: ADir): Task[List[(ADir, List[APath])]] =
      lwfs.children(root).flatMap {
        case None =>
          Task.fail(new Exception(
            s"children call failed on ${Path.posixCodec.printPath(root)}"
          ))
        case Some(childs) =>
          def absolutize(root: ADir, seg: PathSegment): APath =
            root </>
              seg.fold({ case Path.DirName(n)  => Path.dir(n) },
                       { case Path.FileName(n) => Path.file(n) })
          val absoluteChilds = childs.map(absolutize(root, _)).toList
          val newPair = (root, absoluteChilds)
          val recChilds = absoluteChilds.map(Path.refineType).toList.collect {
            case -\/(dir) => dir
          }
          gather(recChilds.map(findPaths), None)
            .map(chs => newPair :: chs.join)
      }

    def readFiles(paths: Map[ADir, List[APath]]): Task[List[(AFile, Option[List[Data]])]] = {
      val files =
        paths.values
          .flatMap(x => x)
          .flatMap(
            Path.refineType(_) match {
              case \/-(f) =>
                f :: Nil
              case _ =>
                Nil
            }
          )
          .toList

      gather(files.map(file =>
        lwfs.read(file).attempt.flatMap {
          case -\/(ex) =>
            Task.fail(new Exception(
              s"Exception occurred while reading $file:\n${showThrowable(ex, 0)}"
            ))
          case \/-(None) =>
            Task.fail(new Exception(
              s"File $file is not readable."
            ))
          case \/-(Some(contentStream)) =>
            contentStream.runLog.map(_.toList).attempt.map {
              case -\/(_) => (file, none)
              case \/-(r) => (file, r.some)
            }
        }
      ), none)
    }

    for {
      paths <- gather(List(findPaths(Path.rootDir)), Some("Find paths"))
      pathsMap = paths.join.toMap
      files <- readFiles(pathsMap)
    } yield TestFS(files.toMap, pathsMap)
  }

  def checkFS(expected: TestFS, actual: TestFS): T = {
    val checkSamePaths: T = {
      val extra = actual.allPaths.keySet -- expected.allPaths.keySet
      val missing = expected.allPaths.keySet -- actual.allPaths.keySet
      val missingErr = if (missing.nonEmpty) {
        Task.fail(new Exception(
          s"""Some unexpected files were present on the filesystem:
          |${missing.map("  " + _.shows).mkString("\n")}
          """
        ))
      } else Task.now(())
      val extraErr = if (extra.nonEmpty) {
        Task.fail(new Exception(
          s"""Some unexpected files were present on the filesystem:
          |${missing.map("  " + _.shows).mkString("\n")}
          """
        ))
      } else Task.now(())
      gather(List(missingErr, extraErr), none).void
    }

    val checkSameFileContents: T = {
      val sharedFiles = expected.allFiles.keySet & actual.allFiles.keySet
      gather(sharedFiles.toList.map {
        file =>
          (expected.allFiles(file), actual.allFiles(file)) match {
            case (Some(expectedData), Some(actualData))
            if expectedData === actualData =>
              Task.now(())
            case (None, None) => Task.now(())
            case (Some(_), None) => Task.fail(new Exception(
              s"No valid data was found at $file"
            ))
            case (None, Some(_)) => Task.fail(new Exception(
              s"Unexpectedly valid data was found at $file"
            ))
            case (Some(expectedData), Some(actualData)) =>
              Task.fail(new Exception(
                s"""Incorrect data was present at $file;
                |Actual:
                |${actualData.shows}
                |Expected:
                |${expectedData.shows}
                """.stripMargin.trim
              ))
          }
      }, none).void
    }

    gather(List(checkSamePaths, checkSameFileContents), "Check filesystem".some).void
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def showThrowable(ex: Throwable, indent: Int): String = {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def flatten(th: Throwable): List[Throwable] \/ Throwable = th match {
      case MultiCauseException(Some(msg), causes) =>
        \/-(MultiCauseException(Some(msg), causes.flatMap { c =>
          flatten(c) match {
            case -\/(newCauses) =>
              newCauses
            case \/-(n) =>
              n :: Nil
          }
        }))
      case MultiCauseException(None, causes) =>
        -\/(causes)
      case c =>
        \/-(c)
    }
    val newEx = flatten(ex) match {
      case -\/(causes) =>
        MultiCauseException(None, causes)
      case \/-(th) =>
        th
    }

    val myIndent = "  " * indent
    newEx match {
      case MultiCauseException(msg, causes) =>
        s"""
        |[${msg.fold("")(s"\n$myIndent(" + _ + ")")}
        |${causes.map(showThrowable(_, indent + 1)).intercalate("\n")}
        |]
        """.stripMargin.trim + "\n"
      case ex =>
        myIndent + ex.toString
    }
  }

  def gather[F[_]: Traverse, A](tasks: F[Task[A]], msg: Option[String]): Task[F[A]] = {
    val attempted: Task[F[Throwable \/ A]] = tasks.traverse(_.attempt)
    val validated: Task[Throwable ValidationNel F[A]] = attempted.map(_.traverse(_.validationNel[Throwable]))
    validated.flatMap {
      case Failure(es) =>
        Task.fail(MultiCauseException(msg, es.list.toList))
      case Success(fa) =>
        Task.now(fa)
    }
  }

  def testLaws(name: String, lwfs: LightweightFileSystem, expectedFS: TestFS): T =
    gather(List(existTests(lwfs, expectedFS), for {
      actualFS <- makeTestFS(lwfs)
      _ <- checkFS(expectedFS, actualFS)
    } yield ()), name.some).void

  def existTests(lwfs: LightweightFileSystem, fs: TestFS): T = fs match {
    case TestFS(files, paths) =>
      val allFiles: List[AFile] =
        paths
          .values
          .flatMap(x => x)
          .flatMap {
            Path.refineType(_) match {
              case \/-(f) =>
                f :: Nil
              case _ =>
                Nil
            }
          }
          .toList
      mustExist(allFiles, lwfs)
  }

  def mustExist(paths: List[AFile], lwfs: LightweightFileSystem): T =
    gather(paths.map { file =>
      lwfs.exists(file).flatMap { doesExist =>
        if (!doesExist)
          Task.fail(new Exception(
            s"File $file does not exist"
          ))
        else Task.now(())
      }
    }, "File existence".some).void

}

final class LWCTestSuite extends Specification {
  "test LWC properties" >> {
    LWCTests.runTests[Result](success, failure).unsafePerformSync must not(throwA[Exception])
  }
}
