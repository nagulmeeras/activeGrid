package com.imaginea.activegrid.core.models

import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.index.query.{BoolQueryBuilder, QueryBuilders}
import org.elasticsearch.node.NodeBuilder
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.collection.JavaConversions._

/**
  * Created by nagulmeeras on 23/11/16.
  */
object EsManager extends App with DefaultJsonProtocol {
  val settings = ImmutableSettings.settingsBuilder()
    .put("cluster.name", "elasticsearch")
    .put("node.name", this.getClass.getProtectionDomain.getCodeSource.getLocation.toURI.getPath + "/config/names.txt")
    .build()
  val node = NodeBuilder.nodeBuilder().local(false).settings(settings).build().start()
  val client = node.client()

  def shutDown(): Unit = {
    node.stop.close()
  }

  def indexEntity[T <: BaseEntity](entity: T, index: String, indexType: String)(implicit formateObj: RootJsonFormat[T]): Unit = {
    entity.id.foreach { id =>
      val v = formateObj.write(entity).toString()
      client.prepareIndex(index, indexType, id.toString).setSource(v).execute().actionGet()
    }
  }

  def boolQuery(esSearchQuery: EsSearchQuery): List[EsSearchResponse] = {
    val index = esSearchQuery.index
    val searchRequest = client.prepareSearch(index)
    searchRequest.addField(esSearchQuery.outputField)
    esSearchQuery.types.foreach(searchType => searchRequest.setTypes(searchType))
    val query = new BoolQueryBuilder
    esSearchQuery.queryFields.foreach { queryField =>
      val qsQuery = QueryBuilders.queryString(addWildCard(queryField.value))
      qsQuery.field(addWildCard(queryField.key))
      qsQuery.analyzeWildcard(true)
      esSearchQuery.queryType match {
        case EsQueryType.OR =>
          query.should(qsQuery)
        case EsQueryType.AND =>
          query.must(qsQuery)
      }
    }
    searchRequest.setQuery(query)
    searchRequest.setSize(1000)
    val response = searchRequest.execute.actionGet
    response.getHits.foldLeft(List.empty[EsSearchResponse]) {
      (responses, hit) =>
        EsSearchResponse(hit.getType, esSearchQuery.outputField, hit.getId) :: responses
    }
  }

  def addWildCard(queryString: String): String = {
    if ("_all" == queryString) queryString
    val wildCardStr = new StringBuilder
    if (!queryString.startsWith("*")) wildCardStr.append("*")
    if (!queryString.endsWith("*")) wildCardStr.append(queryString).append("*")
    wildCardStr.toString
  }

  //TODO Need to write custome json writer.
  //  def fieldMappings(indexName : String , mappingType : String): List[String] = {
  //    try {
  //      val index = indexName.toLowerCase
  //      val clusterState = client.admin().cluster().prepareState().setFilterIndices(index).execute().actionGet().getState
  //      val metaData = Option(clusterState.getMetaData.index(index).mapping(mappingType))
  //      metaData.map{data =>
  //      }
  //    }catch {
  //      case ex: Exception => throw ex
  //    }
  //    List.empty
  //  }


}
