/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.security

import org.scalatest.{Matchers, FlatSpec}

class JsonWebTokenSpec extends FlatSpec with Matchers {

  val tokenWithSignature =
    """eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJwaWQiOiI5ZTQ4YjlhNy05ODYzLTRhZWUtYTYzYS0zYTIzZjhjNDUxODMiLCJjaWQiOiJjNzUwOTE0MS1iMWIwLTQ3ZGItODU4Yy0wZjI1Nzc0M2EwM2MiLCJpYXQiOjE0Mzk5NzI1MTMsImV4cCI6MTQzOTk3NzkxMywiYXVkIjpbIkIyQiJdLCJpc3MiOiJDQSJ9.Gqfg1IAAah7IpdTgNMM5fnGXHBBzt1nCejSaAt3hWBI"""

  val tokenWithoutSignature =
    """eyJ0eXAiOiJKV1QiLCJhbGciOiJub25lIn0.eyJwaWQiOiI5ZTQ4YjlhNy05ODYzLTRhZWUtYTYzYS0zYTIzZjhjNDUxODMiLCJjaWQiOiJjNzUwOTE0MS1iMWIwLTQ3ZGItODU4Yy0wZjI1Nzc0M2EwM2MiLCJpYXQiOjE0Mzk5NzI1MTMsImV4cCI6MTQzOTk3NzkxMywiYXVkIjpbIkIyQiJdLCJpc3MiOiJDQSJ9    """

  "JsonWebToken" should "parse token w/ signature" in {
    JsonWebToken(tokenWithSignature) match {
      case ValidJsonWebToken(jws, jwt, signature) => //pass
      case InvalidJsonWebToken => fail("Cannot parse token")
    }
  }

  "JsonWebToken" should "parse token w/o signature" in {
    JsonWebToken(tokenWithoutSignature) match {
      case ValidJsonWebToken(jws, jwt, signature) => //pass
      case InvalidJsonWebToken => fail("Cannot parse token")
    }
  }
}
