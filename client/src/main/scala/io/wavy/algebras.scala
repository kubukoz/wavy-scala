package io.wavy

import org.scalajs.dom.raw.HTMLCanvasElement
import cats.effect.Sync
import org.scalajs.dom.raw.CanvasRenderingContext2D
import scalajs.js.{Any => JSAny}
import cats.implicits._

object algebras {

  trait CanvasOps[F[_]] {
    def setWidth(width: Int): F[Unit]
    def setHeight(height: Int): F[Unit]
    def get2dContext: F[RenderingContext2D[F]]
  }

  object CanvasOps {
    def apply[F[_]](implicit F: CanvasOps[F]): CanvasOps[F] = F

    def fromCanvas[F[_]: Sync](canvas: HTMLCanvasElement): CanvasOps[F] = new CanvasOps[F] {

      def setWidth(width: Int): F[Unit] =
        Sync[F].delay(canvas.width = width)

      def setHeight(height: Int): F[Unit] =
        Sync[F].delay(canvas.height = height)

      val get2dContext: F[RenderingContext2D[F]] =
        Sync[F].delay(canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]).map(RenderingContext2D.fromContext[F])
    }
  }

  trait RenderingContext2D[F[_]] {
    def lineTo(x: Double, y: Double): F[Unit]
    def strokeStyle(style: JSAny): F[Unit]
    def lineWidth(width: Double): F[Unit]
    def beginPath: F[Unit]
    def stroke: F[Unit]
  }

  object RenderingContext2D {
    def apply[F[_]](implicit F: RenderingContext2D[F]): RenderingContext2D[F] = F

    def fromContext[F[_]: Sync](context: CanvasRenderingContext2D): RenderingContext2D[F] = new RenderingContext2D[F] {
      def lineTo(x: Double, y: Double): F[Unit] = Sync[F].delay(context.lineTo(x, y))

      def strokeStyle(style: JSAny): F[Unit] = Sync[F].delay(context.strokeStyle = style)

      def lineWidth(width: Double): F[Unit] = Sync[F].delay(context.lineWidth = width)

      val beginPath: F[Unit] = Sync[F].delay(context.beginPath())

      val stroke: F[Unit] = Sync[F].delay(context.stroke())
    }
  }
}
