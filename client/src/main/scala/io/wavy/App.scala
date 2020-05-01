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

  type Draw[A] = Kleisli[SyncIO, CanvasRenderingContext2D, A]

  def get2dContext(canvas: HTMLCanvasElement): SyncIO[CanvasRenderingContext2D] =
    SyncIO(canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D])

  def renderCanvas[F[_]: Traverse](canvas: HTMLCanvasElement, screen: Screen, samples: F[Sample]): SyncIO[Unit] = {
    val setCanvasSize = SyncIO {
      canvas.width = screen.width.toInt
      canvas.height = screen.height.toInt
    }

    def drawSample(sample: Sample, index: Int): Draw[Unit] = Kleisli { ctx =>
      SyncIO { ctx.lineTo(index.toDouble, (-sample.value + screen.height / 2)) }
    }

    val startWave: Draw[Unit] = Kleisli { ctx =>
      SyncIO {
        ctx.strokeStyle = "white"
        ctx.lineWidth = 2.0
        ctx.beginPath()
      }
    }

    val drawWave: Draw[Unit] =
      startWave *> samples.traverseWithIndexM(drawSample).void

    for {
      _   <- setCanvasSize
      ctx <- get2dContext(canvas)
      _   <- drawWave.run(ctx)
      _   <- SyncIO(ctx.stroke())
    } yield ()
  }

  import scala.concurrent.duration._

  def websocketStream[A: Decoder, B](
    url: String,
    bufferSize: Int,
    requestMore: WebSocket => IO[Unit]
  )(
    unpack: A => List[B]
  ): Stream[IO, B] =
    Stream.eval(Queue.bounded[IO, B](bufferSize)).flatMap { q =>
      val handle: WebSocket => MessageEvent => IO[Unit] = ws =>
        _.data match {
          case data: String =>
            val pushToQueue = Stream.evalSeq(io.circe.parser.decode[A](data).liftTo[IO].map(unpack)).through(q.enqueue).compile.drain
            pushToQueue *> requestMore(ws).toIO

          case _ => IO.raiseError(new Throwable("Message data wasn't a string"))
        }

      Stream.bracket(IO(new WebSocket(url)))(ws => IO(ws.close())).evalTap { ws =>
        IO {
          ws.onmessage = handle(ws)(_).unsafeRunAsyncAndForget()
          ws.onopen = _ => requestMore(ws).unsafeRunAsyncAndForget()
        }
      } *> q.dequeue
    }

  def clientProcess(screen: Screen, canvas: HTMLCanvasElement): IO[Unit] =
    websocketStream[List[Sample], Sample]("ws://localhost:4000/samples", screen.width.toInt * 2, ws => IO(ws.send("")))(identity)
      .sliding(screen.width.toInt)
      .metered(1.second / 60)
      .evalMap { samples =>
        renderCanvas(canvas, screen, samples).toIO
      }
      .compile
      .drain

  val SineCanvas: FunctionalComponent[Screen] = FunctionalComponent { screen =>
    val canvasRef = useRef(none[HTMLCanvasElement])

    useIO {
      for {
        canvas <- IO(canvasRef.current.toRight(new Exception("No canvas!"))).rethrow
        result <- clientProcess(screen, canvas)
      } yield result
    }(List(screen.width))

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
