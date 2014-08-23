/*
 * Licensed to Typesafe under one or more contributor license agreements.
 * See the CONTRIBUTING file distributed with this work for
 * additional information regarding copyright ownership.
 * This file is licensed to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.squbs.httpclient.pipeline

import spray.httpx.{UnsuccessfulResponseException, PipelineException}
import spray.client.pipelining._
import akka.actor.{ActorRef, ActorSystem}
import spray.httpx.unmarshalling._
import scala.concurrent.{ExecutionContext, Await, Future}
import org.squbs.httpclient._
import spray.http.{Uri, HttpRequest, HttpResponse}
import org.squbs.httpclient.Result
import scala.util.Try
import akka.pattern._
import akka.util.Timeout
import spray.can.Http
import akka.io.IO
import javax.net.ssl.SSLContext
import spray.io.ClientSSLEngineProvider
import org.squbs.httpclient.endpoint.Endpoint

trait Pipeline {
  def requestPipelines: Seq[RequestTransformer]

  def responsePipelines: Seq[ResponseTransformer]
}

trait PipelineHandler {
  def processRequest: RequestTransformer

  def processResponse: ResponseTransformer
}

trait RequestPipelineHandler extends PipelineHandler {
  override def processResponse: ResponseTransformer = {httpResponse => httpResponse}
}

trait ResponsePipelineHandler extends PipelineHandler {
  override def processRequest: RequestTransformer = {httpRequest => httpRequest}
}

object EmptyPipeline extends Pipeline {
  override def responsePipelines: Seq[ResponseTransformer] = Seq.empty[ResponseTransformer]

  override def requestPipelines: Seq[RequestTransformer] = Seq.empty[RequestTransformer]
}

trait PipelineManager{

  import ExecutionContext.Implicits.global

  private def pipelining(client: Client)(implicit actorSystem: ActorSystem) = {

    implicit val connectionTimeout: Timeout = client.endpoint.config.hostSettings.connectionSettings.connectingTimeout.toMillis
    for (
      Http.HostConnectorInfo(connector, _) <-
      IO(Http) ? hostConnectorSetup(client)
    ) yield sendReceive(connector)
  }

  def hostConnectorSetup(client: Client)(implicit actorSystem: ActorSystem) = {
    implicit def sslContext: SSLContext = {
      client.endpoint.config.sslContext match {
        case Some(context) => context
        case None          => SSLContext.getDefault
      }
    }

    implicit val myClientEngineProvider = ClientSSLEngineProvider { engine =>
      engine
    }
    val uri = Uri(client.endpoint.uri)
    val host = uri.authority.host.toString
    val port = if (uri.effectivePort == 0) 80 else uri.effectivePort
    val isSecure = uri.scheme.toLowerCase.equals("https")
    val defaultHostConnectorSetup = Http.HostConnectorSetup(host, port, isSecure)
    defaultHostConnectorSetup.copy(settings = Some(client.endpoint.config.hostSettings), connectionType = client.endpoint.config.connectionType)
  }

  def invokeToHttpResponse(client: Client)(implicit actorSystem: ActorSystem): Try[(HttpRequest => Future[HttpResponse])] = {
    val pipelines = client.endpoint.config.pipeline.getOrElse(EmptyPipeline)
    val reqPipelines = pipelines.requestPipelines
    val resPipelines = pipelines.responsePipelines
    val connTimeout = client.endpoint.config.hostSettings.connectionSettings.connectingTimeout
    Try{
      val futurePipeline = pipelining(client)
      val pipeline = Await.result(futurePipeline, connTimeout)
      (reqPipelines, resPipelines, client.status) match {
        case (_, _, Status.DOWN) =>
          throw new HttpClientMarkDownException(client.name, client.env)
        case (Seq(), Seq(), _) =>
          pipeline
        case (Seq(), _: Seq[ResponseTransformer], _) =>
          pipeline ~> resPipelines.reduceLeft[ResponseTransformer](_ ~> _)
        case (_: Seq[RequestTransformer], Seq(), _) =>
          reqPipelines.reduceLeft[RequestTransformer](_ ~> _) ~> pipeline
        case (_: Seq[RequestTransformer], _: Seq[ResponseTransformer], _) =>
          reqPipelines.reduceLeft[RequestTransformer](_ ~> _) ~> pipeline ~> resPipelines.reduceLeft[ResponseTransformer](_ ~> _)
      }
    }
  }

  def invokeToHttpResponseWithoutSetup(client: Client, actorRef: ActorRef)(implicit actorSystem: ActorSystem): Try[(HttpRequest => Future[HttpResponse])] = {
    val pipelines = client.endpoint.config.pipeline.getOrElse(EmptyPipeline)
    val reqPipelines = pipelines.requestPipelines
    val resPipelines = pipelines.responsePipelines
    implicit val timeout: Timeout = client.endpoint.config.hostSettings.connectionSettings.connectingTimeout.toMillis
    val pipeline = spray.client.pipelining.sendReceive(actorRef)
    Try{
      (reqPipelines, resPipelines, client.status) match {
        case (_, _, Status.DOWN) =>
          throw new HttpClientMarkDownException(client.name, client.env)
        case (Seq(), Seq(), _) =>
          pipeline
        case (Seq(), _: Seq[ResponseTransformer], _) =>
          pipeline ~> resPipelines.reduceLeft[ResponseTransformer](_ ~> _)
        case (_: Seq[RequestTransformer], Seq(), _) =>
          reqPipelines.reduceLeft[RequestTransformer](_ ~> _) ~> pipeline
        case (_: Seq[RequestTransformer], _: Seq[ResponseTransformer], _) =>
          reqPipelines.reduceLeft[RequestTransformer](_ ~> _) ~> pipeline ~> resPipelines.reduceLeft[ResponseTransformer](_ ~> _)
      }
    }
  }

  def invokeToEntity[T: FromResponseUnmarshaller](client: Client)(implicit actorSystem: ActorSystem): Try[(HttpRequest => Future[Result[T]])] = {
    val pipelines = client.endpoint.config.pipeline.getOrElse(EmptyPipeline)
    val reqPipelines = pipelines.requestPipelines
    val resPipelines = pipelines.responsePipelines
    val connTimeout = client.endpoint.config.hostSettings.connectionSettings.connectingTimeout
    Try{
      val futurePipeline = pipelining(client)
      val pipeline = Await.result(futurePipeline, connTimeout)
      (reqPipelines, resPipelines, client.status) match {
        case (_, _, Status.DOWN) =>
          throw new HttpClientMarkDownException(client.name, client.env)
        case (Seq(), Seq(), _) =>
          pipeline ~> unmarshal[T]
        case (Seq(), _: Seq[ResponseTransformer], _) =>
          pipeline ~> resPipelines.reduceLeft[ResponseTransformer](_ ~> _) ~> unmarshal[T]
        case (_: Seq[RequestTransformer], Seq(), _) =>
          reqPipelines.reduceLeft[RequestTransformer](_ ~> _) ~> pipeline ~> unmarshal[T]
        case (_: Seq[RequestTransformer], _: Seq[ResponseTransformer], _) =>
          reqPipelines.reduceLeft[RequestTransformer](_ ~> _) ~> pipeline ~> resPipelines.reduceLeft[ResponseTransformer](_ ~> _) ~> unmarshal[T]
      }
    }
  }

  def unmarshal[T: FromResponseUnmarshaller]: HttpResponse ⇒ Result[T] = {
    response =>
      if (response.status.isSuccess)
        response.as[T] match {
          case Right(value) ⇒ Result[T](value, response)
          case Left(error) ⇒ throw new PipelineException(error.toString)
        }
      else throw new UnsuccessfulResponseException(response)
  }

  implicit def endpointToUri(endpoint: Endpoint): String = {
    endpoint.uri
  }
}