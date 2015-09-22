/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.security

import java.io.IOException

import net.iharder.Base64

case class Jwt(iss: Option[String], sub: Option[String], aud: Option[Vector[String]], exp: Option[Int], nbf: Option[Int],
               iat: Option[Int], jti: Option[String], cty: Option[String], pid: String, cid: String)

case class Jws(alg: Alg.Alg, typ: String)

object Alg extends Enumeration {
  type Alg = Value
  val none, HS256 = Value
}

object JsonWebToken {
  import spray.json._
  import spray.json.DefaultJsonProtocol._

  implicit object JwsFormat extends RootJsonFormat[Jws] {
    override def read(json: JsValue): Jws = json.asJsObject.getFields("alg", "typ") match {
      case Seq(JsString(alg), JsString(typ)) => Jws(Alg.withName(alg), typ)
      case _ => throw new DeserializationException("Jws type expected")
    }

    override def write(jws: Jws): JsValue = JsObject(
      "alg" -> JsString(jws.alg.toString),
      "typ" -> JsString(jws.typ)
    )
  }

  implicit val jwtFormat = jsonFormat10(Jwt)

  def apply(token: String): JsonWebToken = {
    parseToken(token) match {
      case Right((jws_, jwt_, signature_)) => ValidJsonWebToken(token, jws_, jwt_, signature_)
      case Left(_) => InvalidJsonWebToken(token)
    }
  }

  private def parseToken(token: String): Either[IOException, (Jws, Jwt, Option[String])] = {
    val parts: Array[String] = token.split("\\.")
    try {
      val jws = base64urlDecoded(parts(0)).parseJson.convertTo[Jws]
      val jwt = base64urlDecoded(parts(1)).parseJson.convertTo[Jwt]
      val signature = {
        if (parts.length == 3) {
          Some(parts(2))
        } else {
          None
        }
      }
      Right(jws, jwt, signature)
    } catch {
      case e: IOException => Left(e)
      case e => throw new RuntimeException(e)
    }
  }

  @throws(classOf[IOException])
  private def base64urlDecoded (base64url: String): String = {
    var base64: String = base64url
    val lastGroupLength: Int = base64.length % 4
    if (lastGroupLength == 2) {
      base64 += "=="
    }
    else if (lastGroupLength == 3) {
      base64 += "="
    }
    new String(Base64.decode(base64, Base64.URL_SAFE))
  }
}

case class ValidJsonWebToken(override val original: String, jws: Jws, jwt: Jwt, signature: Option[String])
  extends JsonWebToken(original, Some(jws), Some(jwt), signature)

case class InvalidJsonWebToken(override val original: String) extends JsonWebToken(original, None, None, None)

abstract class JsonWebToken (val original: String, jws: Option[Jws], jwt: Option[Jwt], signature: Option[String])
