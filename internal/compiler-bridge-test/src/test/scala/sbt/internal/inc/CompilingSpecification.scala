package sbt
package internal
package inc

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files

import sbt.internal.inc.classpath.ClassLoaderCache
import sbt.io.IO
import sbt.io.syntax._
import xsbti.compile._
import sbt.util.Logger
import xsbti.TestCallback.ExtractedClassDependencies
import xsbti.{ TestCallback, UseScope }
import xsbti.api.ClassLike
import xsbti.api.DependencyContext._

/**
 * Provides common functionality needed for unit tests that require compiling
 * source code using Scala compiler.
 */
trait CompilingSpecification extends BridgeProviderSpecification {
  def scalaVersion =
    sys.props
      .get("zinc.build.compilerbridge.scalaVersion")
      .getOrElse(sys.error("zinc.build.compilerbridge.scalaVersion property not found"))
  def maxErrors = 100

  def scalaCompiler(instance: xsbti.compile.ScalaInstance, bridgeJar: File): AnalyzingCompiler = {
    val bridgeProvider = ZincUtil.constantBridgeProvider(instance, bridgeJar)
    val classpath = ClasspathOptionsUtil.boot
    val cache = Some(new ClassLoaderCache(new URLClassLoader(Array())))
    new AnalyzingCompiler(instance, bridgeProvider, classpath, _ => (), cache)
  }

  /**
   * Compiles given source code using Scala compiler and returns API representation
   * extracted by ExtractAPI class.
   */
  def extractApisFromSrc(src: String): Set[ClassLike] = {
    val (Seq(tempSrcFile), analysisCallback) = compileSrcs(src)
    analysisCallback.apis(tempSrcFile)
  }

  /**
   * Compiles given source code using Scala compiler and returns API representation
   * extracted by ExtractAPI class.
   */
  def extractApisFromSrcs(reuseCompilerInstance: Boolean)(
      srcs: List[String]*): Seq[Set[ClassLike]] = {
    val (tempSrcFiles, analysisCallback) = compileSrcs(srcs.toList, reuseCompilerInstance)
    tempSrcFiles.map(analysisCallback.apis)
  }

  def extractUsedNamesFromSrc(src: String): Map[String, Set[String]] = {
    val (_, analysisCallback) = compileSrcs(src)
    analysisCallback.usedNames.toMap
  }

  def extractBinaryClassNamesFromSrc(src: String): Set[(String, String)] = {
    val (Seq(tempSrcFile), analysisCallback) = compileSrcs(src)
    analysisCallback.classNames(tempSrcFile).toSet
  }

  /**
   * Extract used names from src provided as the second argument.
   * If `assertDefaultScope` is set to true it will fail if there is any name used in scope other then Default
   *
   * The purpose of the first argument is to define names that the second
   * source is going to refer to. Both files are compiled in the same compiler
   * Run but only names used in the second src file are returned.
   */
  def extractUsedNamesFromSrc(
      definitionSrc: String,
      actualSrc: String,
      assertDefaultScope: Boolean = true
  ): Map[String, Set[String]] = {
    // we drop temp src file corresponding to the definition src file
    val (Seq(_, tempSrcFile), analysisCallback) = compileSrcs(definitionSrc, actualSrc)

    if (assertDefaultScope) for {
      (className, used) <- analysisCallback.usedNamesAndScopes
      analysisCallback.TestUsedName(name, scopes) <- used
    } assert(scopes.size() == 1 && scopes.contains(UseScope.Default), s"$className uses $name in $scopes")

    val classesInActualSrc = analysisCallback.classNames(tempSrcFile).map(_._1)
    classesInActualSrc.map(className => className -> analysisCallback.usedNames(className)).toMap
  }

  /**
   * Extract used names from the last source file in `sources`.
   *
   * The previous source files are provided to successfully compile examples.
   * Only the names used in the last src file are returned.
   */
  def extractUsedNamesFromSrc(sources: String*): Map[String, Set[String]] = {
    val (srcFiles, analysisCallback) = compileSrcs(sources: _*)
    srcFiles
      .map { srcFile =>
        val classesInSrc = analysisCallback.classNames(srcFile).map(_._1)
        classesInSrc.map(className => className -> analysisCallback.usedNames(className)).toMap
      }
      .reduce(_ ++ _)
  }

