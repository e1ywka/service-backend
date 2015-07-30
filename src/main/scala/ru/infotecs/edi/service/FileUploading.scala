package ru.infotecs.edi.service

import akka.actor._
import akka.actor.Actor.Receive
import ru.infotecs.edi.service.FileUploading.FileChunk
import spray.http.BodyPart

import scala.concurrent.Future

object FileUploading {
  case class FileChunk(chunkOrder: (Int, Int), file: BodyPart, fileName: String)
}

/**
 * FileUploading aggregates uploaded file chunks and performs file parsing.
 */
class FileUploading extends Actor {
  import context._

  val fileHandlers = new scala.collection.mutable.HashMap[String, ActorRef]

  def receive: Receive = {
    case f@FileChunk(_, _, fileName) => {
      val handler = fileHandlers.getOrElse(fileName, actorOf(Props[FileHandler]))
      watch(handler)
      handler ! f
    }

    case Terminated(h) => {
      fileHandlers.retain((_, handler) => handler.equals(h))
      if (fileHandlers.size == 0) {
        context.stop(self)
      }
    }
  }
}

class FileHandler extends Actor with Stash {
  var fileStream = Stream.empty
  var nextChunk = 0
  var totalChunks = 0
  implicit val ec = context.system.dispatcher

  val idle: Receive = {
    case FileChunk((chunk, chunks), filePart, fileName) =>
      if (chunk == 0) {
        nextChunk = 1
        totalChunks = chunks
        // выполнять парсинг файла
        Future { println(filePart.entity.asString) }
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
        Future { println(filePart.entity.asString) }

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
      println("boo!")
    }
  }

  def receive = idle
}
