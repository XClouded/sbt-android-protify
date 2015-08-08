package android.protify

import sbt.Def.Initialize
import sbt._
import sbt.Keys._
import android.Keys._
import sbt.classpath.ClasspathUtilities
import xsbt.api.Discovery

import scala.annotation.tailrec

import sbt.Cache.seqFormat
import sbt.Cache.StringFormat
import sbt.Cache.IntFormat
import sbt.Cache.tuple2Format

/**
 * @author pfnguyen
 */
object Plugin extends AutoPlugin {
  override def trigger = noTrigger
  override def requires = plugins.JvmPlugin
  val autoImport = Keys
}

object Keys {
  import Internal._
  val protifyLayout = InputKey[Unit]("protify-layout", "prototype an android layout on device")
  val protifyDex = InputKey[Unit]("protify-dex", "prototype code on device")

  private object Internal {
    val protifyDexes = TaskKey[Seq[String]]("protify-dexes", "internal key: autodetected classes with ActivityProxy")
    val protifyLayouts = TaskKey[Seq[(String,Int)]]("protify-layouts", "internal key: autodetected layout files")
  }
  val Protify = config("protify") extend Compile

  lazy val protifySettings: List[Setting[_]] = List(
    ivyConfigurations := overrideConfigs(Protify)(ivyConfigurations.value),
    libraryDependencies += "com.hanhuy.android" % "protify" % "0.1-SNAPSHOT" % "protify",
    protifyDex <<= protifyDexTaskDef(),
    protifyLayout <<= protifyLayoutTaskDef(),
    protifyLayout <<= protifyLayout dependsOn (packageResources in Android, compile in Protify),
    protifyDexes <<= (compile in Protify) map discoverActivityProxies storeAs protifyDexes triggeredBy (compile in Protify),
    protifyLayouts <<= protifyLayoutsTaskDef storeAs protifyLayouts triggeredBy (compile in Compile, compile in Protify)
  ) ++ inConfig(Protify)(Defaults.compileSettings) ++ inConfig(Protify)(List(
    javacOptions := (javacOptions in Compile).value,
    scalacOptions := (scalacOptions in Compile).value,
    unmanagedSourceDirectories :=
      (unmanagedSourceDirectories in Compile).value ++ {
        val layout = (projectLayout in Android).value
        val gradleLike = Seq(
          layout.base / "src" / "protify" / "scala",
          layout.base / "src" / "protify" / "java"
        )
        val antLike = Seq(
          layout.base / "protify"
        )
        @tailrec
        def sourcesFor(p: ProjectLayout): Seq[File] = layout match {
          case g: ProjectLayout.Gradle => gradleLike
          case a: ProjectLayout.Ant => antLike
          case w: ProjectLayout.Wrapped => sourcesFor(w.wrapped)
        }
        sourcesFor(layout)
      }
  ))

  private val protifyLayoutsTaskDef = Def.task {
    val pkg = (packageForR in Android).value
    val loader = ClasspathUtilities.toLoader((classDirectory in Compile).value)
    val clazz = loader.loadClass(pkg + ".R$layout")
    val fields = clazz.getDeclaredFields
    fields.map(f => f.getName -> f.getInt(null)).toSeq
  }

  def protifyLayoutTaskDef(): Initialize[InputTask[Unit]] = {
    val parser = loadForParser(protifyLayouts) { (s, names) =>
      Defaults.runMainParser(s, names.fold(Seq.empty[String])(_ map (_._1)))
    }
    Def.inputTask {
      val res = (packageResources in Android).value
      val l = parser.parsed
      val log = streams.value.log
      val layouts = loadFromContext(protifyLayouts, sbt.Keys.resolvedScoped.value, state.value).fold(Map.empty[String,Int])(_.toMap)
      val resid = layouts.get(l._1)
      log.debug("available layouts: " + layouts)
      import android.Commands
      import com.hanhuy.android.protify.Intents._
      Commands.targetDevice((sdkPath in Android).value, streams.value.log) foreach { dev =>
        val f = java.io.File.createTempFile("resources", ".ap_")
        f.delete()
        val cmd = f"am broadcast -a $LAYOUT_INTENT -e $EXTRA_RESOURCES /sdcard/protify/${f.getName} --ei $EXTRA_LAYOUT ${resid.get} com.hanhuy.android.protify/.LayoutReceiver"
        log.debug("Executing: " + cmd)
        dev.executeShellCommand("rm -rf /sdcard/protify/*", new Commands.ShellResult)
        dev.pushFile(res.getAbsolutePath, s"/sdcard/protify/${f.getName}")
        dev.executeShellCommand(cmd, new Commands.ShellResult)
      }
    }
  }
  def protifyDexTaskDef(): Initialize[InputTask[Unit]] = {
    val parser = loadForParser(protifyDexes) { (s, names) =>
      Defaults.runMainParser(s, names getOrElse Nil)
    }
    Def.inputTask {
      val l = parser.parsed
      streams.value.log.info("Got: " + l)
      val dexes = loadFromContext(protifyDexes, sbt.Keys.resolvedScoped.value, state.value)
      streams.value.log.info("available layouts: " + dexes)
    }
  }
  def discoverActivityProxies(analysis: inc.Analysis): Seq[String] =
    Discovery(Set("com.hanhuy.android.protify.ActivityProxy"), Set.empty)(Tests.allDefs(analysis)).collect({
        case (definition, discovered) if !definition.modifiers.isAbstract &&
          discovered.baseClasses("com.hanhuy.android.protify.ActivityProxy") =>
          definition.name }).sorted
}
