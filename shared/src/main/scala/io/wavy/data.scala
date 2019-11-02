package io.wavy

final case class Noise(rate: Double, factor: Double)
final case class Parameters(period: Double, amplitude: Double, phase: Double, noise: Noise)
