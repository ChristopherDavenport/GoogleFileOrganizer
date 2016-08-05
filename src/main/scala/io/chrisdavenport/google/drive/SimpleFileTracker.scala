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

  val dbConfig: DatabaseConfig[JdbcProfile] = DatabaseConfig.forConfig("sqlite")
  val profile = dbConfig.driver
  implicit val db = dbConfig.db
  import dbConfig.driver.api._

//  val f1 = for {
//    _ <- db.run(googleFiles.schema.create)
//    _ <- db.run(googleParents.schema.create)
//  } yield ()
//
//
//  val a1 = Await.result(f1, 10.seconds)
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
    .map(f =>
      for {
        id <- f.id
      } yield GoogleFile(id, f.name, f.mimeType, f.extension)
    )
    .map(_.map(insertOrUpdateFiles))

  seq.foreach(println)

  val f3 = db.run(googleFiles.result)
    .map(_.foreach(println))

  Await.result(f3, 10.seconds)

  val parentSeq = files
    .map(getParentsRecursive(_, drive))
    .map(insertOrDeleteParents)

  println(s"Total Rows Changed - ${parentSeq.sum}")

  def insertOrUpdateFiles(f: GoogleFile)(implicit db: JdbcProfile#Backend#Database, ec: ExecutionContext): Int = {
    val qTotal = googleFiles.filter(r => r.id === f.id && r.name === f.name && r.mimeType === f.mimeType).exists.result
    Await.result(db.run(qTotal), 10.seconds) match {
      case true => 0
      case false =>
        val q = googleFiles.filter(_.id === f.id).exists.result
        Await.result(db.run(q), 10.seconds) match {
          case true =>
            val a = for {
              rec <- googleFiles if rec.id === f.id
            } yield (rec.name, rec.mimeType, rec.extension)
            val action = a.update((f.name, f.mimeType, f.extension ))
            Await.result(db.run(action), 10.seconds)
          case false =>
            Await.result(db.run(googleFiles += f), 10.seconds)
        }
    }
  }

  def getParentsRecursive(file: edu.eckerd.google.api.services.drive.models.File, drive: Drive, iter: Int = 0): edu.eckerd.google.api.services.drive.models.File = {
//    Thread.sleep(15)
    val t = Try(drive.files.getParents(file))
      .recoverWith {
        case rateLimit: GoogleJsonResponseException if rateLimit.getDetails.getMessage.contains("Rate Limit Exceeded") && iter < 10 =>
          Thread.sleep(100)
          if (iter > 0) println(s"Recursive Loop Entered For ${file.name} - Iter - $iter")
          Try(getParentsRecursive(file, drive, iter + 1))
      }
    t.get
  }

  def insertOrDeleteParents(file: edu.eckerd.google.api.services.drive.models.File)
                           (implicit db: JdbcProfile#Backend#Database, ec: ExecutionContext): Int = {
    val optParentShift = for {
      id <- file.id
      parents <- file.parentIds if parents.nonEmpty
    } yield {
      val dbParents = Await.result(
        db.run(
          googleParents.filter(_.childID === id).map(_.parentID).result
        ),
        10.seconds
      )
      val dbParentsSet = dbParents.toSet
      val googleParentsSet = parents.toSet

     val create = googleParentsSet.diff(dbParentsSet)
       .toList
       .map { parentId =>
         val creatingParent = GoogleParent(id, parentId)
         println(s"Creating $creatingParent")
         Await.result(db.run(googleParents += creatingParent), 10.seconds)
       }

      val delete = dbParentsSet.diff(googleParentsSet)
        .toList
        .map { parentID =>
          val removingParent = GoogleParent(id, parentID)
          println(s"Removing - $removingParent")
          Await.result(
            db.run(
              googleParents.filter(rec =>
                rec.childID === removingParent.childId && rec.parentID === removingParent.parentId).delete
            ),
            10.seconds
          )
        }

      create.sum + delete.sum
    }
    optParentShift.getOrElse(0)
  }

}
