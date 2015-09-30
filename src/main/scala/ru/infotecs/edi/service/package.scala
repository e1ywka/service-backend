/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi

import java.util.UUID

import akka.util.ByteString
import ru.infotecs.edi.security.JsonWebToken
import ru.infotecs.edi.service.FileUploading.{FileChunk, Meta}
import spray.http.HttpHeaders.{Authorization, RawHeader, `Content-Disposition`}
import spray.http._
import spray.httpx.marshalling.Marshaller
import spray.httpx.unmarshalling._

import scala.util.Try

package object service {

  import MediaTypes.{`application/octet-stream`, `multipart/form-data`}
  import spray.json.DefaultJsonProtocol._

  /**
   * Unmarshaller maps multipart request as FileChunk.
   */
  implicit val FileUploadUnmarshaller: Unmarshaller[FileChunk] =
    Unmarshaller.delegate[MultipartFormData, FileChunk](ContentTypeRange(`multipart/form-data`)) { data =>
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
        mediaTypeBodyPart <- data.get("mediaType")
        fileSize <- Try {
          fileSizeBodyPart.entity.asString.toLong
        }.toOption
        fileHashBodyPart <- data.get("sha256hash")
        mediaType <- Try {
          mediaTypeBodyPart.entity.asString
        }.toOption
        meta <- Some(Meta(fileNameBodyPart.entity.asString,
          fileSize,
          mediaType,
          fileHashBodyPart.entity.asString))
      } yield FileUploading.FileChunk((chunk, chunks), file, meta)

      fileUpload match {
        case Some(f) => f
      }
    }

  case class FileServerMessage(part: ByteString, offset: Long, totalSize: Long, jsonWebToken: JsonWebToken, fileId: UUID)

  implicit val FileServerMessageMarshaller =
    Marshaller.of[FileServerMessage](`application/octet-stream`) { (value, contentType, ctx) =>
      ctx.marshalTo(HttpEntity(value.part.toArray),
        Authorization(GenericHttpCredentials("Bearer", value.jsonWebToken.original)),
        `Content-Disposition`("attachment", Map("filename" -> value.fileId.toString)),
        RawHeader("Session-ID", value.fileId.toString),
        RawHeader("X-Content-Range", s"bytes ${value.offset}-${value.offset+value.part.size-1}/${value.totalSize}")
      )
    }

  case class ErrorMessage(error: String)

  implicit val errorMessageUnmarshaller = jsonFormat1(ErrorMessage)
}
