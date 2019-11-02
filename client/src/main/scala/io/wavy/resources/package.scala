package io.wavy

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

package object resources {

  @JSImport("resources/App.css", JSImport.Default)
  @js.native
  object AppCSS extends js.Object

  @JSImport("resources/logo.svg", JSImport.Default)
  @js.native
  object ReactLogo extends js.Object

}