package cosmo.ir

import cosmo.{DefId, FileId}

import cosmo.ir.Value
import cosmo.{DefId, Env}

sealed abstract class Item {
  val level: Int = 0;

  def instantiate(implicit lvl: Int, ctx: cosmo.Env): Item = {
    this match {
      case Variable(_, id, lvl, v) =>
        val info = ctx.defs(id)
        val ty = info.upperBounds.find {
          case Variable(_, id, lvl, v) => false
          case _                       => true
        }
        val ty2 = ty.map { ty =>
          if ty.level > lvl then ty.instantiate
          else ty
        }
        ty2.getOrElse(TopKind(lvl))
      case TopKind(level)    => TopKind((level - 1).min(lvl))
      case BottomKind(level) => BottomKind((level - 1).min(lvl))
      case SelfKind(level)   => SelfKind((level - 1).min(lvl))
      case ty                => ty
    }
  }
}

type Type = Item
case class TopKind(override val level: Int) extends Item
case class BottomKind(override val level: Int) extends Item
case class SelfKind(override val level: Int) extends Item
case class NoneKind(override val level: Int) extends Item
case class RuntimeKind(override val level: Int) extends Item

val NoneItem = NoneKind(0)
val Runtime = RuntimeKind(0)

final case class Lit(value: Int) extends Item {}
final case class Opaque(expr: Option[String], stmt: Option[String])
    extends Item {}
object Opaque {
  def expr(expr: String) = Opaque(Some(expr), None)
  def stmt(stmt: String) = Opaque(None, Some(stmt))
}
final case class Param(name: String, id: DefId, ty: Type) extends Item {}
final case class Def(id: DefId) extends Item {}
final case class EnvItem(env: Env, v: Item) extends Item {}
final case class Var(id: DefId, init: Item, isContant: Boolean) extends Item {}
final case class Variable(
    val nameHint: String,
    val id: DefId,
    override val level: Int,
    val value: Option[Item] = None,
) extends Item {
  override def toString: String = s"$nameHint:${id.id}"
}
final case class BinOp(op: String, lhs: Item, rhs: Item) extends Item {}
final case class Return(value: Item) extends Item {}
final case class Semi(value: Item) extends Item {}
final case class Apply(lhs: Item, rhs: List[Item]) extends Item {}
final case class Select(lhs: Item, rhs: String) extends Item {}
final case class Match(lhs: Item, rhs: Item) extends Item {}
final case class Case(cond: Item, body: Item) extends Item {}
final case class Loop(body: Item) extends Item {}
final case class For(name: String, iter: Item, body: Item) extends Item {}
final case class Break() extends Item {}
final case class Continue() extends Item {}
case object TodoLit extends Item {}
final case class If(cond: Item, cont_bb: Item, else_bb: Option[Item])
    extends Item {}
final case class Region(stmts: List[Item]) extends Item {}
final case class Fn(
    params: Option[List[Param]],
    ret_ty: Option[Type],
    body: Option[Item],
) extends Item {}
final case class TypeAlias(
    ret_ty: Type,
) extends Item {}
final case class Interface(
    env: Env,
    ty: Type,
    id: DefId,
    fields: Map[String, VField],
) extends Item {}
final case class ClassInstance(
    iface: Interface,
) extends Item {}
final case class Class(
    id: DefId,
    params: Option[List[Param]],
    vars: List[Var],
    defs: List[Item],
) extends Item {}
object Class {
  lazy val empty = Class(DefId(0), None, List.empty, List.empty)
}
final case class EnumClass(
    id: DefId,
    params: Option[List[Param]],
    variants: List[Def],
    default: Option[Item],
) extends Item {}

// TopTy
val TopTy = TopKind(1)
val BottomTy = BottomKind(1)
val SelfTy = SelfKind(1)
val SelfVal = SelfKind(0)
val UniverseTy = TopKind(2)
case object BoolTy extends Type {
  override val level = 1
}
case object StringTy extends Type {
  override val level = 1
}
final case class IntegerTy(val width: Int, val isUnsigned: Boolean)
    extends Type {
  override val level = 1
  override def toString: String = s"${if (isUnsigned) "u" else "i"}$width"
}
object IntegerTy {
  def parse(s: String): Option[IntegerTy] = {
    val unsigned = s.startsWith("u")
    val width = s.stripPrefix("u").stripPrefix("i").toInt
    Some(new IntegerTy(width, unsigned))
  }
}
final case class FloatTy(val width: Int) extends Type {
  override val level = 1
  override def toString: String = s"f$width"
}
object FloatTy {
  def parse(s: String): Option[FloatTy] = {
    Some(new FloatTy(s.stripPrefix("f").toInt))
  }
}

final case class ValueTy(val value: Value) extends Type {
  override val level = 1
  override def toString: String = value.toString
}
final case class CIdent(val name: String, val ns: List[String]) extends Type {
  override val level = 1
  override def toString: String = s"cpp($repr)"
  def repr: String = (ns :+ name).mkString("::")
}
final case class NativeType(val name: String) extends Type {
  override val level = 1
  override def toString: String = s"native($repr)"
  def repr: String = name
}
final case class CppInsType(val target: CIdent, val arguments: List[Type])
    extends Type {
  override val level = 1
  override def toString: String = s"cpp(${repr(_.toString)})"
  def repr(rec: Type => String): String =
    target.repr + "<" + arguments
      .map {
        case Variable(nameHint, defId, level, None) => nameHint
        case Variable(nameHint, id, level, Some(v)) => rec(v)
        case ty                                     => rec(ty)
      }
      .mkString(", ") + ">"
}

final case class NativeInsType(
    val target: Type,
    val arguments: List[Type],
) extends Type {
  override val level = 1
  override def toString: String = s"native(${repr(_.toString)})"
  def repr(rec: Type => String): String =
    rec(target) + "<" + arguments
      .map {
        case Variable(nameHint, defId, level, None) => nameHint
        case Variable(nameHint, id, level, Some(v)) => rec(v)
        case ty                                     => rec(ty)
      }
      .mkString(", ") + ">"
}

sealed abstract class Value extends Item
final case class Integer(value: Int) extends Value {}
final case class Str(value: String) extends Value {}
final case class CModule(kind: CModuleKind, path: String) extends Value {}
final case class NativeModule(env: cosmo.Env, fid: FileId) extends Value {}

enum CModuleKind {
  case Builtin, Error, Source
}

sealed abstract class VField
final case class VarField(item: Item) extends VField
final case class DefField(item: Item) extends VField
