package cosmo

class CodeGen(val env: Env) {
  val prelude = """
// NOLINTBEGIN(readability-identifier-naming)

#include "cosmo-rt.h"
"""

  val postlude = """
// NOLINTEND(readability-identifier-naming)
"""

  // Generate Cxx code from the env
  def gen(): String = {
    val itemCode = env.module.stmts.map(genDef(_, true)).mkString("\n")
    val errorCode = env.errors.mkString("\n")
    s"$prelude$itemCode\n#if 0\n$errorCode\n#endif$postlude"
  }

  def defName(id: DefId)(implicit topLevel: Boolean): String = {
    val defInfo = env.defs(id)
    if ((topLevel && defInfo.name == "main"))
      defInfo.name
    else defInfo.nameStem(id.id)
  }

  def genDef(ast: ir.Item, tl: Boolean): String = {
    implicit val topLevel = tl;
    ast match {
      case ir.Def(id) => {
        val item = env.values(id)
        val name = defName(id)
        item match {
          case ir.Fn(None, _) =>
            s"/* cosmo function $name */"
          case ir.Fn(Some(params), body) =>
            val paramCode =
              params
                .map(param =>
                  s"${paramTy(param.ty)} ${param.name}${param.id.id}",
                )
                .mkString(", ")
            val bodyCode = body match {
              case Some(body) => s"{${expr(body)}}"
              case None       => ";"
            }
            s"int $name($paramCode) $bodyCode"
          case ir.CModule(kind, path) =>
            kind match {
              case ir.CModuleKind.Builtin =>
                s"#include <$path>"
              case ir.CModuleKind.Error =>
                s"""#error "cosmo error module $path""""
              case ir.CModuleKind.Source =>
                s"""#include "$path""""
            }
          case ir.Class(_, params, vars, defs) =>
            println(s"class $name has params $params")
            val templateCode = params
              .map { ps =>
                s"template <${ps.map(p => s"typename ${p.name}").mkString(", ")}>"
              }
              .getOrElse("")
            val varsCode = vars.map(genDef(_, false)).mkString(";\n") + ";"
            val defsCode = defs.map(genDef(_, false)).mkString(";\n") + ";"
            val consPref = if (vars.isEmpty) "" else s":"
            val consCode =
              s"$name(${vars.map(genVarParam).mkString(", ")})$consPref${vars.map(genVarCons).mkString(", ")} {}"
            s"$templateCode struct $name {$varsCode$consCode$defsCode};"
          case ir.EnumClass(_, params, variants, default) =>
            val variantNames = variants.map(e => defName(e.id))
            val variantVars = variants.map(e =>
              env.values(e.id) match
                case c: ir.Class => c.vars
                case _           => List.empty,
            )
            println(s"enum $variantVars")
            val bodyCode = variants.map(genDef(_, false)).mkString(";\n")
            val dataCode = variantNames.mkString(", ")
            val variantCons =
              variantNames
                .map(vn => s"$name($vn &&v): data(std::move(v)) {}")
                .mkString("\n  ")
            val variantCons2 = variantNames
              .map(vn =>
                s"""template <typename... Args> static $name ${vn}_cons(Args &&...args) {
    return {${vn}(std::forward<Args>(args)...)};
  }""",
              )
              .mkString("\n  ")
            val variantFields =
              variantNames
                .zip(variantVars)
                .zipWithIndex
                .flatMap { case ((vn, vars), index) =>
                  vars.map(v =>
                    val defInfo = env.defs(v.id)
                    val name = defInfo.nameStem(v.id.id)
                    val ty = defInfo.upperBounds.headOption.getOrElse(TopTy)
                    val mayDeref = if ty == SelfTy then "*" else ""
                    s"""const ${returnTy(
                        ty,
                      )} &${vn}${name}() const { return $mayDeref(std::get<$index>(data)).${name};};""",
                  )
                }
                .mkString("\n  ")
            val variantClones =
              variantNames
                .zip(variantVars)
                .zipWithIndex
                .map { case ((vn, vars), index) =>
                  val clones = vars
                    .map(v =>
                      val defInfo = env.defs(v.id)
                      val name = defInfo.nameStem(v.id.id)
                      val ty = defInfo.upperBounds.headOption.getOrElse(TopTy)
                      val mayClone = if ty == SelfTy then "->clone()" else ""
                      s"std::get<$index>(data).${name}$mayClone",
                    )
                    .mkString(",")
                  s"""case ${index}: return ${vn}_cons($clones);"""
                }
                .mkString("\n    ")
            s"""struct $name {using Self = ${name};using self_t = std::unique_ptr<Self>; $bodyCode;std::variant<${dataCode}> data;
  $name($name &&n) : data(std::move(n.data)) {}
  $name &operator=($name &&n) {
    data = std::move(n.data);
    return *this;
  }

  $variantCons

  $variantCons2

  $variantFields

  Nat clone() const { // NOLINT(misc-no-recursion)
    switch (data.index()) {
    $variantClones
    default:
      unreachable();
    }
  }
};"""
          case _ => s"unknown $name;"
        }
      }
      case v: ir.Var => genVarStore(v)
      case a         => expr(a)
    }
  }

