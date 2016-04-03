package com.jtransc.types

import com.jtransc.ast.*
import com.jtransc.ds.cast
import com.jtransc.ds.hasFlag
import com.jtransc.error.*
import com.jtransc.input.astRef
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.*

val Handle.ast: AstMethodRef get() = AstMethodRef(this.owner.fqname, this.name, AstType.demangleMethod(this.desc))

class DummyLocateRightClass : LocateRightClass {
	override fun locateRightClass(field: AstFieldRef) = field.classRef
	override fun locateRightClass(method: AstMethodRef) = method.classRef
}

fun Asm2Ast(clazz: AstType.REF, method: MethodNode, locateRightClass: LocateRightClass = DummyLocateRightClass()): AstBody = _Asm2Ast(clazz, method, locateRightClass).call()

// http://stackoverflow.com/questions/4324321/java-local-variables-how-do-i-get-a-variable-name-or-type-using-its-index
private class _Asm2Ast(val clazz: AstType.REF, val method: MethodNode, val _locateRightClass: LocateRightClass) {
	companion object {
		//const val DEBUG = true
		const val DEBUG = false

		val PTYPES = listOf(AstType.INT, AstType.LONG, AstType.FLOAT, AstType.DOUBLE, AstType.OBJECT)
		val JUMPOPS = listOf(AstBinop.EQ, AstBinop.NE, AstBinop.LT, AstBinop.GE, AstBinop.GT, AstBinop.LE, AstBinop.EQ, AstBinop.NE)
	}

	val methodRef = method.astRef(clazz.classRef)
	//val list = method.instructions
	val methodType = AstType.demangleMethod(method.desc)
	val stms = ArrayList<AstStm?>()
	val stack = Stack<AstExpr>()
	val tryCatchBlocks = method.tryCatchBlocks.cast<TryCatchBlockNode>()
	val firstInstruction = method.instructions.first
	val locals = hashMapOf<Pair<Int, AstType>, AstExpr.LocalExpr>()  // @TODO: remove this
	val labels = hashMapOf<LabelNode, AstLabel>()
	val isStatic = method.access.hasFlag(Opcodes.ACC_STATIC)
	val referencedLabels = hashSetOf<AstLabel>()
	var tempLocalId = 1000
	val localsAtIndex = hashMapOf<Int, AstExpr.LocalExpr>()
	var lastLine = -1

	fun fixType(type: AstType): AstType {
		if (type is AstType.Primitive) {
			return type
		} else {
			return AstType.OBJECT
		}
	}

	fun nameType(type: AstType): String {
		if (type is AstType.Primitive) {
			return type.chstring
		} else {
			return "A"
		}
	}

	fun localPair(index: Int, type: AstType): Pair<Int, AstType> {
		return Pair(index, fixType(type))
	}

	init {
		var idx = 0
		if (!isStatic) {
			setLocalAtIndex(idx, AstExpr.THIS(clazz.name))
			locals[localPair(idx++, clazz)] = AstExpr.THIS(clazz.name) // @TODO: remove this
		}
		for (arg in methodType.args) {
			setLocalAtIndex(idx, AstExpr.PARAM(arg))
			locals[localPair(idx++, arg.type)] = AstExpr.PARAM(arg) // @TODO: remove this
			if (arg.type.isLongOrDouble()) idx++
		}
	}

	//fun fix(field: AstFieldRef): AstFieldRef = locateRightClass.locateRightField(field)
	//fun fix(method: AstMethodRef): AstMethodRef = locateRightClass.locateRightMethod(method)

	fun fix(field: AstFieldRef): AstFieldRef = field
	fun fix(method: AstMethodRef): AstMethodRef = method

	fun getType(value: Any?): AstType {
		return when (value) {
			is Int -> AstType.INT
			is String -> AstType.STRING // Or custom type?
		//else -> AstType.UNKNOWN
			else -> {
				throw InvalidOperationException("$value")
			}
		}
	}

	fun local(type: AstType, index: Int, prefix: String = "local"): AstExpr.LocalExpr {
		val info = localPair(index, type)
		//if (info !in locals) locals[info] = AstExpr.LOCAL(AstLocal(index, "local${index}_$type", type))
		val type2 = fixType(type)
		if (info !in locals) locals[info] = AstExpr.LOCAL(AstLocal(index, "$prefix${index}_${nameType(type2)}", type2))
		return locals[info]!!
	}

