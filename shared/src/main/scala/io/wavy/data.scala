package io.wavy

import io.circe.Codec
import io.circe.generic.semiauto._
// import io.estatico.newtype.macros.newtype
import io.circe.Encoder
import io.circe.Decoder

final case class Noise(rate: Double, factor: Double)

object Noise {
  implicit val codec: Codec[Noise] = deriveCodec
}

final case class Parameters(period: Double, amplitude: Double, phase: Double, noise: Noise)

object Parameters {
  implicit val codec: Codec[Parameters] = deriveCodec
}

object newtypes {

  // @newtype
  final case class Sample(value: Double) extends AnyVal

  object Sample {
    implicit val codec: Codec[Sample] = Codec.from(Decoder[Double].map(Sample(_)), Encoder[Double].contramap(_.value))
  }
}
