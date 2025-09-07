import sbt._
import Keys._

object CustomHeaderPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  override def projectSettings = Seq(
  )
}