	fun localAtIndex(index: Int): AstExpr.LocalExpr {
		return localsAtIndex[index]!!
	}

	fun setLocalAtIndex(index: Int, expr: AstExpr.LocalExpr) {
		localsAtIndex[index] = expr
	}

	fun tempLocal(type: AstType): AstExpr.LocalExpr {
		return local(type, tempLocalId++, "temp")
	}

	fun label(label: LabelNode): AstLabel {
		if (label !in labels) labels[label] = AstLabel("label_${label.label}")
		return labels[label]!!
	}

	fun ref(label: AstLabel): AstLabel {
		referencedLabels += label
		return label
	}

	fun stmAdd(s: AstStm) {
		// Adding statements must dump stack (and restore later) so we preserve calling order!
		val stack = preserveStack()
		stms.add(s)
		restoreStack(stack)
	}

	fun stackPush(e: AstExpr) {
		stack.push(e)
	}

	fun stackPop(): AstExpr {
		if (stack.isEmpty()) {
			println("stack is empty!")
		}
		return stack.pop()
	}

	fun stackPeek(): AstExpr {
		if (stack.isEmpty()) {
			println("stack is empty!")
		}
		return stack.peek()
	}

	fun stmSet(local: AstExpr.LocalExpr, value: AstExpr) {
		if (local != value) {
			stmAdd(AstStm.SET(local, fastcast(value, local.type)))
		}
	}

	fun handleField(i: FieldInsnNode) {
		//val isStatic = (i.opcode == Opcodes.GETSTATIC) || (i.opcode == Opcodes.PUTSTATIC)
		val ref = fix(AstFieldRef(AstType.REF_INT2(i.owner).fqname.fqname, i.name, com.jtransc.ast.AstType.demangle(i.desc)))
		when (i.opcode) {
			Opcodes.GETSTATIC -> {
				stackPush(AstExprUtils.fastcast(AstExpr.STATIC_FIELD_ACCESS(ref), ref.type))
			}
			Opcodes.GETFIELD -> {
				val obj = AstExprUtils.fastcast(stackPop(), ref.containingTypeRef)
				stackPush(AstExprUtils.fastcast(AstExpr.INSTANCE_FIELD_ACCESS(ref, obj), ref.type))
			}
			Opcodes.PUTSTATIC -> {
				stmAdd(AstStm.SET_FIELD_STATIC(ref, AstExprUtils.fastcast(stackPop(), ref.type)))
			}
			Opcodes.PUTFIELD -> {
				val param = stackPop()
				val obj = AstExprUtils.fastcast(stackPop(), ref.containingTypeRef)
				stmAdd(AstStm.SET_FIELD_INSTANCE(ref, obj, AstExprUtils.fastcast(param, ref.type)))
			}
			else -> invalidOp
		}
	}

	//  peephole optimizations

	fun optimize(e: AstExpr.BINOP): AstExpr {
		return e
	}

	fun cast(expr: AstExpr, to: AstType) = AstExprUtils.cast(expr, to)
	fun fastcast(expr: AstExpr, to: AstType) = AstExprUtils.fastcast(expr, to)

	fun pushBinop(type: AstType, op: AstBinop) {
		val r = stackPop()
		val l = stackPop()
		stackPush(optimize(AstExprUtils.BINOP(type, l, op, r)))
	}

	fun arrayLoad(type: AstType): Unit {
		val index = stackPop()
		val array = stackPop()
		stackPush(AstExpr.ARRAY_ACCESS(fastcast(array, AstType.ARRAY(type)), fastcast(index, AstType.INT)))
	}

	fun arrayStore(elementType: AstType): Unit {
		val expr = stackPop()
		val index = stackPop()
		val array = stackPop()
		stmAdd(AstStm.SET_ARRAY(fastcast(array, AstType.ARRAY(elementType)), fastcast(index, AstType.INT), fastcast(expr, elementType)))
	}

	fun untestedWarn2(msg: String) {
		untestedWarn("$msg : ${clazz.name}::${method.name} @ $lastLine")
	}

