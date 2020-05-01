package io.wavy

import slinky.core._
import slinky.web.html._
import slinky.core.facade.Hooks._
import cats.implicits._
import components.Input
import resources.AppCSS
import resources.ReactLogo
import hooks._
import sttp.client._
import sttp.client.circe._
import cats.effect.IO
import cats.effect.ContextShift
import scala.concurrent.ExecutionContext
import cats.effect.Timer
import org.scalajs.dom.raw.WebSocket
import io.wavy.newtypes.Sample
import org.scalajs.dom.raw.HTMLCanvasElement
import cats.effect.SyncIO
import org.scalajs.dom.raw.CanvasRenderingContext2D
import cats.data.Kleisli
import fs2.concurrent.Queue
import org.scalajs.dom.raw.MessageEvent
import cats.Traverse
import fs2.Stream
import cats.effect.implicits._
import io.circe.Decoder
import cats.effect.Sync
import algebras._
import cats.Monad
import cats.effect.ConcurrentEffect
import cats.effect.Resource

final case class Screen(width: Double, height: Double)

object runtime {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
}

import runtime._

object Composition {
  val backend = FetchBackend()
  def sendRequest[T](req: Request[T, Nothing]): IO[Response[T]] = IO.fromFuture(IO(backend.send(req)))

  val useUpdateParams: Parameters => Unit = params => {
    val asList = List(params.amplitude, params.noise.factor, params.noise.rate, params.period, params.phase)

    val req = basicRequest.put(uri"http://localhost:4000/params").body(params).response(ignore)

    useIO {
      sendRequest(req).map(_.body)
    }(asList)
  }

  type Draw[M[_], A] = Kleisli[M, RenderingContext2D[M], A]

  object Draw {
    def apply[M[_], A](f: RenderingContext2D[M] => M[A]): Draw[M, A] = Kleisli(f)
  }

  def get2dContext(canvas: HTMLCanvasElement): SyncIO[CanvasRenderingContext2D] =
    SyncIO(canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D])

  def renderCanvas[M[_]: Monad: CanvasOps, F[_]: Traverse](screen: Screen)(draw: RenderingContext2D[M] => M[Unit]): M[Unit] =
    for {
      _   <- CanvasOps[M].setHeight(screen.height.toInt)
      _   <- CanvasOps[M].setWidth(screen.width.toInt)
      ctx <- CanvasOps[M].get2dContext
      _   <- draw(ctx)
    } yield ()

  def drawLines[M[_]: RenderingContext2D: Monad, F[_]: Traverse](screen: Screen, samples: F[Sample]): M[Unit] = {

    val startWave: M[Unit] =
      RenderingContext2D[M].strokeStyle("white") *>
        RenderingContext2D[M].lineWidth(2.0) *>
        RenderingContext2D[M].beginPath

    def drawSample(sample: Sample, index: Int): M[Unit] =
      RenderingContext2D[M].lineTo(index.toDouble, (-sample.value + screen.height / 2))

    startWave *> samples.traverseWithIndexM(drawSample).void
  }

  import scala.concurrent.duration._

  def websocketStream[F[_]: ConcurrentEffect, A: Decoder, B](
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

  def clientProcess[M[_]: CanvasOps: ConcurrentEffect: Timer](screen: Screen): M[Unit] =
    websocketStream[M, List[Sample], Sample](url = "ws://localhost:4000/samples", bufferSize = screen.width.toInt * 2) { ws =>
      Sync[M].delay(ws.send(""))
    }(identity)
      .sliding(screen.width.toInt)
      .metered(1.second / 60)
      .evalMap { samples =>
        renderCanvas[M, scala.collection.immutable.Queue](screen) { implicit ctx =>
          drawLines(screen, samples)
        }
      }
      .compile
      .drain

  val SineCanvas: FunctionalComponent[Screen] = FunctionalComponent { screen =>
    val canvasRef = useRef(none[HTMLCanvasElement])

    val downloadAndDraw = IO(canvasRef.current.toRight(new Exception("No canvas!"))).rethrow.flatMap { canvas =>
      implicit val canvasOps = CanvasOps.fromCanvas[IO](canvas)

      clientProcess[IO](screen)
    }

    useIO { downloadAndDraw }(List(screen.width))

    canvas(
      ref := (r => canvasRef.current = Some(r))
    )
  }

  val component: FunctionalComponent[Unit] = FunctionalComponent { _ =>
    val screen = Screen(width = useWindowWidth() min 1000, height = 400)

    val (period, setPeriod) = useState(10.0)
    val (amplitude, setAmplitude) = useState(50.0)
    val (phase, setPhase) = useState(0.0)

    val params = Parameters(period, amplitude, phase, Noise(0.0, 0.0))

    useUpdateParams(params)

    div(
      SineCanvas(screen),
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
