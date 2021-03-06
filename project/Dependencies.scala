import sbt._
import Keys._
import sbt.contraband.ContrabandPlugin.autoImport._

object Dependencies {
  // WARNING: Please Scala update versions in PluginCross.scala too
  val scala212 = "2.12.8"
  lazy val checkPluginCross = settingKey[Unit]("Make sure scalaVersion match up")
  val baseScalaVersion = scala212

  // sbt modules
  private val ioVersion = "1.3.0-M7"
  private val utilVersion = "1.3.0-M6"
  private val lmVersion =
    sys.props.get("sbt.build.lm.version") match {
      case Some(version) => version
      case _             => "1.3.0-M2"
    }
  private val zincVersion = "1.3.0-M3"

  private val sbtIO = "org.scala-sbt" %% "io" % ioVersion

  private val utilPosition = "org.scala-sbt" %% "util-position" % utilVersion
  private val utilLogging = "org.scala-sbt" %% "util-logging" % utilVersion
  private val utilCache = "org.scala-sbt" %% "util-cache" % utilVersion
  private val utilControl = "org.scala-sbt" %% "util-control" % utilVersion
  private val utilRelation = "org.scala-sbt" %% "util-relation" % utilVersion
  private val utilTracking = "org.scala-sbt" %% "util-tracking" % utilVersion
  private val utilScripted = "org.scala-sbt" %% "util-scripted" % utilVersion

  private val libraryManagementCore = "org.scala-sbt" %% "librarymanagement-core" % lmVersion

  private val libraryManagementImpl = {
    val lmOrganization =
      sys.props.get("sbt.build.lm.organization") match {
        case Some(impl) => impl
        case _          => "org.scala-sbt"
      }

    val lmModuleName =
      sys.props.get("sbt.build.lm.moduleName") match {
        case Some(impl) => impl
        case _          => "librarymanagement-ivy"
      }

    lmOrganization %% lmModuleName % lmVersion
  }

  val launcherVersion = "1.0.4"
  val launcherInterface = "org.scala-sbt" % "launcher-interface" % launcherVersion
  val rawLauncher = "org.scala-sbt" % "launcher" % launcherVersion
  val testInterface = "org.scala-sbt" % "test-interface" % "1.0"
  val ipcSocket = "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.0.0"

  private val compilerInterface = "org.scala-sbt" % "compiler-interface" % zincVersion
  private val compilerClasspath = "org.scala-sbt" %% "zinc-classpath" % zincVersion
  private val compilerApiInfo = "org.scala-sbt" %% "zinc-apiinfo" % zincVersion
  private val compilerBridge = "org.scala-sbt" %% "compiler-bridge" % zincVersion
  private val compilerIvyIntegration = "org.scala-sbt" %% "zinc-ivy-integration" % zincVersion
  private val zinc = "org.scala-sbt" %% "zinc" % zincVersion
  private val zincCompile = "org.scala-sbt" %% "zinc-compile" % zincVersion

  def getSbtModulePath(key: String, name: String) = {
    val localProps = new java.util.Properties()
    IO.load(localProps, file("project/local.properties"))
    val path = Option(localProps getProperty key) orElse (sys.props get key)
    path foreach (f => println(s"Using $name from $f"))
    path
  }

  lazy val sbtIoPath = getSbtModulePath("sbtio.path", "sbt/io")
  lazy val sbtUtilPath = getSbtModulePath("sbtutil.path", "sbt/util")
  lazy val sbtLmPath = getSbtModulePath("sbtlm.path", "sbt/lm")
  lazy val sbtZincPath = getSbtModulePath("sbtzinc.path", "sbt/zinc")

  def addSbtModule(p: Project, path: Option[String], projectName: String, m: ModuleID) =
    path match {
      case Some(f) => p dependsOn ProjectRef(file(f), projectName)
      case None    => p settings (libraryDependencies += m, dependencyOverrides += m)
    }

  def addSbtIO(p: Project): Project = addSbtModule(p, sbtIoPath, "io", sbtIO)

  def addSbtUtilPosition(p: Project): Project =
    addSbtModule(p, sbtUtilPath, "utilPosition", utilPosition)
  def addSbtUtilLogging(p: Project): Project =
    addSbtModule(p, sbtUtilPath, "utilLogging", utilLogging)
  def addSbtUtilCache(p: Project): Project =
    addSbtModule(p, sbtUtilPath, "utilCache", utilCache)
  def addSbtUtilControl(p: Project): Project =
    addSbtModule(p, sbtUtilPath, "utilControl", utilControl)
  def addSbtUtilRelation(p: Project): Project =
    addSbtModule(p, sbtUtilPath, "utilRelation", utilRelation)
  def addSbtUtilTracking(p: Project): Project =
    addSbtModule(p, sbtUtilPath, "utilTracking", utilTracking)
  def addSbtUtilScripted(p: Project): Project =
    addSbtModule(p, sbtUtilPath, "utilScripted", utilScripted)

  def addSbtLmCore(p: Project): Project =
    addSbtModule(p, sbtLmPath, "lmCore", libraryManagementCore)
  def addSbtLmImpl(p: Project): Project =
    addSbtModule(p, sbtLmPath, "lmImpl", libraryManagementImpl)

  def addSbtCompilerInterface(p: Project): Project =
    addSbtModule(p, sbtZincPath, "compilerInterface212", compilerInterface)
  def addSbtCompilerClasspath(p: Project): Project =
    addSbtModule(p, sbtZincPath, "zincClasspath212", compilerClasspath)
  def addSbtCompilerApiInfo(p: Project): Project =
    addSbtModule(p, sbtZincPath, "zincApiInfo212", compilerApiInfo)
  def addSbtCompilerBridge(p: Project): Project =
    addSbtModule(p, sbtZincPath, "compilerBridge212", compilerBridge)
  def addSbtCompilerIvyIntegration(p: Project): Project =
    addSbtModule(p, sbtZincPath, "zincIvyIntegration", compilerIvyIntegration)
  def addSbtZinc(p: Project): Project = addSbtModule(p, sbtZincPath, "zinc", zinc)
  def addSbtZincCompile(p: Project): Project =
    addSbtModule(p, sbtZincPath, "zincCompile", zincCompile)

  val sjsonNewScalaJson = Def.setting {
    "com.eed3si9n" %% "sjson-new-scalajson" % contrabandSjsonNewVersion.value
  }

  val jline = "jline" % "jline" % "2.14.6"
  val scalatest = "org.scalatest" %% "scalatest" % "3.0.6-SNAP5"
  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.14.0"
  val specs2 = "org.specs2" %% "specs2-junit" % "4.0.1"
  val junit = "junit" % "junit" % "4.11"
  val templateResolverApi = "org.scala-sbt" % "template-resolver" % "0.1"

  private def scala211Module(name: String, moduleVersion: String) = Def setting (
    ("org.scala-lang.modules" %% name % moduleVersion) :: Nil
  )

  val scalaXml = scala211Module("scala-xml", "1.1.1")
  val scalaParsers = scala211Module("scala-parser-combinators", "1.1.1")

  def log4jVersion = "2.11.2"
  val log4jApi = "org.apache.logging.log4j" % "log4j-api" % log4jVersion
  val log4jCore = "org.apache.logging.log4j" % "log4j-core" % log4jVersion
  val log4jSlf4jImpl = "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4jVersion
  // specify all of log4j modules to prevent misalignment
  val log4jDependencies = Vector(log4jApi, log4jCore, log4jSlf4jImpl)

  val scalaCacheCaffeine = "com.github.cb372" %% "scalacache-caffeine" % "0.20.0"
}
