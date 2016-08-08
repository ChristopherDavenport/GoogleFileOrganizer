package io.chrisdavenport.google.drive.persistence

import scala.language.implicitConversions
import java.sql.Timestamp
import java.time.LocalDateTime
import edu.eckerd.google.api.services.drive.models.CompleteFile

/**
  * Created by davenpcm on 8/5/16.
  */
trait Tables {
  val profile: slick.driver.JdbcProfile
  import profile.api._

  implicit def myLocalDateTimeColumn = MappedColumnType.base[LocalDateTime, Timestamp](
    Timestamp.valueOf, _.toLocalDateTime
  )

  case class CompleteFileRow(id: String,
                             name: String,
                             mimeType: String,
                             size: Long,
                             createdTime: LocalDateTime,
                             modifiedTime: LocalDateTime, trashed: Boolean
                            )

  implicit def completeFileToRow(cf: CompleteFile): CompleteFileRow = {
    CompleteFileRow(cf.id, cf.name, cf.mimeType, cf.size, cf.createdTime, cf.modifiedTime, cf.trashed)
  }

  class GoogleFiles(_tableTag: Tag) extends Table[CompleteFileRow](_tableTag, "files"){
    def * = (id, name, mimeType, size, createdTime, modifiedTime, trashed).shaped <>
      (CompleteFileRow.tupled, CompleteFileRow.unapply)


    val id: Rep[String] = column[String]("id", O.PrimaryKey)
    val name: Rep[String] = column[String]("name")
    val mimeType: Rep[String] = column[String]("mime_type")
    val size : Rep[Long] = column[Long]("size")
    val createdTime: Rep[LocalDateTime] = column[LocalDateTime]("created_time")
    val modifiedTime: Rep[LocalDateTime] = column[LocalDateTime]("modified_time")
    val trashed : Rep[Boolean] = column[Boolean]("trashed")
  }

  lazy val googleFiles = new TableQuery(tag => new GoogleFiles(tag))

  case class GoogleParent(
                     childId: String,
                     parentId: String
                   )

  class GoogleParents(_tableTag: Tag) extends Table[GoogleParent](_tableTag, "parents"){
    def * = (childID, parentID) <> (GoogleParent.tupled, GoogleParent.unapply)
    val childID: Rep[String] = column[String]("child_id")
    val parentID: Rep[String] = column[String]("parent_id")

    val pk = primaryKey("pk_parents1", (childID, parentID))
    def childFile = foreignKey("fk_parents_child1", childID, googleFiles)(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
  }

  lazy val googleParents = new TableQuery(tag => new GoogleParents(tag))

  case class FileView(
                            userId: String,
                            fileId: String
                            )

  class GoogleFileView(_tableTag: Tag) extends Table[FileView](_tableTag, "file_view"){
    def * = (userId, fileId) <> (FileView.tupled, FileView.unapply)
    val userId: Rep[String] = column[String]("user_id")
    val fileId: Rep[String] = column[String]("file_id")

    val pk = primaryKey("file_view_pk", (userId, fileId))
    def fileFK = foreignKey("file_view_file_fk", fileId, googleFiles)( _.id,
      onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade
    )

  }

  lazy val googleFileView = new TableQuery(tag => new GoogleFileView(tag))

}
