package io.chrisdavenport.google.drive

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import edu.eckerd.google.api.services.Scopes._
import edu.eckerd.google.api.services.drive.Drive
import eri.commons.config.SSConfig
import io.chrisdavenport.google.drive.persistence.Tables
import slick.backend.DatabaseConfig
import slick.driver.JdbcProfile

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by davenpcm on 8/5/16.
  */
object SimpleFileTracker extends App with Tables {

  val dbConfig: DatabaseConfig[JdbcProfile] = DatabaseConfig.forConfig("postgres")
  val profile = dbConfig.driver
  implicit val db = dbConfig.db
  import dbConfig.driver.api._

//   Await.result(db.run(googleFiles.schema.create), 10.seconds)
//   Await.result(db.run(googleParents.schema.create), 10.seconds)



//  Await.result(db.run(schema.create), 10.seconds)

  object DriveConfig extends SSConfig("google")

  implicit val drive = Drive(
    DriveConfig.serviceAccount.as[String],
    DriveConfig.impersonatedAccount.as[String],
    //its-drive_owner@eckerd.edu,
    DriveConfig.credentialFilePath.as[String],
    DriveConfig.applicationName.as[String],
    DRIVE
  )

  val files = drive.files.list()

  val seq = files
    .map(f => GoogleFile(f.id.get, f.name, f.mimeType))
    .map(insertOrUpdateFiles)

  val parentSeq = files
    .map(getParentsRecursive(_, drive))
    .map(insertOrDeleteParents)

  def insertOrUpdateFiles(f: GoogleFile)(implicit db: JdbcProfile#Backend#Database, ec: ExecutionContext): Future[Int] = {
    val qTotal = googleFiles.filter(r => r.id === f.id && r.name === f.name && r.mimeType === f.mimeType).exists.result
    val q = googleFiles.filter(_.id === f.id).exists.result
    val existsExactly = for { existsTotal <- db.run(qTotal) } yield existsTotal

    existsExactly.flatMap{
      case true => Future.successful(0)
      case false =>
        val pkExists = for { exists <- db.run(q) } yield exists
        pkExists.flatMap{
          case true =>
            val updateQ = for {rec <- googleFiles if rec.id === f.id} yield (rec.name, rec.mimeType)
            val action = updateQ.update(f.name, f.mimeType)
            db.run(action)
          case false =>
            db.run(googleFiles += f)
        }
    }
  }

  def getParentsRecursive(file: edu.eckerd.google.api.services.drive.models.File,
                          drive: Drive,
                          iter: Int = 0,
                          timeout: Int = 100): edu.eckerd.google.api.services.drive.models.File = {
    val r = new scala.util.Random()
    val t = Try(drive.files.getParents(file))
      .recoverWith {
        case rateLimit: GoogleJsonResponseException if rateLimit.getDetails.getMessage.contains("Rate Limit Exceeded") =>
          Thread.sleep(timeout)
          if (iter > 0) println(s"Recursive Loop Entered For ${file.name} - Iter - $iter")
          Try(getParentsRecursive(file, drive, iter + 1, timeout + r.nextInt(100) ))
      }
    t.get
  }

  def insertOrDeleteParents(file: edu.eckerd.google.api.services.drive.models.File)
                           (implicit db: JdbcProfile#Backend#Database, ec: ExecutionContext): Future[Int] = {
    val optParentShift = for {
      id <- file.id
      parents <- file.parentIds if parents.nonEmpty
    } yield {
      val dbParentsF = db.run(
        googleParents.filter(_.childID === id).map(_.parentID).result
      )

      val createListF = for {
        dbParents <- dbParentsF
      } yield {
        val dbParentsSet = dbParents.toSet
        val googleParentsSet = parents.toSet
        googleParentsSet.diff(dbParentsSet).toList
      }

      val deleteListF = for {
        dbParents <- dbParentsF
      } yield {
        val dbParentsSet = dbParents.toSet
        val googleParentsSet = parents.toSet
        dbParentsSet.diff(googleParentsSet).toList
      }

      val deleteItems = for {
        createList <- createListF
        result <- Future.sequence{
          for {
            parentId <- createList
          } yield db.run(googleParents += GoogleParent(id, parentId))
        }
      } yield result

      val createItems = for {
        deleteList <- deleteListF
        result <- Future.sequence{
          for {
            parentId <- deleteList
          } yield {
            val removingParent = GoogleParent(id, parentId)
            db.run(
              googleParents.filter(rec =>
                rec.childID === removingParent.childId && rec.parentID === removingParent.parentId).delete
            )
          }
        }
      } yield result

      for {
        created <- createItems
        deleted <- deleteItems
      } yield {
        created.sum + deleted.sum
      }
    }
    optParentShift.getOrElse(Future.successful(0))
  }

  val filesMade = Await.result(Future.sequence(seq), Duration.Inf )
  println(s"${filesMade.sum} files added to database")
  val filesDeleted = Await.result(Future.sequence(parentSeq), Duration.Inf )
  println(s"${filesDeleted.sum} files deleted from database")

}
