package io.wavy

import slinky.core._
import slinky.web.html._
import slinky.core.facade.Hooks._
import cats.implicits._
import components.Input
import resources.AppCSS
import resources.ReactLogo
import hooks._

final case class Screen(width: Double, height: Double)

object Composition {

  val useUpdateParams: Parameters => Unit = { params =>
    useEffect(() => {
      // axios.put("http://localhost:4000/params", params)
      println(s"updated params to $params")
    }, List(params.amplitude, params.noise.factor, params.noise.rate, params.period, params.phase))
  }

  val component: FunctionalComponent[Unit] = FunctionalComponent { _ =>
    val screen = Screen(width = useWindowWidth() min 1000, height = 400)

    val (period, setPeriod) = useState(10.0)
    val (amplitude, setAmplitude) = useState(50.0)
    val (phase, setPhase) = useState(0.0)

    val params = Parameters(period, amplitude, phase, Noise(0.0, 0.0))

    useUpdateParams(params)

    div(
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
