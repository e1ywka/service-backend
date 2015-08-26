/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi

import java.util.UUID

import akka.util.ByteString
import ru.infotecs.edi.security.{JsonWebToken}
import ru.infotecs.edi.service.FileUploading.{Meta, FileChunk}
import spray.http.HttpHeaders.{RawHeader, `Content-Disposition`, Authorization}
import spray.http._
import spray.httpx.marshalling.Marshaller
import spray.httpx.unmarshalling._

import scala.util.Try

package object service {

  import MediaTypes.{`application/octet-stream`, `multipart/form-data`}

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
}
