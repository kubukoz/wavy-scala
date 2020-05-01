package io.wavy

import slinky.core._
import slinky.web.html._
import slinky.core.facade.Hooks._
import cats.implicits._
import components.Input
import components.SineCanvas
import components.SineCanvas.Screen
import resources.AppCSS
import resources.ReactLogo
import hooks._
import sttp.client._
import sttp.client.circe._
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

import runtime._

object Composition {

  val component: FunctionalComponent[Unit] = FunctionalComponent { _ =>
    val screen = Screen(width = useWindowWidth() min 1000, height = 400)

    val (period, setPeriod) = useState(10.0)
    val (amplitude, setAmplitude) = useState(50.0)
    val (phase, setPhase) = useState(0.0)

    val params = Parameters(period, amplitude, phase, Noise(0.0, 0.0))

    useUpdateParams(params)

    div(
      SineCanvas.component.apply(screen),
      Input("Period", period)(setPeriod),
      Input("Amplitude", amplitude)(setAmplitude),
      Input("Phase", phase)(setPhase),
      s"Settings: Period $period amp $amplitude phase $phase, screen $screen"
    )
  }
}

object App {

  val component: FunctionalComponent[Unit] = FunctionalComponent { _ =>
    val css = AppCSS
    val _ = css

    div(className := "App")(
      header(className := "App-header")(
        img(src := ReactLogo.asInstanceOf[String], className := "App-logo", alt := "logo"),
        Composition.component(())
      )
    )
  }
}