  /**
   * Compiles given source code snippets (passed as Strings) using Scala compiler and returns extracted
   * dependencies between snippets. Source code snippets are identified by symbols. Each symbol should
   * be associated with one snippet only.
   *
   * Snippets can be grouped to be compiled together in the same compiler run. This is
   * useful to compile macros, which cannot be used in the same compilation run that
   * defines them.
   *
   * Symbols are used to express extracted dependencies between source code snippets. This way we have
   * file system-independent way of testing dependencies between source code "files".
   */
  def extractDependenciesFromSrcs(srcs: List[List[String]]): ExtractedClassDependencies = {
    val (_, testCallback) = compileSrcs(srcs, reuseCompilerInstance = true)

    val memberRefDeps = testCallback.classDependencies collect {
      case (target, src, DependencyByMemberRef) => (src, target)
    }
    val inheritanceDeps = testCallback.classDependencies collect {
      case (target, src, DependencyByInheritance) => (src, target)
    }
    val localInheritanceDeps = testCallback.classDependencies collect {
      case (target, src, LocalDependencyByInheritance) => (src, target)
    }
    ExtractedClassDependencies.fromPairs(memberRefDeps, inheritanceDeps, localInheritanceDeps)
  }

  def extractDependenciesFromSrcs(srcs: String*): ExtractedClassDependencies = {
    extractDependenciesFromSrcs(List(srcs.toList))
  }

  /**
   * Compiles given source code snippets written to temporary files. Each snippet is
   * written to a separate temporary file.
   *
   * Snippets can be grouped to be compiled together in the same compiler run. This is
   * useful to compile macros, which cannot be used in the same compilation run that
   * defines them.
   *
   * The `reuseCompilerInstance` parameter controls whether the same Scala compiler instance
   * is reused between compiling source groups. Separate compiler instances can be used to
   * test stability of API representation (with respect to pickling) or to test handling of
   * binary dependencies.
   *
   * The sequence of temporary files corresponding to passed snippets and analysis
   * callback is returned as a result.
   */
  def compileSrcs(
      groupedSrcs: List[List[String]],
      reuseCompilerInstance: Boolean
  ): (Seq[File], TestCallback) = {
    IO.withTemporaryDirectory { tempDir =>
      val targetDir = tempDir / "target"
      val analysisCallback = new TestCallback
      targetDir.mkdir()
      val cache =
        if (reuseCompilerInstance) new CompilerCache(1)
        else CompilerCache.fresh
      val files = for ((compilationUnit, unitId) <- groupedSrcs.zipWithIndex) yield {
        val srcFiles = compilationUnit.zipWithIndex map {
          case (src, i) =>
            val fileName = s"Test-$unitId-$i.scala"
            prepareSrcFile(tempDir, fileName, src)
        }
        val sources = srcFiles.toArray
        val noLogger = Logger.Null
        val compilerBridge = getCompilerBridge(tempDir, noLogger, scalaVersion)
        val si = scalaInstance(scalaVersion, tempDir, noLogger)
        val sc = scalaCompiler(si, compilerBridge)
        val cp = si.allJars ++ Array(targetDir)
        val emptyChanges: DependencyChanges = new DependencyChanges {
          val modifiedBinaries = new Array[File](0)
          val modifiedClasses = new Array[String](0)
          def isEmpty = true
        }
        sc.apply(
          sources = sources,
          changes = emptyChanges,
          classpath = cp,
          singleOutput = targetDir,
          options = Array(),
          callback = analysisCallback,
          maximumErrors = maxErrors,
          cache = cache,
          log = log
        )
        srcFiles
      }

      // Make sure that the analysis doesn't lie about the class files that are written
      analysisCallback.productClassesToSources.keySet.foreach { classFile =>
        if (classFile.exists()) ()
        else {
          val cfs = Files.list(classFile.toPath.getParent).toArray.mkString("\n")
          sys.error(s"Class file '${classFile.getAbsolutePath}' doesn't exist! Found:\n$cfs")
        }
      }

      (files.flatten, analysisCallback)
    }
  }

  def compileSrcs(srcs: String*): (Seq[File], TestCallback) = {
    compileSrcs(List(srcs.toList), reuseCompilerInstance = true)
  }

  private def prepareSrcFile(baseDir: File, fileName: String, src: String): File = {
    val srcFile = new File(baseDir, fileName)
    IO.write(srcFile, src)
    srcFile
  }
}
