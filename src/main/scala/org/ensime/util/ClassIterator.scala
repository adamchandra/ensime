package org.ensime.util

import org.slf4j.LoggerFactory

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.ClassReader
import java.io._
import java.util.jar.{ JarFile, Manifest => JarManifest }
import java.util.zip._
import java.io.{ File, InputStream, IOException }

trait ClassHandler {
  def onClass(name: String, location: String, flags: Int) {}
  def onMethod(className: String, name: String, location: String, flags: Int) {}
  def onField(className: String, name: String, location: String, flags: Int) {}
}

private class PublicSymbolVisitor(location: File, handler: ClassHandler) extends ClassVisitor(Opcodes.ASM5) {
  var currentClassName: Option[String] = None
  val path: String = location.getPath

  override def visit(version: Int,
    access: Int,
    name: String,
    signature: String,
    superName: String,
    interfaces: Array[String]) {
    val nm = mapClassName(name)
    currentClassName = Some(nm)
    handler.onClass(nm, path, access)
  }

  override def visitMethod(access: Int,
    name: String,
    description: String,
    signature: String,
    exceptions: Array[String]): MethodVisitor =
    {
      handler.onMethod(currentClassName.getOrElse("."), name, path, access)
      null
    }

  override def visitField(access: Int,
    name: String,
    description: String,
    signature: String,
    value: java.lang.Object): FieldVisitor =
    {
      handler.onField(currentClassName.getOrElse("."), name, path, access)
      null
    }

  private def mapClassName(name: String): String = {
    if (name == null) ""
    else name.replaceAll("/", ".")
  }
}

trait RichClassVisitor extends org.objectweb.asm.ClassVisitor {
  type Result
  def result: Option[Result]
}

case class ClassLocation(file: String, entry: String)

object ClassIterator {

  val log = LoggerFactory.getLogger("ClassIterator")

  type Callback = (ClassLocation, ClassReader) => Unit

  /**
   * Invoke callback for each class found in given .class,.jar,.zip, or directory
   * files.
   * TODO(aemoncannon): Should accept Iterable[ClassLocation] so we could
   * search in a more directed fashion.
   */
  def find(path: Iterable[File], callback: Callback) {
    for (f <- path) {
      try {
        findClassesIn(f, callback)
      } catch {
        case e: Exception => log.error("Failed to read: " + f, e)
      }
    }
  }

  /**
   * Invoke methods of handler for each top-level symbol found in given
   * .class,.jar,.zip, or directory files.
   */
  def findPublicSymbols(path: Iterable[File], handler: ClassHandler) {
    find(path, { (location, cr) =>
      val visitor = new PublicSymbolVisitor(new File(location.file), handler)
      cr.accept(visitor, ClassReader.SKIP_CODE)
    })
  }

  val ASMAcceptAll = 0

  /**
   * Visits all classes in given .class,.jar,.zip or directory file with the
   * given visitor and returns the visitor's final result value.
   */
  def findInClasses[T <: RichClassVisitor](
    path: Iterable[File], visitor: T): Option[T#Result] = {
    ClassIterator.find(
      path, (location, classReader) => classReader.accept(visitor, ASMAcceptAll))
    visitor.result
  }

  private def findClassesIn(f: File, callback: Callback) {
    val name = f.getPath.toLowerCase
    if (name.endsWith(".jar"))
      processJar(f, callback)
    else if (name.endsWith(".class"))
      processClassfile(f, callback)
    else if (name.endsWith(".zip"))
      processZip(f, callback)
    else if (f.isDirectory)
      processDirectory(f, callback)
  }

  private def processJar(file: File, callback: Callback) {
    val jar = new JarFile(file)
    processOpenZip(file, jar, callback)
    val manifest = jar.getManifest
    if (manifest != null) {
      val path = loadManifestPath(jar, file, manifest)
      find(path, callback)
    }
  }

  private def loadManifestPath(jar: JarFile,
    jarFile: File,
    manifest: JarManifest): List[File] = {
    val attrs = manifest.getMainAttributes
    val value = attrs.get("Class-Path").asInstanceOf[String]

    if (value == null)
      Nil

    else {
      val parent = jarFile.getParent
      val tokens = value.split("""\s+""").toList
      if (parent == null)
        tokens.map(new File(_))
      else
        tokens.map(s => new File(parent + File.separator + s))
    }
  }

  private def processZip(file: File, callback: Callback) {
    var zf: ZipFile = null
    try {
      zf = new ZipFile(file)
      processOpenZip(file, zf, callback)
    } finally {
      if (zf != null) zf.close()
    }
  }

  private def processOpenZip(file: File, zipFile: ZipFile, callback: Callback) {
    import scala.collection.JavaConversions._
    for (entry <- zipFile.entries) {
      if (isClass(entry)) {
        val is = new BufferedInputStream(zipFile.getInputStream(entry))
        try {
          processClassData(is, ClassLocation(file.getCanonicalPath.replace("\\", "/"), entry.getName),
            callback)
        } finally {
          if (is != null) is.close()
        }
      }
    }
  }

  // Matches both ZipEntry and File
  type FileEntry = {
    def isDirectory(): Boolean
    def getName(): String
  }

  private def isClass(e: FileEntry): Boolean = {
    import scala.language.reflectiveCalls
    (!e.isDirectory) && e.getName.toLowerCase.endsWith(".class")
  }

  private def processDirectory(dir: File, callback: Callback) {
    import FileUtils._
    for (f <- dir.andTree) {
      if (isClass(f)) {
        processClassfile(f, callback)
      }
    }
  }

  private def processClassfile(f: File, callback: Callback) {
    val is = new BufferedInputStream(new FileInputStream(f))
    try {
      processClassData(is, ClassLocation(f.getCanonicalPath.replace("\\", "/"), ""), callback)
    } finally {
      if (is != null) is.close()
    }
  }

  private def processClassData(is: InputStream, location: ClassLocation,
    callback: Callback) {
    val cr = new ClassReader(is)
    callback(location, cr)
  }

}