	fun handleInsn(i: InsnNode): Unit {
		val op = i.opcode
		when (i.opcode) {
			Opcodes.NOP -> stmAdd(AstStm.NOP);
			Opcodes.ACONST_NULL -> stackPush(AstExpr.LITERAL(null))
			in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> stackPush(AstExpr.LITERAL((op - Opcodes.ICONST_0).toInt()))
			in Opcodes.LCONST_0..Opcodes.LCONST_1 -> stackPush(AstExpr.LITERAL((op - Opcodes.LCONST_0).toLong()))
			in Opcodes.FCONST_0..Opcodes.FCONST_2 -> stackPush(AstExpr.LITERAL((op - Opcodes.FCONST_0).toFloat()))
			in Opcodes.DCONST_0..Opcodes.DCONST_1 -> stackPush(AstExpr.LITERAL((op - Opcodes.DCONST_0).toDouble()))
			Opcodes.IALOAD -> arrayLoad(AstType.INT)
			Opcodes.LALOAD -> arrayLoad(AstType.LONG)
			Opcodes.FALOAD -> arrayLoad(AstType.FLOAT)
			Opcodes.DALOAD -> arrayLoad(AstType.DOUBLE)
			Opcodes.AALOAD -> arrayLoad(AstType.OBJECT)
			Opcodes.BALOAD -> arrayLoad(AstType.BYTE)
			Opcodes.CALOAD -> arrayLoad(AstType.CHAR)
			Opcodes.SALOAD -> arrayLoad(AstType.SHORT)
			Opcodes.IASTORE -> arrayStore(AstType.INT)
			Opcodes.LASTORE -> arrayStore(AstType.LONG)
			Opcodes.FASTORE -> arrayStore(AstType.FLOAT)
			Opcodes.DASTORE -> arrayStore(AstType.DOUBLE)
			Opcodes.AASTORE -> arrayStore(AstType.OBJECT)
			Opcodes.BASTORE -> arrayStore(AstType.BYTE)
			Opcodes.CASTORE -> arrayStore(AstType.CHAR)
			Opcodes.SASTORE -> arrayStore(AstType.SHORT)
			Opcodes.POP -> {
				// We store it, so we don't lose all the calculated stuff!
				stmAdd(AstStm.STM_EXPR(stackPop()))
			}
			Opcodes.POP2 -> {
				stmAdd(AstStm.STM_EXPR(stackPop()))
				stmAdd(AstStm.STM_EXPR(stackPop()))
			}
			Opcodes.DUP -> {
				val value = stackPop()
				val local = tempLocal(value.type)

				stmSet(local, value)
				stackPush(local)
				stackPush(local)
			}
		// @TODO: probably wrong!
		// @TODO: Must reproduce these opcodes!
		// It seems to be reproducible in java.lang.Object constructor!
			Opcodes.DUP_X1 -> {
				val v1 = stackPop()
				val v2 = stackPop()
				val local1 = tempLocal(v1.type)
				val local2 = tempLocal(v2.type)
				stmSet(local2, v2)
				stmSet(local1, v1)
				stackPush(local1)
				stackPush(local2)
				stackPush(local1)
			}
		// @TODO: probably wrong!
			Opcodes.DUP_X2 -> {
				untestedWarn2("DUP_X2")
				val v1 = stackPop()
				val v2 = stackPop()
				val local1 = tempLocal(v1.type)
				val local2 = tempLocal(v2.type)
				if (v2.type.isLongOrDouble()) {
					stmSet(local2, v2)
					stmSet(local1, v1)
					stackPush(local1)
					stackPush(local2)
					stackPush(local1)
				} else {
					val v3 = stackPop()
					val local3 = tempLocal(v3.type)
					// @TODO: Check order
					stmSet(local1, v1)
					stmSet(local2, v2)
					stmSet(local3, v3)
					stackPush(local3)
					stackPush(local2)
					stackPush(local1)
					stackPush(local3)
				}
			}
		// @TODO: probably wrong!
			Opcodes.DUP2 -> {
				val v1 = stackPop()
				val local1 = tempLocal(v1.type)
				if (v1.type.isLongOrDouble()) {
					stmSet(local1, v1)
					stackPush(local1)
					stackPush(local1)
				} else {
					untestedWarn2("DUP2 single")
					val local2 = tempLocal(v1.type)
					val v2 = stackPop()
					stmSet(local1, v1)
					stmSet(local2, v2)
					stackPush(local1)
					stackPush(local2)
				}
			}
		// @TODO: probably wrong!
			Opcodes.DUP2_X1 -> {
				untestedWarn2("DUP2_X1")
				if (!stackPeek().type.isLongOrDouble()) {
					val v1 = stackPop() // single
					val v2 = stackPop() // single
					val v3 = stackPop() // single
					val local1 = tempLocal(v1.type)
					val local2 = tempLocal(v2.type)
					val local3 = tempLocal(v3.type)
					stmSet(local1, v1)
					stmSet(local2, v2)
					stmSet(local3, v3)
					stackPush(local1)
					stackPush(local2)
					stackPush(local3)
					stackPush(local1)
					stackPush(local2)
				} else {
					val v1 = stackPop() // double
					val v2 = stackPop() // single
					val local1 = tempLocal(v1.type)
					val local2 = tempLocal(v2.type)
					stmSet(local1, v1)
					stmSet(local2, v2)
					stackPush(local1)
					stackPush(local2)
					stackPush(local1)
				}
			}
			Opcodes.DUP2_X2 -> {
				untestedWarn2("DUP2_X2")
				stmAdd(AstStm.NOT_IMPLEMENTED)
			}
			Opcodes.SWAP -> {
				val v1 = stackPop()
				val v2 = stackPop()
				stackPush(v1)
				stackPush(v2)
			}

			Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG -> stackPush(AstExpr.UNOP(AstUnop.NEG, stackPop()))

		// @TODO: try to homogeinize this!
			in Opcodes.IADD..Opcodes.DADD -> pushBinop(PTYPES[op - Opcodes.IADD], AstBinop.ADD)
			in Opcodes.ISUB..Opcodes.DSUB -> pushBinop(PTYPES[op - Opcodes.ISUB], AstBinop.SUB)
			in Opcodes.IMUL..Opcodes.DMUL -> pushBinop(PTYPES[op - Opcodes.IMUL], AstBinop.MUL)
			in Opcodes.IDIV..Opcodes.DDIV -> pushBinop(PTYPES[op - Opcodes.IDIV], AstBinop.DIV)
			in Opcodes.IREM..Opcodes.DREM -> pushBinop(PTYPES[op - Opcodes.IREM], AstBinop.REM)
			in Opcodes.ISHL..Opcodes.LSHL -> pushBinop(PTYPES[op - Opcodes.ISHL], AstBinop.SHL)
			in Opcodes.ISHR..Opcodes.LSHR -> pushBinop(PTYPES[op - Opcodes.ISHR], AstBinop.SHR)
			in Opcodes.IUSHR..Opcodes.LUSHR -> pushBinop(PTYPES[op - Opcodes.IUSHR], AstBinop.USHR)
			in Opcodes.IAND..Opcodes.LAND -> pushBinop(PTYPES[op - Opcodes.IAND], AstBinop.AND)
			in Opcodes.IOR..Opcodes.LOR -> pushBinop(PTYPES[op - Opcodes.IOR], AstBinop.OR)
			in Opcodes.IXOR..Opcodes.LXOR -> pushBinop(PTYPES[op - Opcodes.IXOR], AstBinop.XOR)

			Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> stackPush(fastcast(stackPop(), AstType.LONG))
			Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> stackPush(fastcast(stackPop(), AstType.FLOAT))
			Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> stackPush(fastcast(stackPop(), AstType.DOUBLE))
			Opcodes.L2I, Opcodes.F2I, Opcodes.D2I -> stackPush(fastcast(stackPop(), AstType.INT))
			Opcodes.I2B -> stackPush(fastcast(stackPop(), AstType.BYTE))
			Opcodes.I2C -> stackPush(fastcast(stackPop(), AstType.CHAR))
			Opcodes.I2S -> stackPush(fastcast(stackPop(), AstType.SHORT))

			Opcodes.LCMP -> pushBinop(AstType.LONG, AstBinop.LCMP)
			Opcodes.FCMPL -> pushBinop(AstType.FLOAT, AstBinop.CMPL)
			Opcodes.FCMPG -> pushBinop(AstType.FLOAT, AstBinop.CMPG)
			Opcodes.DCMPL -> pushBinop(AstType.DOUBLE, AstBinop.CMPL)
			Opcodes.DCMPG -> pushBinop(AstType.DOUBLE, AstBinop.CMPG)
			in Opcodes.IRETURN..Opcodes.ARETURN -> {
				val ret = stackPop()
				dumpExprs()
				stmAdd(AstStm.RETURN(fastcast(ret, this.methodType.ret)))
			}
			Opcodes.RETURN -> {
				dumpExprs()
				stmAdd(AstStm.RETURN(null))
			}
			Opcodes.ARRAYLENGTH -> stackPush(AstExpr.ARRAY_LENGTH(stackPop()))
			Opcodes.ATHROW -> stmAdd(AstStm.THROW(stackPop()))
			Opcodes.MONITORENTER -> stmAdd(AstStm.MONITOR_ENTER(stackPop()))
			Opcodes.MONITOREXIT -> stmAdd(AstStm.MONITOR_EXIT(stackPop()))
			else -> invalidOp("$op")
		}
	}

