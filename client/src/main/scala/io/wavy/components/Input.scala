package io.wavy.components

import slinky.core._
import slinky.web.html._
import cats.implicits._
import simulacrum.typeclass

object Input {

  trait Props {
    type A
    def tpe: Type[A]
    def labelText: String
    def initialValue: A
    def unsafeUpdateF: A => Unit
  }

  object Props {

    def apply[T: Type](label: String, initial: T)(unsafeUpdate: T => Unit): Props { type A = T } = new Props {
      type A = T
      val tpe: Type[T] = Type[T]
      val labelText: String = label
      val initialValue: T = initial
      val unsafeUpdateF: T => Unit = unsafeUpdate
    }
  }

  @typeclass
  trait Type[A] {
    def inputType: String
    def stringify(a: A): String
    def decode: String => Either[Throwable, A]
  }

  object Type {
    implicit val double: Type[Double] = new Type[Double] {
      val inputType: String = "number"
      val decode: String => Either[Throwable, Double] = s => Either.catchNonFatal(s.toDouble)
      def stringify(d: Double): String = d.show
    }
  }

  def apply[A: Type](label: String, initial: A)(unsafeUpdate: A => Unit) =
    component(Props(label, initial)(unsafeUpdate))

  val component: FunctionalComponent[Props] = FunctionalComponent { props =>
    div(
      show"${props.labelText}: ",
      input(
        `type` := props.tpe.inputType,
        title := props.labelText,
        onChange := { e =>
          props.tpe.decode(e.target.value).foreach(props.unsafeUpdateF(_))
        },
        value := props.tpe.stringify(props.initialValue)
      )
    )
  }
}
