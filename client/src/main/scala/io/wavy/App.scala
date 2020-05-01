package io.wavy

import slinky.core._
import slinky.web.html._
import slinky.core.facade.Hooks._
import cats.implicits._
import components.Input
import resources.AppCSS
import resources.ReactLogo
import hooks._
import io.circe.syntax._
import com.softwaremill.sttp.FetchBackend
import cats.effect.IO
import com.softwaremill.sttp.Response
import com.softwaremill.sttp.Request
import cats.effect.ContextShift
import scala.concurrent.ExecutionContext
import cats.effect.Timer
import io.circe.Encoder
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

final case class Screen(width: Double, height: Double)

object runtime {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
}

import runtime._

object Composition {
  val backend = FetchBackend()
  def sendRequest[T](req: Request[T, Nothing]): IO[Response[T]] = IO.fromFuture(IO(backend.send(req)))

  def sendJSON[T: Encoder](req: Request[String, Nothing], t: T): IO[Response[String]] =
    sendRequest(req.contentType("application/json").body(t.asJson.noSpaces))

  val useUpdateParams: Parameters => Unit = params => {
    val asList = List(params.amplitude, params.noise.factor, params.noise.rate, params.period, params.phase)

    useIO {
      import com.softwaremill.sttp._

      sendJSON(sttp.put(uri"http://localhost:4000/params"), params).void
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

  def clientProcess(screen: Screen, canvas: HTMLCanvasElement): SyncIO[(IO[Unit], WebSocket)] =
    Queue.in[SyncIO].bounded[IO, Sample](screen.width.toInt * 2).flatMap { q =>
      val ws = SyncIO(new WebSocket("ws://localhost:4000/samples")).flatTap { ws =>
        val requestMore: SyncIO[Unit] = SyncIO(ws.send(""))

        val handle: MessageEvent => IO[Unit] =
          _.data match {
            case data: String =>
              val pushToQueue = Stream.evalSeq(io.circe.parser.decode[List[Sample]](data).liftTo[IO]).through(q.enqueue).compile.drain
              pushToQueue *> requestMore.toIO

            case _ => IO.raiseError(new Throwable("Message data wasn't a string"))
          }

        SyncIO {
          ws.onmessage = handle(_).unsafeRunAsyncAndForget()
          ws.onopen = _ => requestMore.unsafeRunSync()
        }
      }

      import scala.concurrent.duration._

      val process = q
        .dequeue
        .sliding(screen.width.toInt)
        .metered(1.second / 60)
        .evalMap { samples =>
          renderCanvas(canvas, screen, samples).toIO
        }
        .compile
        .drain

      ws.tupleLeft(process)
    }

  val SineCanvas: FunctionalComponent[Screen] = FunctionalComponent { screen =>
    val canvasRef = useRef(none[HTMLCanvasElement])

    useIO {
      for {
        canvas        <- IO(canvasRef.current.toRight(new Exception("No canvas!"))).rethrow
        (process, ws) <- clientProcess(screen, canvas).toIO
        result        <- process.onCancel(IO(ws.close()))
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