	fun handleMultiArray(i: MultiANewArrayInsnNode) {
		when (i.opcode) {
			Opcodes.MULTIANEWARRAY -> {
				stackPush(AstExpr.NEW_ARRAY(AstType.REF_INT(i.desc) as AstType.ARRAY, (0 until i.dims).map { stackPop() }.reversed()))
			}
			else -> invalidOp("$i")
		}
	}

	fun handleType(i: TypeInsnNode) {
		val type = AstType.REF_INT(i.desc)
		when (i.opcode) {
			Opcodes.NEW -> stackPush(fastcast(AstExpr.NEW(type as AstType.REF), AstType.OBJECT))
			Opcodes.ANEWARRAY -> stackPush(AstExpr.NEW_ARRAY(AstType.ARRAY(type), listOf(stackPop())))
			Opcodes.CHECKCAST -> stackPush(cast(stackPop(), type))
			Opcodes.INSTANCEOF -> stackPush(AstExpr.INSTANCE_OF(stackPop(), type))
			else -> invalidOp("$i")
		}
	}

	fun handleVar(i: VarInsnNode) {
		val op = i.opcode
		val index = i.`var`
		when (i.opcode) {
			in Opcodes.ILOAD..Opcodes.ALOAD -> {
				stackPush(localAtIndex(index))
			}
			in Opcodes.ISTORE..Opcodes.ASTORE -> {
				val expr = stackPop()
				val newLocal = tempLocal(expr.type)
				setLocalAtIndex(index, newLocal)
				stmSet(newLocal, expr)
			}
			Opcodes.RET -> deprecated
			else -> invalidOp
		}
	}


