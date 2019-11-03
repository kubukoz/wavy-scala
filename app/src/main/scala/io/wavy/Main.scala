package io.wavy

import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import cats.effect.Timer
import io.wavy.config.AppConfig
import io.wavy.extensions.all._
import org.http4s.HttpRoutes
import org.http4s.implicits._
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeServerBuilder
import pureconfig.ConfigSource
import scala.concurrent.ExecutionContext
import org.http4s.server.middleware.CORS
import fs2.Stream
import io.wavy.newtypes.Sample
import cats.effect.Concurrent
import fs2.concurrent.SignallingRef
import cats.implicits._
import org.http4s.server.websocket.WebSocketBuilder
import io.circe.Encoder
import org.http4s.websocket.WebSocketFrame
import io.circe.syntax._
import io.wavy.http.HttpRouter
import org.http4s.circe.CirceEntityCodec._
import scala.concurrent.duration._
import cats.effect.Sync
import fs2.concurrent.Queue
import fs2.Pipe
import cats.effect.concurrent.Ref

trait Sampler[F[_]] {
  def update(params: Parameters): F[Unit]
  def samples: Stream[F, Sample]
}

object Sampler {

  def instance[F[_]: Concurrent]: F[Sampler[F]] = SignallingRef[F, Parameters](Parameters(10.0, 50.0, 0.0, Noise(0.0, 0.0))).map { config =>
    new Sampler[F] {
      val samples: Stream[F, Sample] = config.continuous.flatMap { params =>
        Stream.iterate(0.0)(_ + 0.01).takeWhile(_ <= params.period * 2 * 3.14 / 100).map { x =>
          val sine = Math.sin((x * params.period) + params.phase) * params.amplitude

          Sample(sine)
        }
      }

      def update(params: Parameters): F[Unit] = config.set(params)
    }
  }
}

class Application[F[_]: ConcurrentEffect: Timer: ContextShift](config: AppConfig)(implicit executionContext: ExecutionContext) {

  def showRate[A]: Pipe[F, A, A] =
    stream =>
      Stream.eval(Ref.of[F, Int](0)).flatMap { ref =>
        val count = stream.chunks.flatMap(c => Stream.eval_(ref.update(_ + c.size)) ++ Stream.chunk(c))

        val showCounts = Stream.awakeEvery[F](1.second).evalMap(_ => ref.getAndSet(0)).map(_ + " elements/second").showLinesStdOut

        count concurrently showCounts
      }

  def makeServer(router: HttpRoutes[F]): Resource[F, Server[F]] =
    BlazeServerBuilder[F]
      .withWebSockets(true)
      .withHttpApp(CORS(router.orNotFound, CORS.DefaultCORSConfig.copy(allowedOrigins = Set("localhost:8080"))))
      .withExecutionContext(executionContext)
      .bindHttp(config.http.port, "0.0.0.0")
      .resource

  val server: Resource[F, Server[F]] = Resource.suspend {
    (Sampler.instance[F], SignallingRef[F, Long](0L)).mapN { (sampler, frequencyPerSecond) =>
      def toFrame[A: Encoder](a: A): WebSocketFrame = WebSocketFrame.Text(a.asJson.noSpaces)

      makeServer(
        HttpRouter
          .make[F] { dsl =>
            import dsl._

            HttpRoutes.of[F] {
              case GET -> Root / "samples" =>
                Queue.bounded[F, Unit](10).flatMap { chunkRequests =>
                  def limiter[A]: Pipe[F, A, A] =
                    s =>
                      frequencyPerSecond.discrete.switchMap {
                        case 0L   => s
                        case freq => s.metered(1.second / freq)
                      }

                  val frames =
                    sampler
                      .samples
                      .through(showRate)
                      .through(limiter)
                      .groupWithin(1000, 1.second)
                      .zipLeft(chunkRequests.dequeue)
                      .map(_.toList)
                      .map(toFrame(_))

                  WebSocketBuilder[F].build(
                    send = frames,
                    receive = _ /* .map("Received chunk request: " + _).showLinesStdOut */.void.through(chunkRequests.enqueue)
                  )
                }
              case req @ PUT -> Root / "params" =>
                req.decode[Parameters] { params =>
                  sampler.update(params) *> NoContent()
                }

              case PUT -> Root / "frequency" / freq =>
                Sync[F].delay(freq.toLong).flatMap(frequencyPerSecond.set) *> NoContent()
            }
          }
          .routes
      )
    }
  }
}

object Main extends IOApp {
  implicit val ec: ExecutionContext = ExecutionContext.global

  private val loadConfig: IO[AppConfig] = IO(ConfigSource.default.loadOrThrow[AppConfig])

  override def run(args: List[String]): IO[ExitCode] = {
    val app = for {
      config <- loadConfig.liftResource
      server <- new Application[IO](config).server
    } yield server

    app.use(_ => IO.never)
  }
}
