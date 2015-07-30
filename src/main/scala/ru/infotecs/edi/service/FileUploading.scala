package ru.infotecs.edi.service

import akka.actor.{Stash, Actor}
import akka.actor.Actor.Receive
import ru.infotecs.edi.service.FileUploading.FileChunk
import spray.http.BodyPart

object FileUploading {
  case class FileChunk(chunkOrder: (Int, Int), file: BodyPart, fileName: String)
}

/**
 * FileUploading aggregates uploaded file chunks and performs file parsing.
 */
class FileUploading extends Actor with Stash {

  var fileStream = Stream.empty
  var nextChunk = 0
  var totalChunks = 0

  val idle: Receive = {
    case FileChunk((chunk, chunks), filePart, fileName) =>
      if (chunk == 0) {
        nextChunk = 1
        totalChunks = chunks
        // выполнять парсинг файла
        println(filePart.entity.asString)
        if (chunks == 1) {
          context become uploaded
        } else {
          context become uploading
        }
      } else {
        stash()
      }
  }

  val uploading: Receive = {
    case FileChunk((chunk, _), filePart, fileName) =>
      if (chunk == nextChunk) {
        nextChunk += 1
        // выполнять парсинг файла
        println(filePart.entity.asString)
        if (nextChunk == totalChunks) {
          context become uploaded
        }
      } else {
        stash()
      }
  }

  val uploaded: Receive = {
    case _: FileChunk => {
      // логировать сообщение об ошибке
    }
  }

  def receive = idle
}