	fun addJump(cond: AstExpr?, label: AstLabel) {
		preserveStack()
		ref(label)
		stmAdd(AstStm.IF_GOTO(label, cond))
	}

	fun handleJump(i: JumpInsnNode) {
		val op = i.opcode
		when (op) {
			in Opcodes.IFEQ..Opcodes.IFLE -> {
				addJump(AstExpr.BINOP(AstType.BOOL, stackPop(), JUMPOPS[op - Opcodes.IFEQ], AstExpr.LITERAL(0)), label(i.label))
			}
			in Opcodes.IFNULL..Opcodes.IFNONNULL -> {
				addJump(AstExpr.BINOP(AstType.BOOL, stackPop(), JUMPOPS[op - Opcodes.IFNULL], AstExpr.LITERAL(null)), label(i.label))
			}
			in Opcodes.IF_ICMPEQ..Opcodes.IF_ACMPNE -> {
				addJump(AstExpr.BINOP(AstType.BOOL, stackPop(), JUMPOPS[op - Opcodes.IF_ICMPEQ], stackPop()), label(i.label))
			}
			Opcodes.GOTO -> {
				addJump(null, label(i.label))
			}
			Opcodes.JSR -> deprecated
			else -> invalidOp
		}
	}

	fun handleLdc(i: LdcInsnNode) {
		// {@link Integer}, a {@link Float}, a {@link Long}, a {@link Double}, a
		// {@link String} or a {@link org.objectweb.asm.Type}.
		val cst = i.cst
		if (cst is Type) {
			stackPush(AstExpr.CLASS_CONSTANT(AstType.REF_INT(cst.internalName)))
		} else {
			stackPush(AstExpr.LITERAL(cst))
		}
	}