  def genVarParam(node: ir.Var) = {
    val ir.Var(id, init, isContant) = node
    val defInfo = env.defs(id)
    val name = defInfo.nameStem(id.id)
    val ty = paramTy(defInfo.upperBounds.headOption.getOrElse(TopTy))
    var constantStr = if isContant then "const " else ""
    s"${constantStr}${ty} ${name}_p"
  }

  def genVarCons(node: ir.Var) = {
    val ir.Var(id, init, isContant) = node
    val defInfo = env.defs(id)
    val name = defInfo.nameStem(id.id)
    val ty = defInfo.upperBounds.headOption.getOrElse(TopTy)
    val p = s"std::move(${name}_p)"
    if ty == SelfTy then s"$name(std::make_unique<Self>($p))"
    else s"$name($p)"
  }

  def genVarStore(node: ir.Var) = {
    val ir.Var(id, init, isContant) = node
    val defInfo = env.defs(id)
    val name = defInfo.nameStem(id.id)
    val ty = storeTy(defInfo.upperBounds.headOption.getOrElse(TopTy))
    var constantStr = if isContant then "const " else ""
    var initStr = if init == ir.NoneItem then "" else s" = ${expr(init)}"
    s"${constantStr}${ty} $name${initStr}"
  }

  def paramTy(ty: Type): String = {
    ty match {
      case SelfTy => "Self&&"
      case ty     => returnTy(ty)
    }
  }

  def returnTy(ty: Type): String = {
    ty match {
      case SelfTy => "Self"
      case ty     => storeTy(ty)
    }
  }

  def storeTy(ty: Type): String = {
    ty match {
      case IntegerTy(size, isUnsigned) =>
        s"${if (isUnsigned) "u" else ""}int${size}_t"
      case SelfTy         => "self_t"
      case ty: CppType    => ty.repr
      case ty: CppInsType => ty.repr
      case ty             => ty.toString
    }
  }

  def expr(ast: ir.Item): String = {
    ast match {
      case ir.Lit(value)    => value.toString
      case ir.Opaque(value) => value
      case ir.Region(stmts) =>
        stmts.map(genDef(_, false)).mkString(";\n") + ";"
      case ir.Variable(id) => {
        val defInfo = env.defs(id)
        val name = defInfo.nameStem(id.id)
        name
      }
      case ir.Loop(body) => s"for(;;) {${expr(body)}}"
      case ir.For(name, iter, body) =>
        s"for(auto $name : ${expr(iter)}) {${expr(body)}}"
      case ir.Break()    => "break"
      case ir.Continue() => "continue"
      case ir.If(cond, cont_bb, else_bb) =>
        s"if(${expr(cond)}) {${expr(cont_bb)}}${else_bb.map(e => s" else {${expr(e)}}").getOrElse("")}"
      case ir.BinOp("..", lhs, rhs) =>
        s"Range(${expr(lhs)}, ${expr(rhs)})"
      case ir.BinOp(op, lhs, rhs) =>
        s"${expr(lhs)} $op ${expr(rhs)}"
      case ir.Return(value) => s"return ${expr(value)}"
      case ir.Apply(lhs, rhs) =>
        s"${expr(lhs)}(${rhs.map(expr).mkString(", ")})"
      case a => a.toString()
    }
  }
}
