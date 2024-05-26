/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the
 * Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.  See the Apache License Version 2.0 for the specific
 * language governing permissions and limitations there under.
 */

// SBT
import sbt._
import Keys._

object BuildSettings {

  // sbt-assembly settings for building an executable
  import sbtassembly.AssemblyPlugin.autoImport._
  import sbtassembly.MergeStrategy

  val reverseConcat: MergeStrategy = new MergeStrategy {
    val name = "reverseConcat"
    def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] =
      MergeStrategy.concat(tempDir, path, files.reverse)
  }

  lazy val sbtAssemblySettings = Seq(
    // TODO: Roll this back
    assembly / assemblyJarName := s"${moduleName.value}.jar",
//    assembly / assemblyJarName := s"${moduleName.value}-${version.value}.jar",
    assembly / assemblyMergeStrategy := {
      // merge strategy for fixing netty conflict
      case PathList("io", "netty", xs @ _*)                     => MergeStrategy.first
      case fileName if fileName.toLowerCase == "reference.conf" => reverseConcat
      case x if x.endsWith("io.netty.versions.properties")      => MergeStrategy.discard
      case x if x.endsWith("module-info.class")                 => MergeStrategy.first
      case x if x.endsWith("paginators-1.json")                 => MergeStrategy.first
      case x if x.endsWith("service-2.json")                    => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )

  // Scalafmt plugin
  import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
  lazy val formatting = Seq(
    scalafmtConfig := file(".scalafmt.conf"),
    scalafmtOnCompile := true
  )

  lazy val addExampleConfToTestCp = Seq(
    Test / unmanagedClasspath += {
      baseDirectory.value.getParentFile / "examples"
    }
  )
}