	fun handleInt(i: IntInsnNode) {
		when (i.opcode) {
			Opcodes.BIPUSH -> stackPush(AstExpr.LITERAL(i.operand.toByte()))
			Opcodes.SIPUSH -> stackPush(AstExpr.LITERAL(i.operand.toShort()))
			Opcodes.NEWARRAY -> {
				val type = when (i.operand) {
					Opcodes.T_BOOLEAN -> AstType.BOOL
					Opcodes.T_CHAR -> AstType.CHAR
					Opcodes.T_FLOAT -> AstType.FLOAT
					Opcodes.T_DOUBLE -> AstType.DOUBLE
					Opcodes.T_BYTE -> AstType.BYTE
					Opcodes.T_SHORT -> AstType.SHORT
					Opcodes.T_INT -> AstType.INT
					Opcodes.T_LONG -> AstType.LONG
					else -> invalidOp
				}
				stackPush(AstExpr.NEW_ARRAY(AstType.ARRAY(type, 1), listOf(stackPop())))
			}
			else -> invalidOp
		}
	}

	fun handleMethod(i: MethodInsnNode) {
		val type = AstType.REF_INT(i.owner)
		val clazz = if (type is AstType.REF) type else AstType.OBJECT
		val methodRef = fix(com.jtransc.ast.AstMethodRef(clazz.fqname.fqname, i.name, AstType.demangleMethod(i.desc)))
		val isSpecial = i.opcode == Opcodes.INVOKESPECIAL

		val args = methodRef.type.args.reversed().map { fastcast(stackPop(), it.type) }.reversed()
		val obj = if (i.opcode != Opcodes.INVOKESTATIC) stackPop() else null

		when (i.opcode) {
			Opcodes.INVOKESTATIC -> {
				stackPush(AstExpr.CALL_STATIC(clazz, methodRef, args, isSpecial))
			}
			Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL -> {
				if (obj!!.type !is AstType.REF) {
					//invalidOp("Obj must be an object $obj, but was ${obj.type}")
				}
				val obj = fastcast(obj!!, methodRef.containingClassType)
				if (i.opcode != Opcodes.INVOKESPECIAL) {
					stackPush(AstExpr.CALL_INSTANCE(obj, methodRef, args, isSpecial))
				} else {
					//if (methodRef.containingClassType != this.clazz) {
					//	stackPush(AstExpr.CALL_SUPER(obj, methodRef.classRef.type.name, methodRef, args, isSpecial))
					//} else {
					//	stackPush(AstExpr.CALL_INSTANCE(obj, methodRef, args, isSpecial))
					//}
					stackPush(AstExpr.CALL_SPECIAL(AstExprUtils.fastcast(obj, methodRef.containingClassType), methodRef, args, isSpecial = true))
				}
			}
			else -> invalidOp
		}

		if (methodRef.type.retVoid) {
			//preserveStack()
			stmAdd(AstStm.STM_EXPR(stackPop()))
		}
	}

	fun handleLookupSwitch(i: LookupSwitchInsnNode) {
		stmAdd(AstStm.SWITCH_GOTO(
			stackPop(),
			ref(label(i.dflt)),
			i.keys.cast<Int>().zip(i.labels.cast<LabelNode>().map { ref(label(it)) })
		))
	}

	fun handleTableSwitch(i: TableSwitchInsnNode) {
		stmAdd(AstStm.SWITCH_GOTO(
			stackPop(),
			ref(label(i.dflt)),
			(i.min..i.max).zip(i.labels.cast<LabelNode>().map { ref(label(it)) })
		))
	}

	fun handleInvokeDynamic(i: InvokeDynamicInsnNode) {
		stackPush(AstExprUtils.INVOKE_DYNAMIC(
			AstMethodWithoutClassRef(i.name, AstType.demangleMethod(i.desc)),
			i.bsm.ast,
			i.bsmArgs.map { AstExpr.LITERAL(it) }
		))
	}

	fun handleLabel(i: LabelNode) {
		//dumpExprs()
		stmAdd(AstStm.STM_LABEL(label(i)))
	}

	fun handleIinc(i: IincInsnNode) {
		val local = local(AstType.INT, i.`var`)
		stmSet(local, local + AstExpr.LITERAL(1))
	}

	fun handleLineNumber(i: LineNumberNode) {
		lastLine = i.line
		stmAdd(AstStm.LINE(i.line))
	}

	fun preserveStackLocal(index: Int, type: AstType): AstExpr.LocalExpr {
		return local(type, index + 2000)
	}

	fun dumpExprs() {
		while (stack.isNotEmpty()) {
			stmAdd(AstStm.STM_EXPR(stackPop()))
		}
	}

