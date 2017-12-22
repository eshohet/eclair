package fr.acinq.eclair.api

import java.net.InetSocketAddress
import java.util.NoSuchElementException

import akka.actor.ActorRef
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers.HttpOriginRange.*
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.pattern.ask
import akka.util.Timeout
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.Kit
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import fr.acinq.eclair.payment.{PaymentRequest, PaymentResult, ReceivePayment, SendPayment}
import fr.acinq.eclair.wire.{ChannelAnnouncement, NodeAnnouncement}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JInt, JString}
import org.json4s.{JValue, jackson}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object JSON_CONST {
  val id: String = "eclair-node"
  val version: String = "1.0"
}

// @formatter:off
case class JsonRPCBody(jsonrpc: String = JSON_CONST.version, id: String = JSON_CONST.id, method: String, params: Seq[JValue])
case class Error(code: Int, message: String)
case class JsonRPCRes(result: AnyRef, error: Option[Error], id: String)
case class Status(node_id: String)
case class GetInfoResponse(nodeId: PublicKey, alias: String, port: Int, chainHash: BinaryData, blockHeight: Int)
case class ChannelInfo(shortChannelId: String, nodeId1: PublicKey, nodeId2: PublicKey)
final case object UnknownMethodRejection extends Rejection
final case class UnknownParamsRejection(message: String) extends Rejection
final case object NotFoundRejection extends Rejection
// @formatter:on

trait Service extends Logging {

  implicit def ec: ExecutionContext = ExecutionContext.Implicits.global

  implicit val serialization = jackson.Serialization
  implicit val formats = org.json4s.DefaultFormats + new BinaryDataSerializer + new StateSerializer + new ShaChainSerializer + new PublicKeySerializer + new PrivateKeySerializer + new ScalarSerializer + new PointSerializer + new TransactionWithInputInfoSerializer + new OutPointKeySerializer
  implicit val timeout = Timeout(30 seconds)
  implicit val shouldWritePretty: ShouldWritePretty = ShouldWritePretty.True

  import Json4sSupport.{marshaller, unmarshaller}

  def appKit: Kit

