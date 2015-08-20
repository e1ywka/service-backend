/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.security

import java.io.IOException

import net.iharder.Base64
import ru.infotecs.edi.security.Alg.Alg

import spray.json.DefaultJsonProtocol

case class Jwt(iss: String, sub: Option[String], aud: Vector[String], exp: String, nbf: Option[String], iat: String,
               jti: Option[String], cty: Option[String], pid: String, cid: String)

case class Jws(alg: Alg, typ: String)

object Alg extends Enumeration {
  type Alg = Value
  val none, HS256 = Value
}

object JsonWebToken extends DefaultJsonProtocol {
  import spray.json._

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
      case Right((jws_, jwt_, signature_)) => ValidJsonWebToken(jws_, jwt_, signature_)
      case Left(_) => InvalidJsonWebToken
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

case class ValidJsonWebToken(jws: Jws, jwt: Jwt, signature: Option[String]) extends JsonWebToken(Some(jws), Some(jwt), signature)

case object InvalidJsonWebToken extends JsonWebToken(None, None, None)

abstract class JsonWebToken (jws: Option[Jws], jwt: Option[Jwt], signature: Option[String])
