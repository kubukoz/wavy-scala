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

  val useUpdateParams: Parameters => Unit = { params =>
    val asList = List(params.amplitude, params.noise.factor, params.noise.rate, params.period, params.phase)
    useEffect(() => {

      import com.softwaremill.sttp._

      sendJSON(sttp.put(uri"http://localhost:4000/params"), params).unsafeRunAsyncAndForget()

    }, asList)
  }

  type Draw[A] = Kleisli[SyncIO, CanvasRenderingContext2D, A]

  def get2dContext(canvas: HTMLCanvasElement): SyncIO[CanvasRenderingContext2D] =
    SyncIO(canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D])

  def renderCanvas(canvas: HTMLCanvasElement, screen: Screen, samples: List[Sample]): SyncIO[Unit] = {
    val setCanvasSize = SyncIO {
      canvas.width = screen.width.toInt
      canvas.height = screen.height.toInt
    }

    val drawBackground: Draw[Unit] = Kleisli { ctx =>
      SyncIO {
        ctx.fillStyle = "black"
        ctx.fillRect(0, 0, screen.width, screen.height)
      }
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

    setCanvasSize *> get2dContext(canvas).flatTap((drawBackground *> drawWave).run).flatMap { ctx =>
      SyncIO {
        ctx.stroke()
      }
    }
  }

  val SineCanvas: FunctionalComponent[(Screen, List[Sample])] = FunctionalComponent {
    case (screen, samples) =>
      val c = useRef(none[HTMLCanvasElement])

      c.current.traverse(renderCanvas(_, screen, samples)).unsafeRunSync()

      canvas(
        ref := (r => c.current = Some(r))
      )
  }

  val component: FunctionalComponent[Unit] = FunctionalComponent { _ =>
    val screen = Screen(width = useWindowWidth() min 1000, height = 400)

    val (period, setPeriod) = useState(10.0)
    val (amplitude, setAmplitude) = useState(50.0)
    val (phase, setPhase) = useState(0.0)

    val (samples, setSamples) = useState(List.empty[Sample])

    println(s"Got ${samples.size} samples")
    def appendMaxLength[A](old: List[A], newer: List[A], maxLength: Int): List[A] = (old ++ newer).takeRight(maxLength)

    val params = Parameters(period, amplitude, phase, Noise(0.0, 0.0))

    useEffect(() => {
      val ws = new WebSocket("ws://localhost:4000/samples")
      ws.onmessage = e => {
        io.circe.parser.decode[List[Sample]](e.data.asInstanceOf[String]).foreach { newSamples =>
          setSamples(appendMaxLength(_, newSamples, screen.width.toInt))
        }
      }

      () => ws.close()
    }, List(screen.width))
    useUpdateParams(params)

    div(
      SineCanvas((screen, samples)),
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

    div(className := "App")(
      header(className := "App-header")(
        img(src := ReactLogo.asInstanceOf[String], className := "App-logo", alt := "logo"),
        Composition.component(())
      )
    )
  }
}
