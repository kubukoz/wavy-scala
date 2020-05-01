package io.wavy

import slinky.core.facade.Hooks._
import org.scalajs.dom.window
import cats.effect.IO

package object hooks {

  import cats.effect.Concurrent

  val useWindowWidth: () => Double = () => {
    val (get, set) = useState(window.innerWidth)

    useEffect { () =>
      window.addEventListener(
        "resize",
        (_: Any) => set(window.innerWidth)
      )
    }

    get
  }

  def useIO(io: IO[Unit])(deps: List[Any])(implicit conc: Concurrent[IO]): Unit =
    useEffect(() => {
      val fiber = conc.start(io).unsafeRunSync()

      () => fiber.cancel.unsafeRunAsyncAndForget()
    }, deps)

  import sttp.client._
  import sttp.client.circe._

  def useUpdateParams(params: Parameters)(implicit conc: Concurrent[IO], backend: SttpBackend[IO, Nothing, NothingT]): Unit = {
    val asList = List(params.amplitude, params.noise.factor, params.noise.rate, params.period, params.phase)

    val req = basicRequest.put(uri"http://localhost:4000/params").body(params).response(ignore)

    useIO {
      backend.send(req).map(_.body)
    }(asList)
  }
}
