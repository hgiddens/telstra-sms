package com.github.hgiddens.ausms
package smscentral

import java.util.UUID
import org.http4s.{ Method, Request, Status, UrlForm }
import org.http4s.Http4s._
import org.http4s.Uri.uri
import org.http4s.client.Client
import org.http4s.scalaxml._
import org.log4s.getLogger
import scala.util.Try
import scala.xml.Elem
import scalaz.Scalaz._
import scalaz.concurrent.Task

abstract class SmsCentralException(message: String) extends RuntimeException(message)
final class SmsCentralFailure(val code: Int, message: String) extends SmsCentralException(message)
final class SmsCentralError(message: String) extends SmsCentralException(message)

final class SmsCentralClient(client: Client, config: SmsCentralClient.Config) extends SmsClient[Task] {
  private[this] val log = getLogger
  private[this] val base = uri("https://my.smscentral.com.au/api/v3.2")
  private[this] val ErrorRx = """(\d{1,8})\s*(.*)""".r

  def sendMessage(to: PhoneNumber, message: Message): Task[MessageId] =
    for {
      uuid <- Task.delay(UUID.randomUUID())
      id = MessageId(uuid.toString)
      params = UrlForm(
        "USERNAME" -> config.username,
        "PASSWORD" -> config.password,
        "ACTION" -> "send",
        "ORIGINATOR" -> config.originator,
        "REFERENCE" -> id.value,
        "RECIPIENT" -> ("61" + to.value.tail),
        "MESSAGE_TEXT" -> message.value
      )
      request <- Request(Method.POST, base).withBody(params)
      body <- client.fetch(request) { response =>
        if (response.status === Status.Ok) response.as[String]
        else Task.fail(new SmsCentralError(s"Unexpected response status ${response.status.code}"))
      }
      _ <- body match {
        case "0" => Task.now(())
        case ErrorRx(code, error) => Task.fail(new SmsCentralFailure(code.toInt, error))
        case _ => Task.fail(new SmsCentralError(body))
      }
      _ <- Task.delay(log.debug(s"Message sent to ${to.shows} with id ${id.shows}"))
    } yield id

  def messageStatus(message: MessageId): Task[DeliveryStatus] = {
    def parseSuccess(elem: Elem): Task[DeliveryStatus] =
      Task.delay {
        (elem \ "message" \ "status").text match {
          case "PEND" => DeliveryStatus.Pending
          case "SENT" => DeliveryStatus.Sent
          case "DELIVRD" => DeliveryStatus.Delivered
          case "READ" => DeliveryStatus.Read
        }
      }

    def handleFailure(elem: Elem): SmsCentralException =
      Try {
        val code = (elem \ "errorcode").text.toInt
        val message = (elem \ "errormessage").text
        new SmsCentralFailure(code, message)
      }.toOption.getOrElse(new SmsCentralError(elem.toString))

    val params = UrlForm(
      "USERNAME" -> config.username,
      "PASSWORD" -> config.password,
      "REFERENCE" -> message.value
    )
    val request = Request(Method.POST, base / "checkstatus").withBody(params)

    for {
      elem <- client.expect[Elem](request).or(Task.fail(new SmsCentralError("Failure parsing response as XML")))
      status <- parseSuccess(elem).or(Task.fail(handleFailure(elem)))
    } yield status
  }
}
object SmsCentralClient {
  final class Config private[Config] (val username: String, val password: String, val originator: String)
  object Config {
    private[this] val OriginatorRx = """[A-Za-z0-9]{1,11}""".r
    def apply(username: String, password: String, originator: String): Option[Config] =
      originator match {
        case OriginatorRx() => new Config(username, password, originator).some
        case _ => none
      }
  }

  def apply(client: Client, config: SmsCentralClient.Config): SmsCentralClient =
    new SmsCentralClient(client, config)
}
