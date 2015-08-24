/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.{ActorLogging, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import jdk.nashorn.internal.runtime.regexp.joni.Config
import ru.infotecs.edi.security.{InvalidJsonWebToken, JsonWebToken, ValidJsonWebToken}
import ru.infotecs.edi.service.FileUploading._
import spray.http._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling._
import spray.httpx.unmarshalling._
import spray.routing.{ExceptionHandler, HttpServiceActor}

import scala.concurrent.duration._
import scala.util.Try

class UploadService(fileUploading: ActorRef) extends HttpServiceActor with ActorLogging {
  import ServiceJsonFormat._
  import context.dispatcher

  implicit val timeout = Timeout(3 seconds)

  /**
   * Unmarshaller maps multipart request as FileChunk.
   */
  implicit val fileUploadUnmarshaller: Unmarshaller[FileChunk] =
    Unmarshaller.delegate[MultipartFormData, FileChunk](ContentTypeRange(MediaTypes.`multipart/form-data`)) { data =>
      val fileUpload = for {
        chunksBodyPart <- data.get("chunks")
        chunks <- Try {
          chunksBodyPart.entity.asString.toInt
        }.toOption
        chunkBodyPart <- data.get("chunk")
        chunk <- Try {
          chunkBodyPart.entity.asString.toInt
        }.toOption
        file <- data.get("file")
        fileNameBodyPart <- data.get("name")
        fileSizeBodyPart <- data.get("size")
        fileSize <- Try {
          fileSizeBodyPart.entity.asString.toLong
        }.toOption
        fileHashBodyPart <- data.get("sha256hash")
        meta <- Some(Meta(fileNameBodyPart.entity.asString,
          fileSize,
          fileHashBodyPart.entity.asString))
      } yield FileUploading.FileChunk((chunk, chunks), file, meta)

      fileUpload match {
        case Some(f) => f
      }
    }

  val uploadServiceRoute = {
    (pathPrefix("upload") & pathEndOrSingleSlash) {
      get {
        respondWithMediaType(MediaTypes.`text/html`) {
          complete(formUpload)
        }
      } ~
      options {
        respondWithHeaders(
          HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(HttpOrigin("http://localhost:9080") :: Nil)),
          HttpHeaders.`Access-Control-Allow-Credentials`(true),
          HttpHeaders.`Access-Control-Allow-Methods`(HttpMethods.GET, HttpMethods.POST, HttpMethods.OPTIONS),
          HttpHeaders.`Access-Control-Allow-Headers`("Content-Type")
        ) {
          complete()
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
                    case InvalidJsonWebToken => HttpResponse(Unauthorized, "Token is invalid")
                    case ValidJsonWebToken(jws, jwt, _) =>
                      fileUploading ? AuthFileChunk(f, jwt) recover {
                        case e => log.error(e, "Error while handling file upload")
                      } map {
                        case FileChunkUploaded => HttpResponse(NoContent)
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

  def receive = runRoute(uploadServiceRoute)

  implicit val exceptionHanlder = ExceptionHandler {
    case e => complete(HttpResponse(500, e.getMessage))
  }

  lazy val formUpload =
    """
      |<html>
      |<head>
      |  <title>Plupload - spray</title>
      |  <script src="http://www.plupload.com/plupload/js/plupload.full.min.js"></script>
      |</head>
      |<body>
      |  <div id="filelist">Your browser doesn't have Flash, Silverlight or HTML5 support.</div>
      |<br />
      |
      |<div id="container">
      |    <a id="pickfiles" href="javascript:;">[Select files]</a>
      |    <a id="uploadfiles" href="javascript:;">[Upload files]</a>
      |</div>
      |
      |<br />
      |<pre id="console"></pre>
      |
      |
      |<script type="text/javascript">
      |// Custom example logic
      |
      |var uploader = new plupload.Uploader({
      |    runtimes : 'html5,html4',
      |
      |    browse_button : 'pickfiles', // you can pass in id...
      |    container: document.getElementById('container'), // ... or DOM Element itself
      |
      |    url : "/upload",
      |    chunk_size: "10kb",
      |    unique_names: true,
      |
      |    filters : {
      |        max_file_size : '10mb'
      |    },
      |
      |    init: {
      |        PostInit: function() {
      |            document.getElementById('filelist').innerHTML = '';
      |
      |            document.getElementById('uploadfiles').onclick = function() {
      |                uploader.start();
      |                return false;
      |            };
      |        },
      |
      |        BeforeUpload: function(up, file) {
      |            up.settings.multipart_params = {
      |                size: file.size,
      |                sha256hash: "hash"
      |            }
      |        },
      |
      |        FilesAdded: function(up, files) {
      |            plupload.each(files, function(file) {
      |                document.getElementById('filelist').innerHTML += '<div id="' + file.id + '">' + file.name + ' (' + plupload.formatSize(file.size) + ') <b></b></div>';
      |            });
      |        },
      |
      |        UploadProgress: function(up, file) {
      |            document.getElementById(file.id).getElementsByTagName('b')[0].innerHTML = '<span>' + file.percent + "%</span>";
      |        },
      |
      |        Error: function(up, err) {
      |            document.getElementById('console').innerHTML += "\nError #" + err.code + ": " + err.message;
      |        }
      |    }
      |});
      |
      |uploader.init();
      |
      |</script>
      |</body>
      |</html>
    """.stripMargin
}
