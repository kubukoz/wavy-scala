package io.wavy

import sttp.client._
import cats.effect.IO
import cats.effect.ContextShift
import scala.concurrent.ExecutionContext
import cats.effect.Timer
import sttp.client.ws.WebSocketResponse
import sttp.client.impl.cats.implicits._
import scala.concurrent.Future

object runtime {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  val backend = FetchBackend()
  def sendRequest[T](req: Request[T, Nothing]): IO[Response[T]] = IO.fromFuture(IO(backend.send(req)))

  def deferBackend[S, WS[_]](underlying: SttpBackend[Future, S, WS]): SttpBackend[IO, S, WS] =
    new SttpBackend[IO, S, WS] {
      def send[T](request: Request[T, S]): IO[Response[T]] = IO.fromFuture(IO(underlying.send(request)))

      def openWebsocket[T, WS_RESULT](request: Request[T, S], handler: WS[WS_RESULT]): IO[WebSocketResponse[WS_RESULT]] =
        IO.fromFuture(IO(underlying.openWebsocket(request, handler)))

      def close(): IO[Unit] = IO.fromFuture(IO(underlying.close()))

      def responseMonad: sttp.client.monad.MonadError[IO] = catsMonadError[IO]
    }

  implicit val fetchBackend: SttpBackend[IO, Nothing, NothingT] = deferBackend[Nothing, NothingT](backend)
}