	fun preserveStack(): List<AstExpr.LocalExpr> {
		if (stack.isEmpty()) {
			return Collections.EMPTY_LIST as List<AstExpr.LocalExpr>
		} else {
			val items = arrayListOf<AstExpr.LocalExpr>()
			while (stack.isNotEmpty()) {
				val value = stackPop()
				val local = preserveStackLocal(stack.size, value.type)
				stmSet(local, value)
				items.add(local)
			}
			return items
		}
	}

	fun restoreStack(stackToRestore: List<AstExpr.LocalExpr>) {
		if (stackToRestore.size >= 2) {
			//println("stackToRestore.size:" + stackToRestore.size)
		}
		for (i in stackToRestore.reversed()) {
			// @TODO: avoid reversed by inserting in the right order!
			this.stack.push(i)
		}
	}

	fun handleFrame(i: FrameNode) {
		stack.clear()
		// validated order
		for ((index, typeValue) in i.stack.withIndex()) {
			//val type = LiteralToAstType(typeValue)

			val type = when (typeValue) {
				Opcodes.TOP -> invalidOp
				Opcodes.INTEGER -> AstType.INT
				Opcodes.FLOAT -> AstType.FLOAT
				Opcodes.DOUBLE -> AstType.DOUBLE
				Opcodes.LONG -> AstType.LONG
				Opcodes.NULL -> AstType.OBJECT
				Opcodes.UNINITIALIZED_THIS -> AstType.OBJECT
				is String -> AstType.OBJECT
				//else -> LiteralToAstType(typeValue)
				else -> invalidOp
			}

			stackPush(preserveStackLocal(index, type))
			if (DEBUG) println("$index: push($typeValue : ${typeValue?.javaClass})")
		}
	}

	fun call(): AstBody {
		var i = this.firstInstruction
		if (DEBUG) {
			println("--------------------------------------------------------------------")
			println("::::::::::::: ${clazz.name}.${method.name}:${method.desc}")
			println("--------------------------------------------------------------------")
		}
		while (i != null) {
			if (DEBUG) println(AsmOpcode.disasm(i))
			when (i) {
				is FieldInsnNode -> handleField(i)
				is InsnNode -> handleInsn(i)
				is TypeInsnNode -> handleType(i)
				is VarInsnNode -> handleVar(i)
				is JumpInsnNode -> handleJump(i)
				is LdcInsnNode -> handleLdc(i)
				is IntInsnNode -> handleInt(i)
				is MethodInsnNode -> handleMethod(i)
				is LookupSwitchInsnNode -> handleLookupSwitch(i)
				is TableSwitchInsnNode -> handleTableSwitch(i)
				is InvokeDynamicInsnNode -> handleInvokeDynamic(i)
				is LabelNode -> handleLabel(i)
				is IincInsnNode -> handleIinc(i)
				is LineNumberNode -> handleLineNumber(i)
				is FrameNode -> handleFrame(i)
				is MultiANewArrayInsnNode -> handleMultiArray(i)
				else -> invalidOp("$i")
			}
			i = i.next
		}

		fun optimize(stms: MutableList<AstStm?>, item: AstStm?, index: Int, total: Int): AstStm? {
			if (item == null) return null
			// Remove not referenced labels
			if (item is AstStm.STM_LABEL && item.label !in referencedLabels) return null
			if (item is AstStm.NOP) return null
			return item
		}

		fun List<AstStm?>.optimize(): List<AstStm> {
			var stms = this.toMutableList()
			for (n in 0 until stms.size) {
				stms[n] = optimize(stms, stms[n], n, stms.size)
			}

			// DO NOT Remove here tail empty returns
			//while (stms.isNotEmpty() && (stms.last() is AstStm.RETURN && (stms.last() as AstStm.RETURN).retval == null) || (stms.last() == null)) {
			//	stms.removeAt(stms.size - 1)
			//}

			return stms.filterNotNull()
		}

		dumpExprs()

		return AstBody(
			AstStm.STMS(stms.optimize()),
			locals.values.filterIsInstance<AstExpr.LOCAL>().map { it.local },
			tryCatchBlocks.map {
				AstTrap(
					start = label(it.start),
					end = label(it.end),
					handler = label(it.handler),
					exception = if (it.type != null) AstType.REF_INT2(it.type) else AstType.OBJECT
				)
			}
		)
	}
}
