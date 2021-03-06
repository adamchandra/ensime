package org.ensime.indexer

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.Type
import org.objectweb.asm.Opcodes
import scala.collection.mutable

trait MethodDescriber extends MethodVisitor {

  val INTERNAL_NAME = 0
  val FIELD_DESCRIPTOR = 1
  val FIELD_SIGNATURE = 2
  val METHOD_DESCRIPTOR = 3
  val METHOD_SIGNATURE = 4
  val CLASS_SIGNATURE = 5
  val TYPE_DECLARATION = 6
  val CLASS_DECLARATION = 7
  val PARAMETERS_DECLARATION = 8
  val HANDLE_DESCRIPTOR = 9

  import org.objectweb.asm.util.Printer._

  def appendOp(name: String, args: String): Unit
  def appendOp(name: String): Unit = appendOp(name, "")

  private def descriptor(tpe: Int, desc: String): String = {
    if (tpe == CLASS_SIGNATURE || tpe == FIELD_SIGNATURE
      || tpe == METHOD_SIGNATURE) {
      if (desc != null) {
        "// signature " + desc
      } else ""
    } else { desc }
  }

  val labelNames = new mutable.HashMap[Label, String]()
  private def label(l: Label): String = {
    labelNames.get(l) match {
      case Some(name) => name
      case _ =>
        val name = "L" + labelNames.size
        labelNames(l) = name
        name
    }
  }

  override def visitInsn(opcode: Int) {
    appendOp(OPCODES(opcode))
  }

  override def visitIntInsn(opcode: Int, operand: Int) {
    appendOp(OPCODES(opcode),
      if (opcode == Opcodes.NEWARRAY) { TYPES(operand) } else { operand.toString })
  }

  override def visitVarInsn(opcode: Int, variable: Int) {
    appendOp(OPCODES(opcode), variable.toString)
  }

  override def visitTypeInsn(opcode: Int, tpe: String) {
    appendOp(OPCODES(opcode), descriptor(INTERNAL_NAME, tpe))
  }

  override def visitFieldInsn(opcode: Int, owner: String,
    name: String, desc: String) {
    appendOp(OPCODES(opcode),
      descriptor(INTERNAL_NAME, owner) +
        "." + name +
        " : " + descriptor(FIELD_DESCRIPTOR, desc))
  }

  override def visitMethodInsn(opcode: Int, owner: String,
    name: String, desc: String, itf: Boolean) {
    appendOp(OPCODES(opcode),
      descriptor(INTERNAL_NAME, owner) +
        "." + name + descriptor(METHOD_DESCRIPTOR, desc))
  }

  // TODO pending method handle in ASM
  // override def visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle,
  //   val bsmArgs:Object*) {
  //   buf.setLength(0)
  //   buf.append(tab2).append("INVOKEDYNAMIC").append(' ')
  //   buf.append(name)
  //   descriptor(METHOD_DESCRIPTOR, desc)
  //   buf.append(" [")
  //   appendHandle(bsm)
  //   buf.append(tab3).append("// arguments:")
  //   if (bsmArgs.length == 0) {
  //     buf.append(" none")
  //   } else {
  //     buf.append('\n').append(tab3)
  //     for (int i = 0 i < bsmArgs.length i++) {
  //       Object cst = bsmArgs[i]
  //       if (cst instanceof String) {
  //         Printer.appendString(buf, (String) cst)
  //       } else if (cst instanceof Type) {
  //         buf.append(((Type) cst).getDescriptor()).append(".class")
  //       } else if (cst instanceof Handle) {
  //         appendHandle((Handle) cst)
  //       } else {
  //         buf.append(cst)
  //       }
  //       buf.append(", ")
  //     }
  //     buf.setLength(buf.length() - 2)
  //   }
  //   buf.append('\n')
  //   buf.append(tab2).append("]\n")
  //   text.add(buf.toString())
  // }

  override def visitJumpInsn(opcode: Int, lbl: Label) {
    appendOp(OPCODES(opcode), label(lbl))
  }

  override def visitLabel(lbl: Label) {
    appendOp(label(lbl))
  }

  override def visitLdcInsn(cst: Object) {
    appendOp("LDC",
      cst match {
        case _: String =>
          "\"" + cst.toString + "\""
        case value: Type =>
          value.getDescriptor
        case _ =>
          cst.toString
      })
  }

  override def visitIincInsn(variable: Int, increment: Int) {
    appendOp("IINC ", variable.toString + ' ' + increment.toString)
  }

  override def visitTableSwitchInsn(min: Int, max: Int, dflt: Label, labels: Label*) {
    appendOp("TABLESWITCH",
      labels.zipWithIndex.map { pair => (min + pair._2) + ": " + label(pair._1) }.mkString(",") +
        "default: " + label(dflt))
  }

  override def visitLookupSwitchInsn(dflt: Label, keys: Array[Int],
    labels: Array[Label]) {
    appendOp("LOOKUPSWITCH",
      labels.zipWithIndex.map { pair => keys(pair._2) + ": " + label(pair._1) }.mkString(",") +
        "default: " + label(dflt))
  }

  override def visitMultiANewArrayInsn(desc: String, dims: Int) {
    appendOp("MULTIANEWARRAY", descriptor(FIELD_DESCRIPTOR, desc) +
      " " + dims)
  }

}
