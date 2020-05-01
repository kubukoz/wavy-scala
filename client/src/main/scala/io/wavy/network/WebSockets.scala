package io.wavy.network

import cats.implicits._
import org.scalajs.dom.raw.WebSocket
import fs2.concurrent.Queue
import org.scalajs.dom.raw.MessageEvent
import fs2.Stream
import cats.effect.implicits._
import io.circe.Decoder
import cats.effect.Sync
import cats.effect.ConcurrentEffect
import cats.effect.Resource

object WebSockets {

  def backpressuredStream[F[_]: ConcurrentEffect, A: Decoder, B](
    url: String,
    bufferSize: Int
  )(
    requestMore: WebSocket => F[Unit]
  )(
    unpack: A => List[B]
  ): Stream[F, B] = {
    def websocket(handle: WebSocket => MessageEvent => F[Unit]) =
      Resource.make(Sync[F].delay(new WebSocket(url)))(ws => Sync[F].delay(ws.close())).evalTap { ws =>
        Sync[F].delay {
          ws.onmessage = handle(ws)(_).toIO.unsafeRunAsyncAndForget()
          ws.onopen = _ => requestMore(ws).toIO.unsafeRunAsyncAndForget()
        }
      }

    Stream.eval(Queue.bounded[F, B](bufferSize)).flatMap { q =>
      val handle: WebSocket => MessageEvent => F[Unit] = ws =>
        _.data match {
          case data: String =>
            val pushToQueue = Stream.evalSeq(io.circe.parser.decode[A](data).liftTo[F].map(unpack)).through(q.enqueue).compile.drain

            pushToQueue *> requestMore(ws)

          case _ => new Throwable("Message data wasn't a string").raiseError[F, Unit]
        }

      Stream.resource(websocket(handle)) *> q.dequeue
    }
  }
}
