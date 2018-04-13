package com.dslplatform.sbt

import com.dslplatform.compiler.client.parameters.{Settings, Targets}
import sbt.Keys._
import sbt._
import sbt.complete.Parsers

object SbtDslPlatformPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  object autoImport {
    val dsl = taskKey[Seq[File]]("Compile DSL into generated source ready for usage")
    val dslGenerate = taskKey[Seq[File]]("Compile DSL into generated source ready for usage")
    val dslResource = inputKey[Seq[File]]("Scan code and create META-INF/services files for plugins")
    val dslMigrate = inputKey[Unit]("Create an SQL migration file based on difference from DSL in project and in the target database")
    val dslExecute = inputKey[Unit]("Execute custom DSL compiler command")

    val dslLibraries = settingKey[Map[Targets.Option, File]]("Compile libraries to specified outputs")
    val dslSources = settingKey[Map[Targets.Option, File]]("Generate sources to specified folders")
    val dslCompiler = settingKey[String]("Path to custom dsl-compiler.exe or port to running instance (requires .NET/Mono)")
    val dslServerMode = settingKey[Boolean]("Talk with DSL compiler in server mode (will be faster)")
    val dslServerPort = settingKey[Option[Int]]("Use a specific port to talk with DSL compiler in server mode")
    val dslPostgres = settingKey[String]("JDBC-like connection string to the Postgres database")
    val dslOracle = settingKey[String]("JDBC-like connection string to the Oracle database")
    val dslApplyMigration = settingKey[Boolean]("Apply SQL migration directly to the database")
    val dslNamespace = settingKey[String]("Root namespace for target language")
    val dslSettings = settingKey[Seq[Settings.Option]]("Additional compilation settings")
    val dslCustomSettings = settingKey[Seq[String]]("Custom compilation settings")
    val dslDslPath = settingKey[Seq[File]]("Path to DSL folder(s)")
    val dslResourcePath = settingKey[Option[File]]("Path to META-INF/services folder")
    val dslDependencies = settingKey[Map[Targets.Option, File]]("Library compilation requires various dependencies. Customize default paths to dependencies")
    val dslSqlPath = settingKey[File]("Output folder for SQL scripts")
    val dslLatest = settingKey[Boolean]("Check for latest versions (dsl-compiler, libraries, etc...)")
    val dslForce = settingKey[Boolean]("Force actions without prompt (destructive migrations, missing folders etc.)")
    val dslPlugins = settingKey[Option[File]]("Path to additional DSL plugins")
    val dslDownload = settingKey[Option[String]]("Download URL for a custom DSL compiler")
  }

  import autoImport._

  override lazy val projectSettings =
    inConfig(Compile)(baseDslSettings(Compile)) ++ Set(
      sourceGenerators in Compile += (dsl in Compile).taskValue
    ) ++
    inConfig(Test)(baseDslSettings(Test)) ++ Set(
      sourceGenerators in Test += (dsl in Test).taskValue
    )

  def baseDslSettings(scope: Configuration) = Seq(
    dsl := (dslGenerate in dsl).value,
    dslLibraries in dsl := Map.empty,
    dslSources in dsl := Map.empty,
    dslCompiler in dsl := "",
    dslServerMode in dsl := false,
    dslServerPort in dsl := Some(55662),
    dslPostgres in dsl := "",
    dslOracle in dsl := "",
    dslApplyMigration in dsl := false,
    dslNamespace in dsl := "",
    dslSettings in dsl := Nil,
    dslCustomSettings in dsl := Nil,
    dslDslPath in dsl := Seq(baseDirectory.value / "dsl"),
    dslDependencies in dsl := Map.empty,
    dslResourcePath in dsl := None,
    dslSqlPath in dsl := baseDirectory.value / "sql",
    dslLatest in dsl := true,
    dslForce in dsl := false,
    dslPlugins in dsl := Some(baseDirectory.value),
    dslDownload in dsl := None
  ) ++ inTask(dsl)(Seq(
    dslGenerate := {
      val logger         = streams.value.log
      val depClassPath   = dependencyClasspath.value
      val cacheDirectory = streams.value.cacheDirectory

      if (dslSources.value.isEmpty) {
        logger.error(s"$scope: dslSources must be set")
        Seq()
      } else {
        val cached = FileFunction.cached(
          cacheDirectory / "dsl-generate",
          inStyle = FilesInfo.hash,
          outStyle = FilesInfo.hash
        ) { _: Set[File] =>
          dslSources.value.toList.flatMap { case (targetArg, targetOutput) =>
            Actions.generateSource(
              logger,
              targetArg,
              targetOutput,
              dslDslPath.value,
              dslPlugins.value,
              dslCompiler.value,
              dslServerMode.value,
              dslDownload.value,
              dslServerPort.value,
              dslNamespace.value,
              dslSettings.value,
              dslCustomSettings.value,
              depClassPath,
              dslLatest.value
            ).map(_.getAbsoluteFile)
          }.toSet
        }

        // Index only *.dsl files
        val dslPathFiles = dslDslPath
          .value
          .filter(_.exists())
          .flatMap(_.listFiles().filter(_.getPath.endsWith(".dsl")))
          .toSet
        logger.info(s"Found ${dslPathFiles.size} DSL files")
        cached(dslPathFiles).toSeq
      }
    },

    dslResource := {
      if (dslSources.value.isEmpty && dslLibraries.value.isEmpty) {
        streams.value.log.error(s"$scope: dslSources and/or dslLibraries must be set")
        Seq()
      } else if (dslResourcePath.value.isEmpty) {
        streams.value.log.error(s"$scope: dslResourcePath must be set")
        Seq()
      } else {
        val targets = (dslSources.value.keys ++ dslLibraries.value.keys)
          .toSet[Targets.Option]

        targets.flatMap { tgt =>
          Actions.generateResources(
            streams.value.log,
            tgt,
            dslResourcePath.value.get,
            Seq((target in scope).value),
            (fullClasspath in scope).value)
        }.toSeq
      }
    },

    dslMigrate := {
      if (dslPostgres.value.isEmpty && dslOracle.value.isEmpty)
        streams.value.log.error(s"$scope: JDBC connection string not defined for PostgreSQL or Oracle")
      else {
        val jdbc =
          if (dslPostgres.value.nonEmpty) dslPostgres.value
          else dslOracle.value

        Actions.dbMigration(
          streams.value.log,
          jdbc,
          dslPostgres.value.nonEmpty,
          dslSqlPath.value,
          dslDslPath.value,
          dslPlugins.value,
          dslCompiler.value,
          dslServerMode.value,
          dslDownload.value,
          dslServerPort.value,
          dslApplyMigration.value,
          dslForce.value,
          dslLatest.value)
      }
    },

    dslExecute := Def.inputTask {
      Actions.execute(
        streams.value.log,
        dslDslPath.value,
        dslPlugins.value,
        dslCompiler.value,
        dslServerMode.value,
        dslDownload.value,
        dslServerPort.value,
        Parsers.spaceDelimited("<arg>").parsed)
    }
  ))
}
