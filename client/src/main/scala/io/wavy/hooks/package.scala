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
}