  val customHeaders = `Access-Control-Allow-Origin`(*) ::
    `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(PUT, GET, POST, DELETE, OPTIONS) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) ::
    `Access-Control-Allow-Headers`("x-requested-with") :: Nil

  val myExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      extractRequest { request =>
        logger.info(s"API call failed with cause=${t.getMessage}")
        complete(StatusCodes.InternalServerError, JsonRPCRes(null, Some(Error(StatusCodes.InternalServerError.intValue, t.getMessage)), JSON_CONST.id))
      }
  }

  def completeRpcFuture(future: Future[AnyRef]): Route = onComplete(future) {
    case Success(s) => completeRpc(s)
    case Failure(_) => reject
  }
  def completeRpc(result: AnyRef): Route = complete(JsonRPCRes(result, None, JSON_CONST.id))

  val myRejectionHandler: RejectionHandler = RejectionHandler.newBuilder()
    .handleNotFound {
      complete(StatusCodes.NotFound, JsonRPCRes(null, Some(Error(StatusCodes.NotFound.intValue, "not found")), JSON_CONST.id))
    }
    .handle {
      case v: ValidationRejection ⇒ complete(StatusCodes.BadRequest, JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, v.message)), JSON_CONST.id))
      case NotFoundRejection ⇒ complete(StatusCodes.NotFound, JsonRPCRes(null, Some(Error(StatusCodes.NotFound.intValue, "not found")), JSON_CONST.id))
      case UnknownMethodRejection ⇒ complete(StatusCodes.BadRequest, JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, "method not found")), JSON_CONST.id))
      case p: UnknownParamsRejection ⇒ complete(StatusCodes.BadRequest, JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, s"invalid parameters for this method, should be: ${p.message}")), JSON_CONST.id))
      case r ⇒ logger.error(s"API call failed with cause=$r")
        complete(StatusCodes.BadRequest, JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, r.toString)), JSON_CONST.id))
    }
    .result()

  val route: Route =
    respondWithDefaultHeaders(customHeaders) {
      handleExceptions(myExceptionHandler) {
        handleRejections(myRejectionHandler) {
          pathSingleSlash {
            post {
              entity(as[JsonRPCBody]) {
                req =>
                  val kit = appKit
                  import kit._

                  req.method match {
                    // utility methods
                    case "getinfo"      => completeRpcFuture(getInfoResponse)
                    case "help"         => completeRpc(help)

                    // channel lifecycle methods
                    case "connect"      => req.params match {
                      case JString(nodeId) :: JString(host) :: JInt(port) :: Nil =>
                        completeRpcFuture((switchboard ? NewConnection(PublicKey(nodeId), new InetSocketAddress(host, port.toInt), None)).mapTo[String])
                      case _ => reject(UnknownParamsRejection("[nodeId, host, port]"))
                    }
                    case "open"         => req.params match {
                      case JString(nodeId) :: JString(host) :: JInt(port) :: JInt(fundingSatoshi) :: JInt(pushMsat) :: JInt(newChannel) :: Nil =>
                        completeRpcFuture((switchboard ? NewConnection(PublicKey(nodeId), new InetSocketAddress(host, port.toInt), Some(NewChannel(Satoshi(fundingSatoshi.toLong),
                          MilliSatoshi(pushMsat.toLong), Some(newChannel.toByte))))).mapTo[String])
                      case JString(nodeId) :: JString(host) :: JInt(port) :: JInt(fundingSatoshi) :: JInt(pushMsat) :: Nil =>
                        completeRpcFuture((switchboard ? NewConnection(PublicKey(nodeId), new InetSocketAddress(host, port.toInt), Some(NewChannel(Satoshi(fundingSatoshi.toLong),
                          MilliSatoshi(pushMsat.toLong), None)))).mapTo[String])
                      case _ => reject(UnknownParamsRejection("[nodeId, host, port, fundingSatoshi, pushMsat] or [nodeId, host, port, fundingSatoshi, pushMsat, newChannel]"))
                    }
                    case "close"        => req.params match {
                      case JString(identifier) :: Nil => completeRpc(sendToChannel(identifier, CMD_CLOSE(scriptPubKey = None)).mapTo[String])
                      case JString(identifier) :: JString(scriptPubKey) :: Nil => completeRpc(sendToChannel(identifier, CMD_CLOSE(scriptPubKey = Some(scriptPubKey))).mapTo[String])
                      case _ => reject(UnknownParamsRejection("[channelId] or [channelId, scriptPubKey]"))
                    }

                    // local network methods
                    case "peers"        => completeRpcFuture((switchboard ? 'peers).mapTo[Map[PublicKey, ActorRef]].map(_.map(_._1.toBin)))
                    case "channels"     => completeRpcFuture((register ? 'channels).mapTo[Map[Long, ActorRef]].map(_.keys))
                    case "channelsto"   => req.params match {
                      case JString(remoteNodeId) :: Nil => Try(PublicKey(remoteNodeId)) match {
                        case Success(pk) => completeRpcFuture((register ? 'channelsTo).mapTo[Map[BinaryData, PublicKey]].map(_.filter(_._2 == pk).keys))
                        case Failure(f) => reject(ValidationRejection(s"invalid remote node id '$remoteNodeId'"))
                      }
                      case _ => reject(UnknownParamsRejection("[remoteNodeId]"))
                    }
                    case "channel"      => req.params match {
                      case JString(identifier) :: Nil => completeRpcFuture(sendToChannel(identifier, CMD_GETINFO).mapTo[RES_GETINFO])
                      case _ => reject(UnknownParamsRejection("[channelId]"))
                    }

                    // global network methods
                    case "allnodes"     => completeRpcFuture((router ? 'nodes).mapTo[Iterable[NodeAnnouncement]].map(_.map(_.nodeId)))
                    case "allchannels"  => completeRpcFuture((router ? 'channels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelInfo(c.shortChannelId.toHexString, c.nodeId1, c.nodeId2))))

                    // payment methods
                    case "receive"      => req.params match {
                      // only the payment description is given: user may want to generate a donation payment request
                      case JString(description) :: Nil =>
                        completeRpcFuture((paymentHandler ? ReceivePayment(None, description)).mapTo[PaymentRequest].map(PaymentRequest.write))
                      // the amount is now given with the description
                      case JInt(amountMsat) :: JString(description) :: Nil =>
                        completeRpcFuture((paymentHandler ? ReceivePayment(Some(MilliSatoshi(amountMsat.toLong)), description)).mapTo[PaymentRequest].map(PaymentRequest.write))
                      case _ => reject(UnknownParamsRejection("[description] or [amount, description]"))
                    }
                    case "send"         => req.params match {
                      // user manually sets the payment information
                      case JInt(amountMsat) :: JString(paymentHash) :: JString(nodeId) :: Nil =>
                        (Try(BinaryData(paymentHash)), Try(PublicKey(nodeId))) match {
                          case (Success(ph), Success(pk)) => completeRpcFuture((paymentInitiator ? SendPayment(amountMsat.toLong, ph, pk)).mapTo[PaymentResult])
                          case (Failure(_), _) => reject(ValidationRejection(s"invalid payment hash '$paymentHash'"))
                          case _ => reject(ValidationRejection(s"invalid node id '$nodeId'"))
                        }
                      // user gives a Lightning payment request
                      case JString(paymentRequest) :: rest => Try(PaymentRequest.read(paymentRequest)) match {
                        case Success(pr) =>
                          // setting the payment amount
                          val amount_msat: Long = (pr.amount, rest) match {
                            // optional amount always overrides the amount in the payment request
                            case (_, JInt(amount_msat_override) :: Nil) => amount_msat_override.toLong
                            case (Some(amount_msat_pr), _) => amount_msat_pr.amount
                            case _ => throw new RuntimeException("you must manually specify an amount for this payment request")
                          }
                          logger.debug(s"api call for sending payment with amount_msat=$amount_msat")
                          // optional cltv expiry
                          val sendPayment = pr.minFinalCltvExpiry match {
                            case None => SendPayment(amount_msat, pr.paymentHash, pr.nodeId)
                            case Some(exp) => SendPayment(amount_msat, pr.paymentHash, pr.nodeId, exp)
                          }
                          completeRpcFuture((paymentInitiator ? sendPayment).mapTo[PaymentResult])
                        case _ => reject(ValidationRejection(s"payment request is not valid"))
                      }
                      case _ => reject(UnknownParamsRejection("[amountMsat, paymentHash, nodeId or [paymentRequest] or [paymentRequest, amountMsat]"))
                    }

                    // consult received payments
                    case "allpayments" => completeRpc(nodeParams.paymentsDb.listPayments())
                    case "payment" => req.params match {
                      case JString(identifier) :: Nil => Try(PaymentRequest.read(identifier)) match {
                        // payment identifier might be a payment request
                        case Success(pr) => Try(nodeParams.paymentsDb.findByPaymentHash(pr.paymentHash)) match {
                          case Failure(e: NoSuchElementException) => reject(NotFoundRejection)
                          case Success(p) => completeRpc(p)
                          case _ => reject
                        }
                        case Failure(f) => Try(BinaryData(identifier)) match {
                          // payment identifier might be a payment hash
                          case Success(payment_hash) => Try(nodeParams.paymentsDb.findByPaymentHash(payment_hash)) match {
                            case Failure(e: NoSuchElementException) => reject(NotFoundRejection)
                            case Success(p) => completeRpc(p)
                            case _ => reject
                          }
                          case Failure(_) => reject(ValidationRejection("payment identifier must be a Payment Request or a Payment Hash"))
                        }
                      }
                      case _ => reject(UnknownParamsRejection("[paymentHash] or [paymentRequest]"))
                    }

                    // method name was not found
                    case _ => reject(UnknownMethodRejection)
                  }
              }
            }
          }
        }
      }
    }

  def getInfoResponse: Future[GetInfoResponse]

  def help = List("connect (nodeId, host, port): connect to another lightning node through a secure connection",
    "open (nodeId, host, port, fundingSatoshi, pushMsat, channelFlags = 0x01): open a channel with another lightning node",
    "peers: list existing local peers",
    "channels: list existing local channels",
    "channelsto (nodeId): list existing local channels to a particular nodeId",
    "channel (channelId): retrieve detailed information about a given channel",
    "allnodes: list all known nodes",
    "allchannels: list all known channels",
    "receive (amountMsat, description): generate a payment request for a given amount",
    "send (amountMsat, paymentHash, nodeId): send a payment to a lightning node",
    "send (paymentRequest): send a payment to a lightning node using a BOLT11 payment request",
    "send (paymentRequest, amountMsat): send a payment to a lightning node using a BOLT11 payment request and a custom amount",
    "close (channelId): close a channel",
    "close (channelId, scriptPubKey): close a channel and send the funds to the given scriptPubKey",
    "allpayments: list all received payments",
    "payment (paymentHash or paymentRequest): returns the payment if it has been received",
    "help: display this message")

  /**
    * Sends a request to a channel and expects a response
    *
    * @param channelIdentifier can be a shortChannelId (8-byte hex encoded) or a channelId (32-byte hex encoded)
    * @param request
    * @return
    */
  def sendToChannel(channelIdentifier: String, request: Any): Future[Any] =
    for {
      fwdReq <- Future(Register.ForwardShortId(java.lang.Long.parseLong(channelIdentifier, 16), request))
        .recoverWith { case _ => Future(Register.Forward(BinaryData(channelIdentifier), request)) }
        .recoverWith { case _ => Future.failed(new RuntimeException(s"invalid channel identifier '$channelIdentifier'")) }
      res <- appKit.register ? fwdReq
    } yield res
}
