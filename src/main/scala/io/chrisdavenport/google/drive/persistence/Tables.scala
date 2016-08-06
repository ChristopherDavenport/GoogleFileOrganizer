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
                       mimeType: String
                       )

  class GoogleFiles(_tableTag: Tag) extends Table[GoogleFile](_tableTag, "files"){
    def * = (id, name, mimeType) <> (GoogleFile.tupled, GoogleFile.unapply)

    val id: Rep[String] = column[String]("id", O.PrimaryKey)
    val name: Rep[String] = column[String]("name")
    val mimeType: Rep[String] = column[String]("mime_type")
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

    val pk = primaryKey("pk_parents", (childID, parentID))
    def childFile = foreignKey("fk_child", childID, googleFiles)(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
  }

  lazy val googleParents = new TableQuery(tag => new GoogleParents(tag))

}
