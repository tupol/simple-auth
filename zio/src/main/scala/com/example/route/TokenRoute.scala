package com.example.route

import com.example.SimpleAuthConfig
import com.example.auth.{ KeyTools, TokenCreator }
import com.example.db.{ DbQuery, HikariConnectionPool }
import zio.{ Console, Duration, UIO, ZIO }
import zio.http._
import zio.http.model.Method
import zio.json._

import java.nio.charset.StandardCharsets.UTF_8
import java.time.temporal.ChronoUnit.NANOS
import java.util.UUID

case class TokenRequest(username: String, password: String)
object TokenRequest {
  implicit val decoder = DeriveJsonDecoder.gen[TokenRequest]
}

case class TokenResponse(access_token: String, id_token: String)
object TokenResponse {
  implicit val encoder = DeriveJsonEncoder.gen[TokenResponse]
}

object TokenRoute {

  val app: Http[SimpleAuthConfig with HikariConnectionPool with TokenCreator, Nothing, Request, Response] =
    Http.collectZIO[Request] {
      case Method.GET -> !! / "token" =>
        for {
          resp <- ZIO.succeed(Response.text("Try POSTing"))
        } yield resp
      case req @ Method.POST -> !! / "token" =>
        (for {
          tokenReq <- ZIO.absolve(req.body.asString(UTF_8).map(_.fromJson[TokenRequest]))
          pool     <- ZIO.service[HikariConnectionPool]
          tuple1   <- ZIO.fromTry(DbQuery.userInfo(tokenReq.username, pool)).timed
          userInfo = tuple1._2
          tuple2 <- ZIO
                     .fromTry(
                       KeyTools.verifyHmacHash(tokenReq.password.getBytes(UTF_8), userInfo.salt, userInfo.hashpassword)
                     )
                     .timed
          _            <- ZIO.cond(tuple2._2, ZIO.succeed(()), ZIO.fail("Incorrect password"))
          tokenCreator <- ZIO.service[TokenCreator]
          tuple3       <- ZIO.fromTry(tokenCreator.createTokenPair(userInfo, UUID.randomUUID().toString)).timed
          tokenPair    = tuple3._2
          response     = TokenResponse(tokenPair.accessToken.rawToken, tokenPair.idToken.rawToken)
          _ <- Console.printLine(
                s"ZIO Db time ${toMs(tuple1._1)} Password hash ${toMs(tuple2._1)} Token creation ${toMs(tuple3._1)}."
              )
          resp <- ZIO.succeed(Response.json(response.toJson))
        } yield resp).catchAll(ex => ZIO.succeed(Response.text(s"error $ex")))
    }

  private def toMs(dur: Duration) = s"${dur.get(NANOS) / 1e6}ms"
}
