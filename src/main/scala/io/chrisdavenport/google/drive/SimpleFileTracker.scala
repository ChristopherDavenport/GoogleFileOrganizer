package io.chrisdavenport.google.drive

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.typesafe.scalalogging.LazyLogging
import edu.eckerd.google.api.services.Scopes._
import edu.eckerd.google.api.services.drive.Drive
import edu.eckerd.google.api.services.drive.models.CompleteFile
import eri.commons.config.SSConfig
import io.chrisdavenport.google.drive.persistence.Tables
import slick.backend.DatabaseConfig
import slick.driver.JdbcProfile
import edu.eckerd.google.api.services.drive.models.File

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
  * Created by davenpcm on 8/5/16.
  */
object SimpleFileTracker extends App with Tables with LazyLogging {

  lazy val dbConfig: DatabaseConfig[JdbcProfile] = DatabaseConfig.forConfig("postgres")
  val profile = dbConfig.driver
  implicit val db = dbConfig.db
  import profile.api._

  Try(Await.result(db.run(googleFiles.schema.create), 10.seconds))
  Try(Await.result(db.run(googleParents.schema.create), 10.seconds))
  Try(Await.result(db.run(googleFileView.schema.create), 10.seconds))

  object DriveConfig extends SSConfig("google")

  val user1 = DriveConfig.impersonatedAccount.as[String]
  val Users = List(user1)

  val f = Future.sequence{
    Users.par.map(AddForUser).seq
  }

  Await.result(f, 11.hours).map(_.sum).foreach(println)

  def AddForUser(userID: String)
                (implicit db: JdbcProfile#Backend#Database, ec: ExecutionContext): Future[Seq[Int]] = {
    implicit val drive = Drive(
      DriveConfig.serviceAccount.as[String],
      userID,
      DriveConfig.credentialFilePath.as[String],
      DriveConfig.applicationName.as[String],
      DRIVE
    )

    val files = drive.files.list
    logger.debug(s"$userID - Number of Files - ${files.length}")
    Future.traverse(files)(getAndInsertOrDelete(_, userID))
  }



  def insertFileView(f: File, userId: String)
                    (implicit db: JdbcProfile#Backend#Database, ec: ExecutionContext): Future[Int] = {
    val fileView = FileView(userId, f.id)

    db.run(googleFileView.filter(r => r.userId === fileView.userId && r.fileId === fileView.fileId).exists.result)
      .flatMap{
        case true => Future.successful(0)
        case false => db.run(googleFileView += fileView)
      }
  }

  def insertOrUpdateFiles(f: CompleteFile)(implicit db: JdbcProfile#Backend#Database, ec: ExecutionContext): Future[Int] = {
    val qTotal = googleFiles.filter(r =>
      r.id === f.id &&
        r.name === f.name &&
        r.mimeType === f.mimeType &&
        r.size === f.size &&
        r.createdTime === f.createdTime &&
        r.modifiedTime === f.modifiedTime &&
        r.trashed === f.trashed
    ).exists.result
    val q = googleFiles.filter(_.id === f.id).exists.result
    val existsExactly = for { existsTotal <- db.run(qTotal) } yield existsTotal

    existsExactly.flatMap{
      case true =>
        logger.debug(s"Doing Nothing - $f")
        Future.successful(0)
      case false =>
        val pkExists = for { exists <- db.run(q) } yield exists
        pkExists.flatMap{
          case true =>
            val updateQ = for {
              rec <- googleFiles if rec.id === f.id
            } yield (rec.name, rec.mimeType, rec.size, rec.createdTime, rec.modifiedTime, rec.trashed)
            val action = updateQ.update(f.name, f.mimeType, f.size, f.createdTime, f.modifiedTime, f.trashed)
            logger.debug(s"Updating $f")
            db.run(action)
          case false =>
            logger.debug(s"Inserting $f")
            db.run(googleFiles += f)
        }
    }
  }

  def getRecursive(file: File, iter: Int, timeout: Int )
                  (implicit drive: Drive): CompleteFile = {
    val r = new scala.util.Random()
    val t = Try(drive.files.get(file))
      .recoverWith {
        case rateLimit: GoogleJsonResponseException if iter < 50  =>
          Thread.sleep(timeout)
          if (iter > 5) logger.error(s"Recursive Loop Entered For ${file.name} - Iter - $iter")
          Try(getRecursive(file, iter + 1, timeout + r.nextInt(100) ))
      }
    t.get match {
      case bingo : CompleteFile => bingo
      case e => println(e); throw new Error("File Did Not Return Correctly")
    }
  }

  def getAndInsertOrDelete(file: File, userId: String)
                          (implicit db: JdbcProfile#Backend#Database,
                           drive: Drive,
                           ec: ExecutionContext
                          ): Future[Int] = {
    val fileWithParents = getRecursive(file, 0, 100)
    for {
      inserted <- insertOrUpdateFiles(fileWithParents)
      parents <- insertOrDeleteParents(fileWithParents)
      fileView <- insertFileView(file, userId)
    } yield {
      inserted + parents + fileView
    }
  }

  def insertOrDeleteParents(file: CompleteFile)
                           (implicit db: JdbcProfile#Backend#Database, ec: ExecutionContext): Future[Int] = {
    val dbParentsF = db.run(
      googleParents.filter(_.childID === file.id).map(_.parentID).result
    )

    val createListF = for {
      dbParents <- dbParentsF
    } yield {
      val dbParentsSet = dbParents.toSet
      val googleParentsSet = file.parentIds.toSet
      val createList = googleParentsSet.diff(dbParentsSet).toList
      if (createList.nonEmpty) logger.debug(s"Create List ${file.name} - $createList")
      createList
    }

    val deleteListF = for {
      dbParents <- dbParentsF
    } yield {
      val dbParentsSet = dbParents.toSet
      val googleParentsSet = file.parentIds.toSet
      val deleteList = dbParentsSet.diff(googleParentsSet).toList
      if(deleteList.nonEmpty) logger.debug(s"Delete List ${file.name}  - $deleteList")
      deleteList
    }

    val createItems = for {
      createList <- createListF
      result <- Future.sequence{
        for {
          parentId <- createList
        } yield {
          db.run(googleParents += GoogleParent(file.id, parentId))
        }
      }
    } yield result

    val deleteItems = for {
      deleteList <- deleteListF
      result <- Future.sequence{
        for {
          parentId <- deleteList
        } yield {
          val removingParent = GoogleParent(file.id, parentId)
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
      val infoChangedNumber = created.sum + deleted.sum
      logger.info(s"${file.name} - ${file.id} - Altered $infoChangedNumber records")
      infoChangedNumber
    }
  }
}
