/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.io.{ByteArrayInputStream, InputStreamReader, BufferedReader, StringReader}

import akka.actor.{Props, Actor}
import akka.actor.Actor.Receive
import spray.can.Http
import spray.http._
import HttpMethods._
import spray.httpx.unmarshalling.{BasicUnmarshallers, PimpedHttpEntity, FormDataUnmarshallers}

import scala.collection.mutable
import scala.util.Try

case object ServiceStatus

class RequestHandler extends Actor {
  import context._
  val fileUploadingHandler = actorOf(Props[FileUploading])

  def receive: Receive = {
    case _: Http.Connected => sender ! Http.Register(system.actorOf(Props[RequestHandler]))

    case HttpRequest(GET, Uri.Path("/status"), _, _, _) => {
      sender ! HttpResponse(status = 200, entity = "Server is working")
    }

    case HttpRequest(GET, Uri.Path("/upload"), _, _, _) => {
      sender ! HttpResponse(status = 200, entity = HttpEntity(ContentType(MediaTypes.`text/html`, HttpCharsets.`UTF-8`), formUpload))
    }

    case HttpRequest(POST, Uri.Path("/upload"), headers, entity, _) => {
      import FormDataUnmarshallers._

      entity.as[MultipartFormData] match {
        case Left(e) => sender ! HttpResponse(status = 500, "Unsupported media type")
        case Right(data) => {
          val fileChunk = for {
            chunksBodyPart <- data.get("chunks")
            chunks <- Try { chunksBodyPart.entity.asString.toInt }.toOption
            chunkBodyPart <- data.get("chunk")
            chunk <- Try { chunkBodyPart.entity.asString.toInt }.toOption
            file <- data.get("file")
            fileNameBodyPart <- data.get("name")
          } yield FileUploading.FileChunk((chunk, chunks), file, fileNameBodyPart.entity.asString(HttpCharsets.`UTF-8`))
          fileChunk match {
            case Some(f) => {
              fileUploadingHandler ! f
              sender ! HttpResponse(status = 204)
            }
            case None => sender ! HttpResponse(status = 500, "Unsupported media type")
          }
        }
      }

      //println(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data.toByteArray), charset.getOrElse(HttpCharsets.`UTF-8`).nioCharset)))
      //sender ! HttpResponse(status = 200, "Ok")
    }
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
