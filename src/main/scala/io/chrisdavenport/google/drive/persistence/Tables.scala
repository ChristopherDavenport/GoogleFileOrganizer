package io.chrisdavenport.google.drive.persistence

/**
  * Created by davenpcm on 8/5/16.
  */
trait Tables {
  val profile: slick.driver.JdbcProfile
  import profile.api._

  lazy val schema: profile.SchemaDescription = googleFiles.schema ++ googleParents.schema

  case class GoogleFile(
                       id: String,
                       name: String,
                       mimeType: String,
                       extension: Option[String]
                       )

  class GoogleFiles(_tableTag: Tag) extends Table[GoogleFile](_tableTag, "FILES"){
    def * = (id, name, mimeType, extension) <> (GoogleFile.tupled, GoogleFile.unapply)

    val id: Rep[String] = column[String]("ID", O.PrimaryKey)
    val name: Rep[String] = column[String]("NAME")
    val mimeType: Rep[String] = column[String]("MIME_TYPE")
    val extension: Rep[Option[String]] = column[Option[String]]("EXTENSION")
  }

  lazy val googleFiles = new TableQuery(tag => new GoogleFiles(tag))

  case class GoogleParent(
                     childId: String,
                     parentId: String
                   )

  class GoogleParents(_tableTag: Tag) extends Table[GoogleParent](_tableTag, "PARENTS"){
    def * = (childID, parentID) <> (GoogleParent.tupled, GoogleParent.unapply)
    val childID: Rep[String] = column[String]("CHILD_ID")
    val parentID: Rep[String] = column[String]("PARENT_ID")

    val pk = primaryKey("PK_PARENTS", (childID, parentID))
    def childFile = foreignKey("CHILD_FK", childID, googleFiles)(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
  }

  lazy val googleParents = new TableQuery(tag => new GoogleParents(tag))

}
