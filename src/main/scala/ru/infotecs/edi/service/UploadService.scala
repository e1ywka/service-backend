/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import ru.infotecs.edi.security.{InvalidJsonWebToken, JsonWebToken, ValidJsonWebToken}
import ru.infotecs.edi.service.FileUploading._
import ru.infotecs.edi.service.Parser.ParserException
import spray.http.StatusCodes._
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling._
import spray.httpx.unmarshalling._
import spray.routing._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class UploadServiceActor(fileUploading: ActorRef) extends Actor with ActorLogging with UploadService {
  implicit val ec = context.dispatcher

  def actorRefFactory = context

  def receive = runRoute(uploadServiceRoute(fileUploading))
}

trait UploadService extends HttpService {

  import ServiceJsonFormat._

  implicit val timeout = Timeout(3 seconds)

  def uploadServiceRoute(fileUploading: ActorRef)(implicit ec: ExecutionContext) = {
    (pathPrefix("upload") & pathEndOrSingleSlash) {
      options {
        respondWithHeaders(
          HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(HttpOrigin("http://localhost:9080") :: Nil)),
          HttpHeaders.`Access-Control-Allow-Credentials`(true),
          HttpHeaders.`Access-Control-Allow-Methods`(HttpMethods.GET, HttpMethods.POST, HttpMethods.OPTIONS),
          HttpHeaders.`Access-Control-Allow-Headers`("Content-Type")
        ) {
          complete(OK)
        }
      } ~
      post {
        respondWithHeaders(
          HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(HttpOrigin("http://localhost:9080") :: Nil)),
          HttpHeaders.`Access-Control-Allow-Credentials`(true)
        ) {
          cookie("rememberme") { token =>
            entity(as[FileChunk]) { f =>
              detach() {
                complete {
                  JsonWebToken(token.content) match {
                    case InvalidJsonWebToken(_) => HttpResponse(Unauthorized, "Token is invalid")
                    case t: ValidJsonWebToken =>
                      fileUploading ? AuthFileChunk(f, t) map {
                        case UnparsedDocumentPart => HttpResponse(NoContent)
                        case informal: InformalDocument => HttpResponse(OK, marshalUnsafe(informal))
                        case formal: FormalDocument => HttpResponse(OK, marshalUnsafe(formal))
                      }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  implicit val exceptionHandler = ExceptionHandler {
    case e: ParserException => complete(BadRequest, ErrorMessage(e.getErrorMessage))
    case e: FileServerClientException => complete(InternalServerError)
    case e: Throwable => complete(InternalServerError)
  }
}
