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

  def makeServer(router: HttpRoutes[F]): Resource[F, Server[F]] =
    BlazeServerBuilder[F]
      .withWebSockets(true)
      .withHttpApp(CORS(router.orNotFound, CORS.DefaultCORSConfig.copy(allowedOrigins = Set("localhost:8080"))))
      .withExecutionContext(executionContext)
      .bindHttp(config.http.port, "0.0.0.0")
      .resource

  val server: Resource[F, Server[F]] = Resource.suspend {
    (Sampler.instance[F], SignallingRef[F, Long](100L)).mapN { (sampler, frequencyPerSecond) =>
      def toFrame[A: Encoder](a: A): WebSocketFrame = WebSocketFrame.Text(a.asJson.noSpaces)

      makeServer(
        HttpRouter
          .make[F] { dsl =>
            import dsl._

            HttpRoutes.of[F] {
              case GET -> Root / "samples" =>
                val frames = frequencyPerSecond
                  .discrete
                  .switchMap { frequency =>
                    sampler.samples.metered(1.second / frequency)
                  }
                  .chunkN(1000)
                  .map(_.toList)
                  .map(toFrame(_))

                WebSocketBuilder[F].build(send = frames, receive = _.drain)
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
