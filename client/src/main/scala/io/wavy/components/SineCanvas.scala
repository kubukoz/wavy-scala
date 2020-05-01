package io.wavy.components

import slinky.core._
import slinky.web.html._
import slinky.core.facade.Hooks._
import cats.implicits._
import cats.effect.IO
import cats.effect.Timer
import io.wavy.newtypes.Sample
import org.scalajs.dom.raw.HTMLCanvasElement
import cats.Traverse
import cats.effect.Sync
import cats.Monad
import cats.effect.ConcurrentEffect
import io.wavy.algebras.CanvasOps
import io.wavy.algebras.RenderingContext2D
import io.wavy.hooks.useIO
import io.wavy.network.WebSockets

object SineCanvas {
  final case class Screen(width: Double, height: Double)

  def component(implicit timer: Timer[IO], concEff: ConcurrentEffect[IO]): FunctionalComponent[Screen] = FunctionalComponent { screen =>
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

  def renderCanvas[M[_]: Monad: CanvasOps](screen: Screen): M[RenderingContext2D[M]] =
    CanvasOps[M].setHeight(screen.height.toInt) *>
      CanvasOps[M].setWidth(screen.width.toInt) *>
      CanvasOps[M].get2dContext

  def drawLines[M[_]: RenderingContext2D: Monad, F[_]: Traverse](screen: Screen, samples: F[Sample]): M[Unit] = {

    val startWave: M[Unit] =
      RenderingContext2D[M].strokeStyle("white") *>
        RenderingContext2D[M].lineWidth(2.0) *>
        RenderingContext2D[M].beginPath

    def drawSample(sample: Sample, index: Int): M[Unit] =
      RenderingContext2D[M].lineTo(index.toDouble, (-sample.value + screen.height / 2))

    startWave *> samples.traverseWithIndexM(drawSample).void *> RenderingContext2D[M].stroke
  }

  import scala.concurrent.duration._

  def clientProcess[M[_]: CanvasOps: ConcurrentEffect: Timer](screen: Screen): M[Unit] =
    WebSockets
      .backpressuredStream[M, List[Sample], Sample](url = "ws://localhost:4000/samples", bufferSize = screen.width.toInt * 2) { ws =>
        Sync[M].delay(ws.send(""))
      }(identity)
      .sliding(screen.width.toInt)
      .metered(1.second / 60)
      .evalMap { samples =>
        renderCanvas[M](screen).flatMap(implicit ctx => drawLines(screen, samples))
      }
      .compile
      .drain

}
