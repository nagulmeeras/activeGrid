package com.activegrid.services

import com.activegrid.model.GraphDBExecutor
import com.activegrid.model.{GraphDBExecutor, ImageInfo}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by shareefn on 22/9/16.
  */
class CatalogService(implicit val executionContext: ExecutionContext){

  val db = new GraphDBExecutor()

  def getImages(): Future[Option[List[ImageInfo]]] = Future {

    db.getEntities[ImageInfo]

  }

  def buildImage(image:ImageInfo):Future[Option[ImageInfo]] = Future {

  db.persistEntity[ImageInfo](image, "ImageInfo")

  }

  def deleteImage(imageId:Long): Future[Option[String]] = Future {

    db.deleteEntity[ImageInfo](imageId)

    Some("true")

  }

}