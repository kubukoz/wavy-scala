package io.wavy

import slinky.core.facade.Hooks._
import org.scalajs.dom.window

package object hooks {

  val useWindowWidth: () => Double = () => {
    val (get, set) = useState(window.innerWidth)

    useEffect(() => {
      window.addEventListener(
        "resize",
        (_: Any) => set(window.innerWidth)
      )
    })

    get
  }
}
