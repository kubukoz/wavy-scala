package io.wavy

import slinky.core._
import slinky.web.html._
import slinky.core.facade.Hooks._
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import org.scalajs.dom.window
import cats.effect.SyncIO
import cats.implicits._

@JSImport("resources/App.css", JSImport.Default)
@js.native
object AppCSS extends js.Object

@JSImport("resources/logo.svg", JSImport.Default)
@js.native
object ReactLogo extends js.Object

object hooks {

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

import hooks._

final case class Screen(width: Double, height: Double)

object Input {

  trait Props {
    type A
    def tpe: Type[A]
    def labelText: String
    def unsafeUpdateF: A => Unit
    def initialValue: A
  }

  object Props {

    def apply[T: Type](label: String, initial: T)(unsafeUpdate: T => Unit): Props { type A = T } = new Props {
      type A = T
      val tpe: Type[T] = Type[T]
      val initialValue: T = initial
      val labelText: String = label
      val unsafeUpdateF: T => Unit = unsafeUpdate
    }

  }

  trait Type[A] {
    def decode: String => Either[Throwable, A]
    def stringify: A => String
  }

  object Type {
    def apply[A](implicit A: Type[A]): Type[A] = A

    implicit val double: Type[Double] = new Type[Double] {
      val decode: String => Either[Throwable, Double] = s => Either.catchNonFatal(s.toDouble)
      val stringify: Double => String = _.show
    }
  }

  def apply[A: Type](label: String, initial: A)(unsafeUpdate: A => Unit) = component(
    Props[A](label, initial)(unsafeUpdate)
  )

  val component: FunctionalComponent[Props] = FunctionalComponent { props =>
    div(
      s"${props.labelText}: ",
      input(
        `type` := "number",
        title := props.labelText,
        onChange := { e =>
          props.tpe.decode(e.target.value).foreach(props.unsafeUpdateF(_))
        },
        value := props.tpe.stringify(props.initialValue)
      )
    )
  }
}

object Comp {

  val component: FunctionalComponent[Unit] = FunctionalComponent { _ =>
    val screen = Screen(width = useWindowWidth() min 1000, height = 400)

    val (period, setPeriod) = useState(10.0)
    val (amplitude, setAmplitude) = useState(50.0)
    val (phase, setPhase) = useState(0.0)

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
        Comp.component(())
      )
    )
  }
}
